# What is Chunking?

Chunking is a fabric mod made to improve the chunk generation, I/O, and loading performance of Minecraft 26.2. This is achieved by taking advantage of multiple CPU cores in parallel, compiling hot worldgen paths, and pacing live chunk streaming so gameplay stays responsive.It is made as superfast alternative to C2ME engine(you can view benchmarks below) and perform faster on low number of threads and with smoother MSPT and FPS.

**Important: Because Chunking works deeply with multithreaded world generation and chunk I/O, always back up your worlds before installing**

# Vanilla Parity
Same as C2ME, the mod doesn't sacrifice the vanilla functionality and behaviour and generation. But yes the [non-determinism of Minecraft](https://bugs.mojang.com/browse/MC/issues/MC-55596) still comes into play. So the same seed can have different features/decorations.

Eitherways bugs can happen as its just early stage so report any if you find on github issues.

# Compatibility & Recommendations

For the best overall performance, it is recommended to use Chunking alongside Sodium, Lithium, FerriteCore, and ScalableLux. Chunking detects installed optimization mods automatically and yields any overlapping mixins cleanly:

Chunking is compatible with all vanilla-compatible worldgen mods.Tested with Terralith, Biomes O' Plenty, Tectonic, Moog's Voyager Structures, and Dungeons & Taverns as fully functional. Any other worldgen mod should also be fully compatible.

LOD Mods like Distant Horizons, Voxy are fully compatible.It also automatically reduces client worldgen workers to leave headroom for LOD generation.. 
