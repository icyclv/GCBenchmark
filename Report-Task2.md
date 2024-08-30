# Kona JDK GC分析-任务二
## 一. 任务
专注于G1GC，写一个JDK的jtreg测试用例，使用一些现有的whitebox API（有必要的话可以自己扩展whitebox API）来实现一个典型的LRU cache，随机的添加LRU cache内容。运行一段时间之后统计old region的对象存活情况。

## 二. 实现思路

### 现有可用的WhiteBox API
1. g1GetMixedGCInfo

Java API定义如下：
```java
  /**
   * Enumerates old regions with liveness less than specified and produces some statistics
   * @param liveness percent of region's liveness (live_objects / total_region_size * 100).
   * @return long[3] array where long[0] - total count of old regions
   *                             long[1] - total memory of old regions
   *                             long[2] - lowest estimation of total memory of old regions to be freed (non-full
   *                             regions are not included)
   */
  public native long[] g1GetMixedGCInfo(int liveness);
``` 
其中表明了该API可以获取到old region中liveness小于指定值的区域的统计信息。包含了old region的总数、总内存、以及最低估计的可以被释放的内存。所以当我们设置liveness为101时，可以获取到所有的old region的统计信息。

其中最低估计可是放内存，即lowest estimation of total memory of old regions to be freed (non-full regions are not included)，我认为存在一定误解，这部分可以查看其源码：
```C++
class OldRegionsLivenessClosure: public HeapRegionClosure {

 private:
  const int _liveness;
  size_t _total_count;
  size_t _total_memory;
  size_t _total_memory_to_free;

 public:
  OldRegionsLivenessClosure(int liveness) :
    _liveness(liveness),
    _total_count(0),
    _total_memory(0),
    _total_memory_to_free(0) { }

    size_t total_count() { return _total_count; }
    size_t total_memory() { return _total_memory; }
    size_t total_memory_to_free() { return _total_memory_to_free; }

  bool do_heap_region(HeapRegion* r) {
    if (r->is_old()) {
      size_t prev_live = r->marked_bytes();
      size_t live = r->live_bytes();
      size_t size = r->used();
      size_t reg_size = HeapRegion::GrainBytes;
      if (size > 0 && ((int)(live * 100 / size) < _liveness)) {
        _total_memory += size;
        ++_total_count;
        if (size == reg_size) {
        // we don't include non-full regions since they are unlikely included in mixed gc
        // for testing purposes it's enough to have lowest estimation of total memory that is expected to be freed
          _total_memory_to_free += size - prev_live;
        }
      }
    }
    return false;
  }
};


WB_ENTRY(jlongArray, WB_G1GetMixedGCInfo(JNIEnv* env, jobject o, jint liveness))
  if (!UseG1GC) {
    THROW_MSG_NULL(vmSymbols::java_lang_UnsupportedOperationException(), "WB_G1GetMixedGCInfo: G1 GC is not enabled");
  }
  if (liveness < 0) {
    THROW_MSG_NULL(vmSymbols::java_lang_IllegalArgumentException(), "liveness value should be non-negative");
  }

  G1CollectedHeap* g1h = G1CollectedHeap::heap();
  OldRegionsLivenessClosure rli(liveness);
  g1h->heap_region_iterate(&rli);

  typeArrayOop result = oopFactory::new_longArray(3, CHECK_NULL);
  result->long_at_put(0, rli.total_count());
  result->long_at_put(1, rli.total_memory());
  result->long_at_put(2, rli.total_memory_to_free());
  return (jlongArray) JNIHandles::make_local(THREAD, result);
WB_END
```

可以看到，这里的Free Memory的计算是
``_total_memory_to_free += size - prev_live;``
其中prev_live是获取HeapRegion的marked_bytes，即上一次GC之后的存活对象大小。
```shell
  // The number of bytes marked live in the region in the last marking phase.
  size_t marked_bytes()    { return _prev_marked_bytes; }
```
因此，我的理解是这里计算的结果是自上次mark时的垃圾和自上次mark到当前时刻新增的对象，都归在了Free Memory中。所以我的理解是这里的Free Memory是不一定被低估的，也可能是高估的。

2. isObjectInOldGen(Object obj)
Java API定义如下：
```java
  public   boolean isObjectInOldGen(Object o) {
        Objects.requireNonNull(o);
        return isObjectInOldGen0(o);
        }

```
C++实现如下：
```C++
WB_ENTRY(jboolean, WB_isObjectInOldGen(JNIEnv* env, jobject o, jobject obj))
  oop p = JNIHandles::resolve(obj);
#if INCLUDE_G1GC
  if (UseG1GC) {
    G1CollectedHeap* g1h = G1CollectedHeap::heap();
    const HeapRegion* hr = g1h->heap_region_containing(p);
    if (hr == NULL) {
      return false;
    }
    return !(hr->is_young());
  }
  ........
```
这里主要是通过获取对象所在的HeapRegion，然后判断该HeapRegion是否是young region。

## 三. 实现思路
1.这里和任务一类似，我们需要实现一个LRU Cache，并设置Live Data Fraction等参数
2.为了统计相关GC信息，这里我主要统计两个信息
- g1GetMixedGCInfo提供的old region数量、大小、以及估计的可以被释放的内存。并根据value的大小，估计old region中的对象数量（不准确）。
- 设置BitSet统计LRU Cache中在OldGen中KV，以及统计上一次统计时LRU中在队头（即即将被移除）的1000个KV在这次统计时是否仍在队内，以估计LRUCache对GC的压力（需要注意的是这里存在一些误差，因为只统计了Key，没统计Value的地址，所以如果一个Key被驱逐后，又重新生成了同一个Key，新的Value的KV对加入Cache，也会被认为是没有被驱逐的）。


- 代码见[TestLRUCache.java](src/main/java/gc/g1/TestLRUCache.java)
```java
    long[] gcInfo = wb.g1GetMixedGCInfo(101);
    double uptimeSec = (double) runtimeMxBean.getUptime() / 1000;
    double usedMemoryMB = (double) gcInfo[1] / 1024 / 1024;
    double freeMemoryMB = (double) gcInfo[2] / 1024 / 1024;

    int valueCount = (int)(gcInfo[1]/objectSize);
    System.out.printf("[INFO] JVM Uptime: %.2f seconds, OldGen Region Count: %d, OldGen Used Memory: %.2f MB, Estimated memory to be freed: %.2f MB, Estimated OldGen object count: %d%n",
            uptimeSec, gcInfo[0], usedMemoryMB, freeMemoryMB, valueCount);

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
    int gap = valueCount - cardinality;
    System.out.printf("[INFO] Number of keys in OldGen: %d, Gap with estimated OldGen object: %d (%d MB) %n", cardinality, gap, gap*objectSize/1024/1024);
    preLRUKeysInOldGen.and(keysInOldGen);
    System.out.printf("[INFO] Number of pre top %d LRU keys still in the Cache and OldGen: %d %n ",statLRUSize, preLRUKeysInOldGen.cardinality());

    // Update and clear for the next iteration
    preLRUKeysInOldGen.clear();
    preLRUKeysInOldGen.or(curLRUKeysInOldGen);
    curLRUKeysInOldGen.clear();
    keysInOldGen.clear();
```

3. 实验设置： 目前使用参数为
- -Xms 1G -Xmx 1G 
- ObjectSize 4KB
- Live Data Fraction 0.6 （即存在157286个KV）
- hit rate 0.9
- 另尝试了通过ProcessTools.createJavaProcessBuilder创建了新的测试进程，但目前仍没有在原进程上进行分析，而是直接手动分析日志。
## 四. 日志分析
JTReg保留的信息见[该文件](log/jtreg/TestLRUCache.jtr)
### Young GC
这里为了方便分析，我们选择GC 日志与我们输出的INFO想接近的一次GC
```shell
[0.200s][info][gc,start    ] GC(4) Pause Young (Normal) (G1 Evacuation Pause)
[0.200s][info][gc,task     ] GC(4) Using 23 workers of 23 for evacuation
[0.204s][info][gc,phases   ] GC(4)   Pre Evacuate Collection Set: 0.1ms
[0.204s][info][gc,phases   ] GC(4)   Merge Heap Roots: 0.1ms
[0.204s][info][gc,phases   ] GC(4)   Evacuate Collection Set: 3.1ms
[0.204s][info][gc,phases   ] GC(4)   Post Evacuate Collection Set: 0.7ms
[0.204s][info][gc,phases   ] GC(4)   Other: 0.1ms
[0.204s][info][gc,heap     ] GC(4) Eden regions: 44->0(49)
[0.204s][info][gc,heap     ] GC(4) Survivor regions: 7->7(7)
[0.204s][info][gc,heap     ] GC(4) Old regions: 173->220
[0.204s][info][gc,heap     ] GC(4) Archive regions: 0->0
[0.204s][info][gc,heap     ] GC(4) Humongous regions: 2->2
[0.204s][info][gc,metaspace] GC(4) Metaspace: 7940K(8128K)->7940K(8128K) NonClass: 7207K(7296K)->7207K(7296K) Class: 732K(832K)->732K(832K)
[0.204s][info][gc          ] GC(4) Pause Young (Normal) (G1 Evacuation Pause) 225M->228M(1024M) 4.152ms
[0.204s][info][gc,cpu      ] GC(4) User=0.04s Sys=0.04s Real=0.00s
[0.210s][info][gc,start    ] GC(5) Pause Young (Normal) (G1 Evacuation Pause)
[0.210s][info][gc,task     ] GC(5) Using 23 workers of 23 for evacuation
[0.215s][info][gc,phases   ] GC(5)   Pre Evacuate Collection Set: 0.2ms
[0.215s][info][gc,phases   ] GC(5)   Merge Heap Roots: 0.1ms
[0.215s][info][gc,phases   ] GC(5)   Evacuate Collection Set: 4.4ms
[0.215s][info][gc,phases   ] GC(5)   Post Evacuate Collection Set: 0.1ms
[0.215s][info][gc,phases   ] GC(5)   Other: 0.1ms
[0.215s][info][gc,heap     ] GC(5) Eden regions: 49->0(62)
[0.215s][info][gc,heap     ] GC(5) Survivor regions: 7->7(7)
[0.215s][info][gc,heap     ] GC(5) Old regions: 220->272
[0.215s][info][gc,heap     ] GC(5) Archive regions: 0->0
[0.215s][info][gc,heap     ] GC(5) Humongous regions: 2->2
[0.215s][info][gc,metaspace] GC(5) Metaspace: 7940K(8128K)->7940K(8128K) NonClass: 7207K(7296K)->7207K(7296K) Class: 732K(832K)->732K(832K)
[0.215s][info][gc          ] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 277M->281M(1024M) 4.997ms
[0.215s][info][gc,cpu      ] GC(5) User=0.04s Sys=0.04s Real=0.01s
[INFO] Iteration: 100000
[INFO] JVM Uptime: 0.22 seconds, OldGen Region Count: 272, OldGen Used Memory: 272.00 MB, Estimated memory to be freed: 272.00 MB, Estimated OldGen object count: 69632
[INFO] Number of keys in OldGen: 65382, Gap with estimated OldGen object: 4250 (16 MB) 
[INFO] Number of pre top 1000 LRU keys stail in OldGen: 0 
```
其中[INFO]为我们的程序输出的日志。带有时间戳的为GC日志。可以看到这里是第一次输出统计信息，因此``[INFO] Number of pre top 1000 LRU keys stail in OldGen: 0 ``输出为0。另外，可以看到Young GC会将Eden晋升为Survivor和Old gen，
即``Old regions: 220->272``。这与我们输出的INFO信息像一致。


另外可以看到，此时由于还没有发生mark周期，导致g1GetMixedGCInfo输出的预取”最小“回收内存为272MB，这与我们的OldGen Used Memory一致。因此与我对该”最小”注释正确性的怀疑所一致。它也可能高估了可回收内存。


### 并发标记
随后是并发标记阶段
```shell
[0.389s][info][gc,start    ] GC(9) Pause Young (Concurrent Start) (G1 Evacuation Pause)
[0.389s][info][gc,task     ] GC(9) Using 23 workers of 23 for evacuation
[0.402s][info][gc,phases   ] GC(9)   Pre Evacuate Collection Set: 0.3ms
[0.402s][info][gc,phases   ] GC(9)   Merge Heap Roots: 0.4ms
[0.402s][info][gc,phases   ] GC(9)   Evacuate Collection Set: 10.8ms
[0.402s][info][gc,phases   ] GC(9)   Post Evacuate Collection Set: 0.5ms
[0.402s][info][gc,phases   ] GC(9)   Other: 0.4ms
[0.402s][info][gc,heap     ] GC(9) Eden regions: 109->0(87)
[0.402s][info][gc,heap     ] GC(9) Survivor regions: 14->16(16)
[0.402s][info][gc,heap     ] GC(9) Old regions: 513->623
[0.402s][info][gc,heap     ] GC(9) Archive regions: 0->0
[0.402s][info][gc,heap     ] GC(9) Humongous regions: 2->2
[0.402s][info][gc,metaspace] GC(9) Metaspace: 7947K(8128K)->7947K(8128K) NonClass: 7215K(7296K)->7215K(7296K) Class: 732K(832K)->732K(832K)
[0.402s][info][gc          ] GC(9) Pause Young (Concurrent Start) (G1 Evacuation Pause) 638M->640M(1024M) 12.458ms
[0.402s][info][gc,cpu      ] GC(9) User=0.08s Sys=0.14s Real=0.01s
[0.402s][info][gc          ] GC(10) Concurrent Mark Cycle
[0.402s][info][gc,marking  ] GC(10) Concurrent Clear Claimed Marks
[0.402s][info][gc,marking  ] GC(10) Concurrent Clear Claimed Marks 0.013ms
[0.402s][info][gc,marking  ] GC(10) Concurrent Scan Root Regions
[0.407s][info][gc,marking  ] GC(10) Concurrent Scan Root Regions 5.229ms
[0.407s][info][gc,marking  ] GC(10) Concurrent Mark
[0.407s][info][gc,marking  ] GC(10) Concurrent Mark From Roots
[0.407s][info][gc,task     ] GC(10) Using 6 workers of 6 for marking
[0.417s][info][gc,marking  ] GC(10) Concurrent Mark From Roots 9.762ms
[0.417s][info][gc,marking  ] GC(10) Concurrent Preclean
[0.417s][info][gc,marking  ] GC(10) Concurrent Preclean 0.178ms
[0.418s][info][gc,start    ] GC(10) Pause Remark
[0.420s][info][gc          ] GC(10) Pause Remark 651M->651M(1024M) 2.084ms
[0.420s][info][gc,cpu      ] GC(10) User=0.01s Sys=0.00s Real=0.00s
[0.420s][info][gc,marking  ] GC(10) Concurrent Mark 12.479ms
[0.420s][info][gc,marking  ] GC(10) Concurrent Rebuild Remembered Sets
[0.425s][info][gc,marking  ] GC(10) Concurrent Rebuild Remembered Sets 5.463ms
[0.426s][info][gc,start    ] GC(10) Pause Cleanup
[0.426s][info][gc          ] GC(10) Pause Cleanup 657M->657M(1024M) 0.321ms
[0.426s][info][gc,cpu      ] GC(10) User=0.00s Sys=0.00s Real=0.00s
[0.426s][info][gc,marking  ] GC(10) Concurrent Cleanup for Next Mark
[0.428s][info][gc,marking  ] GC(10) Concurrent Cleanup for Next Mark 1.930ms
[0.428s][info][gc          ] GC(10) Concurrent Mark Cycle 26.036ms
[INFO] Iteration: 400000
[INFO] JVM Uptime: 0.43 seconds, OldGen Region Count: 623, OldGen Used Memory: 622.50 MB, Estimated memory to be freed: 136.60 MB, Estimated OldGen object count: 159360
[INFO] Number of keys in OldGen: 148053, Gap with estimated OldGen object: 11307 (44 MB) 
[INFO] Number of pre top 1000 LRU keys stail in OldGen: 1000 
[INFO] Iteration: 500000
[INFO] JVM Uptime: 0.48 seconds, OldGen Region Count: 623, OldGen Used Memory: 622.50 MB, Estimated memory to be freed: 136.60 MB, Estimated OldGen object count: 159360
[INFO] Number of keys in OldGen: 138220, Gap with estimated OldGen object: 21140 (82 MB) 
[INFO] Number of pre top 1000 LRU keys stail in OldGen: 32 
```

这里可以看到，Estimated memory to be freed的值与OldGen Used Memory的值产生了差距，表明并发标记阶段，HeapRegion的_prev_marked_bytes被修改。

另外在该阶段可以看到，其中初始标记阶段是利用了Young GC的周期实现以减少STW的影响。随后进行的并发标记阶段，其中remark和cleanup会进行短暂的暂停。

另外可以看到，在两次输出信息之前，此前的Top 1000个LRU Cache中的Key都仅存在32个仍在Cache List及OldGen中。这表明了LRU Cache与常规应用的不同，即违反了 Generational Hypotheses中的Strong Generational Hypotheses（存活越久的对象约可能继续存活）。

### Mixed GC

```shell
 [0.721s][info][gc,start    ] GC(15) Pause Young (Prepare Mixed) (G1 Evacuation Pause)
[0.721s][info][gc,task     ] GC(15) Using 23 workers of 23 for evacuation
[0.727s][info][gc,phases   ] GC(15)   Pre Evacuate Collection Set: 0.1ms
[0.727s][info][gc,phases   ] GC(15)   Merge Heap Roots: 0.3ms
[0.727s][info][gc,phases   ] GC(15)   Evacuate Collection Set: 4.7ms
[0.727s][info][gc,phases   ] GC(15)   Post Evacuate Collection Set: 0.3ms
[0.727s][info][gc,phases   ] GC(15)   Other: 0.1ms
[0.727s][info][gc,heap     ] GC(15) Eden regions: 44->0(44)
[0.727s][info][gc,heap     ] GC(15) Survivor regions: 7->7(7)
[0.727s][info][gc,heap     ] GC(15) Old regions: 812->858
[0.727s][info][gc,heap     ] GC(15) Archive regions: 0->0
[0.727s][info][gc,heap     ] GC(15) Humongous regions: 2->2
[0.727s][info][gc,metaspace] GC(15) Metaspace: 7947K(8128K)->7947K(8128K) NonClass: 7215K(7296K)->7215K(7296K) Class: 732K(832K)->732K(832K)
[0.727s][info][gc          ] GC(15) Pause Young (Prepare Mixed) (G1 Evacuation Pause) 864M->867M(1024M) 5.647ms
[0.727s][info][gc,cpu      ] GC(15) User=0.08s Sys=0.02s Real=0.01s
[INFO] Iteration: 900000
[INFO] JVM Uptime: 0.74 seconds, OldGen Region Count: 858, OldGen Used Memory: 858.00 MB, Estimated memory to be freed: 283.60 MB, Estimated OldGen object count: 219648
[INFO] Number of keys in OldGen: 152307, Gap with estimated OldGen object: 67341 (263 MB) 
[INFO] Number of pre top 1000 LRU keys stail in OldGen: 269 
 [0.788s][info][gc,start    ] GC(16) Pause Young (Mixed) (G1 Evacuation Pause)
[0.788s][info][gc,task     ] GC(16) Using 23 workers of 23 for evacuation
[0.797s][info][gc,phases   ] GC(16)   Pre Evacuate Collection Set: 0.5ms
[0.797s][info][gc,phases   ] GC(16)   Merge Heap Roots: 0.6ms
[0.797s][info][gc,phases   ] GC(16)   Evacuate Collection Set: 6.8ms
[0.797s][info][gc,phases   ] GC(16)   Post Evacuate Collection Set: 1.1ms
[0.797s][info][gc,phases   ] GC(16)   Other: 0.1ms
[0.797s][info][gc,heap     ] GC(16) Eden regions: 44->0(44)
[0.797s][info][gc,heap     ] GC(16) Survivor regions: 7->7(7)
[0.797s][info][gc,heap     ] GC(16) Old regions: 858->845
[0.797s][info][gc,heap     ] GC(16) Archive regions: 0->0
[0.797s][info][gc,heap     ] GC(16) Humongous regions: 2->2
[0.797s][info][gc,metaspace] GC(16) Metaspace: 7956K(8128K)->7956K(8128K) NonClass: 7223K(7296K)->7223K(7296K) Class: 732K(832K)->732K(832K)
[0.797s][info][gc          ] GC(16) Pause Young (Mixed) (G1 Evacuation Pause) 911M->854M(1024M) 9.052ms
[0.797s][info][gc,cpu      ] GC(16) User=0.14s Sys=0.02s Real=0.01s
[INFO] Iteration: 1000000
[INFO] JVM Uptime: 0.81 seconds, OldGen Region Count: 845, OldGen Used Memory: 845.00 MB, Estimated memory to be freed: 320.23 MB, Estimated OldGen object count: 216320
[INFO] Number of keys in OldGen: 152928, Gap with estimated OldGen object: 63392 (247 MB) 
[INFO] Number of pre top 1000 LRU keys stail in OldGen: 288 
```
在Mixed GC中，首次Mixed GC标记为Prepare Mixed，随后为Mixed。这里的Mixed GC其实并不能很好的展现Mixed GC的特点，因为我们的分配速度较快，导致一些Mixed GC中存在Old region在该阶段仍显示上升。
但在第二次Mixed gc可以看到，Old regions: 858->845，即Old region的数量有所下降。另外Gap with estimated OldGen object和OldGen Used Memory的值在第二次Mixed GC前后有所下降，表明GC成功回收的Old Gen的对象。

### Full GC

```shell
[21.313s][info][gc,start       ] GC(574) Pause Young (Mixed) (G1 Evacuation Pause)
[21.313s][info][gc,task        ] GC(574) Using 23 workers of 23 for evacuation
[21.319s][info][gc             ] GC(574) To-space exhausted
[21.319s][info][gc,phases      ] GC(574)   Pre Evacuate Collection Set: 0.2ms
[21.319s][info][gc,phases      ] GC(574)   Merge Heap Roots: 0.6ms
[21.319s][info][gc,phases      ] GC(574)   Evacuate Collection Set: 4.3ms
[21.319s][info][gc,phases      ] GC(574)   Post Evacuate Collection Set: 0.7ms
[21.319s][info][gc,phases      ] GC(574)   Other: 0.1ms
[21.319s][info][gc,heap        ] GC(574) Eden regions: 1->0(51)
[21.319s][info][gc,heap        ] GC(574) Survivor regions: 1->0(0)
[21.319s][info][gc,heap        ] GC(574) Old regions: 1020->1022
[21.319s][info][gc,heap        ] GC(574) Archive regions: 0->0
[21.319s][info][gc,heap        ] GC(574) Humongous regions: 2->2
[21.319s][info][gc,metaspace   ] GC(574) Metaspace: 8272K(8448K)->8272K(8448K) NonClass: 7532K(7616K)->7532K(7616K) Class: 740K(832K)->740K(832K)
[21.319s][info][gc             ] GC(574) Pause Young (Mixed) (G1 Evacuation Pause) 1014M->1014M(1024M) 5.936ms
[21.319s][info][gc,cpu         ] GC(574) User=0.07s Sys=0.00s Real=0.01s
[21.319s][info][gc,ergo        ] Attempting full compaction
[21.319s][info][gc,task        ] GC(575) Using 23 workers of 23 for full compaction
[21.319s][info][gc,start       ] GC(575) Pause Full (G1 Compaction Pause)
[21.319s][info][gc,phases,start] GC(575) Phase 1: Mark live objects
[21.323s][info][gc,phases      ] GC(575) Phase 1: Mark live objects 3.638ms
[21.323s][info][gc,phases,start] GC(575) Phase 2: Prepare for compaction
[21.324s][info][gc,phases      ] GC(575) Phase 2: Prepare for compaction 1.306ms
[21.324s][info][gc,phases,start] GC(575) Phase 3: Adjust pointers
[21.326s][info][gc,phases      ] GC(575) Phase 3: Adjust pointers 2.308ms
[21.326s][info][gc,phases,start] GC(575) Phase 4: Compact heap
[21.345s][info][gc,phases      ] GC(575) Phase 4: Compact heap 18.416ms
[21.346s][info][gc,heap        ] GC(575) Eden regions: 0->0(88)
[21.346s][info][gc,heap        ] GC(575) Survivor regions: 0->0(0)
[21.346s][info][gc,heap        ] GC(575) Old regions: 1022->641
[21.346s][info][gc,heap        ] GC(575) Archive regions: 0->0
[21.346s][info][gc,heap        ] GC(575) Humongous regions: 2->2
[21.346s][info][gc,metaspace   ] GC(575) Metaspace: 8272K(8448K)->8272K(8448K) NonClass: 7532K(7616K)->7532K(7616K) Class: 740K(832K)->740K(832K)
[21.346s][info][gc             ] GC(575) Pause Full (G1 Compaction Pause) 1014M->630M(1024M) 26.572ms
[21.346s][info][gc,cpu         ] GC(575) User=0.47s Sys=0.00s Real=0.03s
[INFO] Iteration: 47700000
[INFO] JVM Uptime: 21.36 seconds, OldGen Region Count: 641, OldGen Used Memory: 628.62 MB, Estimated memory to be freed: 0.00 MB, Estimated OldGen object count: 160926
[INFO] Number of keys in OldGen: 151732, Gap with estimated OldGen object: 9194 (35 MB) 
[INFO] Number of pre top 1000 LRU keys stail in OldGen: 233 
```

在574次GC中，我们可以看到To-space exhausted,即在Mixed GC没有清理左右空间前，又太多对象从新生代晋升，导致Old Gen没有足够空间容纳所有晋升对象。

随后触发了Full GC，可以看到，该过程是STW的，另外Old Regions数量有了明显的下降（远超Mixed GC)。

此外， Estimated memory to be freed可以看到为零，这表明Full GC回收了所有不存活的对象。也可以从Gap with estimated OldGen object看出现在除LRUCache中的value外，OldGen中仅存在约35MB其他对象。

## 五. 总结
1. 通过实验，我们可以看到LRU Cache与常规应用的不同，即违反了 Generational Hypotheses中的Strong Generational Hypotheses（存活越久的对象约可能继续存活）。
2. 目前仅通过WhiteBox API和JTReg进行了简单的统计，并且没有详细的分析，可能需要查找或实现更多获取对象详细信息的API。
3. 通过现有的信息，我们学习了G1 GC的一些阶段特点。
4. 另外，先存在一些疑问：g1GetMixedGCInfo中的lowest estimation of total memory of old regions to be freed 不明确是否注释有些问题，因为根据我的理解，他应该是存在高估的可能的。