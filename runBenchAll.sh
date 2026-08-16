#!/bin/bash

cd rust && cargo build --release --bin bench_mat --bin bench_tprf3
cd .. && sbt "runMain uni.apps.BenchAll"
