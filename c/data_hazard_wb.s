# GCC did not emit expected code when compiling sample code at p.203
# Instead I hand-assemble the code here
	.file	"data_hazard_wb.c"
	.option nopic
	.option checkconstraints
	.attribute arch, "rv32i2p0"
	.attribute unaligned_access, 0
	.attribute stack_align, 16
	.text
	.align	2
	.globl	main
	.type	main, @function
main:
	li a0, 1
	nop
	nop
	add a1, a0, a0
	nop
	nop
	nop
	unimp
	li	a5,0
	mv	a0,a5
	lw	s0,12(sp)
	addi	sp,sp,16
	jr	ra
	.size	main, .-main
	.ident	"GCC: (GNU) 9.2.0"
