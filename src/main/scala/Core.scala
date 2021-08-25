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

    // global pointer. it is necessary to check if test case passes in riscv-tests
    val gp = Output(UInt(WORD_LEN.W))
  })

  val inst = io.imem.inst

  // RISC-V has 32 registers. Size is 32bits (32bits * 32regs).
  val regfile = Mem(32, UInt(WORD_LEN.W))
  // Control and Status Registers
  val csr_regfile = Mem(4096, UInt(WORD_LEN.W))

  /*
   * Instruction Fetch (IF)
   */

  // Uninitialized ports for branch instructions. They will be connected later.
  val br_flag = Wire(Bool())
  val br_target = Wire(UInt(WORD_LEN.W))

  val jmp_flag = inst === JAL || inst === JALR
  val alu_out = Wire(UInt(WORD_LEN.W))

  // Program counter register. It counts up per 4 bytes since size of instruction is 32bits, but
  // memory address is byte oriented.
  val pc = RegInit(START_ADDR)
  val pc_next = pc + 4.U(WORD_LEN.W)
  pc := MuxCase(pc_next, Seq(
    br_flag  -> br_target, // Branch instructions write back br_target address to program counter
    jmp_flag -> alu_out, // Jump instructions calculate the jump address by ALU
    // CSRs[0x305] is mtvec (trap_vector). The process on exception (syscall) is written at the
    // trap_vector address. Note that there is no OS on this CPU.
    (inst === ECALL) -> csr_regfile(0x305),
  ))

  // Connect program counter to address output. This output is connected to memory to fetch the
  // address as instruction
  io.imem.addr := pc

  /*
   * Instruction Decode (ID)
   */

  // Spec 2.2 Base Instruction Formats
  //
  //  31      30 29 28 27 26 25 24 23 22 21   20   19 18 17 16 15 14 13 12 11 10 9 8     7   6 5 4 3 2 1 0
  //  ----------------------------------------------------------------------------------------------------
  // |         funct7          |       rs2        |     rs1      | funct3 |       rd        |   opcode    | R-type
  //  ----------------------------------------------------------------------------------------------------
  // |                imm[11:0]                   |     rs1      | funct3 |       rd        |   opcode    | I-type
  //  ----------------------------------------------------------------------------------------------------
  // |        imm[11:5]        |       rs2        |     rs1      | funct3 |   imm[4:0]      |   opcode    | S-type
  //  ----------------------------------------------------------------------------------------------------
  // |imm[12]|    imm[10:5]    |       rs2        |     rs1      | funct3 |imm[4:1] |imm[11]|   opcode    | B-type
  //  ----------------------------------------------------------------------------------------------------
  // |                             imm[31:12]                             |       rd        |   opcode    | U-type
  //  ----------------------------------------------------------------------------------------------------
  // |imm[20]|         imm[10:1]          |imm[11]|      imm[19:12]       |       rd        |   opcode    | J-type
  //  ----------------------------------------------------------------------------------------------------

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

  // Decode imm of B-type instruction
  val imm_b = Cat(inst(31), inst(7), inst(30, 25), inst(11, 8))
  // imm[0] does not exist in B-type instruction. This is because the first bit of program counter
  // is always zero (p.126). Size of instruction is 32bit or 16bit, so instruction pointer (pc)
  // address always points an even address.
  val imm_b_sext = Cat(Fill(19, imm_b(11)), imm_b, 0.U(1.U))

  // Decode imm of J-type instruction
  val imm_j = Cat(inst(31), inst(19, 12), inst(20), inst(30, 21))
  val imm_j_sext = Cat(Fill(11, imm_j(19)), imm_j, 0.U(1.U)) // Set LSB to zero

  // Decode imm of U-type instruction
  val imm_u = inst(31, 12)
  val imm_u_shifted = Cat(imm_u, Fill(12, 0.U)) // for LUI and AUIPC

  // Decode imm of I-type instruction
  val imm_z = inst(19, 15)
  val imm_z_uext = Cat(Fill(27, 0.U), imm_z) // for CSR instructions

  // Decode operand sources and memory/register write back behavior
  val List(exe_fun, op1_sel, op2_sel, mem_wen, rf_wen, wb_sel, csr_cmd) = ListLookup(
    inst,
    List(ALU_NONE, OP1_RS1, OP2_RS2, MEN_NONE, REN_NONE, WB_NONE, CSR_NONE),
    Array(
      // 2.6 Load and Store Instructions
      LW     -> List(ALU_ADD,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_MEM,  CSR_NONE), // x[rs1] + sext(imm_i)
      SW     -> List(ALU_ADD,  OP1_RS1,  OP2_IMS,  MEN_SCALAR, REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] + sext(imm_s)
      // 2.4 Integer Computational Instructions
      ADD    -> List(ALU_ADD,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] + x[rs2]
      ADDI   -> List(ALU_ADD,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] + sext(imm_i)
      SUB    -> List(ALU_SUB,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] - x[rs2]
      AND    -> List(ALU_AND,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] & x[rs2]
      OR     -> List(ALU_OR,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] | x[rs2]
      XOR    -> List(ALU_XOR,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] ^ x[rs2]
      ANDI   -> List(ALU_AND,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] & sext(imm_i)
      ORI    -> List(ALU_OR ,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] | sext(imm_i)
      XORI   -> List(ALU_XOR,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] ^ sext(imm_i)
      SLL    -> List(ALU_SLL,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] << x[rs2](4,0)
      SRL    -> List(ALU_SRL,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>u x[rs2](4,0)
      SRA    -> List(ALU_SRA,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>s x[rs2](4,0)
      SLLI   -> List(ALU_SLL,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] << imm_i_sext(4,0)
      SRLI   -> List(ALU_SRL,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>u imm_i_sext(4,0)
      SRAI   -> List(ALU_SRA,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>s imm_i_sext(4,0)
      SLT    -> List(ALU_SLT,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <s x[rs2]
      SLTU   -> List(ALU_SLTU, OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <u x[rs2]
      SLTI   -> List(ALU_SLT,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <s imm_i_sext
      SLTIU  -> List(ALU_SLTU, OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <u imm_i_sext
      LUI    -> List(ALU_ADD,  OP1_NONE, OP2_IMU,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // sext(imm_u[31:12] << 12)
      AUIPC  -> List(ALU_ADD,  OP1_PC,   OP2_IMU,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // PC + sext(imm_u[31:12] << 12)
      // 2.5 Control Transfer Instructions
      BEQ    -> List(BR_BEQ,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] === x[rs2] then PC+sext(imm_b)
      BNE    -> List(BR_BNE,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] =/= x[rs2] then PC+sext(imm_b)
      BGE    -> List(BR_BGE,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] >=s x[rs2] then PC+sext(imm_b)
      BGEU   -> List(BR_BGEU,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] >=u x[rs2] then PC+sext(imm_b)
      BLT    -> List(BR_BLT,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] <s x[rs2]  then PC+sext(imm_b)
      BLTU   -> List(BR_BLTU,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] <u x[rs2]  then PC+sext(imm_b)
      JAL    -> List(ALU_ADD,  OP1_PC,   OP2_IMJ,  MEN_NONE,   REN_SCALAR, WB_PC,   CSR_NONE), // x[rd] <- PC+4 and PC+sext(imm_j)
      JALR   -> List(ALU_JALR, OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_PC,   CSR_NONE), // x[rd] <- PC+4 and (x[rs1]+sext(imm_i))&~1
      // 9.1 "Zicsr", Control and Status Register (CSR) Instructions
      CSRRW  -> List(ALU_RS1,  OP1_RS1,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_W), // CSRs[csr] <- x[rs1]
      CSRRWI -> List(ALU_RS1,  OP1_IMZ,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_W), // CSRs[csr] <- uext(imm_z)
      CSRRS  -> List(ALU_RS1,  OP1_RS1,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_S), // CSRs[csr] <- CSRs[csr] | x[rs1]
      CSRRSI -> List(ALU_RS1,  OP1_IMZ,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_S), // CSRs[csr] <- CSRs[csr] | uext(imm_z)
      CSRRC  -> List(ALU_RS1,  OP1_RS1,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_C), // CSRs[csr] <- CSRs[csr]&~x[rs1]
      CSRRCI -> List(ALU_RS1,  OP1_IMZ,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_C), // CSRs[csr] <- CSRs[csr]&~uext(imm_z)
      // 2.8 Environment Call and Breakpoints
      ECALL  -> List(ALU_NONE, OP1_NONE, OP2_NONE, MEN_NONE,   REN_NONE,   WB_NONE, CSR_E),
    ),
  )

  // Determine 1st operand data signal
  val op1_data = MuxCase(0.U(WORD_LEN.W), Seq(
    (op1_sel === OP1_RS1) -> rs1_data,
    (op1_sel === OP1_PC) -> pc,
  ))

  // Determine 2nd operand data signal
  val op2_data = MuxCase(0.U(WORD_LEN.W), Seq(
    (op2_sel === OP2_RS2) -> rs2_data,
    (op2_sel === OP2_IMI) -> imm_i_sext,
    (op2_sel === OP2_IMS) -> imm_s_sext,
    (op2_sel === OP2_IMJ) -> imm_j_sext,
    (op2_sel === OP2_IMU) -> imm_u_shifted, // for LUI and AUIPC
  ))

  /*
   * Execute (EX)
   */

  // Arithmetic Logic Unit process arithmetic/logical calculations for each instruction.
  alu_out := MuxCase(0.U(WORD_LEN.W), Seq(
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
    // &~1 sets the LSB to zero (& 0b1111..1110) for jump instructions
    (exe_fun === ALU_JALR) -> ((op1_data + op2_data) & ~1.U(WORD_LEN.W)),
    (exe_fun === ALU_RS1) -> op1_data,
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

  // Handle CSR instructions
  val csr_addr = Mux(
    csr_cmd === CSR_E,
    // CSRs[0x342] is mcause register which describes where the ecall is executed from.
    //    8: from use mode
    //    9: from supervisor mode
    //   10: from hypervisor mode
    //   11: from machine mode
    0x342.U(CSR_ADDR_LEN.W),
    inst(31, 20), // I-type imm value
  )
  val csr_rdata = csr_regfile(csr_addr) // Read CSRs[csr]
  val csr_wdata = MuxCase(0.U(WORD_LEN.W), Seq(
    (csr_cmd === CSR_W) -> op1_data, // Write
    (csr_cmd === CSR_S) -> (csr_rdata | op1_data), // Read and Set Bits
    (csr_cmd === CSR_C) -> (csr_rdata & ~op1_data), // Read and Clear Bits
    // This CPU only implements the machine mode. 11 (machine mode) is always written to
    // CSRs[0x342] (mcause).
    (csr_cmd === CSR_E) -> 11.U(WORD_LEN.W),
  ))

  when(csr_cmd =/= CSR_NONE) {
    csr_regfile(csr_addr) := csr_wdata
  }

  /*
   * Write Back (WB)
   */

  // By default, write back the ALU result to register (wb_sel == WB_ALU)
  val wb_data = MuxCase(alu_out, Seq(
    (wb_sel === WB_MEM) -> io.dmem.rdata, // Loaded data from memory
    (wb_sel === WB_PC) -> pc_next, // Jump instruction stores the next pc (pc+4) to x[rd]
    (wb_sel === WB_CSR) -> csr_rdata, // CSR instruction write back CSRs[csr]
  ))
  when(rf_wen === REN_SCALAR) {
    regfile(wb_addr) := wb_data // Write back to the register specified by rd
  }

  // `exit` port output is `true` when the instruction is 0x44.
  // riscv-tests reaches 0x44 when the test case finishes.
  io.exit := pc === 0x44.U(WORD_LEN.W)
  io.gp := regfile(3)

  printf(p"pc:         0x${Hexadecimal(pc)}\n") // program counter
  printf(p"gp :        ${regfile(3)}\n") // global pointer
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
