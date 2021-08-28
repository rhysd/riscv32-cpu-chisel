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
    val gp = Output(UInt(WORD_LEN.W)) // for riscv-tests
    val pc = Output(UInt(WORD_LEN.W)) // for riscv-tests
    val inst = Output(UInt(WORD_LEN.W)) // for c-tests
  })

  // RISC-V has 32 registers. Size is 32bits (32bits * 32regs).
  val regfile = Mem(32, UInt(WORD_LEN.W))
  // Control and Status Registers
  val csr_regfile = Mem(4096, UInt(WORD_LEN.W))

  /*
   * Pipeline registers
   */

  //         ┌─┐          ┌─┐          ┌─┐           ┌─┐
  // ┌────┐  │ │  ┌────┐  │ │  ┌────┐  │ │  ┌─────┐  │ │  ┌────┐
  // │    │  │ │  │    │  │ │  │    │  │ │  │     │  │ │  │    │
  // │ IF ├─►│ ├─►│ ID ├─►│ ├─►│ EX ├─►│ ├─►│ MEM ├─►│ ├─►│ WB │
  // │    │  │ │  │    │  │ │  │    │  │ │  │     │  │ │  │    │
  // └────┘  │ │  └────┘  │ │  └────┘  │ │  └─────┘  │ │  └────┘
  //         └─┘          └─┘          └─┘           └─┘
  //        IF/ID        IF/ID        IF/MEM       MEM/ID
  //      registers    registers    registers     registers

  // IF/ID
  val id_reg_pc             = RegInit(0.U(WORD_LEN.W))
  val id_reg_inst           = RegInit(0.U(WORD_LEN.W))

  // ID/EX
  val exe_reg_pc            = RegInit(0.U(WORD_LEN.W))
  val exe_reg_wb_addr       = RegInit(0.U(ADDR_LEN.W))
  val exe_reg_op1_data      = RegInit(0.U(WORD_LEN.W))
  val exe_reg_op2_data      = RegInit(0.U(WORD_LEN.W))
  val exe_reg_rs2_data      = RegInit(0.U(WORD_LEN.W))
  val exe_reg_exe_fun       = RegInit(0.U(EXE_FUN_LEN.W))
  val exe_reg_mem_wen       = RegInit(0.U(MEN_LEN.W))
  val exe_reg_rf_wen        = RegInit(0.U(REN_LEN.W))
  val exe_reg_wb_sel        = RegInit(0.U(WB_SEL_LEN.W))
  val exe_reg_csr_addr      = RegInit(0.U(CSR_ADDR_LEN.W))
  val exe_reg_csr_cmd       = RegInit(0.U(CSR_LEN.W))
  val exe_reg_imm_i_sext    = RegInit(0.U(WORD_LEN.W))
  val exe_reg_imm_s_sext    = RegInit(0.U(WORD_LEN.W))
  val exe_reg_imm_b_sext    = RegInit(0.U(WORD_LEN.W))
  val exe_reg_imm_u_shifted = RegInit(0.U(WORD_LEN.W))
  val exe_reg_imm_z_uext    = RegInit(0.U(WORD_LEN.W))

  // EX/MEM
  val mem_reg_pc            = RegInit(0.U(WORD_LEN.W))
  val mem_reg_wb_addr       = RegInit(0.U(ADDR_LEN.W))
  val mem_reg_op1_data      = RegInit(0.U(WORD_LEN.W))
  val mem_reg_rs2_data      = RegInit(0.U(WORD_LEN.W))
  val mem_reg_mem_wen       = RegInit(0.U(MEN_LEN.W))
  val mem_reg_rf_wen        = RegInit(0.U(REN_LEN.W))
  val mem_reg_wb_sel        = RegInit(0.U(WB_SEL_LEN.W))
  val mem_reg_csr_addr      = RegInit(0.U(CSR_ADDR_LEN.W))
  val mem_reg_csr_cmd       = RegInit(0.U(CSR_LEN.W))
  val mem_reg_imm_z_uext    = RegInit(0.U(WORD_LEN.W))
  val mem_reg_alu_out       = RegInit(0.U(WORD_LEN.W))

  // MEM/WB
  val wb_reg_wb_addr        = RegInit(0.U(ADDR_LEN.W))
  val wb_reg_rf_wen         = RegInit(0.U(REN_LEN.W))
  val wb_reg_wb_data        = RegInit(0.U(WORD_LEN.W))

  /*
   * Instruction Fetch (IF)
   */

  val if_inst = io.imem.inst

  // Uninitialized ports for branch instructions. They will be connected in EX stage
  val exe_br_flag = Wire(Bool())
  val exe_br_target = Wire(UInt(WORD_LEN.W))
  val exe_jmp_flag = Wire(Bool())
  val exe_alu_out = Wire(UInt(WORD_LEN.W))

  // Program counter register. It counts up per 4 bytes since size of instruction is 32bits, but
  // memory address is byte oriented.
  val if_reg_pc = RegInit(START_ADDR)
  val if_pc_next = if_reg_pc + 4.U(WORD_LEN.W)
  if_reg_pc := MuxCase(if_pc_next, Seq(
    exe_br_flag  -> exe_br_target, // Branch instructions write back br_target address to program counter
    exe_jmp_flag -> exe_alu_out, // Jump instructions calculate the jump address by ALU
    // CSRs[0x305] is mtvec (trap_vector). The process on exception (syscall) is written at the
    // trap_vector address. Note that there is no OS on this CPU.
    (if_inst === ECALL) -> csr_regfile(0x305),
  ))

  // Connect program counter to address output. This output is connected to memory to fetch the
  // address as instruction
  io.imem.addr := if_reg_pc

  // Save IF states for next stage
  id_reg_pc := if_reg_pc
  // Jump instructions cause pipeline branch hazard. Replace the instruction being fetched with NOP
  // not to execute it.
  //
  //      IF     ID     EX     MEM
  //   ┌──────┬──────┐
  //   │INST A│ JUMP │
  //   │ (X+4)│ (X)  │
  //   └──────┴──────┘
  //   ┌──────┬──────┬──────┐
  //   │INST B│INST A│JUMP X│
  //   │ (X+8)│ (X+4)│(X->Y)│
  //   └──┬───┴──┬───┴──────┘
  //      ▼      ▼
  //   ┌──────┬──────┬──────┐
  //   │ NOP  │ NOP  │JUMP X│
  //   │      │      │(X->Y)│
  //   └──────┴──────┴──────┘
  //   ┌──────┬──────┬──────┬──────┐
  //   │INST P│ NOP  │ NOP  │JUMP X│
  //   │ (Y)  │      │      │(X->Y)│
  //   └──────┴──────┴──────┴──────┘
  //
  id_reg_inst := Mux(exe_br_flag || exe_jmp_flag, BUBBLE, if_inst)

  /*
   * Instruction Decode (ID)
   */

  // Spec 2.2 Base Instruction Formats
  //
  //  31      30 29 28 27 26 25 24 23 22 21   20   19 18 17 16 15 14 13 12 11 10 9 8     7   6 5 4 3 2 1 0
  // ┌─────────────────────────┬──────────────────┬──────────────┬────────┬─────────────────┬─────────────┐
  // │         funct7          │       rs2        │     rs1      │ funct3 │       rd        │   opcode    │ R-type
  // ├─────────────────────────┴──────────────────┼──────────────┼────────┼─────────────────┼─────────────┤
  // │                imm[11:0]                   │     rs1      │ funct3 │       rd        │   opcode    │ I-type
  // ├─────────────────────────┬──────────────────┼──────────────┼────────┼─────────────────┼─────────────┤
  // │        imm[11:5]        │       rs2        │     rs1      │ funct3 │   imm[4:0]      │   opcode    │ S-type
  // ├───────┬─────────────────┼──────────────────┼──────────────┼────────┼─────────┬───────┼─────────────┤
  // │imm[12]│    imm[10:5]    │       rs2        │     rs1      │ funct3 │imm[4:1] │imm[11]│   opcode    │ B-type
  // ├───────┴─────────────────┴──────────────────┴──────────────┴────────┼─────────┴───────┼─────────────┤
  // │                             imm[31:12]                             │       rd        │   opcode    │ U-type
  // ├───────┬────────────────────────────┬───────┬───────────────────────┼─────────────────┼─────────────┤
  // │imm[20]│         imm[10:1]          │imm[11]│      imm[19:12]       │       rd        │   opcode    │ J-type
  // └───────┴────────────────────────────┴───────┴───────────────────────┴─────────────────┴─────────────┘

  // Jump instructions cause pipeline branch hazard. Replace the instruction being fetched with NOP
  // not to execute it.
  val id_inst = Mux(exe_br_flag || exe_jmp_flag, BUBBLE, id_reg_inst)

  val id_rs1_addr = id_inst(19, 15)
  val id_rs2_addr = id_inst(24, 20)
  val id_wb_addr = id_inst(11, 7) // rd
  // When rs1 is non-zero, read the address from register map. When the address is zero, the value
  // of register #0 must always be zero.
  val id_rs1_data = Mux(id_rs1_addr =/= 0.U(WORD_LEN.U), regfile(id_rs1_addr), 0.U(WORD_LEN.W))
  val id_rs2_data = Mux(id_rs2_addr =/= 0.U(WORD_LEN.U), regfile(id_rs2_addr), 0.U(WORD_LEN.W))

  // Spec 2.6: The effective address is obtained by adding register rs1 to the sign-extended 12-bit offset.
  // sext 12bit value to 32bit value.
  val id_imm_i = id_inst(31, 20) // imm for I-type
  val id_imm_i_sext = Cat(Fill(20, id_imm_i(11)), id_imm_i)
  val id_imm_s = Cat(id_inst(31, 25), id_inst(11, 7)) // imm for S-type
  val id_imm_s_sext = Cat(Fill(20, id_imm_s(11)), id_imm_s)

  // Decode imm of B-type instruction
  val id_imm_b = Cat(id_inst(31), id_inst(7), id_inst(30, 25), id_inst(11, 8))
  // imm[0] does not exist in B-type instruction. This is because the first bit of program counter
  // is always zero (p.126). Size of instruction is 32bit or 16bit, so instruction pointer (pc)
  // address always points an even address.
  val id_imm_b_sext = Cat(Fill(19, id_imm_b(11)), id_imm_b, 0.U(1.U))

  // Decode imm of J-type instruction
  val id_imm_j = Cat(id_inst(31), id_inst(19, 12), id_inst(20), id_inst(30, 21))
  val id_imm_j_sext = Cat(Fill(11, id_imm_j(19)), id_imm_j, 0.U(1.U)) // Set LSB to zero

  // Decode imm of U-type instruction
  val id_imm_u = id_inst(31, 12)
  val id_imm_u_shifted = Cat(id_imm_u, Fill(12, 0.U)) // for LUI and AUIPC

  // Decode imm of I-type instruction
  val id_imm_z = id_inst(19, 15)
  val id_imm_z_uext = Cat(Fill(27, 0.U), id_imm_z) // for CSR instructions

  // Decode operand sources and memory/register write back behavior
  val List(id_exe_fun, id_op1_sel, id_op2_sel, id_mem_wen, id_rf_wen, id_wb_sel, id_csr_cmd) = ListLookup(
    id_inst,
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
  val id_op1_data = MuxCase(0.U(WORD_LEN.W), Seq(
    (id_op1_sel === OP1_RS1) -> id_rs1_data,
    (id_op1_sel === OP1_PC)  -> id_reg_pc,
    (id_op1_sel === OP1_IMZ) -> id_imm_z_uext,
  ))

  // Determine 2nd operand data signal
  val id_op2_data = MuxCase(0.U(WORD_LEN.W), Seq(
    (id_op2_sel === OP2_RS2) -> id_rs2_data,
    (id_op2_sel === OP2_IMI) -> id_imm_i_sext,
    (id_op2_sel === OP2_IMS) -> id_imm_s_sext,
    (id_op2_sel === OP2_IMJ) -> id_imm_j_sext,
    (id_op2_sel === OP2_IMU) -> id_imm_u_shifted, // for LUI and AUIPC
  ))

  // Decode CSR instructions
  val id_csr_addr = Mux(
    id_csr_cmd === CSR_E,
    // CSRs[0x342] is mcause register which describes where the ecall is executed from.
    //    8: from use mode
    //    9: from supervisor mode
    //   10: from hypervisor mode
    //   11: from machine mode
    0x342.U(CSR_ADDR_LEN.W),
    id_inst(31, 20), // I-type imm value
  )

  // Save ID states for next stage
  exe_reg_pc            := id_reg_pc
  exe_reg_op1_data      := id_op1_data
  exe_reg_op2_data      := id_op2_data
  exe_reg_rs2_data      := id_rs2_data
  exe_reg_wb_addr       := id_wb_addr
  exe_reg_rf_wen        := id_rf_wen
  exe_reg_exe_fun       := id_exe_fun
  exe_reg_wb_sel        := id_wb_sel
  exe_reg_imm_i_sext    := id_imm_i_sext
  exe_reg_imm_s_sext    := id_imm_s_sext
  exe_reg_imm_b_sext    := id_imm_b_sext
  exe_reg_imm_u_shifted := id_imm_u_shifted
  exe_reg_imm_z_uext    := id_imm_z_uext
  exe_reg_csr_addr      := id_csr_addr
  exe_reg_csr_cmd       := id_csr_cmd
  exe_reg_mem_wen       := id_mem_wen

  /*
   * Execute (EX)
   */

  // Arithmetic Logic Unit process arithmetic/logical calculations for each instruction.
  exe_alu_out := MuxCase(0.U(WORD_LEN.W), Seq(
    (exe_reg_exe_fun === ALU_ADD)  -> (exe_reg_op1_data + exe_reg_op2_data),
    (exe_reg_exe_fun === ALU_SUB)  -> (exe_reg_op1_data - exe_reg_op2_data),
    (exe_reg_exe_fun === ALU_AND)  -> (exe_reg_op1_data & exe_reg_op2_data),
    (exe_reg_exe_fun === ALU_OR)   -> (exe_reg_op1_data | exe_reg_op2_data),
    (exe_reg_exe_fun === ALU_XOR)  -> (exe_reg_op1_data ^ exe_reg_op2_data),
    // Note: (31, 0) is necessary because << extends bits of the result value
    // Note: (4, 0) is necessary for I instructions (imm[4:0])
    (exe_reg_exe_fun === ALU_SLL)  -> (exe_reg_op1_data << exe_reg_op2_data(4, 0))(31, 0),
    (exe_reg_exe_fun === ALU_SRL)  -> (exe_reg_op1_data >> exe_reg_op2_data(4, 0)).asUInt(),
    (exe_reg_exe_fun === ALU_SRA)  -> (exe_reg_op1_data.asSInt() >> exe_reg_op2_data(4, 0)).asUInt(),
    // Compare as signed integers
    (exe_reg_exe_fun === ALU_SLT)  -> (exe_reg_op1_data.asSInt() < exe_reg_op2_data.asSInt()).asUInt(),
    (exe_reg_exe_fun === ALU_SLTU) -> (exe_reg_op1_data < exe_reg_op2_data).asUInt(),
    // &~1 sets the LSB to zero (& 0b1111..1110) for jump instructions
    (exe_reg_exe_fun === ALU_JALR) -> ((exe_reg_op1_data + exe_reg_op2_data) & ~1.U(WORD_LEN.W)),
    (exe_reg_exe_fun === ALU_RS1) -> exe_reg_op1_data,
  ))

  // Branch instructions
  exe_br_flag := MuxCase(false.B, Seq(
    (exe_reg_exe_fun === BR_BEQ)  ->  (exe_reg_op1_data === exe_reg_op2_data),
    (exe_reg_exe_fun === BR_BNE)  -> !(exe_reg_op1_data === exe_reg_op2_data),
    (exe_reg_exe_fun === BR_BLT)  ->  (exe_reg_op1_data.asSInt() < exe_reg_op2_data.asSInt()),
    (exe_reg_exe_fun === BR_BGE)  -> !(exe_reg_op1_data.asSInt() < exe_reg_op2_data.asSInt()),
    (exe_reg_exe_fun === BR_BLTU) ->  (exe_reg_op1_data < exe_reg_op2_data),
    (exe_reg_exe_fun === BR_BGEU) -> !(exe_reg_op1_data < exe_reg_op2_data),
  ))
  exe_br_target := exe_reg_pc + exe_reg_imm_b_sext

  exe_jmp_flag := exe_reg_wb_sel === WB_PC

  // Save EX states for next stage
  mem_reg_pc         := exe_reg_pc
  mem_reg_op1_data   := exe_reg_op1_data
  mem_reg_rs2_data   := exe_reg_rs2_data
  mem_reg_wb_addr    := exe_reg_wb_addr
  mem_reg_alu_out    := exe_alu_out
  mem_reg_rf_wen     := exe_reg_rf_wen
  mem_reg_wb_sel     := exe_reg_wb_sel
  mem_reg_csr_addr   := exe_reg_csr_addr
  mem_reg_csr_cmd    := exe_reg_csr_cmd
  mem_reg_imm_z_uext := exe_reg_imm_z_uext
  mem_reg_mem_wen    := exe_reg_mem_wen

  /*
   * Memory Access (MEM)
   */
  io.dmem.addr := mem_reg_alu_out // Always output data to memory regardless of instruction
  io.dmem.wen := mem_reg_mem_wen // mem_wen is integer and here it is implicitly converted to bool
  io.dmem.wdata := mem_reg_rs2_data

  // Handle CSR instructions
  val csr_rdata = csr_regfile(mem_reg_csr_addr) // Read CSRs[csr]
  val csr_wdata = MuxCase(0.U(WORD_LEN.W), Seq(
    (mem_reg_csr_cmd === CSR_W) -> mem_reg_op1_data, // Write
    (mem_reg_csr_cmd === CSR_S) -> (csr_rdata | mem_reg_op1_data), // Read and Set Bits
    (mem_reg_csr_cmd === CSR_C) -> (csr_rdata & ~mem_reg_op1_data), // Read and Clear Bits
    // This CPU only implements the machine mode. 11 (machine mode) is always written to
    // CSRs[0x342] (mcause).
    (mem_reg_csr_cmd === CSR_E) -> 11.U(WORD_LEN.W),
  ))

  when(mem_reg_csr_cmd =/= CSR_NONE) {
    csr_regfile(mem_reg_csr_addr) := csr_wdata
  }

  // By default, write back the ALU result to register (wb_sel == WB_ALU)
  val mem_wb_data = MuxCase(mem_reg_alu_out, Seq(
    (mem_reg_wb_sel === WB_MEM) -> io.dmem.rdata, // Loaded data from memory
    (mem_reg_wb_sel === WB_PC) -> (mem_reg_pc + 4.U(WORD_LEN.W)), // Jump instruction stores the next pc (pc+4) to x[rd]
    (mem_reg_wb_sel === WB_CSR) -> csr_rdata, // CSR instruction write back CSRs[csr]
  ))

  // Save MEM states for next stage
  wb_reg_wb_addr := mem_reg_wb_addr
  wb_reg_rf_wen  := mem_reg_rf_wen
  wb_reg_wb_data := mem_wb_data

  /*
   * Write Back (WB)
   */

  when(wb_reg_rf_wen === REN_SCALAR) {
    regfile(wb_reg_wb_addr) := wb_reg_wb_data // Write back to the register specified by rd
  }

  // `exit` port output is `true` when the instruction is 0x44.
  // riscv-tests reaches 0x44 when the test case finishes.
  io.exit := id_inst === UNIMP
  io.gp := regfile(3)
  io.pc := mem_reg_pc
  io.inst := id_inst

  printf(p"if:   pc=0x${Hexadecimal(if_reg_pc)}\n")
  printf(p"id:   pc=0x${Hexadecimal(id_reg_pc)} inst=0x${Hexadecimal(id_inst)} rs1=${id_rs1_addr} rs2=${id_rs2_addr}\n")
  printf(p"exe:  pc=0x${Hexadecimal(exe_reg_pc)} wb_addr=${exe_reg_wb_addr} op1=0x${Hexadecimal(exe_reg_op1_data)} op2=0x${Hexadecimal(exe_reg_op2_data)} alu_out=0x${Hexadecimal(exe_alu_out)}\n")
  printf(p"mem:  pc=0x${Hexadecimal(mem_reg_pc)} wb_data=0x${Hexadecimal(mem_wb_data)}\n")
  printf(p"wb:   wb_data=0x${Hexadecimal(wb_reg_wb_data)}\n")
  printf(p"dmem: addr=${io.dmem.addr} wen=${io.dmem.wen} wdata=0x${Hexadecimal(io.dmem.wdata)}\n") // memory address loaded by LW
  printf("----------------\n")
}
