// Test SEW=e32, LMUL=m1

int main() {
    unsigned int size = 5; // Number of elements to compute
    unsigned int vl;

    // Expected behavior:
    //   1st loop: AVL=5, VL=4, size=1
    //   2nd loop: AVL=1, VL=1, size=0

    // Loop until all elements are computed.
    while (size > 0) {
        // `size` might be larger than VL. Store how many elements will be calculated in `vl`.
        asm volatile("vsetvli %0, %1, e32, m1"
                     : "=r"(vl)
                     : "r"(size));

        // Avoid data hazard
        asm volatile("nop");
        asm volatile("nop");
        asm volatile("nop");

        size -= vl;

        // Some vector computation goes here
    }

    return 0;
}
