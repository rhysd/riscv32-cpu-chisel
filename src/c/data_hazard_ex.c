// data hazard between ID and EX

int main() {
    asm volatile("addi a0, x0, 1");
    asm volatile("add a1, a0, a0 "); // addi is at EX and add is at ID

    asm volatile("nop");
    asm volatile("nop");
    asm volatile("nop");

    return 0;
}
