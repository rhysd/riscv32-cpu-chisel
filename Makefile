UI_INSTS := sw lw add addi sub and andi or ori xor xori sll srl sra slli srli srai slt sltu slti sltiu beq bne blt bge bltu bgeu jal jalr lui auipc
MI_INSTS := csr scall

ELF := $(patsubst %, target/share/riscv-tests/isa/rv32ui-p-%, $(UI_INSTS)) $(patsubst %, target/share/riscv-tests/isa/rv32mi-p-%, $(MI_INSTS))
RISCV_OUT := $(patsubst %, riscv-tests-results/%.out, $(notdir $(ELF)))
C_OUT := $(patsubst %.c, c-tests-results/%.out, $(notdir $(wildcard c/*.c)))
S_OUT := $(patsubst %.s, c-tests-results/%.out, $(filter-out crt0.s, $(notdir $(wildcard c/*.s))))

SRC := $(wildcard src/main/scala/*.scala)
VERILOG_SRC := verilog/Top.v verilog/Top.Memory.mem.v

target/share/riscv-tests/isa/%:
	cd ./riscv-tests && \
	patch -p1 < ../patch/start_addr.patch && \
	autoconf && \
	./configure "--prefix=$(shell pwd)/target" && \
	make -j && \
	make install

%.hex: %.bin
	od -An -tx1 -w1 -v $< > $@

riscv-tests-results/%.bin: target/share/riscv-tests/isa/%
	riscv64-unknown-elf-objcopy -O binary $< $@

riscv-tests-results/%.out: riscv-tests-results/%.hex $(SRC) src/test/scala/RiscvTests.scala
	MEMORY_HEX_FILE_PATH="$<" sbt "testOnly cpu.RiscvTests" | tee "$@"

riscv-tests: $(RISCV_OUT)

c/%.o: c/%.s
	riscv64-unknown-elf-gcc -march=rv32i -mabi=ilp32 -c -o $@ $<

c/%.o: c/%.c
	riscv64-unknown-elf-gcc -march=rv32i -mabi=ilp32 -c -o $@ $<

c/%.s: c/%.c
	riscv64-unknown-elf-gcc -march=rv32i -mabi=ilp32 -S -o $@ $<

c/%.elf: c/%.o c/crt0.o c/link.ld
	riscv64-unknown-elf-ld -b elf32-littleriscv $< -T ./c/link.ld -o $@ ./c/crt0.o

%.bin: %.elf
	riscv64-unknown-elf-objcopy -O binary $< $@

%.dump: %.elf
	riscv64-unknown-elf-objdump -b elf32-littleriscv -D $< > $@

c-tests-results/%.out: c/%.hex $(SRC) src/test/scala/CTests.scala
	MEMORY_HEX_FILE_PATH="$<" sbt "testOnly cpu.CTests" | tee "$@"

c-tests: $(C_OUT) $(S_OUT)

test: c-tests riscv-tests

$(VERILOG_SRC): $(SRC)
	MEMORY_HEX_FILE_PATH="$(MEMORY_HEX_FILE_PATH)" sbt 'run --target-dir ./verilog'

verilog: $(VERILOG_SRC)

clean:
	rm -f ./riscv-tests-results/*.out ./riscv-tests-results/*.hex ./riscv-tests-results/*.bin
	rm -f ./c/*.elf ./c/*.hex ./c/*.dump
	rm -f ./c-tests-results/*.out

.PHONY: test clean riscv-tests c-tests verilog
