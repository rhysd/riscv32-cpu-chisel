UI_INSTS := sw lw add addi sub and andi or ori xor xori sll srl sra slli srli srai slt sltu slti sltiu beq bne blt bge bltu bgeu jal jalr lui auipc
MI_INSTS := csr scall

RISCV_ELF := $(patsubst %,target/share/riscv-tests/isa/rv32ui-p-%,$(UI_INSTS)) $(patsubst %,target/share/riscv-tests/isa/rv32mi-p-%,$(MI_INSTS))
RISCV_HEX := $(patsubst %,riscv-tests-results/%.hex,$(notdir $(RISCV_ELF)))
RISCV_OUT := $(patsubst %.hex,%.out,$(RISCV_HEX))
C_HEX := $(patsubst %.c,%.hex,$(wildcard c/*.c)) $(patsubst %.s,%.hex,$(filter-out c/crt0.s,$(wildcard c/*.s)))
C_OUT := $(patsubst %.hex,c-tests-results/%.out,$(notdir $(C_HEX)))
RUST_HEX := rust/fib.hex

SRC := $(wildcard src/main/scala/*.scala)
VERILOG_SRC := verilog/Top.v verilog/Top.Memory.mem.v

target/share/riscv-tests:
	cd ./riscv-tests && \
	patch -p1 < ../patch/start_addr.patch && \
	autoconf && \
	./configure "--prefix=$(shell pwd)/target" && \
	make -j && \
	make install

%.hex: %.bin
	od -An -tx1 -w1 -v $< > $@

riscv-tests-results/%.bin: target/share/riscv-tests
	riscv64-unknown-elf-objcopy -O binary target/share/riscv-tests/isa/$(basename $(notdir $@)) $@

riscv-tests-results/%.out: riscv-tests-results/%.hex $(SRC) src/test/scala/RiscvTests.scala
	sbt "testOnly cpu.RiscvTests -- -z $<" | tee "$@"

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
	sbt "testOnly cpu.CTests -- -z $<" | tee "$@"

c-tests: $(C_OUT)

rust/fib.elf:
	cd ./rust && cargo build
	cp ./rust/target/riscv32i-unknown-none-elf/debug/fib ./rust/fib.elf

rust-tests-results/%.out: rust/%.hex $(SRC) src/test/scala/RustTests.scala
	sbt "testOnly cpu.RustTests -- -z $<" | tee "$@"

# Use `cat` not to connect output to tty. This prevents sbt shows prompt line on each stdout output line on CI.
ci: $(RISCV_HEX) $(C_HEX) $(RUST_HEX)
	sbt test | cat

$(VERILOG_SRC): $(SRC)
	sbt "run --target-dir ./verilog --memoryHexFile $(MEMORY_HEX_FILE_PATH)"

verilog: $(VERILOG_SRC)

clean:
	rm -f ./riscv-tests-results/*.out ./riscv-tests-results/*.hex ./riscv-tests-results/*.bin
	rm -f ./c/*.elf ./c/*.hex ./c/*.dump
	rm -f ./c-tests-results/*.out
	rm -f ./rust/*.elf ./rust/*.dump ./rust/*.hex
	rm -rf ./rust/target
	rm -f ./rust-tests-results/*.out
	rm -f ./verilog/*.v ./verilog/*.json ./verilog/*.f ./verilog/*.fir

.PHONY: clean riscv-tests c-tests verilog ci
