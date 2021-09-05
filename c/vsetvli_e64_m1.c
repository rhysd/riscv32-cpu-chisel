// Test SEW=e64, LMUL=m1

int main() {
    unsigned int size = 5; // Number of elements to compute
    unsigned int vl;

    // Expected behavior:
    //   1st loop: AVL=5, VL=2, size=3
    //   2nd loop: AVL=3, VL=2, size=1
    //   3rd loop: AVL=1, VL=1, size=0

    // Loop until all elements are computed.
    while (size > 0) {
        // Avoid data hazard
        asm volatile("nop");
        asm volatile("nop");
        asm volatile("nop");

        // `size` might be larger than VL. Store how many elements will be calculated in `vl`.
        asm volatile("vsetvli %0, %1, e64, m1"
                     : "=r"(vl)
                     : "r"(size));

        size -= vl;

        // Some vector computation goes here
    }

    return 0;
}
