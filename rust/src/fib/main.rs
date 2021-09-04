#![no_std]
#![no_main]

use core::panic::PanicInfo;

#[panic_handler]
fn panic(_: &PanicInfo) -> ! {
    loop {}
}

fn fib(i: i32) -> i32 {
    if i <= 1 {
        1
    } else {
        fib(i - 1) + fib(i - 2)
    }
}

#[no_mangle]
pub extern "C" fn main() -> i32 {
    fib(6)
}
