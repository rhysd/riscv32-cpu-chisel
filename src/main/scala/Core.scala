package cpu

import chisel3._
import chisel3.util._
import common.Consts._
import common.Instructions._

class Core extends Module {
  val io = IO(new Bundle {
    // Flip input and output to connect to memory
    val imem = Flipped(new ImemPortIo())
    val dmem = Flipped(new DmemPortIo())

    // This port signal will be `true` when a program finished
    val exit = Output(Bool())
  })

  // RISC-V has 32 registers. Size is 32bits (32bits * 32regs).
  val regfile = Mem(32, UInt(WORD_LEN.W))

  /*
   * Instruction Fetch (IF)
   */

  // Program counter register. It counts up per 4 bytes since size of instruction is 32bits, but
  // memory address is byte oriented.
  val pc = RegInit(START_ADDR)
  pc := pc + 4.U(WORD_LEN.W) // Add 4 bytes per cycle

  // Connect program counter to address output. This output is connected to memory to fetch the
  // address as instruction
  io.imem.addr := pc
  val inst = io.imem.inst

  /*
   * Instruction Decode (ID)
   */

  // Spec 2.2 Base Instruction Formats
  //
  //  31      30 29 28 27 26 25 24 23 22 21   20   19 18 17 16 15 14 13 12 11 10 9 8    7   6 5 4 3 2 1 0
  //  ---------------------------------------------------------------------------------------------------
  // |         funct7          |       rs2        |     rs1      | funct3 |      rd        |   opcode    | R-type
  //  ---------------------------------------------------------------------------------------------------
  // |                imm[11:0]                   |     rs1      | funct3 |      rd        |   opcode    | I-type
  //  ---------------------------------------------------------------------------------------------------
  // |        imm[11:5]        |       rs2        |     rs1      | funct3 |   imm[4:0]     |   opcode    | S-type
  //  ---------------------------------------------------------------------------------------------------
  // |imm[12]|    imm[10:5]    |       rs2        |     rs1      | funct3 |imm[4:1]|imm[11]|   opcode    | B-type
  //  ---------------------------------------------------------------------------------------------------
  // |                             imm[31:12]                             |      rd        |   opcode    | U-type
  //  ---------------------------------------------------------------------------------------------------
  // |imm[20]|         imm[10:1]          |imm[11]|      imm[19:12]       |      rd        |   opcode    | J-type
  //  ---------------------------------------------------------------------------------------------------

  val rs1_addr = inst(19, 15)
  val rs2_addr = inst(24, 20)
  val wb_addr = inst(11, 7) // rd
  // When rs1 is non-zero, read the address from register map. When the address is zero, the value
  // of register #0 must always be zero.
  val rs1_data = Mux(rs1_addr =/= 0.U(WORD_LEN.U), regfile(rs1_addr), 0.U(WORD_LEN.W))
  val rs2_data = Mux(rs2_addr =/= 0.U(WORD_LEN.U), regfile(rs2_addr), 0.U(WORD_LEN.W))

  // Spec 2.6: The effective address is obtained by adding register rs1 to the sign-extended 12-bit offset.
  // sext 12bit value to 32bit value.
  val imm_i = inst(31, 20) // imm for I-type
  val imm_i_sext = Cat(Fill(20, imm_i(11)), imm_i)
  val imm_s = Cat(inst(31, 25), inst(11, 7)) // imm for S-type
  val imm_s_sext = Cat(Fill(20, imm_s(11)), imm_s)

  /*
   * Execute (EX)
   */
  // Arithmetic Logic Unit process arithmetic/logical calculations for each instruction.
  val alu_out = MuxCase(0.U(WORD_LEN.W), Seq(
    // Load Word: x[rs1] + sext(imm_i)
    // Calculate the address to load by adding immediate.
    // This may cause integer overflow. In the case, Chisel truncates the value to 32bit value.
    // ADDI: x[rs1] + sext(imm_i)
    (inst === LW || inst === ADDI) -> (rs1_data + imm_i_sext),
    // Store Word: x[rs1] + sext(imm_s)
    (inst === SW) -> (rs1_data + imm_s_sext),
    // x[rs1] + x[rs2]
    (inst === ADD) -> (rs1_data + rs2_data),
    // x[rs1] - x[rs2]
    (inst === SUB) -> (rs1_data - rs2_data),
  ))

  /*
   * Memory Access (MEM)
   */
  // LW: x[rd] = M[x[rs1] + sext(imm_i)]
  io.dmem.addr := alu_out // Always output data to memory regardless of instruction

  // SW: M[x[rs1] + sext(imm_s)] = x[rs2]
  io.dmem.wen := inst === SW
  io.dmem.wdata := rs2_data

  /*
   * Write Back (WB)
   */
  // By default, write back the ALU result to register
  val wb_data = MuxCase(alu_out, Seq(
    (inst === LW) -> io.dmem.rdata, // Loaded data from memory
  ))
  when(
    inst === LW
      || inst === ADD
      || inst === ADDI
      || inst === SUB
  ) {
    regfile(wb_addr) := wb_data // Write back to the register specified by rd
  }

  // For debugging.
  // `exit` port output is `true` when the instruction is 0x00602823. It means 2nd line in sw.hex.
  io.exit := inst === 0x00602823.U(WORD_LEN.W)

  printf(p"pc:         0x${Hexadecimal(pc)}\n") // program counter
  printf(p"inst:       0x${Hexadecimal(inst)}\n") // fetched instruction
  printf(p"rs1_addr:   $rs1_addr\n") // register1 address
  printf(p"rs2_addr:   $rs2_addr\n") // register2 address
  printf(p"wb_addr:    $wb_addr\n") // register address to write data loaded from memory
  printf(p"rs1_data:   0x${Hexadecimal(rs1_data)}\n") // data at register1
  printf(p"rs2_data:   0x${Hexadecimal(rs2_data)}\n") // data at register2
  printf(p"wb_data:    0x${Hexadecimal(wb_data)}\n") // data to write back to register loaded from memory
  printf(p"dmem.addr:  ${io.dmem.addr}\n") // memory address loaded by LW
  printf(p"dmem.wen:   ${io.dmem.wen}\n") // boolean signal to know the timing to write data to memory
  printf(p"dmem.wdata: 0x${Hexadecimal(io.dmem.wdata)}\n") // data written to memory by SW instruction
  printf("-------------\n")
}
