## Register names

| Register | ABI           | Description                        |
|----------|---------------|------------------------------------|
| 0        | `zero`        | Constant `0`                       |
| 1        | `ra`          | Return address                     |
| 2        | `sp`          | Stack pointer                      |
| 3        | `gp`          | Global pointer                     |
| 4        | `tp`          | Thread pointer                     |
| 5 to 7   | `t0` to `t2`  | Temporary register                 |
| 8        | `s0`/`fp`     | Save register, frame pointer       |
| 9        | `s1`          | Save register                      |
| 10 to 17 | `a0` to `a7`  | Function arguments or return value |
| 18 to 27 | `s2` to `s11` | Save register                      |
| 28 to 31 | `t3` to `t6`  | Temporary register                 |

[Assembly Manual](https://github.com/riscv/riscv-asm-manual/blob/master/riscv-asm.md)

## SEW ("V" Spec 6.1)

| Assembly | Bits    | SEW     | `vsew` |
|----------|---------|---------|--------|
| `e8`     | 000 (0) | 8bit    | 0      |
| `e16`    | 001 (1) | 16bit   | 1      |
| `e32`    | 010 (2) | 32bit   | 2      |
| `e64`    | 011 (3) | 64bit   | 3      |
| `e128`   | 100 (4) | 128bit  | 4      |
| `e256`   | 101 (5) | 256bit  | 5      |
| `e512`   | 110 (6) | 512bit  | 6      |
| `e1024`  | 111 (7) | 1024bit | 7      |

## LMUL ("V" Spec 6.1)

| Assembly | `vlmul`  | LMUL |
|----------|----------|------|
| `m1`     | 000 (0)  | 1    |
| `m2`     | 001 (1)  | 2    |
| `m4`     | 010 (2)  | 4    |
| `m8`     | 011 (3)  | 8    |
| `mf8`    | 101 (-3) | 1/8  |
| `mf4`    | 110 (-2) | 1/4  |
| `mf2`    | 111 (-1) | 1/2  |
