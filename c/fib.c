int fib(int i) {
    if (i <= 1) {
        return 1;
    }
    return fib(i - 1) + fib(i - 2);
}

int main() {
    return fib(6);
}
