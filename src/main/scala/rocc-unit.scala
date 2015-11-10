package hwacha

import Chisel._
import cde.Parameters
import Commands._

class HwachaConfigIO(implicit p: Parameters) extends HwachaBundle()(p) with LaneParameters {
  val morelax = Bool(OUTPUT)
  val lstride = UInt(OUTPUT, 2)
  val vstride = UInt(OUTPUT, bRFAddr)
  val pstride = UInt(OUTPUT, bPredAddr)
}

class DecodeRegConfig(implicit p: Parameters) extends HwachaBundle()(p) {
  val nppr = UInt(width = bPRegs)
  val nvpr = UInt(width = bVRegs)
}
class CMDQIO(implicit p: Parameters) extends HwachaBundle()(p) {
  val cmd = Decoupled(Bits(width = CMD_X.getWidth))
  val imm = Decoupled(Bits(width = regLen))
  val rd  = Decoupled(Bits(width = bSDest))
  val cnt = Decoupled(Bits(width = bMLVLen))
}

class CMDQ(resetSignal: Bool = null)(implicit p: Parameters) extends HwachaModule(_reset = resetSignal)(p) {
  val io = new Bundle {
    val enq = new CMDQIO().flip
    val deq = new CMDQIO()
  }

  io.deq.cmd <> Queue(io.enq.cmd, confvcmdq.ncmd)
  io.deq.imm <> Queue(io.enq.imm, confvcmdq.nimm)
  io.deq.rd <> Queue(io.enq.rd, confvcmdq.nrd)
  io.deq.cnt <> Queue(io.enq.cnt, confvcmdq.ncnt)
}

object HwachaDecodeTable extends HwachaDecodeConstants {
  import HwachaInstructions._
  val default: List[BitPat] =
                // * means special case decode code below     checkvl?             
                //     inst_val                               |                         save
                //     |  priv                                | vrd?      resp?         | restore
                //     |  |  vmcd_val                         | | imm?    |             | |
                //     |  |  |  cmd          rtype  imm       | | | vcnt? | resptype    | | kill
                //     |  |  |  |            |      |         | | | |     | |           | | |
                  List(N, N, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    N,RESP_X,     N,N,N)
  val table: Array[(BitPat, List[BitPat])] = Array(
    // General instructions
    VSETCFG    -> List(Y, N, Y, CMD_VSETCFG, VRT_X, IMM_VLEN, N,N,Y,N,    N,RESP_X,     N,N,N), //* set maxvl register
    VSETVL     -> List(Y, N, Y, CMD_VSETVL,  VRT_X, IMM_VLEN, N,N,Y,N,    Y,RESP_NVL,   N,N,N), //* set vl register
    VGETCFG    -> List(Y, N, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    Y,RESP_CFG,   N,N,N),
    VGETVL     -> List(Y, N, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    Y,RESP_VL,    N,N,N),
    VF         -> List(Y, N, Y, CMD_VF,      VRT_X, IMM_ADDR, Y,N,Y,N,    N,RESP_X,     N,N,N),
    VFT        -> List(Y, N, Y, CMD_VFT,     VRT_X, IMM_ADDR, Y,N,Y,N,    N,RESP_X,     N,N,N),
    VMSA       -> List(Y, N, Y, CMD_VMSA,    VRT_A, IMM_RS1,  N,Y,Y,N,    N,RESP_X,     N,N,N),
    VMSS       -> List(Y, N, Y, CMD_VMSS,    VRT_S, IMM_RS1,  N,Y,Y,N,    N,RESP_X,     N,N,N),
    // Exception and save/restore instructions
    VXCPTCAUSE -> List(Y, Y, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    Y,RESP_CAUSE, N,N,N),
    VXCPTAUX   -> List(Y, Y, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    Y,RESP_AUX,   N,N,N),
    VXCPTSAVE  -> List(Y, Y, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    N,RESP_X,     Y,N,N),
    VXCPTRESTORE->List(Y, Y, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    N,RESP_X,     N,Y,N),
    VXCPTKILL  -> List(Y, Y, N, CMD_X,       VRT_X, IMM_X,    N,N,N,N,    N,RESP_X,     N,N,Y)
  )
}

class RoCCUnit(implicit p: Parameters) extends HwachaModule()(p) with LaneParameters with MinMax{
  import HwachaDecodeTable._

  val io = new Bundle {
    val rocc = new rocket.RoCCInterface

    val vf_active = Bool(INPUT)
    val pending = new Bundle {
      val mseq = Bool(INPUT)
      val mrt = Bool(INPUT)
    }

    val cfg = new HwachaConfigIO

    val cmdq = new CMDQIO
    val vrucmdq = new CMDQIO
  }

  // Cofiguration state
  val cfg_maxvl = Reg(init=UInt(8, bMLVLen))
  val cfg_vl = Reg(init=UInt(0, bMLVLen))
  val cfg_vregs = Reg(init=UInt(256, bfVRegs))
  val cfg_pregs = Reg(init=UInt(16, bfPRegs))

  io.cfg.morelax := Bool(false)
  io.cfg.lstride := UInt(3)
  io.cfg.vstride := cfg_vregs
  io.cfg.pstride := cfg_pregs

  // Decode
  val rocc_inst = io.rocc.cmd.bits.inst.toBits
  val rocc_imm12 = rocc_inst(31, 20)
  val rocc_split_imm12 = Cat(rocc_inst(31, 25), rocc_inst(11, 7))
  val rocc_rd = rocc_inst(11, 7)
  val rocc_srd = Cat(rocc_inst(22, 20), rocc_rd)

  val logic = rocket.DecodeLogic(rocc_inst, HwachaDecodeTable.default, HwachaDecodeTable.table)
  val cs = logic.map {
    case b if b.inputs.head.getClass == classOf[Bool] => b.toBool
    case u => u
  }

  val flush_kill = this.reset 
  val cmdq = Module(new CMDQ(resetSignal = flush_kill))

  // TODO: probably want to change the length of queues in here
  val vrucmdq = Module(new CMDQ(resetSignal = flush_kill))
  val respq = Module(new Queue(io.rocc.resp.bits, 2))

  val (inst_val: Bool) :: (inst_priv: Bool) :: (enq_cmd_ : Bool) :: sel_cmd :: rd_type :: sel_imm :: cs0 = cs
  val (check_vl: Bool) :: (enq_rd_ : Bool) :: (enq_imm_ : Bool) :: (enq_vcnt_ : Bool) :: cs1 = cs0
  val (enq_resp_ : Bool) :: sel_resp :: (decode_save: Bool) :: (decode_rest: Bool) :: (decode_kill: Bool) :: Nil = cs1

  val stall_hold = Reg(init=Bool(false))
  val stall = stall_hold

  val decode_vcfg = enq_cmd_ && (sel_cmd === CMD_VSETCFG)
  val decode_vsetvl = enq_cmd_ && (sel_cmd === CMD_VSETVL)

  val keepcfg = Bool()
  val mask_vcfg = !decode_vcfg || !keepcfg

  val mask_vl = !check_vl || cfg_vl =/= UInt(0)
  val enq_cmd = mask_vl && enq_cmd_
  val enq_imm = mask_vl && enq_imm_
  val enq_rd = mask_vl && enq_rd_
  val enq_cnt = Bool(false)
  val enq_resp = mask_vl && enq_resp_

  val vru_insts_wanted = !(sel_cmd === CMD_VMSS)
  val vru_enq_cmd = enq_cmd && vru_insts_wanted
  val vru_enq_imm = enq_imm && vru_insts_wanted
  val vru_enq_rd = enq_rd && vru_insts_wanted
  val vru_enq_cnt = Bool(false)

  val mask_vxu_cmd_ready = !enq_cmd || cmdq.io.enq.cmd.ready
  val mask_vxu_imm_ready = !enq_imm || cmdq.io.enq.imm.ready
  val mask_vxu_rd_ready = !enq_rd || cmdq.io.enq.rd.ready
  val mask_vxu_cnt_ready = !enq_cnt || cmdq.io.enq.cnt.ready
  val mask_resp_ready = !enq_resp || respq.io.enq.ready

  val mask_vru_cmd_ready = !vru_enq_cmd || vrucmdq.io.enq.cmd.ready
  val mask_vru_imm_ready = !vru_enq_imm || vrucmdq.io.enq.imm.ready
  val mask_vru_rd_ready = !vru_enq_rd || vrucmdq.io.enq.rd.ready
  val mask_vru_cnt_ready = !vru_enq_cnt || vrucmdq.io.enq.cnt.ready

  def fire(exclude: Bool, include: Bool*) = {
    val rvs = Seq(
      !stall, mask_vcfg,
      io.rocc.cmd.valid,
      mask_vxu_cmd_ready, 
      mask_vxu_imm_ready, 
      mask_vxu_rd_ready, 
      mask_vxu_cnt_ready, 
      mask_resp_ready,
      mask_vru_cmd_ready, 
      mask_vru_imm_ready, 
      mask_vru_rd_ready, 
      mask_vru_cnt_ready
    )
    (rvs.filter(_ ne exclude) ++ include).reduce(_ && _)
  }

  // Logic to handle vector length calculation
  val nregs_imm = new DecodeRegConfig().fromBits(rocc_imm12)
  val nregs_rs1 = new DecodeRegConfig().fromBits(io.rocc.cmd.bits.rs1)
  val nvpr = UInt(1, bfVRegs) + nregs_imm.nvpr + nregs_rs1.nvpr // widening add
  val nppr = UInt(1, bfPRegs) + nregs_imm.nppr + nregs_rs1.nppr // widening add

  // vector length lookup
  val lookup_tbl_nvpr = (0 to nVRegs).toArray map { n =>
    (UInt(n), UInt(if (n < 2) (nSRAM) else (nSRAM / n), width = log2Down(nSRAM)+1)) }
  val lookup_tbl_nppr = (0 to nPRegs).toArray map { n =>
    (UInt(n), UInt(if (n < 2) (nPred) else (nPred / n), width = log2Down(nPred)+1)) }

  // epb: elements per bank
  val epb_nvpr = Lookup(nvpr, lookup_tbl_nvpr.last._2, lookup_tbl_nvpr)
  val epb_nppr = Lookup(nppr, lookup_tbl_nppr.last._2, lookup_tbl_nppr)
  val epb = min(epb_nvpr, epb_nppr)
  val new_maxvl = epb * UInt(nLanes) * UInt(nBanks) * UInt(nSlices)
  val new_vl = min(cfg_maxvl, io.rocc.cmd.bits.rs1)(bMLVLen-1, 0)

  when (fire(null, decode_vcfg)) {
    cfg_maxvl := new_maxvl
    cfg_vl := UInt(0)
    cfg_vregs := nvpr
    cfg_pregs := nppr
    printf("H: VSETCFG[nlanes=%d][nvpr=%d][nppr=%d][lstride=%d][epb_nvpr=%d][epb_nppr=%d][maxvl=%d]\n",
      UInt(nLanes), nvpr, nppr, io.cfg.lstride, epb_nvpr, epb_nppr, new_maxvl)
  }
  when (fire(null, decode_vsetvl)) {
    cfg_vl := new_vl
    printf("H: VSETVL[maxvl=%d][vl=%d]\n",
      cfg_maxvl, new_vl)
  }

  // Hookup ready port of RoCC cmd queue
  //COLIN FIXME: we use the exception flag to set a sticky bit that causes to always be ready after exceptions
  io.rocc.cmd.ready := fire(io.rocc.cmd.valid)
  cmdq.io.enq.cmd.valid := fire(mask_vxu_cmd_ready, enq_cmd)
  cmdq.io.enq.imm.valid := fire(mask_vxu_imm_ready, enq_imm)
  cmdq.io.enq.rd.valid := fire(mask_vxu_rd_ready, enq_rd)
  cmdq.io.enq.cnt.valid := fire(mask_vxu_cnt_ready, enq_cnt)
  respq.io.enq.valid := fire(mask_resp_ready, enq_resp)

  vrucmdq.io.enq.cmd.valid := fire(mask_vru_cmd_ready, vru_enq_cmd)
  vrucmdq.io.enq.imm.valid := fire(mask_vru_imm_ready, vru_enq_imm)
  vrucmdq.io.enq.rd.valid := fire(mask_vru_rd_ready, vru_enq_rd)
  vrucmdq.io.enq.cnt.valid := fire(mask_vru_cnt_ready, vru_enq_cnt)

  // cmdq dpath
  val cmd_out = sel_cmd
  val imm_out = 
    MuxLookup(sel_imm, Bits(0), Array(
      IMM_VLEN -> new_vl,
      IMM_RS1  -> io.rocc.cmd.bits.rs1,
      IMM_ADDR -> (io.rocc.cmd.bits.rs1 + rocc_split_imm12.toSInt).toUInt
    ))
  val rd_out = Mux(rd_type === VRT_S, rocc_srd, rocc_rd)
  cmdq.io.enq.cmd.bits := cmd_out
  cmdq.io.enq.imm.bits := imm_out
  cmdq.io.enq.rd.bits := rd_out

  vrucmdq.io.enq.cmd.bits := cmd_out
  vrucmdq.io.enq.imm.bits := imm_out
  vrucmdq.io.enq.rd.bits := rd_out


  // respq dpath
  respq.io.enq.bits.data :=
    MuxLookup(sel_resp, Bits(0), Array(
      RESP_NVL -> new_vl,
      RESP_CFG -> Cat(cfg_pregs, cfg_vregs),
      RESP_VL  -> cfg_vl
    ))
  respq.io.enq.bits.rd := io.rocc.cmd.bits.inst.rd

  // hookup output ports
  io.cmdq.cmd <> cmdq.io.deq.cmd
  io.cmdq.imm <> cmdq.io.deq.imm
  io.cmdq.rd <> cmdq.io.deq.rd

  io.rocc.resp <> respq.io.deq

  io.vrucmdq.cmd <> vrucmdq.io.deq.cmd
  io.vrucmdq.imm <> vrucmdq.io.deq.imm
  io.vrucmdq.rd <> vrucmdq.io.deq.rd

 // COLIN FIXME: update keepcfg
  keepcfg :=
    cmdq.io.deq.cmd.valid ||
    io.vf_active || io.pending.mseq

  // Busy signal for fencing
  val busy =
    cmdq.io.deq.cmd.valid ||
    io.vf_active || io.pending.mseq || io.pending.mrt

  io.rocc.busy := busy

  // Setup interrupt
  io.rocc.interrupt := Bool(false)


  //COLIN FIXME: do we need to do something on the rocc.s field that hold used to do
  /*
  val reg_hold = Reg(init=Bool(false))
  when (rocc_valid && decl_hold && construct_ready(null)) { reg_hold := Bool(true) }
  when (reg_hold && !io.rocc.s) { reg_hold := Bool(false) }

  io.xcpt.rocc.exception := io.rocc.exception
  io.xcpt.rocc.evac := rocc_valid && decl_evac && construct_ready(null)
  io.xcpt.rocc.evac_addr := io.rocc.cmd.bits.rs1
  io.xcpt.rocc.hold := reg_hold
  io.xcpt.rocc.kill := rocc_valid && decl_kill && construct_ready(null)
  */

}
