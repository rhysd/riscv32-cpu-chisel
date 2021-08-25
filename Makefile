UI_INSTS := sw lw add addi sub and andi or ori xor xori sll srl sra slli srli srai slt sltu slti sltiu beq bne blt bge bltu bgeu jal jalr lui auipc
MI_INSTS := csr scall

ELF := $(patsubst %, target/share/riscv-tests/isa/rv32ui-p-%, $(UI_INSTS)) $(patsubst %, target/share/riscv-tests/isa/rv32mi-p-%, $(MI_INSTS))
HEX := $(patsubst %, src/riscv/%.hex, $(notdir $(ELF)))
OUT := $(patsubst %, riscv-tests-results/%.out, $(notdir $(ELF)))

SRC := $(wildcard src/main/scala/*.scala)

.PRECIOUS: $(HEX)

test:
	sbt test

target/share/riscv-tests/isa:
	cd riscv-tests
	patch -p1 < ../patch/start_addr.patch
	autoconf
	./configure --prefix=../target
	make -j
	make install

%.hex: %.bin
	od -An -tx1 -w1 -v $< > $@

src/riscv/%.bin: target/share/riscv-tests/isa/%
	riscv64-unknown-elf-objcopy -O binary $< $@

riscv-tests-results/%.out: src/riscv/%.hex $(SRC)
	./scripts/run-riscv-tests.bash $<

riscv-tests: $(OUT)

clean:
	rm -f ./src/riscv/*.hex ./src/riscv/*.bin
	rm -f ./riscv-tests-results/*.out

.PHONY: test clean riscv-tests
