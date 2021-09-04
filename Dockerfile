# https://github.com/chadyuu/riscv-chisel-book/blob/master/dockerfile
FROM ubuntu:18.04

ENV RISCV=/opt/riscv
ENV PATH=$RISCV/bin:/root/.cargo/bin:$PATH
WORKDIR $RISCV

# Install apt packages
RUN apt-get update && \
    apt-get install -y --no-install-recommends autoconf automake autotools-dev curl libmpc-dev libmpfr-dev libgmp-dev gawk build-essential bison flex texinfo gperf libtool patchutils bc zlib1g-dev libexpat-dev pkg-config git libusb-1.0-0-dev device-tree-compiler default-jdk gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee -a /etc/apt/sources.list.d/sbt.list && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian /" | tee /etc/apt/sources.list.d/sbt_old.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && \
    apt-get install -y --no-install-recommends sbt && \
    apt-get clean && rm -rf /var/lib/apt/lists/*

# Install RISC-V GNU toolchain
# Note: Docker crashes with `make -j`.
RUN git clone -b rvv-0.9.x --single-branch https://github.com/riscv/riscv-gnu-toolchain.git && \
    cd riscv-gnu-toolchain && git checkout 5842fde8ee5bb3371643b60ed34906eff7a5fa31 && \
    git submodule update --init --recursive && \
    mkdir build && cd build && ../configure --prefix=${RISCV} --enable-multilib && make -j4 && \
    cd ${RISCV} && rm -rf ${RISCV}/riscv-gnu-toolchain

# Install Rust toolchain
RUN curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs > /init-rustup.sh && \
    sh /init-rustup.sh -y --default-toolchain stable --profile minimal --target riscv32i-unknown-none-elf && \
    ~/.cargo/bin/rustup show && \
    rm /init-rustup.sh
