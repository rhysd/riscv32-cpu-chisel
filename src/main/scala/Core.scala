package cpu

import chisel3._
import chisel3.util._
import Consts._
import Instructions._

//         ┌─┐          ┌─┐          ┌─┐           ┌─┐
// ┌────┐  │ │  ┌────┐  │ │  ┌────┐  │ │  ┌─────┐  │ │  ┌────┐
// │    │  │ │  │    │  │ │  │    │  │ │  │     │  │ │  │    │
// │ IF ├─►│ ├─►│ ID ├─►│ ├─►│ EX ├─►│ ├─►│ MEM ├─►│ ├─►│ WB │
// │    │  │ │  │    │  │ │  │    │  │ │  │     │  │ │  │    │
// └────┘  │ │  └────┘  │ │  └────┘  │ │  └─────┘  │ │  └────┘
//         └─┘          └─┘          └─┘           └─┘
//        IF/ID        IF/ID        IF/MEM       MEM/ID
//      registers    registers    registers     registers

// IF
class FetchStage {
  val stall_flag = Wire(Bool()) // True when data hazard occurs at EX stage
  // IF/ID pipeline registers
  val reg_pc = RegInit(0.U(WORD_LEN.W))
  val reg_inst = RegInit(0.U(WORD_LEN.W))

  def connect(imem: ImemPortIo, exe: ExecuteStage, csr: Mem[UInt]) = {
    val inst = imem.inst

    // Uninitialized ports for branch instructions. They will be connected in EX stage

    // Program counter register. It counts up per 4 bytes since size of instruction is 32bits, but
    // memory address is byte oriented.
    val pc = RegInit(START_ADDR)
    pc := MuxCase(pc + 4.U(WORD_LEN.W), Seq(
      exe.br_flag  -> exe.br_target, // Branch instructions write back br_target address to program counter
      exe.jmp_flag -> exe.alu_out, // Jump instructions calculate the jump address by ALU
      // CSRs[0x305] is mtvec (trap_vector). The process on exception (syscall) is written at the
      // trap_vector address. Note that there is no OS on this CPU.
      (inst === ECALL) -> csr(0x305),
      // Keep current pc due to stall by data hazard
      stall_flag -> pc,
    ))

    // Connect program counter to address output. This output is connected to memory to fetch the
    // address as instruction
    imem.addr := pc

    // Save IF states for next stage
    reg_pc := Mux(stall_flag, reg_pc, pc) // Keep current pc due to stall by data hazard

    // On branch hazard:
    //
    // Jump instructions cause pipeline branch hazard. Replace the instruction being fetched with NOP
    // not to execute it.
    //
    //      IF     ID     EX     MEM
    //   ┌──────┬──────┐
    //   │INST A│ JUMP │                CYCLE = C
    //   │ (X+4)│ (X)  │
    //   └──────┴──────┘
    //   ┌──────┬──────┬──────┐
    //   │INST B│INST A│JUMP X│         CYCLE = C+1
    //   │ (X+8)│ (X+4)│(X->Y)│
    //   └──┬───┴──┬───┴──────┘
    //      ▼      ▼
    //   ┌──────┬──────┬──────┐
    //   │ NOP  │ NOP  │JUMP X│         CYCLE = C+1
    //   │      │      │(X->Y)│
    //   └──────┴──────┴──────┘
    //   ┌──────┬──────┬──────┬──────┐
    //   │INST P│ NOP  │ NOP  │JUMP X│  CYCLE = C+2
    //   │ (Y)  │      │      │(X->Y)│
    //   └──────┴──────┴──────┴──────┘
    //
    //
    // On data hazard:
    //
    // To fetch the register data which is now being calculated at EX stage in IF stage, IF and ID
    // must wait for the data being written back to the register. The data will be forwarded to IF
    // and ID at MEM.
    //
    //      IF     ID     EX     MEM
    //   ┌──────┬──────┬──────┐
    //   │INST B│INST A│INST C│         CYCLE = C
    //   │ (X+8)│ (X+4)│ (X)  │
    //   └┬─────┴┬─────┴──────┘
    //    │keep  │keep
    //   ┌▼─────┐▼─────┬──────┬──────┐
    //   │INST B│INST A│ NOP  │INST C│  CYCLE = C+1
    //   │ (X+8)│ (X+4)│      │ (X)  │
    //   └──────┴──▲───┴──────┴──┬───┘
    //             └─────────────┘
    //                 forward
    //
    reg_inst := MuxCase(inst, Seq(
      (exe.br_flag || exe.jmp_flag) -> BUBBLE, // Prioritize branch hazard over data hazard
      stall_flag -> reg_inst,
    ))

    printf(p"IF: pc=0x${Hexadecimal(pc)}\n")
  }
}

// ID
class DecodeStage {
  val inst = Wire(UInt(WORD_LEN.W))
  // ID/EX pipeline registers
  val reg_pc            = RegInit(0.U(WORD_LEN.W))
  val reg_wb_addr       = RegInit(0.U(ADDR_LEN.W))
  val reg_op1_data      = RegInit(0.U(WORD_LEN.W))
  val reg_op2_data      = RegInit(0.U(WORD_LEN.W))
  val reg_rs1_data      = RegInit(0.U(WORD_LEN.W))
  val reg_rs2_data      = RegInit(0.U(WORD_LEN.W))
  val reg_exe_fun       = RegInit(0.U(EXE_FUN_LEN.W))
  val reg_mem_wen       = RegInit(0.U(MEN_LEN.W))
  val reg_rf_wen        = RegInit(0.U(REN_LEN.W))
  val reg_wb_sel        = RegInit(0.U(WB_SEL_LEN.W))
  val reg_csr_addr      = RegInit(0.U(CSR_ADDR_LEN.W))
  val reg_csr_cmd       = RegInit(0.U(CSR_LEN.W))
  val reg_imm_i_sext    = RegInit(0.U(WORD_LEN.W))
  val reg_imm_s_sext    = RegInit(0.U(WORD_LEN.W))
  val reg_imm_b_sext    = RegInit(0.U(WORD_LEN.W))
  val reg_imm_u_shifted = RegInit(0.U(WORD_LEN.W))
  val reg_imm_z_uext    = RegInit(0.U(WORD_LEN.W))

  def connectStallFlag(prev: FetchStage) = {
    // rs1_addr and rs2_addr in connect() are not available because they are connected from inst and
    // inst wire is connected to stall_flag wire. Separate wires are necessary.
    val rs1_addr = prev.reg_inst(19, 15)
    val rs2_addr = prev.reg_inst(24, 20)

    // stall_flag outputs true signal when data hazard occurs in RS1 or RS2 at EX stage.
    prev.stall_flag := (
      reg_rf_wen === REN_SCALAR &&
      rs1_addr =/= 0.U &&
      rs1_addr === reg_wb_addr
    ) || (
      reg_rf_wen === REN_SCALAR &&
      rs2_addr =/= 0.U &&
      rs2_addr === reg_wb_addr
    )
  }

  def connect(prev: FetchStage, exe: ExecuteStage, mem: MemStage, gr: Mem[UInt]) = {
    connectStallFlag(prev)

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
    inst := Mux(exe.br_flag || exe.jmp_flag || prev.stall_flag, BUBBLE, prev.reg_inst)

    val rs1_addr = inst(19, 15)
    val rs2_addr = inst(24, 20)
    val wb_addr = inst(11, 7) // rd

    val rs1_data = MuxCase(gr(rs1_addr), Seq(
      // The value of register #0 is always zero
      (rs1_addr === 0.U) -> 0.U(WORD_LEN.W),
      // Forward data from MEM stage to avoid data hazard. In this case, the data is not written back
      // to the register. To fix this, ID stage needs to wait for the data is written back (stall).
      // To avoid the stall, directly read the data being written back to the register at MEM stage.
      (rs1_addr === exe.reg_wb_addr && exe.reg_rf_wen === REN_SCALAR) -> mem.wb_data,
      // Forward data from WB stage to avoid data hazard. The same as above.
      (rs1_addr === mem.reg_wb_addr && mem.reg_rf_wen === REN_SCALAR) -> mem.reg_wb_data,
    ))
    val rs2_data = MuxCase(gr(rs2_addr), Seq(
      // The value of register #0 is always zero
      (rs2_addr === 0.U) -> 0.U(WORD_LEN.W),
      // Forward data from MEM stage to avoid data hazard. The same as RS1 above.
      (rs2_addr === exe.reg_wb_addr && exe.reg_rf_wen === REN_SCALAR) -> mem.wb_data,
      // Forward data from WB stage to avoid data hazard. The same as above.
      (rs2_addr === mem.reg_wb_addr && mem.reg_rf_wen === REN_SCALAR) -> mem.reg_wb_data,
    ))

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
        LW      -> List(ALU_ADD,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_MEM,  CSR_NONE), // x[rs1] + sext(imm_i)
        SW      -> List(ALU_ADD,  OP1_RS1,  OP2_IMS,  MEN_SCALAR, REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] + sext(imm_s)
        // 2.4 Integer Computational Instructions
        ADD     -> List(ALU_ADD,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] + x[rs2]
        ADDI    -> List(ALU_ADD,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] + sext(imm_i)
        SUB     -> List(ALU_SUB,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] - x[rs2]
        AND     -> List(ALU_AND,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] & x[rs2]
        OR      -> List(ALU_OR,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] | x[rs2]
        XOR     -> List(ALU_XOR,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] ^ x[rs2]
        ANDI    -> List(ALU_AND,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] & sext(imm_i)
        ORI     -> List(ALU_OR ,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] | sext(imm_i)
        XORI    -> List(ALU_XOR,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] ^ sext(imm_i)
        SLL     -> List(ALU_SLL,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] << x[rs2](4,0)
        SRL     -> List(ALU_SRL,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>u x[rs2](4,0)
        SRA     -> List(ALU_SRA,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>s x[rs2](4,0)
        SLLI    -> List(ALU_SLL,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] << imm_i_sext(4,0)
        SRLI    -> List(ALU_SRL,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>u imm_i_sext(4,0)
        SRAI    -> List(ALU_SRA,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] >>s imm_i_sext(4,0)
        SLT     -> List(ALU_SLT,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <s x[rs2]
        SLTU    -> List(ALU_SLTU, OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <u x[rs2]
        SLTI    -> List(ALU_SLT,  OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <s imm_i_sext
        SLTIU   -> List(ALU_SLTU, OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // x[rs1] <u imm_i_sext
        LUI     -> List(ALU_ADD,  OP1_NONE, OP2_IMU,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // sext(imm_u[31:12] << 12)
        AUIPC   -> List(ALU_ADD,  OP1_PC,   OP2_IMU,  MEN_NONE,   REN_SCALAR, WB_ALU,  CSR_NONE), // PC + sext(imm_u[31:12] << 12)
        // 2.5 Control Transfer Instructions
        BEQ     -> List(BR_BEQ,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] === x[rs2] then PC+sext(imm_b)
        BNE     -> List(BR_BNE,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] =/= x[rs2] then PC+sext(imm_b)
        BGE     -> List(BR_BGE,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] >=s x[rs2] then PC+sext(imm_b)
        BGEU    -> List(BR_BGEU,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] >=u x[rs2] then PC+sext(imm_b)
        BLT     -> List(BR_BLT,   OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] <s x[rs2]  then PC+sext(imm_b)
        BLTU    -> List(BR_BLTU,  OP1_RS1,  OP2_RS2,  MEN_NONE,   REN_NONE,   WB_NONE, CSR_NONE), // x[rs1] <u x[rs2]  then PC+sext(imm_b)
        JAL     -> List(ALU_ADD,  OP1_PC,   OP2_IMJ,  MEN_NONE,   REN_SCALAR, WB_PC,   CSR_NONE), // x[rd] <- PC+4 and PC+sext(imm_j)
        JALR    -> List(ALU_JALR, OP1_RS1,  OP2_IMI,  MEN_NONE,   REN_SCALAR, WB_PC,   CSR_NONE), // x[rd] <- PC+4 and (x[rs1]+sext(imm_i))&~1
        // 9.1 "Zicsr", Control and Status Register (CSR) Instructions
        CSRRW   -> List(ALU_RS1,  OP1_RS1,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_W), // CSRs[csr] <- x[rs1]
        CSRRWI  -> List(ALU_RS1,  OP1_IMZ,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_W), // CSRs[csr] <- uext(imm_z)
        CSRRS   -> List(ALU_RS1,  OP1_RS1,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_S), // CSRs[csr] <- CSRs[csr] | x[rs1]
        CSRRSI  -> List(ALU_RS1,  OP1_IMZ,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_S), // CSRs[csr] <- CSRs[csr] | uext(imm_z)
        CSRRC   -> List(ALU_RS1,  OP1_RS1,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_C), // CSRs[csr] <- CSRs[csr]&~x[rs1]
        CSRRCI  -> List(ALU_RS1,  OP1_IMZ,  OP2_NONE, MEN_NONE,   REN_SCALAR, WB_CSR,  CSR_C), // CSRs[csr] <- CSRs[csr]&~uext(imm_z)
        // 2.8 Environment Call and Breakpoints
        ECALL   -> List(ALU_NONE, OP1_NONE, OP2_NONE, MEN_NONE,   REN_NONE,   WB_NONE, CSR_E),
        // "V" Vector Extension
        VSETVLI -> List(ALU_NONE, OP1_NONE, OP2_NONE, MEN_NONE,   REN_SCALAR, WB_VL,   CSR_V),
      ),
    )

    // Determine 1st operand data signal
    val op1_data = MuxCase(0.U(WORD_LEN.W), Seq(
      (op1_sel === OP1_RS1) -> rs1_data,
      (op1_sel === OP1_PC)  -> prev.reg_pc,
      (op1_sel === OP1_IMZ) -> imm_z_uext,
    ))

    // Determine 2nd operand data signal
    val op2_data = MuxCase(0.U(WORD_LEN.W), Seq(
      (op2_sel === OP2_RS2) -> rs2_data,
      (op2_sel === OP2_IMI) -> imm_i_sext,
      (op2_sel === OP2_IMS) -> imm_s_sext,
      (op2_sel === OP2_IMJ) -> imm_j_sext,
      (op2_sel === OP2_IMU) -> imm_u_shifted, // for LUI and AUIPC
    ))

    // Decode CSR instructions
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

    // Save ID states for next stage
    reg_pc            := prev.reg_pc
    reg_op1_data      := op1_data
    reg_op2_data      := op2_data
    reg_rs1_data      := rs1_data
    reg_rs2_data      := rs2_data
    reg_wb_addr       := wb_addr
    reg_rf_wen        := rf_wen
    reg_exe_fun       := exe_fun
    reg_wb_sel        := wb_sel
    reg_imm_i_sext    := imm_i_sext
    reg_imm_s_sext    := imm_s_sext
    reg_imm_b_sext    := imm_b_sext
    reg_imm_u_shifted := imm_u_shifted
    reg_imm_z_uext    := imm_z_uext
    reg_csr_addr      := csr_addr
    reg_csr_cmd       := csr_cmd
    reg_mem_wen       := mem_wen

    printf(p"ID: pc=0x${Hexadecimal(prev.reg_pc)} inst=0x${Hexadecimal(inst)} rs1=${rs1_data} rs2=${rs2_data} stall=${prev.stall_flag}\n")
  }
}

// EX
class ExecuteStage {
  val br_flag = Wire(Bool())
  val br_target = Wire(UInt(WORD_LEN.W))
  val jmp_flag = Wire(Bool())
  val alu_out = Wire(UInt(WORD_LEN.W))

  // EX/MEM pipeline registers
  val reg_pc         = RegInit(0.U(WORD_LEN.W))
  val reg_wb_addr    = RegInit(0.U(ADDR_LEN.W))
  val reg_op1_data   = RegInit(0.U(WORD_LEN.W))
  val reg_rs1_data   = RegInit(0.U(WORD_LEN.W))
  val reg_rs2_data   = RegInit(0.U(WORD_LEN.W))
  val reg_mem_wen    = RegInit(0.U(MEN_LEN.W))
  val reg_rf_wen     = RegInit(0.U(REN_LEN.W))
  val reg_wb_sel     = RegInit(0.U(WB_SEL_LEN.W))
  val reg_csr_addr   = RegInit(0.U(CSR_ADDR_LEN.W))
  val reg_csr_cmd    = RegInit(0.U(CSR_LEN.W))
  val reg_imm_i_sext = RegInit(0.U(WORD_LEN.W))
  val reg_imm_z_uext = RegInit(0.U(WORD_LEN.W))
  val reg_alu_out    = RegInit(0.U(WORD_LEN.W))

  def connect(prev: DecodeStage) = {
    // Arithmetic Logic Unit process arithmetic/logical calculations for each instruction.
    alu_out := MuxCase(0.U(WORD_LEN.W), Seq(
      (prev.reg_exe_fun === ALU_ADD)  -> (prev.reg_op1_data + prev.reg_op2_data),
      (prev.reg_exe_fun === ALU_SUB)  -> (prev.reg_op1_data - prev.reg_op2_data),
      (prev.reg_exe_fun === ALU_AND)  -> (prev.reg_op1_data & prev.reg_op2_data),
      (prev.reg_exe_fun === ALU_OR)   -> (prev.reg_op1_data | prev.reg_op2_data),
      (prev.reg_exe_fun === ALU_XOR)  -> (prev.reg_op1_data ^ prev.reg_op2_data),
      // Note: (31, 0) is necessary because << extends bits of the result value
      // Note: (4, 0) is necessary for I instructions (imm[4:0])
      (prev.reg_exe_fun === ALU_SLL)  -> (prev.reg_op1_data << prev.reg_op2_data(4, 0))(31, 0),
      (prev.reg_exe_fun === ALU_SRL)  -> (prev.reg_op1_data >> prev.reg_op2_data(4, 0)).asUInt(),
      (prev.reg_exe_fun === ALU_SRA)  -> (prev.reg_op1_data.asSInt() >> prev.reg_op2_data(4, 0)).asUInt(),
      // Compare as signed integers
      (prev.reg_exe_fun === ALU_SLT)  -> (prev.reg_op1_data.asSInt() < prev.reg_op2_data.asSInt()).asUInt(),
      (prev.reg_exe_fun === ALU_SLTU) -> (prev.reg_op1_data < prev.reg_op2_data).asUInt(),
      // &~1 sets the LSB to zero (& 0b1111..1110) for jump instructions
      (prev.reg_exe_fun === ALU_JALR) -> ((prev.reg_op1_data + prev.reg_op2_data) & ~1.U(WORD_LEN.W)),
      (prev.reg_exe_fun === ALU_RS1) -> prev.reg_op1_data,
    ))

    // Branch instructions
    br_flag := MuxCase(false.B, Seq(
      (prev.reg_exe_fun === BR_BEQ)  ->  (prev.reg_op1_data === prev.reg_op2_data),
      (prev.reg_exe_fun === BR_BNE)  -> !(prev.reg_op1_data === prev.reg_op2_data),
      (prev.reg_exe_fun === BR_BLT)  ->  (prev.reg_op1_data.asSInt() < prev.reg_op2_data.asSInt()),
      (prev.reg_exe_fun === BR_BGE)  -> !(prev.reg_op1_data.asSInt() < prev.reg_op2_data.asSInt()),
      (prev.reg_exe_fun === BR_BLTU) ->  (prev.reg_op1_data < prev.reg_op2_data),
      (prev.reg_exe_fun === BR_BGEU) -> !(prev.reg_op1_data < prev.reg_op2_data),
    ))
    br_target := prev.reg_pc + prev.reg_imm_b_sext

    jmp_flag := prev.reg_wb_sel === WB_PC

    // Save EX states for next stage
    reg_pc         := prev.reg_pc
    reg_op1_data   := prev.reg_op1_data
    reg_rs1_data   := prev.reg_rs1_data
    reg_rs2_data   := prev.reg_rs2_data
    reg_wb_addr    := prev.reg_wb_addr
    reg_alu_out    := alu_out
    reg_rf_wen     := prev.reg_rf_wen
    reg_wb_sel     := prev.reg_wb_sel
    reg_csr_addr   := prev.reg_csr_addr
    reg_csr_cmd    := prev.reg_csr_cmd
    reg_imm_i_sext := prev.reg_imm_i_sext
    reg_imm_z_uext := prev.reg_imm_z_uext
    reg_mem_wen    := prev.reg_mem_wen

    printf(p"EX: pc=0x${Hexadecimal(prev.reg_pc)} wb_addr=${prev.reg_wb_addr} op1=0x${Hexadecimal(prev.reg_op1_data)} op2=0x${Hexadecimal(prev.reg_op2_data)} alu_out=0x${Hexadecimal(alu_out)} jmp=${jmp_flag}\n")
  }
}

// MEM
class MemStage {
  val wb_data = Wire(UInt(WORD_LEN.W)) // Declare wire for forwarding (p.200)
  // MEM/WB pipeline registers
  val reg_wb_addr = RegInit(0.U(ADDR_LEN.W))
  val reg_rf_wen  = RegInit(0.U(REN_LEN.W))
  val reg_wb_data = RegInit(0.U(WORD_LEN.W))

  def connect(dmem: DmemPortIo, prev: ExecuteStage, decode: DecodeStage, csr: Mem[UInt]) = {
    dmem.addr := prev.reg_alu_out // Always output data to memory regardless of instruction
    dmem.wen := prev.reg_mem_wen // mem_wen is integer and here it is implicitly converted to bool
    dmem.wdata := prev.reg_rs2_data

    // Handle CSR instructions
    val csr_rdata = csr(prev.reg_csr_addr) // Read CSRs[csr]
    val csr_wdata = MuxCase(0.U(WORD_LEN.W), Seq(
      (prev.reg_csr_cmd === CSR_W) -> prev.reg_op1_data, // Write
      (prev.reg_csr_cmd === CSR_S) -> (csr_rdata | prev.reg_op1_data), // Read and Set Bits
      (prev.reg_csr_cmd === CSR_C) -> (csr_rdata & ~prev.reg_op1_data), // Read and Clear Bits
      // This CPU only implements the machine mode. 11 (machine mode) is always written to
      // CSRs[0x342] (mcause).
      (prev.reg_csr_cmd === CSR_E) -> 11.U(WORD_LEN.W),
    ))

    when(prev.reg_csr_cmd =/= CSR_NONE) {
      csr(prev.reg_csr_addr) := csr_wdata
    }

    // "V" 6: Configuration-Setting Instructions (I-type)
    //
    //  31 30 29  25 24    20 19     15 14 12 11      7 6     0
    // ┌──┬──────────────────┬─────────┬─────┬─────────┬───────┐
    // │0 │    zimm[10:0]    │   rs1   │ 111 │   rd    │1010111│ vsetvli
    // └──┴──────────────────┴─────────┴─────┴─────────┴───────┘
    // ┌──┬──┬───────────────┬─────────┬─────┬─────────┬───────┐
    // │1 │1 │   zimm[9:0]   │uimm[4:0]│ 111 │   rd    │1010111│ vsetivli
    // └──┴──┴───────────────┴─────────┴─────┴─────────┴───────┘
    // ┌──┬──┬─────┬─────────┬─────────┬─────┬─────────┬───────┐
    // │1 │0 │00000│   rs2   │   rs1   │ 111 │   rd    │1010111│ vsetvl
    // └──┴──┴─────┴─────────┴─────────┴─────┴─────────┴───────┘
    //
    // Note: VLEN is 128bit.
    // TODO: Implement vsetivli and vsetvl
    // TODO: Current implementation is limited: e8 <= SEW <= e64 and m1 <= LMUL <= m8

    // "V" 6.1: vtype encoding (p.226)
    //
    //    31 30               8   7   6 5       3 2        0
    // ┌────┬──────────────────┬───┬───┬─────────┬──────────┐
    // │vill│     reserved     │vma│vta│vsew[2:0]│vlmul[2:0]│
    // └────┴──────────────────┴───┴───┴─────────┴──────────┘
    //
    // vtype setting is encoded in imm of vsetvli/vsetivli and rs2 of vsetvl.
    val vtype = prev.reg_imm_i_sext
    // Note: vtype(2, 0) is correct range of vsew, but we only supports range of m1~m8 for lmul
    // (mf8~mf2 are unsupported). The MSB is discarded here.
    val vlmul = vtype(1, 0)
    val vsew = vtype(4, 2)
    // sew = 2^(vsew+3)
    // lmul = 2^vlmul
    // vlmax = (vlen * lmul) / sew = (vlen * 2^vlmul) / 2^(vsew+3) = (vlen << vlmul) >> (vsew+3)
    val vlmax = ((VLEN.U << vlmul) >> (vsew + 3.U(3.W))).asUInt()
    val avl = prev.reg_rs1_data
    // Condition is AVL/2 <= VL <= VLMAX (See "V" 6.3). It means AVL <= VLMAX*2. When AVL is larger
    // than VLMAX, it is separate into 2 computations. Compute VLMAX elements at first, then compute
    // (VLMAX - AVL) elements.
    val vl = MuxCase(0.U(WORD_LEN.W), Seq(
      (avl <= vlmax) -> avl,
      (avl > vlmax) -> vlmax,
    ))
    when(prev.reg_csr_cmd === CSR_V) {
      csr(VL_ADDR) := vl
      csr(VTYPE_ADDR) := vtype
    }

    // By default, write back the ALU result to register (wb_sel == WB_ALU)
    wb_data := MuxCase(prev.reg_alu_out, Seq(
      (prev.reg_wb_sel === WB_MEM) -> dmem.rdata, // Loaded data from memory
      (prev.reg_wb_sel === WB_PC) -> (prev.reg_pc + 4.U(WORD_LEN.W)), // Jump instruction stores the next pc (pc+4) to x[rd]
      (prev.reg_wb_sel === WB_CSR) -> csr_rdata, // CSR instruction write back CSRs[csr]
      (prev.reg_wb_sel === WB_VL) -> vl, // vsetvli, vsetivli, vsetvl
    ))

    // Save MEM states for next stage
    reg_wb_addr := prev.reg_wb_addr
    reg_rf_wen  := prev.reg_rf_wen
    reg_wb_data := wb_data

    printf(p"MEM: pc=0x${Hexadecimal(prev.reg_pc)} wb_data=0x${Hexadecimal(wb_data)} rs1=0x${Hexadecimal(prev.reg_rs1_data)} rs2=0x${Hexadecimal(prev.reg_rs2_data)}\n")
  }
}

// WB
class WriteBackStage {
  def connect(prev: MemStage, gr: Mem[UInt]) = {
    when(prev.reg_rf_wen === REN_SCALAR) {
      gr(prev.reg_wb_addr) := prev.reg_wb_data // Write back to the register specified by rd
    }

    printf(p"WB: wb_data=0x${Hexadecimal(prev.reg_wb_data)}\n")
  }
}

class Core extends Module {
  val io = IO(new Bundle {
    // Flip input and output to connect to memory
    val imem = Flipped(new ImemPortIo())
    val dmem = Flipped(new DmemPortIo())

    // This port signal will be `true` when a program finished
    val exit = Output(Bool())
    val gp = Output(UInt(WORD_LEN.W)) // for riscv-tests
    val pc = Output(UInt(WORD_LEN.W)) // for riscv-tests
  })

  // RISC-V has 32 registers. Size is 32bits (32bits * 32regs).
  val regfile = Mem(32, UInt(WORD_LEN.W))
  // Control and Status Registers
  val csr_regfile = Mem(4096, UInt(WORD_LEN.W))

  val fetch = new FetchStage()
  val decode = new DecodeStage()
  val execute = new ExecuteStage()
  val mem = new MemStage()
  val wb = new WriteBackStage()

  fetch.connect(io.imem, execute, csr_regfile)
  decode.connect(fetch, execute, mem, regfile)
  execute.connect(decode)
  mem.connect(io.dmem, execute, decode, csr_regfile)
  wb.connect(mem, regfile)

  // We can know that a program is exiting when it is jumping to the current address. This never
  // happens in C source since C does not allow an infinite loop without any side effect. The
  // infinite loop is put in start.s.
  //
  //    00000008 <_loop>:
  //       8:   0000006f                j       8 <_loop>
  //
  // This seems a normal way to represent a program exits. GCC generates a similar code in _exit
  // function (eventually called when a program exists).
  //
  // 0000000000010402 <_exit>:
  //    ...
  //    10410:       00000073                ecall
  //    10414:       00054363                bltz    a0,1041a <_exit+0x18>
  //    10418:       a001                    j       10418 <_exit+0x16>
  //    ...
  //    10426:       008000ef                jal     ra,1042e <__errno>
  //    1042a:       c100                    sw      s0,0(a0)
  //    1042c:       a001                    j       1042c <_exit+0x2a>
  //
  io.exit := execute.jmp_flag && (decode.reg_pc === execute.alu_out)

  io.gp := regfile(3)
  io.pc := execute.reg_pc

  printf(p"dmem: addr=${io.dmem.addr} wen=${io.dmem.wen} wdata=0x${Hexadecimal(io.dmem.wdata)}\n") // memory address loaded by LW

  when(io.exit) {
    printf(p"returned from main with ${regfile(10)}\n") // x10 = a0 = return value or function argument 0
  }
  printf("----------------\n")
}
