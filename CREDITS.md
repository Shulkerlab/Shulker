# Credits

Shulker is built on [Paper](https://github.com/PaperMC/Paper) and ports optimisations from other
Paper forks. Every ported patch keeps its original author and license note in the patch header
(`shulker-server/minecraft-patches/features/`), and copied classes keep a header naming their
origin. Shulker as a whole is distributed under the [GNU GPL v3](LICENSE).

| Source | What Shulker uses | License |
|---|---|---|
| [Paper](https://github.com/PaperMC/Paper) | the server itself | GPL-3.0 / MIT (see Paper's LICENSE.md) |
| [paperweight-examples](https://github.com/PaperMC/paperweight-examples), [Purpur](https://github.com/PurpurMC/Purpur) | build setup; "Dont send useless entity packets" | MIT |
| [Leaf](https://github.com/Winds-Studio/Leaf) | io_uring transport, PoiAccess visited-section tracking, brain collections, villager stream removal, multithreaded entity tracker; carrier for the patches below | MIT, or the license in each patch header (GPL-3.0 / LGPL-3.0) |
| [Petal](https://github.com/Bloom-host/Petal), [Kaiiju](https://github.com/KaiijuMC/Kaiiju) | async pathfinding, multithreaded tracker base | GPL-3.0 |
| [Airplane](https://github.com/TECHNOVE/Airplane-Experimental) | multithreaded tracker origin, "better checking for useless move packets" | GPL-3.0 |
| [Gale](https://github.com/Dreeam-qwq/Gale) | "better checking for useless move packets" | GPL-3.0 |
| [Lithium](https://github.com/CaffeineMC/lithium) | reduced Brain memory lookups | LGPL-3.0 |
| [Canvas](https://github.com/CraftCanvasMC/Canvas) | entity motion packet filter | GPL-3.0 |
| [SparklyPaper](https://github.com/SparklyPower/SparklyPaper) by MrPowerGamerBR | parallel world ticking, per-world MSPT, skipping `distanceToSqr` in ServerEntity | see note |
| [zstd-jni](https://github.com/luben/zstd-jni) | zstd for `.shulk` | BSD-2-Clause |

**SparklyPaper:** the repository publishes no license file. Its patches modify Paper's GPL-3.0
code and SparklyPaper distributes builds of the result, so under GPL-3.0 section 5 those
modifications are conveyed under GPL-3.0; Leaf and DivineMC redistribute them on the same
basis. Shulker credits the author in every ported patch. If the author objects, the parallel
world ticking patch (off by default) will be removed.

The PoiAccess optimisation carries an additional GPL-3.0 section 7(b) term: attribution to
HaHaWTH and MrlingXD must be preserved, which the patch header and
`dev.shulker.server.util.SectionVisitSet` do.
