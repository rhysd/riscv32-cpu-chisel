// Test SEW=e32, LMUL=m2

int main() {
    unsigned int size = 10; // Number of elements to compute
    unsigned int vl;

    // Expected behavior:
    //   1st loop: AVL=10, VL=8, size=2
    //   2nd loop: AVL=2,  VL=2, size=0

    // Loop until all elements are computed.
    while (size > 0) {
        // Avoid data hazard
        asm volatile("nop");
        asm volatile("nop");
        asm volatile("nop");

        // `size` might be larger than VL. Store how many elements will be calculated in `vl`.
        asm volatile("vsetvli %0, %1, e32, m2"
                     : "=r"(vl)
                     : "r"(size));

        size -= vl;

        // Some vector computation goes here
    }

    return 0;
}
