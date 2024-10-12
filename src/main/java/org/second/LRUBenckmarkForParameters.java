package org.second;


import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.Throughput)
@OutputTimeUnit(TimeUnit.SECONDS)
@Warmup(iterations = 5, time = 10)
@Measurement(iterations = 5, time = 10)
@State(Scope.Benchmark)
public class LRUBenckmarkForParameters {

    private final Random random = new Random();
    @Param({"4096"})
    public int objectSize; // size of the object to store in the cache
    //
    public int cacheSize; // size of the LRU cache in number of objects
    @Param({"0.9"})
    public double hitRate; // hit rate of the cache
    @Param({"0.5"})
    public double liveDataFraction; // fraction of live data in the cache
    private LRUCache<Integer, byte[]> cache;

    @Setup
    public void setUp() {
        // cache  size is calculated based on the live data fraction
        cacheSize = (int) (Runtime.getRuntime().maxMemory() / objectSize * liveDataFraction);
        cache = new LRUCache<>(cacheSize);
    }



    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC",  "-XX:+UnlockExperimentalVMOptions", "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_originalG1GC_%t_%p.log"})
    public void G1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1MixedGCCountTarget=6", "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_mixedGCCountTargetG1GC_%t_%p.log"})
    public void mixedCountTargetG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1HeapWastePercent=3", "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_heapWasteG1GC_%t_%p.log"})
    public void heapWasteG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1MixedGCLiveThresholdPercent=95", "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_liveThreshG1GC_%t_%p.log"})
    public void liveThresholdG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1OldCSetRegionThresholdPercent=20", "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_oldCSetRegionThreshG1GC_%t_%p.log"})
    public void oldCSetRegionThresholdG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1OldCSetRegionThresholdPercent=20", "-XX:G1MixedGCCountTarget=6","-Xlog:gc+cset*=debug,gc*,gc+ihop*=debug:file=gc_log_CSetAndCountG1GC_%t_%p.log"})
    public void oldCSetRegionThresholdAndCountG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1HeapWastePercent=3", "-XX:G1MixedGCLiveThresholdPercent=95", "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_heapWasteAndLiveThresholdG1GC_%t_%p.log"})
    public void heapWasteAndLiveThresholdG1GC(Blackhole blackhole) {
        testLRU(blackhole);
    }

    @Benchmark
    @Fork(value = 1, jvmArgsAppend = {"-server", "-XX:+UseG1GC", "-XX:+UnlockExperimentalVMOptions","-XX:G1OldCSetRegionThresholdPercent=20", "-XX:G1MixedGCCountTarget=6","-XX:G1HeapWastePercent=3", "-XX:G1MixedGCLiveThresholdPercent=95",  "-Xlog:gc*,gc+cset*=debug,gc+ihop*=debug:file=gc_log_allG1GC_%t_%p.log"})
    public void allG1GC(Blackhole blackhole) {
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

    @TearDown(Level.Iteration)
    public void doFullGC() {
        System.gc(); // Request Full GC after each iteration
    }
    public static void main(String[] args) throws RunnerException {
      // jmh start
        Options opt = new OptionsBuilder()
                .include(LRUBenckmarkForParameters.class.getSimpleName())
                .build();
        new Runner(opt).run();
    }

}
