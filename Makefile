test:
	sbt test

target/share/riscv-tests:
	cd riscv-tests
	patch -p1 < ../patch/start_addr.patch
	autoconf
	./configure --prefix=../target
	make -j
	make install

.PHONY: test
