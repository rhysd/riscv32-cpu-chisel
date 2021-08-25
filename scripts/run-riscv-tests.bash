#!/bin/bash

set -e -o pipefail

run-test-case() {
    local file base out
    file="$1"
    base="${file##*/}"
    out="./riscv-tests-results/${base%.*}.out"
    echo "Running test case $file..."
    MEMORY_HEX_FILE_PATH="$file" sbt "testOnly cpu.RiscvTests" | tee "$out"
}

if [ ! -d .git ]; then
    echo 'must be run from repository root' 1>&2
    exit 1
fi

if [[ "$#" != "0" ]]; then
    run-test-case "$1"
else
    for f in ./src/riscv/*.hex; do
        run-test-case "$f"
    done
fi
