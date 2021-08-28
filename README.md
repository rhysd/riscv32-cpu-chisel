RISC-V 32bit CPU written in Chisel
==================================
[![CI][ci-badge]][ci]

[RISC-V][riscv] 32bit CPU written in [Chisel][chisel]. This project is for my learning purpose to understand how to design/implement
hardware with Chisel and what RISC-V architecture is. Working in progress.

## References

- RISC-VとChiselで学ぶ はじめてのCPU自作 (Introduction to making your own CPU with RISC-V and Chisel): https://gihyo.jp/book/2021/978-4-297-12305-5
- Chisel API Document: https://www.chisel-lang.org/api/latest/chisel3/index.html
- RISC-V Spec: https://riscv.org/technical/specifications/
  - https://github.com/riscv/riscv-isa-manual/releases/download/Ratified-IMAFDQC/riscv-spec-20191213.pdf
- Assembly Manual: https://github.com/riscv/riscv-asm-manual/blob/master/riscv-asm.md

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
[docker]: https://hub.docker.com/repository/docker/rhysd/riscv-cpu-chisel
