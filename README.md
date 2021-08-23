riscv32-cpu-chisel
==================

[RISC-V][riscv] 32bit CPU written in [Chisel][chisel]. This project is for my learning purpose to understand how to design/implement
hardware with Chisel and what RISC-V architecture is.

## References

- [RISC-VとChiselで学ぶ はじめてのCPU自作](https://gihyo.jp/book/2021/978-4-297-12305-5) (Introduction to make your own CPU with RISC-V and Chisel)
- Chisel API Document: https://www.chisel-lang.org/api/latest/chisel3/index.html
- RISC-V Spec: https://riscv.org/technical/specifications/ (https://github.com/riscv/riscv-isa-manual/releases/download/Ratified-IMAFDQC/riscv-spec-20191213.pdf)

This repository was imported from [chisel-template@f5f33c6](https://github.com/freechipsproject/chisel-template/tree/f5f33c69f04a64531cbdb31581e09b95583fba91).

## Test

How to run tests:

```
sbt test
```

[riscv]: https://riscv.org/
[chisel]: https://www.chisel-lang.org/
