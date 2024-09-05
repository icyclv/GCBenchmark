package gc.g1;


/*
 * @test TestLRUCache.java
 * @summary Test g1 phrases with LRU Cache case
 * @modules java.base/jdk.internal.misc
 * @modules java.management/sun.management
 * @library /test/lib /
 * @requires vm.gc.G1
 * @build jdk.test.whitebox.WhiteBox
 * @run driver jdk.test.lib.helpers.ClassFileInstaller jdk.test.whitebox.WhiteBox
 * @run main/othervm -Xbootclasspath/a:. -XX:+UseG1GC -XX:+UnlockDiagnosticVMOptions -XX:+WhiteBoxAPI  gc.g1.TestLRUCache
 */

import jdk.test.lib.process.OutputAnalyzer;
import jdk.test.lib.process.ProcessTools;
import jdk.test.whitebox.WhiteBox;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public class TestLRUCache {
    public static void main(String[] args) throws Exception {
        final int HeapSize = 1024;

        ProcessBuilder pb = ProcessTools.createJavaProcessBuilder(
                "-Xbootclasspath/a:.",
                "-XX:+UseG1GC",
                "-Xms" + HeapSize + "m",
                "-Xmx" + HeapSize + "m",
                "-XX:+UnlockDiagnosticVMOptions",
                "-XX:+WhiteBoxAPI",
                "-Xlog:gc*",
                GCTest.class.getName());
        OutputAnalyzer output = new OutputAnalyzer(pb.start());
        System.out.println(output.getOutput());
        output.shouldHaveExitValue(0);

    }
}

class GCTest{
    static class LRUCache<Key,Value> {
        private final LinkedHashMap<Key, Value> cache;

        public LRUCache(int size) {

            this.cache = new LinkedHashMap<>(size*4/3, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Key, Value> eldest) {
                    return size() > size;
                }
            };
        }

        public Value get(Key key) {
            return cache.get(key);
        }

        public void put(Key key, Value value) {
            cache.put(key, value);
        }

        public int size() {
            return cache.size();
        }

        public Set<Map.Entry<Key, Value>> entrySet() {
            return cache.entrySet();
        }

        public void clear() {
            cache.clear();
        }


    }

    public static void main(String[] args) {
        final int objectSize = 1024 * 4; // size of the object to store in the cache
        final double hitRate = 0.95; // hit rate of the cache
        final int cacheSize = (int) (Runtime.getRuntime().maxMemory() * 0.6 / objectSize);
        final int statLRUSize = 1000;
        final int maxKey = (int) (cacheSize / hitRate);
        final Random random = new Random();
        final int iteration = 50000000;
        final int logInterval = 100000;


        // Initialize the LRUCache
        LRUCache<Integer, byte[]> cache = new LRUCache<>(cacheSize);
        System.out.println("[INFO] Cache initialized. Size: " + cacheSize + " objects.");
        WhiteBox wb = WhiteBox.getWhiteBox();
        RuntimeMXBean runtimeMxBean = ManagementFactory.getRuntimeMXBean();
        BitSet preLRUKeysInOldGen = new BitSet(maxKey);
        BitSet curLRUKeysInOldGen = new BitSet(maxKey);
        BitSet keysInOldGen = new BitSet(maxKey);

        for (int i = 0; i < iteration; i++) {
            if (i % logInterval == 0) {
                System.out.println("[INFO] Iteration: " + i);

                long[] gcInfo = wb.g1GetOldStat(false);
                double uptimeSec = (double) runtimeMxBean.getUptime() / 1000;
                long regionCount = gcInfo[0];
                double usedMemoryMB = (double) gcInfo[1] / 1024 / 1024;
                double liveMemoryMB = (double) gcInfo[2] / 1024 / 1024;
                long couldBeFreedRegionCount = gcInfo[3];
                double freeMemoryMB = (double) gcInfo[4] / 1024 / 1024;
                long regionSize = gcInfo[5];

                int valueCount = (int)(gcInfo[1]/objectSize);

                int index = 0;
                for (Map.Entry<Integer, byte[]> entry : cache.entrySet()) {
                    int key = entry.getKey();
                    if (wb.isObjectInOldGen(entry.getValue())) {
                        keysInOldGen.set(key);
                        if (index < statLRUSize) {
                            curLRUKeysInOldGen.set(key);
                        }
                    }
                    index++;
                }
                int cardinality = keysInOldGen.cardinality();
                preLRUKeysInOldGen.and(keysInOldGen);

                int gap = valueCount - cardinality;
                System.out.printf("[INFO] JVM Uptime: %.2f seconds, OldGen Region Count: %d, OldGen Used Memory: %.2f MB, OldGen Live Memory: %.2f MB, Total count of old regions to be freed %d, Estimated memory to be freed: %.2f MB, Estimated OldGen object count: %d%n" +
                                "[INFO] Average Region used: %.2f %%, Live percent of used: %.2f %%, Live percent of region size: %.2f %% \n",
                        uptimeSec, regionCount, usedMemoryMB,liveMemoryMB,couldBeFreedRegionCount, freeMemoryMB, valueCount,(double)gcInfo[1]/(regionSize*regionCount)*100, (double)gcInfo[2]/gcInfo[1]*100, (double)gcInfo[2]/(regionSize*regionCount)*100);
                System.out.printf("[INFO] Number of keys in OldGen: %d, Gap with estimated OldGen object: %d (%d MB) %n", cardinality, gap, gap*objectSize/1024/1024);
                System.out.println("[INFO] Number of pre-LRU keys in OldGen: " + preLRUKeysInOldGen.cardinality());

                // Update and clear for the next iteration
                preLRUKeysInOldGen.clear();
                preLRUKeysInOldGen.or(curLRUKeysInOldGen);
                curLRUKeysInOldGen.clear();
                keysInOldGen.clear();
            }

            // Cache operation
            int key = random.nextInt(maxKey);
            byte[] value = cache.get(key);

            if (value == null) {
                value = new byte[objectSize];
                cache.put(key, value);
            }

//            try {
//                Thread.sleep(1);
//            } catch (InterruptedException e) {
//                throw new RuntimeException(e);
//            }
        }
    }
}