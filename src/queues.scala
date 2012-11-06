package hwacha

import Chisel._
import Node._
import scala.math._

class io_qcnt(w: Int) extends Bundle
{
  val flush = Bool(INPUT)
  val inc = Bool(INPUT)
  val dec = Bool(INPUT)
  val qcnt = UFix(INPUT, w)
  val watermark = Bool(OUTPUT)
  val qcnt2 = UFix(INPUT, w)
  val watermark2 = Bool(OUTPUT)
  val full = Bool(OUTPUT)
  val empty = Bool(OUTPUT)
}

class qcnt(reset_cnt: Int, max_cnt: Int, flushable: Boolean = false) extends Component
{
  val size = log2Down(max_cnt) + 1

  val io = new io_qcnt(size)
  val count = Reg(resetVal = UFix(reset_cnt, size))
  val next_count = UFix(width = size)

  next_count := count
  when (io.inc ^ io.dec) {
    when (io.inc) {next_count := count + UFix(1)}
    when (!io.inc) {next_count := count - UFix(1)}
  }

  count := next_count

  // we need to look at what's in the queue on the next cycle
  io.watermark := count >= io.qcnt
  io.watermark2 := count >= io.qcnt2

  io.full := (count === UFix(reset_cnt,size))
  io.empty := (count === UFix(0,size))

  if (flushable) {
    when (io.flush) { count := UFix(reset_cnt) }
  }
}

class io_skidbuf[T <: Data](data: => T) extends Bundle
{
  val flush = Bool(INPUT)
  val enq = new FIFOIO()(data).flip
  val deq = new FIFOIO()(data)
  val pipereg = new PipeIO()(data)
  val nack = Bool(INPUT)
  val empty = Bool(OUTPUT)
  val kill = Bool(OUTPUT)
}

class skidbuf[T <: Data](late_nack: Boolean, flushable: Boolean = false)(data: => T) extends Component
{
  val io = new io_skidbuf(data)

  val pipereg = new Queue(1, pipe = true, flushable = flushable)(data)

  pipereg.io.flush := io.flush
  pipereg.io.enq <> io.enq

  val reg_ready = Reg(resetVal = Bool(true))
  val reg_nack = Reg(resetVal = Bool(false))

  reg_ready := io.deq.ready
  reg_nack := io.nack

  var rejected = !reg_ready || io.nack
  pipereg.io.deq.ready := !rejected
  io.empty := !pipereg.io.deq.valid
  io.kill := Bool(false)

  if (late_nack) {
    rejected = !reg_ready || reg_nack
    pipereg.io.deq.ready := !rejected && !io.nack
    io.kill := reg_nack
  }

  val sel_pipereg = rejected && pipereg.io.deq.valid
  io.deq.valid := Mux(sel_pipereg, pipereg.io.deq.valid, io.enq.valid)
  io.deq.bits := Mux(sel_pipereg, pipereg.io.deq.bits, io.enq.bits)

  if (flushable) {
    when (io.flush) {
      reg_ready := Bool(true)
      reg_nack := Bool(false)
    }
  }

  io.pipereg.bits <> pipereg.io.deq.bits
  io.pipereg.valid := pipereg.io.deq.valid
}

object SkidBuffer
{
  def apply[T <: Data](enq: FIFOIO[T], late_nack: Boolean = false, flushable: Boolean = false) =
  {
    val sb = new skidbuf(late_nack, flushable)(enq.bits.clone)
    sb.io.enq <> enq
    sb
  }
}

class io_queue_reorder_qcnt_enq_bundle(ROQ_DATA_SIZE: Int, ROQ_TAG_SIZE: Int) extends Bundle
{
  val data = Bits(width = ROQ_DATA_SIZE)
  val rtag = UFix(width = ROQ_TAG_SIZE)
}

class io_queue_reorder_qcnt(ROQ_DATA_SIZE: Int, ROQ_TAG_SIZE: Int) extends Bundle
{
  val flush = Bool(INPUT)
  val deq_rtag = new FIFOIO()(Bits(width = ROQ_TAG_SIZE))
  val deq_data = new FIFOIO()(Bits(width = ROQ_DATA_SIZE))
  val enq = new PipeIO()(new io_queue_reorder_qcnt_enq_bundle(ROQ_DATA_SIZE, ROQ_TAG_SIZE)).flip

  val qcnt = UFix(INPUT, ROQ_TAG_SIZE)
  val watermark = Bool(OUTPUT)
}

class queue_reorder_qcnt(ROQ_DATA_SIZE: Int, ROQ_TAG_ENTRIES: Int, ROQ_MAX_QCNT: Int, flushable: Boolean = false) extends Component
{
  val ROQ_TAG_SIZE = log2Up(ROQ_TAG_ENTRIES)

  val io = new io_queue_reorder_qcnt(ROQ_DATA_SIZE, ROQ_TAG_SIZE)

  val read_ptr = Reg(resetVal = UFix(0, ROQ_TAG_SIZE))
  val write_ptr = Reg(resetVal = UFix(0, ROQ_TAG_SIZE))
  val read_ptr_next = read_ptr + UFix(1)
  val write_ptr_next = write_ptr + UFix(1)
  val full = Reg(resetVal = Bool(false))

  val roq_data_deq = io.deq_data.ready && io.deq_data.valid
  val roq_rtag_deq = io.deq_rtag.ready && io.deq_rtag.valid

  val data_array = Mem(ROQ_TAG_ENTRIES, seqRead = true) { io.enq.bits.data.clone }
  val data_out = Reg() { io.enq.bits.data.clone }
  when (io.enq.valid) { data_array(io.enq.bits.rtag) := io.enq.bits.data }
  data_out := data_array(Mux(roq_data_deq, read_ptr_next, read_ptr))

  val vb_array = Reg(resetVal = Bits(0, ROQ_TAG_ENTRIES))
  val vb_update_read = Mux(roq_data_deq, ~(Bits(1) << read_ptr), Fill(ROQ_TAG_ENTRIES, Bits(1)))
  val vb_update_write = Mux(io.enq.valid, (Bits(1) << io.enq.bits.rtag), Bits(0, ROQ_TAG_ENTRIES))
  vb_array := (vb_array & vb_update_read) | vb_update_write

  val deq_data_val_int = Reg(resetVal = Bool(false))

  deq_data_val_int := vb_array(read_ptr)

  when (roq_rtag_deq) { write_ptr := write_ptr_next }
  when (roq_data_deq) {
    deq_data_val_int := vb_array(read_ptr_next)
    read_ptr := read_ptr_next
  }

  io.deq_rtag.valid := !full
  io.deq_rtag.bits := write_ptr

  val full_next =
    Mux(roq_rtag_deq && !roq_data_deq && (write_ptr_next === read_ptr), Bool(true),
    Mux(roq_data_deq && full, Bool(false),
        full))

  full := full_next


  io.deq_data.valid := deq_data_val_int
  io.deq_data.bits := data_out

  // Logic for watermark
  val shifted_vb_array = Reg(resetVal = Bits(0, ROQ_TAG_ENTRIES))
  val shifted_write_ptr =
    Mux(read_ptr <= io.enq.bits.rtag, io.enq.bits.rtag - read_ptr,
        UFix(ROQ_TAG_ENTRIES) - read_ptr + io.enq.bits.rtag)(ROQ_TAG_SIZE-1,0)

  val shifted_vb_update_write =
    Mux(io.enq.valid, (Bits(1) << shifted_write_ptr),
        Bits(0, ROQ_TAG_ENTRIES))

  shifted_vb_array :=
    Mux(roq_data_deq, ((shifted_vb_array | shifted_vb_update_write) >> UFix(1)),
        (shifted_vb_array | shifted_vb_update_write))

  // a limited version of leading count ones
  // maximum cnt is defined by ROQ_MAX_QCNT
  var sel = shifted_vb_array(0)
  var locnt = UFix(0, ROQ_TAG_SIZE)
  for (i <- 0 until ROQ_MAX_QCNT) {
    locnt = Mux(sel, UFix(i+1), locnt)
    sel = sel & shifted_vb_array(i+1)
  }

  io.watermark := locnt >= io.qcnt

  if (flushable) {
    when (io.flush) {
      read_ptr := UFix(0)
      write_ptr := UFix(0)
      full := Bool(false)
      vb_array := Bits(0)
      deq_data_val_int := Bool(false)
      shifted_vb_array := Bits(0)
    }
  }
}

