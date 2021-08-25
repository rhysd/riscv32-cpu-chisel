ELF := $(filter-out %.dump, $(wildcard target/share/riscv-tests/isa/rv32*i-p-*))
HEX := $(patsubst %, src/riscv/%.hex, $(notdir $(ELF)))
OUT := $(patsubst %, riscv-tests-results/%.out, $(notdir $(ELF)))

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

riscv-tests-results/%.out: src/riscv/%.hex
	./scripts/run-riscv-tests.bash $<

riscv-tests: $(OUT)

clean:
	rm -f ./src/riscv/*.hex ./src/riscv/*.bin
	rm -f ./riscv-tests-results/*.out

.PHONY: test clean riscv-tests
