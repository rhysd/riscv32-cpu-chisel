package cpu

import chisel3._
import chisel3.util._
import common.Consts._

class Core extends Module {
  val io = IO(new Bundle {
    // Flip input and output to connect to memory
    val imem = Flipped(new ImemPortIo())

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
  // -----------------------------------------------------------------------------------------------------
  // |         funct7          |       rs2        |     rs1      | funct3 |      rd        |   opcode    | R-type
  // -----------------------------------------------------------------------------------------------------
  // |                imm[11:0]                   |     rs1      | funct3 |      rd        |   opcode    | I-type
  // -----------------------------------------------------------------------------------------------------
  // |        imm[11:5]        |       rs2        |     rs1      | funct3 |   imm[4:0]     |   opcode    | S-type
  // -----------------------------------------------------------------------------------------------------
  // |imm[12]|    imm[10:5]    |       rs2        |     rs1      | funct3 |imm[4:1]|imm[11]|   opcode    | B-type
  // -----------------------------------------------------------------------------------------------------
  // |                             imm[31:12]                             |      rd        |   opcode    | U-type
  // -----------------------------------------------------------------------------------------------------
  // |imm[20]|         imm[10:1]          |imm[11]|      imm[19:12]       |      rd        |   opcode    | J-type
  // -----------------------------------------------------------------------------------------------------

  val rs1_addr = inst(19, 15)
  val rs2_addr = inst(24, 20)
  val wb_addr = inst(11, 7)

  // When rs1 is non-zero, read the address from register map. When the address is zero, the value
  // of register #0 must always be zero.
  val rs1_data = Mux(rs1_addr =/= 0.U(WORD_LEN.U), regfile(rs1_addr), 0.U(WORD_LEN.W))
  val rs2_data = Mux(rs2_addr =/= 0.U(WORD_LEN.U), regfile(rs2_addr), 0.U(WORD_LEN.W))

  // `exit` port output is `true` when the instruction is 0x34333231. 0x34333231 means the end of a loaded program.
  io.exit := inst === 0x34333231.U(WORD_LEN.W)

  printf(p"pc:       0x${Hexadecimal(pc)}\n")
  printf(p"inst:     0x${Hexadecimal(inst)}\n")
  printf(p"rs1_addr: $rs1_addr\n")
  printf(p"rs2_addr: $rs2_addr\n")
  printf(p"wb_addr:  $wb_addr\n")
  printf(p"rs1_data: 0x${Hexadecimal(rs1_data)}\n")
  printf(p"rs2_data: 0x${Hexadecimal(rs2_data)}\n")
  printf("-------------\n")
}
