/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.cache

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.util._
import utility._
import xiangshan._
import utils._
import freechips.rocketchip.tilelink._
import mem.{AddPipelineReg}
import huancun.{PaddrKey, PaddrField}

class RefillBufferPtr(implicit p: Parameters) extends CircularQueuePtr[RefillBufferPtr](
  p => p(XSCoreParamsKey).DcacheRefillBufferSize
){
}

object RefillBufferPtr {
  def apply(f: Bool, v: UInt)(implicit p: Parameters): RefillBufferPtr = {
    val ptr = Wire(new RefillBufferPtr)
    ptr.flag := f
    ptr.value := v
    ptr
  }
}

class RefillBufferEntry(edge: TLEdgeOut)(implicit p: Parameters) extends DCacheModule {
    val io = IO(new Bundle() {
        // to refill pipe
        val refill_pipe_req = DecoupledIO(new RefillPipeReq)
        // come from miss queue
        val miss_queue_req = Flipped(DecoupledIO(new RefillPipeReq))
        // forward information
        val forwardInfo = Output(new RefillBufferForwardIO)
        // tilelink D channel
        val mem_grant = Flipped(DecoupledIO(new TLBundleD(edge.bundle)))

        val state_sleep = Output(Bool())
        val state_zombie = Output(Bool())
        val req_miss_id = Output(UInt(log2Up(cfg.nMissEntries).W))

        val entry_release_vec = Input(Vec(cfg.nMissEntries, Bool()))
        val mshr_paddr_vec = Input(Vec(cfg.nMissEntries, UInt(PAddrBits.W)))
        val flush = Input(Bool())
    })

    val req = RegInit(0.U.asTypeOf(new RefillPipeReq))
    val opcode_r = RegInit(TLMessages.Grant)

    val s_idle :: s_zombie :: s_wait_second_beat :: s_sleep :: s_send_refill :: Nil = Enum(5)
    val refillBufferState = RegInit(s_idle)

    io.state_sleep := refillBufferState === s_sleep
    io.state_zombie := refillBufferState === s_zombie
    io.req_miss_id := req.miss_id

    when(io.mem_grant.fire()) {
        req.miss_id := io.mem_grant.bits.source
        opcode_r := io.mem_grant.bits.opcode
        // req.addr := io.mshr_paddr_vec(io.mem_grant.bits.source)
        req.addr := io.mem_grant.bits.user.lift(PaddrKey).getOrElse(0.U)
        assert(io.mem_grant.bits.user.lift(PaddrKey).getOrElse(0.U) === io.mshr_paddr_vec(io.mem_grant.bits.source), "sourceD paddr is not equals to MSHR paddr")
        when(refillBufferState === s_idle) {
            when(io.mem_grant.bits.opcode === TLMessages.Grant) {
                refillBufferState := s_sleep
            }.otherwise {
                // TLMessages.GrantData
                refillBufferState := s_wait_second_beat
            }
            // req.data.asTypeOf(Vec(blockBytes/beatBytes, UInt(beatBits.W)))(0) := io.mem_grant.bits.data (bad chisel syntax)
            for (i <- 0 until beatRows) {
                val idx = i.U
                val grant_row = io.mem_grant.bits.data(rowBits * (i + 1) - 1, rowBits * i)
                req.data(idx) := grant_row
            }
        }.elsewhen(refillBufferState === s_wait_second_beat) {
            refillBufferState := s_sleep
            // req.data.asTypeOf(Vec(blockBytes/beatBytes, UInt(beatBits.W)))(1) := io.mem_grant.bits.data (bad chisel syntax)
            for (i <- 0 until beatRows) {
                val idx = (1.U << log2Floor(beatRows)) + i.U
                val grant_row = io.mem_grant.bits.data(rowBits * (i + 1) - 1, rowBits * i)
                req.data(idx) := grant_row
            }
        }
    }
    io.mem_grant.ready := (refillBufferState === s_idle) || (refillBufferState === s_wait_second_beat && req.miss_id === io.mem_grant.bits.source)

    when(io.miss_queue_req.fire()) {
        req := io.miss_queue_req.bits
        refillBufferState := s_send_refill
    }
    io.miss_queue_req.ready := refillBufferState === s_sleep

    when(io.refill_pipe_req.fire()) {
        refillBufferState := s_idle
    }
    io.refill_pipe_req.valid := refillBufferState === s_send_refill
    io.refill_pipe_req.bits := req

    // flush logic
    // if a mshr is going to release in next cycle, and no coming refill req from it(AMO), release this entry
    // 1. goto zombie state
    when(refillBufferState =/= s_idle && io.entry_release_vec(req.miss_id)) {
        refillBufferState := s_zombie
    }
    // 2. when deqptr find it, flush it
    when(io.flush) {
        refillBufferState := s_idle
    }

    // NOTE: if the response is Grant without data, do not forward until mshr give the whole data to this entry
    io.forwardInfo.apply(refillBufferState =/= s_idle, req.addr, 
                         req.data.asTypeOf(Vec(blockBytes/beatBytes, UInt(beatBits.W))), 
                         Mux(opcode_r === TLMessages.Grant, refillBufferState === s_send_refill, refillBufferState >= s_wait_second_beat),
                         Mux(opcode_r === TLMessages.Grant, refillBufferState === s_send_refill, refillBufferState >= s_sleep)
                         )
}

class RefillBuffer(edge: TLEdgeOut)(implicit p: Parameters) extends DCacheModule with HasCircularQueuePtrHelper{
    val io = IO(new Bundle() {
        // to refill pipe
        val refill_pipe_req = DecoupledIO(new RefillPipeReq)
        // dup to reduce fanout
        val refill_pipe_req_dup = Vec(nDupStatus, DecoupledIO(new RefillPipeReqCtrl))
        // come from miss queue
        val miss_queue_req = Flipped(DecoupledIO(new RefillPipeReq))
        // incoming forward req
        val forward = Vec(LoadPipelineWidth, new LduToRefillBufferForwardIO)
        // tilelink D channel
        val mem_grant = Flipped(DecoupledIO(new TLBundleD(edge.bundle)))

        val entry_release_vec = Input(Vec(cfg.nMissEntries, Bool()))
        val mshr_paddr_vec = Input(Vec(cfg.nMissEntries, UInt(PAddrBits.W)))
    })

    io := DontCare
    
    val enqPtrExt = RegInit(0.U.asTypeOf(new RefillBufferPtr))
    val deqPtrExt = RegInit(0.U.asTypeOf(new RefillBufferPtr))

    val enqPtr = enqPtrExt.value
    val deqPtr = deqPtrExt.value

    val entries = Seq.fill(DcacheRefillBufferSize)(Module(new RefillBufferEntry(edge)))

    val forwardInfo_vec = VecInit(entries.map(_.io.forwardInfo))
    val req_miss_id_vec = VecInit(entries.map(_.io.req_miss_id))
    val state_sleep_vec = VecInit(entries.map(_.io.state_sleep))
    val state_zombie_vec = VecInit(entries.map(_.io.state_zombie))

    val (_, _, refill_done, _) = edge.count(io.mem_grant)
    
    entries.zipWithIndex.foreach {
        case (e, i) =>
            e.io.entry_release_vec := io.entry_release_vec
            e.io.mshr_paddr_vec := io.mshr_paddr_vec
            // incoming tilelink D 
            e.io.mem_grant.valid := false.B
            e.io.mem_grant.bits := DontCare
            when(enqPtr === i.U) {
                io.mem_grant.ready := e.io.mem_grant.ready
                e.io.mem_grant.valid := io.mem_grant.valid
                e.io.mem_grant.bits := io.mem_grant.bits
            }
            // incoming req from miss queue
            e.io.miss_queue_req.valid := false.B
            e.io.miss_queue_req.bits := DontCare
            when(io.miss_queue_req.bits.miss_id === req_miss_id_vec(i) && state_sleep_vec(i)) {
                io.miss_queue_req.ready := e.io.miss_queue_req.ready
                e.io.miss_queue_req.valid := io.miss_queue_req.valid
                e.io.miss_queue_req.bits := io.miss_queue_req.bits
            }
            // outgoing req to refill pipe
            e.io.refill_pipe_req.ready := false.B
            when(deqPtr === i.U) {
                val out_refill_pipe_req_ctrl = Wire(Decoupled(new RefillPipeReqCtrl))
                out_refill_pipe_req_ctrl.valid := e.io.refill_pipe_req.valid
                out_refill_pipe_req_ctrl.bits := e.io.refill_pipe_req.bits.getCtrl
                e.io.refill_pipe_req.ready := out_refill_pipe_req_ctrl.ready
                for (dup <- io.refill_pipe_req_dup) {
                    AddPipelineReg(out_refill_pipe_req_ctrl, dup, false.B)
                }
                AddPipelineReg(e.io.refill_pipe_req, io.refill_pipe_req, false.B)
            }
            // flush logic
            e.io.flush := false.B
            when(deqPtr === i.U && state_zombie_vec(i)) {
                e.io.flush := true.B
                deqPtrExt := deqPtrExt + 1.U
            }
    }
    assert(PopCount((0 until DcacheRefillBufferSize).map(i => {state_sleep_vec(i) && io.miss_queue_req.bits.miss_id === req_miss_id_vec(i) && io.miss_queue_req.valid})) <= 1.U, "miss queue req should only match one entry")

    when(io.mem_grant.fire() && refill_done) {
        enqPtrExt := enqPtrExt + 1.U
    }
    when(io.refill_pipe_req.fire()) {
        deqPtrExt := deqPtrExt + 1.U
    }

    // forward logic
    (0 until LoadPipelineWidth).map(i => {
        val req_valid = io.forward(i).valid
        val paddr = io.forward(i).paddr

        val (forward_valid_vec, forwardData_vec) = forwardInfo_vec.map{info => info.forward(req_valid, paddr)}.unzip
        io.forward(i) := DontCare
        io.forward(i).forward_refillBuffer := Cat(forward_valid_vec).orR
        io.forward(i).forwardData := ParallelMux(forward_valid_vec zip forwardData_vec)
        assert(!io.forward(i).forward_refillBuffer || (PopCount(forward_valid_vec) === 0.U || PopCount(forward_valid_vec) === 1.U), s"port{$i} can not forward 2 or more entries in refillBuffer")
        // io.forward(i).forward_data := forwardData_vec.zip(forward_valid_vec).reduce(Mux(_._2, _._1, 0.U) | Mux(_._2, _._1, 0.U))
  })

    // perf 
    val validCount = distanceBetween(enqPtrExt, deqPtrExt)
    QueuePerf(DcacheRefillBufferSize, validCount, validCount === DcacheRefillBufferSize.U)
    XSPerfAccumulate("forward_refillBuffer", PopCount((0 until LoadPipelineWidth).map(i => io.forward(i).forward_refillBuffer)))
    XSPerfAccumulate("refill_buffer_not_ready", !io.mem_grant.ready)
    XSPerfAccumulate("tl_d_user_paddr_not_zero", io.mem_grant.fire() && (io.mem_grant.bits.user.lift(PaddrKey).getOrElse(0.U) =/= 0.U))
}