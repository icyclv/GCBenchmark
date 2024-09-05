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

其中最低估计可释放的内存，即lowest estimation of total memory of old regions to be freed (non-full regions are not included)，我认为存在一定误解，这部分可以查看其源码：
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

根据老师的帮助，我们可以参考最新JDK的实现：
```C++
 _total_memory_to_free += size - live;
 ```
即被加数由prev_live改为了live，即free memory的计算变为了(top_at_mark_start-bottom)*HeapWordSize-marked_bytes；而此前为(top-bottom)*HeapWordSize-marked_bytes。

该部分修改可以参考：
- https://bugs.openjdk.org/browse/JDK-8290357
- https://github.com/openjdk/jdk/pull/9511#discussion_r922066796

因此，在JDK17中的G1GetMixedGCInfo得到的Free Memory是有一定错误（高估）的。

2. G1GetOldStat(int printFlag)

如上所示，由于G1GetMixedGCInfo的Free Memory存在一定的错误，因此这里我们添加了一个新的API，用于获取Old Gen的统计信息。
Closure的do_heap_region代码如下，详情可参考[该文件](JTReg/whitebox.cpp#L708):

```C++
 bool do_heap_region(HeapRegion* r) {
    if (r->is_old()) {
      size_t live = r->live_bytes();
      size_t used = r->used();
      size_t reg_size = HeapRegion::GrainBytes;
      if (used > 0) {
        _total_used += used;
        _total_live += live;
        ++_total_count;
        if(_print_flag) {
          tty->print_cr("Region used: %d%% live percent bytes of used: %d%% live percent of region size: %d%%", (int)(used * 100 / reg_size), (int)(live * 100 / used), (int)(live * 100 / reg_size));
        }
        if (used == reg_size && live < (HeapRegion::GrainBytes * (size_t) G1MixedGCLiveThresholdPercent / 100) ) {
          _total_could_free_region++;
          _total_memory_to_free += used - live;

        }
      }
    }
    return false;
  }
  ```

其中判断是否可回收添加了 live < (HeapRegion::GrainBytes * (size_t) G1MixedGCLiveThresholdPercent / 100)的条件。

WhiteBox API定义如下：
```java
 /**
   * Produces some old gen statistics
   * @param liveness percent of region's liveness (live_objects / total_region_size * 100).
   * @return long[6] array where long[0] - total count of old regions
   *                             long[1] - total memory of old regions
   *                             long[2] - total live memory of old regions
   *                             long[3] - total count of old regions to be freed (non-full regions are not included)
   *                             long[4] - lowest estimation of total memory of old regions to be freed
   *                             long[5] - Region size 
   */
  public native long[] g1GetOldStat(boolean printInfo);
```


这里统计了了old gen的数量、使用的内存、存活的内存（marked）、根据计算得到可回收的region数量、可回收的内存、以及Region的大小。



3. isObjectInOldGen(Object obj)

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


- 代码见[TestLRUCache.java](JTReg/TestLRUCache.java)

3. 实验设置： 目前使用参数为
- -Xms 1G -Xmx 1G 
- ObjectSize 4KB
- Live Data Fraction 0.6 （即存在157286个KV）
- hit rate 0.9
- 另尝试了通过ProcessTools.createJavaProcessBuilder创建了新的测试进程，但目前仍没有在原进程上进行分析，而是直接手动分析日志。
## 四. 日志分析

### Young GC
以第8个GC日志为例：
```
[0.323s][info][gc,start    ] GC(8) Pause Young (Normal) (G1 Evacuation Pause)
[0.323s][info][gc,task     ] GC(8) Using 23 workers of 23 for evacuation
[0.333s][info][gc,phases   ] GC(8)   Pre Evacuate Collection Set: 0.3ms
[0.333s][info][gc,phases   ] GC(8)   Merge Heap Roots: 0.3ms
[0.333s][info][gc,phases   ] GC(8)   Evacuate Collection Set: 8.9ms
[0.333s][info][gc,phases   ] GC(8)   Post Evacuate Collection Set: 0.5ms
[0.333s][info][gc,phases   ] GC(8)   Other: 0.1ms
[0.333s][info][gc,heap     ] GC(8) Eden regions: 95->0(109)
[0.333s][info][gc,heap     ] GC(8) Survivor regions: 11->14(14)
[0.333s][info][gc,heap     ] GC(8) Old regions: 421->518
[0.333s][info][gc,heap     ] GC(8) Archive regions: 0->0
[0.333s][info][gc,heap     ] GC(8) Humongous regions: 2->2
[0.333s][info][gc,metaspace] GC(8) Metaspace: 7947K(8128K)->7947K(8128K) NonClass: 7215K(7296K)->7215K(7296K) Class: 732K(832K)->732K(832K)
[0.333s][info][gc          ] GC(8) Pause Young (Normal) (G1 Evacuation Pause) 529M->534M(1024M) 10.106ms
[0.333s][info][gc,cpu      ] GC(8) User=0.06s Sys=0.09s Real=0.01s
[INFO] Iteration: 300000
[INFO] JVM Uptime: 0.35 seconds, OldGen Region Count: 518, OldGen Used Memory: 518.00 MB, OldGen Live Memory: 518.00 MB, Total count of old regions to be freed 0, Estimated memory to be freed: 0.00 MB, Estimated OldGen object count: 132608
[INFO] Average Region used: 100.00 %, Live percent of used: 100.00 %, Live percent of region size: 100.00 % 
[INFO] Number of keys in OldGen: 121858, Gap with estimated OldGen object: 10750 (41 MB) 
[INFO] Number of pre-LRU keys in OldGen: 1000
```

可以看到本次GC将Eden晋升为Survivor和Old Gen，即``Old regions: 421->518``。


同时，由于还没有进行Mark，所以我们输出``Live percent of used: 100.00 %``，也就是说top_at_mark_start和marked_bytes还均为未更新，导致所有的对象都被计算为存活的。


### 并发标记

```
[0.401s][info][gc          ] GC(10) Concurrent Mark Cycle
[0.401s][info][gc,marking  ] GC(10) Concurrent Clear Claimed Marks
[0.401s][info][gc,marking  ] GC(10) Concurrent Clear Claimed Marks 0.013ms
[0.401s][info][gc,marking  ] GC(10) Concurrent Scan Root Regions
[0.406s][info][gc,marking  ] GC(10) Concurrent Scan Root Regions 5.366ms
[0.406s][info][gc,marking  ] GC(10) Concurrent Mark
[0.406s][info][gc,marking  ] GC(10) Concurrent Mark From Roots
[0.406s][info][gc,task     ] GC(10) Using 6 workers of 6 for marking
[0.414s][info][gc,marking  ] GC(10) Concurrent Mark From Roots 7.940ms
[0.414s][info][gc,marking  ] GC(10) Concurrent Preclean
[0.415s][info][gc,marking  ] GC(10) Concurrent Preclean 0.173ms
[0.415s][info][gc,start    ] GC(10) Pause Remark
[0.417s][info][gc          ] GC(10) Pause Remark 659M->659M(1024M) 2.356ms
[0.417s][info][gc,cpu      ] GC(10) User=0.00s Sys=0.01s Real=0.01s
[0.417s][info][gc,marking  ] GC(10) Concurrent Mark 10.893ms
[0.417s][info][gc,marking  ] GC(10) Concurrent Rebuild Remembered Sets
[0.424s][info][gc,marking  ] GC(10) Concurrent Rebuild Remembered Sets 6.118ms
[0.424s][info][gc,start    ] GC(10) Pause Cleanup
[0.424s][info][gc          ] GC(10) Pause Cleanup 666M->666M(1024M) 0.333ms
[0.424s][info][gc,cpu      ] GC(10) User=0.00s Sys=0.00s Real=0.00s
[0.424s][info][gc,marking  ] GC(10) Concurrent Cleanup for Next Mark
[0.426s][info][gc,marking  ] GC(10) Concurrent Cleanup for Next Mark 1.710ms
[0.426s][info][gc          ] GC(10) Concurrent Mark Cycle 25.115ms
[INFO] Iteration: 500000
[INFO] JVM Uptime: 0.43 seconds, OldGen Region Count: 627, OldGen Used Memory: 627.00 MB, OldGen Live Memory: 594.60 MB, Total count of old regions to be freed 50, Estimated memory to be freed: 27.41 MB, Estimated OldGen object count: 160512
[INFO] Average Region used: 100.00 %, Live percent of used: 94.83 %, Live percent of region size: 94.83 % 
[INFO] Number of keys in OldGen: 147609, Gap with estimated OldGen object: 12903 (50 MB) 
[INFO] Number of pre-LRU keys in OldGen: 769
```

在并发标记阶段，我们可以看到``Live percent of used%``由100%降低到了94.83%。这是由于top_at_mark_start和marked_bytes的更新导致在bottom到top_at_mark_start之间的未被标记的对象都归为了垃圾。

其中，``Total count of old regions to be freed 50, Estimated memory to be freed: 27.41 MB``，这是根据我们实现的G1GetOldStat API得到的。
即其中满足了``used==reg_size && live < (HeapRegion::GrainBytes * (size_t) G1MixedGCLiveThresholdPercent / 100)``的Region有50个，总共可以释放27.41MB的内存。

这是为进一步的Mixed GC做准备。

### Mixed GC

```
[0.520s][info][gc,start    ] GC(11) Pause Young (Prepare Mixed) (G1 Evacuation Pause)
[0.520s][info][gc,task     ] GC(11) Using 23 workers of 23 for evacuation
[0.529s][info][gc,phases   ] GC(11)   Pre Evacuate Collection Set: 0.3ms
[0.529s][info][gc,phases   ] GC(11)   Merge Heap Roots: 1.0ms
[0.529s][info][gc,phases   ] GC(11)   Evacuate Collection Set: 6.6ms
[0.529s][info][gc,phases   ] GC(11)   Post Evacuate Collection Set: 1.0ms
[0.529s][info][gc,phases   ] GC(11)   Other: 0.1ms
[0.529s][info][gc,heap     ] GC(11) Eden regions: 86->0(38)
[0.529s][info][gc,heap     ] GC(11) Survivor regions: 16->13(13)
[0.529s][info][gc,heap     ] GC(11) Old regions: 627->717
[0.529s][info][gc,heap     ] GC(11) Archive regions: 0->0
[0.529s][info][gc,heap     ] GC(11) Humongous regions: 2->2
[0.529s][info][gc,metaspace] GC(11) Metaspace: 7958K(8128K)->7958K(8128K) NonClass: 7226K(7296K)->7226K(7296K) Class: 732K(832K)->732K(832K)
[0.529s][info][gc          ] GC(11) Pause Young (Prepare Mixed) (G1 Evacuation Pause) 731M->731M(1024M) 9.074ms
[0.529s][info][gc,cpu      ] GC(11) User=0.07s Sys=0.08s Real=0.01s
......
[INFO] JVM Uptime: 0.56 seconds, OldGen Region Count: 717, OldGen Used Memory: 716.50 MB, OldGen Live Memory: 684.10 MB, Total count of old regions to be freed 50, Estimated memory to be freed: 27.41 MB, Estimated OldGen object count: 183424
[INFO] Average Region used: 99.93 %, Live percent of used: 95.48 %, Live percent of region size: 95.41 % 
[INFO] Number of keys in OldGen: 148589, Gap with estimated OldGen object: 34835 (136 MB) 
[INFO] Number of pre-LRU keys in OldGen: 71
[0.591s][info][gc,start    ] GC(12) Pause Young (Mixed) (G1 Evacuation Pause)
[0.591s][info][gc,task     ] GC(12) Using 23 workers of 23 for evacuation
[0.597s][info][gc,phases   ] GC(12)   Pre Evacuate Collection Set: 0.3ms
[0.597s][info][gc,phases   ] GC(12)   Merge Heap Roots: 0.3ms
[0.597s][info][gc,phases   ] GC(12)   Evacuate Collection Set: 4.6ms
[0.597s][info][gc,phases   ] GC(12)   Post Evacuate Collection Set: 0.5ms
[0.597s][info][gc,phases   ] GC(12)   Other: 0.1ms
[0.597s][info][gc,heap     ] GC(12) Eden regions: 38->0(44)
[0.597s][info][gc,heap     ] GC(12) Survivor regions: 13->7(7)
[0.597s][info][gc,heap     ] GC(12) Old regions: 717->763
[0.597s][info][gc,heap     ] GC(12) Archive regions: 0->0
[0.597s][info][gc,heap     ] GC(12) Humongous regions: 2->2
[0.597s][info][gc,metaspace] GC(12) Metaspace: 7969K(8192K)->7969K(8192K) NonClass: 7236K(7360K)->7236K(7360K) Class: 732K(832K)->732K(832K)
[0.597s][info][gc          ] GC(12) Pause Young (Mixed) (G1 Evacuation Pause) 769M->771M(1024M) 5.935ms
[0.597s][info][gc,cpu      ] GC(12) User=0.07s Sys=0.03s Real=0.01s
[INFO] Iteration: 1000000
[INFO] JVM Uptime: 0.60 seconds, OldGen Region Count: 763, OldGen Used Memory: 762.50 MB, OldGen Live Memory: 731.08 MB, Total count of old regions to be freed 49, Estimated memory to be freed: 26.42 MB, Estimated OldGen object count: 195200
[INFO] Average Region used: 99.93 %, Live percent of used: 95.88 %, Live percent of region size: 95.82 % 
[INFO] Number of keys in OldGen: 153889, Gap with estimated OldGen object: 41311 (161 MB) 
[INFO] Number of pre-LRU keys in OldGen: 263
......
[0.678s][info][gc,start    ] GC(13) Pause Young (Mixed) (G1 Evacuation Pause)
[0.678s][info][gc,task     ] GC(13) Using 23 workers of 23 for evacuation
[0.684s][info][gc,phases   ] GC(13)   Pre Evacuate Collection Set: 0.1ms
[0.684s][info][gc,phases   ] GC(13)   Merge Heap Roots: 0.3ms
[0.684s][info][gc,phases   ] GC(13)   Evacuate Collection Set: 4.5ms
[0.684s][info][gc,phases   ] GC(13)   Post Evacuate Collection Set: 1.3ms
[0.684s][info][gc,phases   ] GC(13)   Other: 0.1ms
[0.684s][info][gc,heap     ] GC(13) Eden regions: 44->0(44)
[0.684s][info][gc,heap     ] GC(13) Survivor regions: 7->7(7)
[0.684s][info][gc,heap     ] GC(13) Old regions: 763->809
[0.684s][info][gc,heap     ] GC(13) Archive regions: 0->0
[0.684s][info][gc,heap     ] GC(13) Humongous regions: 2->2
[0.684s][info][gc,metaspace] GC(13) Metaspace: 7999K(8192K)->7999K(8192K) NonClass: 7267K(7360K)->7267K(7360K) Class: 732K(832K)->732K(832K)
[0.684s][info][gc          ] GC(13) Pause Young (Mixed) (G1 Evacuation Pause) 815M->817M(1024M) 6.377ms
[0.684s][info][gc,cpu      ] GC(13) User=0.05s Sys=0.04s Real=0.00s
[INFO] Iteration: 1200000
[INFO] JVM Uptime: 0.69 seconds, OldGen Region Count: 809, OldGen Used Memory: 808.45 MB, OldGen Live Memory: 777.99 MB, Total count of old regions to be freed 48, Estimated memory to be freed: 25.47 MB, Estimated OldGen object count: 206964
[INFO] Average Region used: 99.93 %, Live percent of used: 96.23 %, Live percent of region size: 96.17 % 
[INFO] Number of keys in OldGen: 154076, Gap with estimated OldGen object: 52888 (206 MB) 
[INFO] Number of pre-LRU keys in OldGen: 297
```

在Mixed GC阶段，我们可以看到Total count of old regions to be freed由50降低到了49，Estimated memory to be freed也由27.41MB降低到了26.42MB。即在Mixed GC阶段，释放了部分liveness较低的Old Region。
同时，从GC info中可以看到，虽然Mixed GC回收了部分Old Region，但同时Eden和Survivor也晋升到Old Region，导致Old Region的数量反而增加。

因此，在多次这样的更新后，由于Mided GC无法及时将Old Region中的对象回收，最终导致晋升空间不足，进而导致Full GC。

### Full GC

```
[INFO] Iteration: 1900000
[INFO] JVM Uptime: 0.99 seconds, OldGen Region Count: 939, OldGen Used Memory: 938.71 MB, OldGen Live Memory: 623.81 MB, Total count of old regions to be freed 790, Estimated memory to be freed: 308.42 MB, Estimated OldGen object count: 240310
[INFO] Average Region used: 99.97 %, Live percent of used: 66.45 %, Live percent of region size: 66.43 % 
[INFO] Number of keys in OldGen: 149734, Gap with estimated OldGen object: 90576 (353 MB) 
[INFO] Number of pre-LRU keys in OldGen: 50
[1.035s][info][gc,start    ] GC(19) Pause Young (Mixed) (G1 Preventive Collection)
[1.035s][info][gc,task     ] GC(19) Using 23 workers of 23 for evacuation
[1.040s][info][gc          ] GC(19) To-space exhausted
[1.040s][info][gc,phases   ] GC(19)   Pre Evacuate Collection Set: 0.2ms
[1.040s][info][gc,phases   ] GC(19)   Merge Heap Roots: 0.6ms
[1.040s][info][gc,phases   ] GC(19)   Evacuate Collection Set: 3.3ms
[1.040s][info][gc,phases   ] GC(19)   Post Evacuate Collection Set: 0.5ms
[1.040s][info][gc,phases   ] GC(19)   Other: 0.1ms
[1.040s][info][gc,heap     ] GC(19) Eden regions: 6->0(46)
[1.040s][info][gc,heap     ] GC(19) Survivor regions: 7->5(7)
[1.040s][info][gc,heap     ] GC(19) Old regions: 972->1017
[1.040s][info][gc,heap     ] GC(19) Archive regions: 0->0
[1.040s][info][gc,heap     ] GC(19) Humongous regions: 2->2
[1.040s][info][gc,metaspace] GC(19) Metaspace: 8063K(8256K)->8063K(8256K) NonClass: 7331K(7424K)->7331K(7424K) Class: 732K(832K)->732K(832K)
[1.040s][info][gc          ] GC(19) Pause Young (Mixed) (G1 Preventive Collection) 986M->1024M(1024M) 4.753ms
[1.040s][info][gc,cpu      ] GC(19) User=0.09s Sys=0.00s Real=0.01s
[1.040s][info][gc,ergo     ] Attempting full compaction
[1.040s][info][gc,task     ] GC(20) Using 23 workers of 23 for full compaction
[1.040s][info][gc,start    ] GC(20) Pause Full (G1 Compaction Pause)
[1.040s][info][gc,phases,start] GC(20) Phase 1: Mark live objects
[1.045s][info][gc,phases      ] GC(20) Phase 1: Mark live objects 4.923ms
[1.045s][info][gc,phases,start] GC(20) Phase 2: Prepare for compaction
[1.048s][info][gc,phases      ] GC(20) Phase 2: Prepare for compaction 2.109ms
[1.048s][info][gc,phases,start] GC(20) Phase 3: Adjust pointers
[1.051s][info][gc,phases      ] GC(20) Phase 3: Adjust pointers 3.885ms
[1.052s][info][gc,phases,start] GC(20) Phase 4: Compact heap
[1.077s][info][gc,phases      ] GC(20) Phase 4: Compact heap 25.341ms
[1.078s][info][gc,heap        ] GC(20) Eden regions: 0->0(89)
[1.078s][info][gc,heap        ] GC(20) Survivor regions: 5->0(7)
[1.078s][info][gc,heap        ] GC(20) Old regions: 1017->642
[1.078s][info][gc,heap        ] GC(20) Archive regions: 0->0
[1.078s][info][gc,heap        ] GC(20) Humongous regions: 2->2
[1.078s][info][gc,metaspace   ] GC(20) Metaspace: 8063K(8256K)->8058K(8256K) NonClass: 7331K(7424K)->7327K(7424K) Class: 732K(832K)->730K(832K)
[1.078s][info][gc             ] GC(20) Pause Full (G1 Compaction Pause) 1024M->630M(1024M) 37.214ms
[1.078s][info][gc,cpu         ] GC(20) User=0.60s Sys=0.01s Real=0.04s
[INFO] Iteration: 2000000
[INFO] JVM Uptime: 1.09 seconds, OldGen Region Count: 642, OldGen Used Memory: 628.27 MB, OldGen Live Memory: 628.27 MB, Total count of old regions to be freed 0, Estimated memory to be freed: 0.00 MB, Estimated OldGen object count: 160837
[INFO] Average Region used: 97.86 %, Live percent of used: 100.00 %, Live percent of region size: 97.86 % 
[INFO] Number of keys in OldGen: 155464, Gap with estimated OldGen object: 5373 (20 MB) 
[INFO] Number of pre-LRU keys in OldGen: 327
```
在第19个GC中，我们可以看到``To-space exhausted``的标志，
即在Mixed GC没有清理左右空间前，又太多对象从新生代晋升，导致Old Gen没有足够空间容纳所有晋升对象。

随后发生了Full GC，这里我们可以看到，该GC导致STW时间较长，同时回收也非常彻底。

从Full GC开始前的日志可以看到`` Live percent of used: 66.45 %``，即平均每个Region中使用的内存由不低于33%的内存是垃圾对象。

而在Full GC结束后，Old regions: 1017->642，即下降了接近35%的老区，同时我们可以看到``Live percent of used: 100.00 %``，即所有的Region中的对象都是存活的。



<details>
<summary>基于G1GetMixedGCInfo的分析 </summary>


这部分是此前基于G1GetMixedGCInfo的日志分析，因为探索发现JDK17中该信息有一定误解，所以隐藏该部分 。JTReg保留的信息见[该文件](log/jtreg/TestLRUCache.jtr)
### Young GC
这里为了方便分析，我们选择GC 日志与我们输出的INFO最接近的一次GC
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
</details>


## 五. 总结
1. 通过实验，我们可以看到LRU Cache与常规应用的不同，即违反了 Generational Hypotheses中的Strong Generational Hypotheses（存活越久的对象约可能继续存活）。
2. 目前通过WhiteBox API和JTReg进行了G1 GC的过程进行了简单的统计分析。
3. 通过现有的信息，我们学习了G1 GC的一些阶段特点。
4. 另外发现在JDK17中的g1GetMixedGCInfo中的lowest estimation of total memory of old regions to be freed 有些问题，即应该是存在高估的可能的，该问题在该[issue](https://bugs.openjdk.org/browse/JDK-8290357)被一并修复。