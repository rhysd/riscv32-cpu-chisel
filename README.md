riscv32-cpu-chisel
==================

[RISC-V][riscv] 32bit CPU written in [Chisel][chisel]. This project is for my learning purpose to understand how to design/implement
hardware with Chisel and what RISC-V architecture is.

## References

- [RISC-VとChiselで学ぶ はじめてのCPU自作](https://gihyo.jp/book/2021/978-4-297-12305-5) (Introduction to make your own CPU with RISC-V and Chisel)
- Chisel API Document: https://www.chisel-lang.org/api/latest/chisel3/index.html
- RISC-V Spec: https://riscv.org/technical/specifications/
  - https://github.com/riscv/riscv-isa-manual/releases/download/Ratified-IMAFDQC/riscv-spec-20191213.pdf

This repository was imported from [chisel-template@f5f33c6](https://github.com/freechipsproject/chisel-template/tree/f5f33c69f04a64531cbdb31581e09b95583fba91).

## Install

Clone this repository:

```sh
git clone --recursive https://github.com/rhysd/riscv32-cpu-chisel.git
```

And build Docker image for RISC-V GNU toolchain and Scala toolchain:

```sh
docker build . -t riscv/mycpu
docker run -it -v $(pwd):/app riscv/mycpu
```

## Test

### riscv-tests

To run all tests in [riscv-tests](https://github.com/riscv/riscv-tests):

```sh
make riscv-tests
```

Outputs of tests are stored in `riscv-tests-results` directory.

To run a specific test case in riscv-tests (when running `rv32ui-p-addi` test case):

```sh
make ./riscv-tests-results/rv32ui-p-addi.out
```

### C tests

To run all tests with C sources in [`src/c`](./src/c/):

```sh
make c-tests
```

Outputs of tests are stored in `c-tests-results` directory.

To run a specific test case in riscv-tests (when running [`ctest.c`](./src/c/ctest.c) test case):

```sh
make ./c-tests-results/ctest.out
```

[riscv]: https://riscv.org/
[chisel]: https://www.chisel-lang.org/
