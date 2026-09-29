# Credits

Shulker is built on [Paper](https://github.com/PaperMC/Paper) and ports optimisations from other
Paper forks. Every ported patch keeps its original author and license note in the patch header
(`shulker-server/minecraft-patches/features/`), and copied classes keep a header naming their
origin. Shulker as a whole is distributed under the [GNU GPL v3](LICENSE).

| Source | What Shulker uses | License |
|---|---|---|
| [Paper](https://github.com/PaperMC/Paper) | the server itself | GPL-3.0 / MIT (see Paper's LICENSE.md) |
| [paperweight-examples](https://github.com/PaperMC/paperweight-examples), [Purpur](https://github.com/PurpurMC/Purpur) | build setup; "Dont send useless entity packets" | MIT |
| [Leaf](https://github.com/Winds-Studio/Leaf) (`ver/26.2`) | its whole patch set (`shulker-server/minecraft-patches/features` 0017-0333, `paper-patches/features`, `shulker-api/paper-patches/features`) and its own classes (`org.dreeam.leaf`, `org.galemc.gale`, `org.purpurmc.purpur`, `gg.pufferfish`, `org.leavesmc`, `net.caffeinemc`, `su.plo.matter`, `me.titaniumtown`, `me.earthme`, `com.ishland`), except Leaf's branding and the parts Shulker has its own version of (async pathfinding, entity tracker, parallel world ticking, io_uring, Linear region format) | MIT, or the license in each patch header (GPL-3.0 / LGPL-3.0 / Apache-2.0 / AGPL-3.0, see below) |
| [Purpur](https://github.com/PurpurMC/Purpur), [Gale](https://github.com/Dreeam-qwq/Gale), [Pufferfish](https://github.com/pufferfish-gg/Pufferfish), [Leaves](https://github.com/LeavesMC/Leaves), [Luminol](https://github.com/LuminolMC/Luminol), [Sakura](https://github.com/Samsuik/Sakura), [Plazma](https://github.com/PlazmaMC/PlazmaBukkit), [Nitori](https://github.com/Gensokyo-Reimagined/Nitori), [Matter](https://github.com/plasmoapp/matter), [Slice](https://github.com/Cryptite/Slice), [EMC](https://github.com/starlis/empirecraft), [carpet-fixes](https://github.com/fxmorin/carpet-fixes), [Mirai](https://github.com/etil2jz/Mirai), [Pluto](https://github.com/Yive/Pluto), [Fish](https://github.com/Biquaternions/Fish) and the other projects named in the patch headers | patches carried by Leaf | as noted in each patch header |
| [TT20](https://github.com/snackbag/TT20) | lag compensation (off by default), carried by Leaf | AGPL-3.0-only |
| [Petal](https://github.com/Bloom-host/Petal), [Kaiiju](https://github.com/KaiijuMC/Kaiiju) | async pathfinding, multithreaded tracker base | GPL-3.0 |
| [Airplane](https://github.com/TECHNOVE/Airplane-Experimental) | multithreaded tracker origin, "better checking for useless move packets" | GPL-3.0 |
| [Gale](https://github.com/Dreeam-qwq/Gale) | "better checking for useless move packets" | GPL-3.0 |
| [Lithium](https://github.com/CaffeineMC/lithium) | reduced Brain memory lookups | LGPL-3.0 |
| [Canvas](https://github.com/CraftCanvasMC/Canvas) | entity motion packet filter | GPL-3.0 |
| [SparklyPaper](https://github.com/SparklyPower/SparklyPaper) by MrPowerGamerBR | parallel world ticking, per-world MSPT, skipping `distanceToSqr` in ServerEntity | see note |
| [DivineMC](https://github.com/BX-Team/DivineMC) by NONPLAYT | parallel sensor phase (`dev.shulker.server.async.sensing`); carrier for the C2ME and Lithium ports below | GPL-3.0 |
| [C2ME](https://github.com/RelativityMC/C2ME-fabric) by ishland | End biome cache, NBT write cache limit | MIT |
| [Lithium](https://github.com/CaffeineMC/lithium) | long jump target list (`dev.shulker.server.util.LongJumpChoiceList`), goat item sensor | LGPL-3.0 |
| [zstd-jni](https://github.com/luben/zstd-jni) | zstd for `.shulk` | BSD-2-Clause |

**SparklyPaper:** the repository publishes no license file. Its patches modify Paper's GPL-3.0
code and SparklyPaper distributes builds of the result, so under GPL-3.0 section 5 those
modifications are conveyed under GPL-3.0; Leaf and DivineMC redistribute them on the same
basis. Shulker credits the author in every ported patch. If the author objects, the parallel
world ticking patch (off by default) will be removed.

**TT20:** the lag compensation patch is AGPL-3.0-only. GPL-3.0 section 13 allows combining it
with Shulker; that part keeps the AGPL's terms, including offering its source to players who
interact with a server running a modified version of it.

The PoiAccess optimisation carries an additional GPL-3.0 section 7(b) term: attribution to
HaHaWTH and MrlingXD must be preserved, which the patch header and
`dev.shulker.server.util.SectionVisitSet` do.
