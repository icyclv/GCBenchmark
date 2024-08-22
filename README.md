# GCBenchmark

This is a simple **LRU benchmark** to compare the performance of different garbage collectors in Java. 

## Why LRU?
The Generational Hypotheses, which are the foundation of many GC strategies, state that:

1. Most objects die young.
2. Old objects tend to have longer lifespans.

The LRU algorithm, however, can create a workload that violates these hypotheses. 
## How to run
```shell
mvn clean install
cd target
java -Xms100G -Xmx100G -jar ../GCBench.jar -rf json -rff result.json -p liveDataFraction=0.2,0.3,0.4,0.5,0.6,0.7,0.8 -w 60s -r 60s
```

If you want to control the thread, please use (linux)
```shell
numactl -C 0-11 -m 0  java -Xms100G -Xmx100G -XX:ActiveProcessorCount=12 -jar ../GCBench.jar -rf json -rff result.json -p liveDataFraction=0.2,0.3,0.4,0.5,0.6,0.7,0.8 -w 60s -r 60
```

## Project Report
Project Report is available [here](./Report.md)

