int main() {
    asm volatile("addi a0, x0, 1");
    asm volatile("addi a1, x0, 2");
    asm volatile("jal x0, jump");

    // These instructions should not be executed
    asm volatile("addi a0, x0, 2");
    asm volatile("addi a1, x0, 3");

    // Jump to here
    asm volatile("jump:");

    asm volatile("nop"); // These NOPs runs the instruction until the end of pipeline
    asm volatile("nop");
    asm volatile("nop");
    asm volatile("nop");
    asm volatile("add a2, a0, a1");
    asm volatile("nop");
    asm volatile("nop");
    asm volatile("nop");
    asm volatile("nop");

    return 0;
}
