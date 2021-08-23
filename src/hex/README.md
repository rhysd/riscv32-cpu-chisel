### fetch.hex

```
0: 14131211
4: 24232221
8: 34333231
```

### lw.hex

```
0: 00802303
4: 14131211
8: 22222222
```

0x00802303 is a LW instruction. It loads 4bytes data at memory address 0x08 (0x22222222) to register #6.

```
x[6] = M[x[0] + sext(8)]
```

where `x[0]` is always zero.

### sw.hex

```
0: 00802303
4: 00602823
8: 22222222
```

00602823 is a SW instruction which stores data at register #6 (0x22222222) to memory address 0x10.

```
M[x[0] + sext(16)] = x[6]
```
