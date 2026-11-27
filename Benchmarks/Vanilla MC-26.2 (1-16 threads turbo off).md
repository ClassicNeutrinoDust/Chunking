# Benchmark setup
All the benchmark done on laptop CPU ```Intel® Core™ i7 processor 14700HX 14th gen``` with Turbo off @ ```2.1ghz``` base speed with ```Long power PL1 = 115W and short power PL2 = 90W``` and on ```windows 11 26H2 OS build 26300.9974``` . 

# Chunking Benchmark
Fabric mods used are:

```
chunking-mc26.2-1.0.0.jar
Chunky-Fabric-1.5.66.jar
cloth-config-26.2.155.jar
fabric-api-0.161.0+26.2.jar
ScalableLux-fabric-0.3.0-alpha.0.3-all.jar
spark-1.10.187-fabric.jar
```

Config: Set ```worldgen_workers``` = {Threads being used} in chunking.properties.

JVM Flags: ```-Xms4G -Xmx4G -Datlas.entity.hotpath=true -Datlas.entity.ai.index=true -Datlas.entity.ai.sensor.cache=false -Datlas.entity.ai.poi.index=false -Datlas.entity.ai.navigation.cache=false -Datlas.entity.ai.collision.query=false -Datlas.entity.profile=true -Datlas.entity.query.profile=true```

Mesurement: In overworld, ```Radius = 3072``` , Shape circle, ```center = 100000, 100000``` , 148225 chunks in total.

### Chunking Benchmark (CPU Turbo Boost Off)(Threads 1-16)


| Thread Count | Chunking CPS | C2ME CPS | Vanilla CPS |
|---:|---:|---:|---:|
| 1 | 101.73 | - | - |
| 2 | 206.72 | - | - |
| 3 | 297.3 | - | - |
| 4 | 380.4 | - | - |
| 5 | 495.53 | - | - |
| 6 | 576.2 | - | - |
| 7 | 652.2 | - | - |
| 8 | 702.3 | - | - |
| 9 | 744.3 | - | - |
| 10 | 801.1 | - | - |
| 11 | 851.3 | - | - |
| 12 | 882.5 | - | - |
| 13 | 920 | - | - |
| 14 | 926 | - | - |
| 15 | 962 | - | - |
| 16 | 988 | - | - |
