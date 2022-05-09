# org.eclipse.tm4e.core.benchmarks

This module provides benchmarks for the `org.eclipse.tm4e.core` plugin.

Currently only one benchmark is implemented:

## GrammarBenchmark

The [GrammarBenchmark](src/main/java/org/eclipse/tm4e/core/benchmark/GrammarBenchmark.java) measures how long it takes to tokenize a given source file using the
**[Grammar](../org.eclipse.tm4e.core/src/main/java/org/eclipse/tm4e/core/internal/grammar/Grammar.java)#tokenizeLine()**
method and how much memory is allocated on the JVM heap to do so. As test source file the
[GrammarBenchmark.JavaFile.txt](src/main/resources/GrammarBenchmark.JavaFile.txt) is used.

The benchmark executes multiple rounds. In the beginning warm-up rounds are executed to get the JIT compiler activated.
The results of these rounds should be ignored.

Each round is executed sequentially in a new thread. This gives the OS the opportunity, that in case for the first round an over-utilized core was chosen, to
select a better core to execute the new thread of the next benchmark round.

The output will look something like this:
```yaml
[0.056s][info][gc] Using G1
Source Code chars: 36.385
Source Code lines: 901
JVM Vendor: ojdkbuild
JVM Version: 11.0.14
JVM Inital Heap: 2048.00 MB
JVM Maximum Heap: 2048.00 MB
JVM Args: -Xms2048M -Xmx2048M -XX:+UseG1GC -Xlog:gc:stderr -Dfile.encoding=UTF-8
--------------------------------
Warmup Rounds: 3
Benchmark Rounds: 3
Operations per Benchmark Round: 50
--------------------------------
warm-up #1/3...
#[1.211s][info][gc] GC(0) Pause Young (Normal) (G1 Evacuation Pause) 102M->4M(2048M) 4.842ms
#[2.223s][info][gc] GC(1) Pause Young (Normal) (G1 Evacuation Pause) 241M->5M(2048M) 3.404ms
#[3.204s][info][gc] GC(2) Pause Young (Normal) (G1 Evacuation Pause) 255M->5M(2048M) 3.464ms
#[4.810s][info][gc] GC(3) Pause Young (Normal) (G1 Evacuation Pause) 415M->5M(2048M) 3.597ms
#[7.100s][info][gc] GC(4) Pause Young (Normal) (G1 Evacuation Pause) 593M->5M(2048M) 3.885ms
#[9.617s][info][gc] GC(5) Pause Young (Normal) (G1 Evacuation Pause) 662M->5M(2048M) 3.785ms
#[12.508s][info][gc] GC(6) Pause Young (Normal) (G1 Evacuation Pause) 764M->5M(2048M) 3.840ms
 -> result:  833.39 ops/s |   72.00 ms/op
warm-up #2/3...
#[15.947s][info][gc] GC(7) Pause Young (Normal) (G1 Evacuation Pause) 888M->5M(2048M) 3.773ms
#[20.155s][info][gc] GC(8) Pause Young (Normal) (G1 Evacuation Pause) 1064M->5M(2048M) 3.753ms
#[24.854s][info][gc] GC(9) Pause Young (Normal) (G1 Evacuation Pause) 1220M->5M(2048M) 3.811ms
 -> result:  857.20 ops/s |   70.00 ms/op
warm-up #3/3...
#[29.620s][info][gc] GC(10) Pause Young (Normal) (G1 Evacuation Pause) 1227M->5M(2048M) 3.797ms
#[34.370s][info][gc] GC(11) Pause Young (Normal) (G1 Evacuation Pause) 1227M->5M(2048M) 3.546ms
#[39.043s][info][gc] GC(12) Pause Young (Normal) (G1 Evacuation Pause) 1227M->5M(2048M) 3.655ms
 -> result:  873.68 ops/s |   68.68 ms/op
--------------------------------
benchmark #1/3...
#[42.586s][info][gc] GC(13) Pause Full (System.gc()) 932M->5M(2048M) 8.208ms
#[43.593s][info][gc] GC(14) Pause Full (System.gc()) 5M->5M(2048M) 6.200ms
 -> result:  895.52 ops/s |   67.00 ms/op |   17.88 MB/op
benchmark #2/3...
#[47.952s][info][gc] GC(15) Pause Full (System.gc()) 899M->5M(2048M) 8.303ms
#[48.960s][info][gc] GC(16) Pause Full (System.gc()) 5M->5M(2048M) 6.336ms
 -> result:  872.09 ops/s |   68.80 ms/op |   17.88 MB/op
benchmark #3/3...
#[53.409s][info][gc] GC(17) Pause Full (System.gc()) 899M->5M(2048M) 8.205ms
#[54.415s][info][gc] GC(18) Pause Full (System.gc()) 5M->5M(2048M) 5.578ms
 -> result:  904.98 ops/s |   66.30 ms/op |   17.88 MB/op
DONE.
```

The results of the three benchmark rounds state that it was possible to parse the whole source file between 872.09 and 904.98 times per second.
One time parsing of the source file took between 66.30 and 68.80ms and allocated 17.88MB of temporary objects on the JVM heap.

### How to run the benchmark
To run the benchmark execute the `run-grammar-benchmark.sh` or `run-grammar-benchmark.cmd` from a command line window.

The project also includes an Eclipse launch configuration [GrammarBenchmark.launch](GrammarBenchmark.launch) to run the benchmark from within Eclipse.
You should however only use it for development/debugging/testing purposes and not rely on the results.
