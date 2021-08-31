.section .init

# TODO: Setup an exception handler

.global _start
_start:
	call main
1:
	j 1b
