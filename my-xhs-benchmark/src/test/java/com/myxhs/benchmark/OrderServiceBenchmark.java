package com.myxhs.benchmark;

import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@State(Scope.Benchmark)
@Warmup(iterations = 3, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 2, timeUnit = TimeUnit.SECONDS)
@Fork(1)
public class OrderServiceBenchmark {

    @Benchmark
    public void benchmarkOrderCreation() {
        // Simulate order creation logic
        // In a real benchmark, you would inject the actual service
        simulateOrderCreation();
    }

    private void simulateOrderCreation() {
        // Lightweight simulation of order creation pipeline
        // Simulate: validation, price calculation, inventory check, persistence
        for (int i = 0; i < 50; i++) {
            Math.sqrt(i + 1);
        }
    }
}
