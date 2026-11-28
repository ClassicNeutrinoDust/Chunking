# Benchmark setup
All the benchmark done on laptop CPU ```Intel® Core™ i7 processor 14700HX 14th gen``` with Turbo off @ ```2.1ghz``` base speed with ```Long power PL1 = 115W and short power PL2 = 90W``` and on ```windows 11 26H2 OS build 26300.9974``` . 

```
java version "25.0.4" 2026-07-21 LTS
Java(TM) SE Runtime Environment (build 25.0.4+7-LTS-189)
Java HotSpot(TM) 64-Bit Server VM (build 25.0.4+7-LTS-189, mixed mode, sharing)
```

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
# C2ME Benchmark
Fabric mods used are:

```
c2me-fabric-mc26.2-0.4.2-alpha.0.67.jar
Chunky-Fabric-1.5.66.jar
cloth-config-26.2.155.jar
fabric-api-0.161.0+26.2.jar
ScalableLux-fabric-0.3.0-alpha.0.3-all.jar
spark-1.10.187-fabric.jar
```

# Vanilla Benchmark
Fabric mods used are:

```
Chunky-Fabric-1.5.66.jar
cloth-config-26.2.155.jar
fabric-api-0.161.0+26.2.jar
ScalableLux-fabric-0.3.0-alpha.0.3-all.jar
spark-1.10.187-fabric.jar
```
Chunking Config: Set ```worldgen_workers = {Threads being used}``` in chunking.properties.
C2ME config: ```threadPoolPriorityPreset = "UNSET"``` and ```globalExecutorParallelism = {Threads being used}``` and rest default config.

Vanilla JVM flags: ```-Xms4G -Xmx4G -Datlas.entity.hotpath=true -Datlas.entity.ai.index=true -Datlas.entity.ai.sensor.cache=false -Datlas.entity.ai.poi.index=false -Datlas.entity.ai.navigation.cache=false -Datlas.entity.ai.collision.query=false -Datlas.entity.profile=true -Datlas.entity.query.profile=true -Dmax.bg.threads=<N>```

JVM Flags(C2ME and Chunking): ```-Xms4G -Xmx4G -Datlas.entity.hotpath=true -Datlas.entity.ai.index=true -Datlas.entity.ai.sensor.cache=false -Datlas.entity.ai.poi.index=false -Datlas.entity.ai.navigation.cache=false -Datlas.entity.ai.collision.query=false -Datlas.entity.profile=true -Datlas.entity.query.profile=true```

**Seed:** `114862964781274189`

**Mesurement**: In overworld | ```Radius = 3072``` | Shape circle | ```center = 100000, 100000``` | 148225 chunks in total.

### Chunking Benchmark (CPU Turbo Boost Off)(Threads 1-16)


| Thread Count | Chunking CPS | C2ME CPS | Vanilla CPS |
|---:|---:|---:|---:|
| 1 | 101.73 | 28.76 | 23.87 |
| 2 | 206.72 | 56.12 | 33.01 |
| 3 | 297.31 | 82.29 | 37.72 |
| 4 | 380.44 | 109.52 | 39.86 |
| 5 | 495.53 | 131.07 | 40.63 |
| 6 | 576.26 | 152.24 | 41.83 |
| 7 | 652.21 | 223.89 | 41.84 |
| 8 | 702.33 | 255.38 | 42.25 |
| 9 | 744.30 | 276.49 | 42.68 |
| 10 | 801.15 | 294.51 | 42.69 |
| 11 | 851.39 | 308.62 | 42.67 |
| 12 | 882.53 | 331.66 | 41.83 |
| 13 | 920.11 | 345.53 | 41.85 |
| 14 | 926.19 | 363.90 | 42.27 |
| 15 | 962.28 | 381.21 | 42.71 |
| 16 | 988.56 | 398.07 | 42.70 |
