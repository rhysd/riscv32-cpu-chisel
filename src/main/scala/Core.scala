package cpu

import chisel3._
import chisel3.util._
import Consts._
import Instructions._

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

  // Uninitialized ports for branch instructions. They will be connected later.
  var br_flag = Wire(Bool())
  val br_target = Wire(UInt(WORD_LEN.W))

  // Program counter register. It counts up per 4 bytes since size of instruction is 32bits, but
  // memory address is byte oriented.
  val pc = RegInit(START_ADDR)
  pc := MuxCase(pc + 4.U(WORD_LEN.W), Seq(
    br_flag -> br_target, // Branch instructions write back br_target address to program counter
  ))

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

  // decode imm of B-type instruction
  val imm_b = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8))
  // imm[0] does not exist in B-type instruction. This is because the first bit of program counter
  // is always zero (p.126). Size of instruction is 32bit or 16bit, so instruction pointer (pc)
  // address always points an even address.
  val imm_b_sext = Cat(Fill(19, imm_b(11)), imm_b, 0.U(1.U))

  // Decode operand sources and memory/register write back behavior
  val List(exe_fun, op1_sel, op2_sel, mem_wen, rf_wen, wb_sel) = ListLookup(
    inst,
    List(ALU_NONE, OP1_RS1, OP2_RS2, MEN_NONE, REN_NONE, WB_NONE),
    Array(
      // 2.6 Load and Store Instructions
      LW    -> List(ALU_ADD,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_MEM), // x[rs1] + sext(imm_i)
      SW    -> List(ALU_ADD,  OP1_RS1, OP2_IMS, MEN_SCALAR, REN_NONE,   WB_NONE), // x[rs1] + sext(imm_s)
      // 2.4 Integer Computational Instructions
      ADD   -> List(ALU_ADD,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] + x[rs2]
      ADDI  -> List(ALU_ADD,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] + sext(imm_i)
      SUB   -> List(ALU_SUB,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] - x[rs2]
      AND   -> List(ALU_AND,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] & x[rs2]
      OR    -> List(ALU_OR,   OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] | x[rs2]
      XOR   -> List(ALU_XOR,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] ^ x[rs2]
      ANDI  -> List(ALU_AND,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] & sext(imm_i)
      ORI   -> List(ALU_OR ,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] | sext(imm_i)
      XORI  -> List(ALU_XOR,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] ^ sext(imm_i)
      SLL   -> List(ALU_SLL,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] << x[rs2](4,0)
      SRL   -> List(ALU_SRL,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] >>u x[rs2](4,0)
      SRA   -> List(ALU_SRA,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] >>s x[rs2](4,0)
      SLLI  -> List(ALU_SLL,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] << imm_i_sext(4,0)
      SRLI  -> List(ALU_SRL,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] >>u imm_i_sext(4,0)
      SRAI  -> List(ALU_SRA,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] >>s imm_i_sext(4,0)
      SLT   -> List(ALU_SLT,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] <s x[rs2]
      SLTU  -> List(ALU_SLTU, OP1_RS1, OP2_RS2, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] <u x[rs2]
      SLTI  -> List(ALU_SLT,  OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] <s imm_i_sext
      SLTIU -> List(ALU_SLTU, OP1_RS1, OP2_IMI, MEN_NONE,   REN_SCALAR, WB_ALU), // x[rs1] <u imm_i_sext
      // 2.5 Control Transfer Instructions
      BEQ   -> List(BR_BEQ,   OP1_RS1, OP2_RS2, MEN_NONE,   REN_NONE,   WB_NONE), // x[rs1] === x[rs2] then PC+sext(imm_b)
      BNE   -> List(BR_BNE,   OP1_RS1, OP2_RS2, MEN_NONE,   REN_NONE,   WB_NONE), // x[rs1] =/= x[rs2] then PC+sext(imm_b)
      BGE   -> List(BR_BGE,   OP1_RS1, OP2_RS2, MEN_NONE,   REN_NONE,   WB_NONE), // x[rs1] >=s x[rs2] then PC+sext(imm_b)
      BGEU  -> List(BR_BGEU,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_NONE,   WB_NONE), // x[rs1] >=u x[rs2] then PC+sext(imm_b)
      BLT   -> List(BR_BLT,   OP1_RS1, OP2_RS2, MEN_NONE,   REN_NONE,   WB_NONE), // x[rs1] <s x[rs2]  then PC+sext(imm_b)
      BLTU  -> List(BR_BLTU,  OP1_RS1, OP2_RS2, MEN_NONE,   REN_NONE,   WB_NONE), // x[rs1] <u x[rs2]  then PC+sext(imm_b)
    ),
  )

  // Determine 1st operand data signal
  val op1_data = MuxCase(0.U(WORD_LEN.W), Seq(
    (op1_sel === OP1_RS1) -> rs1_data,
  ))

  // Determine 2nd operand data signal
  val op2_data = MuxCase(0.U(WORD_LEN.W), Seq(
    (op2_sel === OP2_RS2) -> rs2_data,
    (op2_sel === OP2_IMI) -> imm_i_sext,
    (op2_sel === OP2_IMS) -> imm_s_sext,
  ))

  /*
   * Execute (EX)
   */

  // Arithmetic Logic Unit process arithmetic/logical calculations for each instruction.
  val alu_out = MuxCase(0.U(WORD_LEN.W), Seq(
    (exe_fun === ALU_ADD)  -> (op1_data + op2_data),
    (exe_fun === ALU_SUB)  -> (op1_data - op2_data),
    (exe_fun === ALU_AND)  -> (op1_data & op2_data),
    (exe_fun === ALU_OR)   -> (op1_data | op2_data),
    (exe_fun === ALU_XOR)  -> (op1_data ^ op2_data),
    // Note: (31, 0) is necessary because << extends bits of the result value
    // Note: (4, 0) is necessary for I instructions (imm[4:0])
    (exe_fun === ALU_SLL)  -> (op1_data << op2_data(4, 0))(31, 0),
    (exe_fun === ALU_SRL)  -> (op1_data >> op2_data(4, 0)).asUInt(),
    (exe_fun === ALU_SRA)  -> (op1_data.asSInt() >> op2_data(4, 0)).asUInt(),
    // Compare as signed integers
    (exe_fun === ALU_SLT)  -> (op1_data.asSInt() < op2_data.asSInt()).asUInt(),
    (exe_fun === ALU_SLTU) -> (op1_data < op2_data).asUInt(),
  ))

  // Branch instructions
  br_flag := MuxCase(false.B, Seq(
    (exe_fun === BR_BEQ)  ->  (op1_data === op2_data),
    (exe_fun === BR_BNE)  -> !(op1_data === op2_data),
    (exe_fun === BR_BLT)  ->  (op1_data.asSInt() < op2_data.asSInt()),
    (exe_fun === BR_BGE)  -> !(op1_data.asSInt() < op2_data.asSInt()),
    (exe_fun === BR_BLTU) ->  (op1_data < op2_data),
    (exe_fun === BR_BGEU) -> !(op1_data < op2_data),
  ))
  br_target := pc + imm_b_sext

  /*
   * Memory Access (MEM)
   */
  io.dmem.addr := alu_out // Always output data to memory regardless of instruction
  io.dmem.wen := mem_wen // mem_wen is integer and here it is implicitly converted to bool
  io.dmem.wdata := rs2_data

  /*
   * Write Back (WB)
   */

  // By default, write back the ALU result to register (wb_sel == WB_ALU)
  val wb_data = MuxCase(alu_out, Seq(
    (wb_sel === WB_MEM) -> io.dmem.rdata, // Loaded data from memory
  ))
  when(rf_wen === REN_SCALAR) {
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
