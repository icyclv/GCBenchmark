package org.second;


import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 5, time = 10)
@State(Scope.Benchmark)
public class LRUBenckmark {

    private final Random random = new java.util.Random();
    @Param({"4096"})
    public int objectSize; // size of the object to store in the cache
    //
    public int cacheSize; // size of the LRU cache in number of objects
    @Param({"0.9"})
    public double hitRate; // hit rate of the cache
    @Param({"0.6"})
    public double liveDataFraction; // fraction of live data in the cache
    private LRUCache<Integer, byte[]> cache;

    @Setup
    public void setUp() {
        // cache  size is calculated based on the live data fraction
        cacheSize = (int) (Runtime.getRuntime().maxMemory() / objectSize * liveDataFraction);
        cache = new LRUCache<>(cacheSize);
    }


    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-Xlog:gc*:file=gc_log_G1_%t_%p.log"})
    public void G1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions", "-XX:G1MixedGCCountTarget=4", "-Xlog:gc*:file=gc_log_MixedGCCountTargetLowG1GC_%t_%p.log"})
    public void MixedGCCountTargetLowG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseParallelGC", "-Xlog:gc*:file=gc_log_ParallelGC_%t_%p.log"})
    public void ParallelGC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseZGC", "-Xlog:gc*:file=gc_log_ZGC_%t_%p.log"})
    public void ZGC(Blackhole blackhole) {
        testLRU(blackhole);
    }


    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseShenandoahGC", "-XX:+UnlockExperimentalVMOptions", "-Xlog:gc*:file=gc_log_Shenandoah_%t_%p.log"})
    public void ShenandoahGC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseSerialGC", "-XX:+UnlockExperimentalVMOptions", "-Xlog:gc*:file=gc_log_SerialGC_%t_%p.log"})
    public void SerialGC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    public void testLRU(Blackhole blackhole) {
        int key = random.nextInt((int) (cacheSize / hitRate));
        byte[] value = cache.get(key);
        if (value == null) {
            value = new byte[objectSize];
            cache.put(key, value);
        }
        blackhole.consume(value); // TODO: Do we need to consume the value? It seems that if we remove this line, the benchmark will still function properly. However, for now, we are keeping it.
    }


}
