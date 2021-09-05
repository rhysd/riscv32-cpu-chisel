RISC-V 32bit CPU written in Chisel
==================================
[![CI][ci-badge]][ci]

[RISC-V][riscv] 32bit CPU written in [Chisel][chisel]. This project is for my learning purpose to understand how to design/implement
hardware with Chisel and what RISC-V architecture is. Working in progress.

## References

- RISC-VとChiselで学ぶ はじめてのCPU自作: https://gihyo.jp/book/2021/978-4-297-12305-5
  -  'Introduction to making your own CPU with RISC-V and Chisel' Japanese book
- Chisel API Document: https://www.chisel-lang.org/api/latest/chisel3/index.html
- RISC-V Spec: https://riscv.org/technical/specifications/
  - https://github.com/riscv/riscv-isa-manual/releases/download/Ratified-IMAFDQC/riscv-spec-20191213.pdf
- Assembly Manual: https://github.com/riscv/riscv-asm-manual/blob/master/riscv-asm.md
- Reference simulator: https://github.com/riscv/riscv-isa-sim
  - Proxy kernel: https://github.com/riscv/riscv-pk
- "V" vector extension: https://github.com/riscv/riscv-v-spec
  - https://github.com/riscv/riscv-v-spec/releases/download/v1.0-rc1/riscv-v-spec-1.0-rc1.pdf
- GNU toolchain: https://github.com/riscv/riscv-gnu-toolchain
  - **TODO:** This repository currently refers to `rvv-0.9.x` branch but it should refer to `rvv-intrinsic` branch for RVV v1.0-rc.

This repository was imported from [chisel-template@f5f33c6](https://github.com/freechipsproject/chisel-template/tree/f5f33c69f04a64531cbdb31581e09b95583fba91).

## Install

Clone this repository:

```sh
git clone --recursive https://github.com/rhysd/riscv32-cpu-chisel.git
```

Build Docker image for RISC-V GNU toolchain and Scala toolchain:

```sh
docker build . -t riscv/mycpu
```

Start an interactive shell with mounting this repository:

```sh
docker run -it -v $(pwd):/app riscv/mycpu
```

## Generate Verilog sources

Verilog sources can be generated from Chisel sources via `sbt run`:

```sh
make ./c/fib.hex # Make hex dump of memory image of program to run
make verilog MEMORY_HEX_FILE_PATH=./c/fib.hex # Generate Verilog sources
cat ./verilog/Top.v
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

To run all tests with C sources in [`c/` directory](./c/):

```sh
make c-tests
```

Outputs of tests are stored in `c-tests-results` directory.

To run a specific test case in c-tests (when running [`fib.c`](./c/fib.c) test case):

```sh
make ./c-tests-results/fib.out
```

### Rust tests

To run all tests with Rust sources in [`rust/` directory](./rust/):

```sh
make rust-tests
```

Outputs of tests are stored in `rust-tests-results` directory.

To run a specific test case in rust-tests (when running [`fib`](./rust/src/fib) test case):

```sh
make ./rust-tests-results/fib.out
```

### Run tests with the uploaded Docker image

Run `docker run` with [the uploaded Docker image][docker].

```sh
docker run --rm -v $(pwd):/app --workdir /app -t rhysd/riscv-cpu-chisel:latest make riscv-tests
```

## License

Distributed under [the MIT license](./LICENSE.txt).

[ci-badge]: https://github.com/rhysd/riscv32-cpu-chisel/actions/workflows/ci.yaml/badge.svg
[ci]: https://github.com/rhysd/riscv32-cpu-chisel/actions/workflows/ci.yaml
[riscv]: https://riscv.org/
[chisel]: https://www.chisel-lang.org/
[docker]: https://hub.docker.com/r/rhysd/riscv-cpu-chisel
