# PE 可视开发示例

完整测试套件入口现为 [../README.md](../README.md)，包括9份实际CT脚本及18Java addon；本文保留原13台/106配方的可视操作。原生数据/仓室与网络/研究/渲染新增场景分别见 [NATIVE-CASES.md](NATIVE-CASES.md) 和 [NETWORK-RENDER-TESTS.md](NETWORK-RENDER-TESTS.md)。新增脚本机器应由用户使用 `run/start-tests.ps1` 完整启动，以加载真实CT2运行依赖；下文旧启动方式仅供原addon历史说明。

本目录提供独立的 `pe_visual_examples` 开发附加模组，现有 **13 台机器、106 个配方、12 个默认 JEI 配方池和 1 个独立自定义 JEI 页**。原 3 台机器、8 条配方全部保留，另加 10 类边界场景。机器使用当前 PE 的真实控制器、可放置方块结构、配方与仓室接口；源码和开发 Jar 均留在 `run` 内，PE 正式库没有加入这些玩法内容。

## 在当前开发工作区加载

已安装目标为 `run/mods/pe_visual_examples-1.0.0-dev.jar`。这是针对当前 Java 25、Cleanroom 1.12.2 与命名开发类路径编译的开发 Jar，供此工作区的 `runClient` 开发运行使用。不要额外把 PE Jar 或其依赖复制进 `run/mods`：此工作区的原开发启动已加载 PE 主源码以及 AE2S、JEI 等依赖，重复放入 PE Jar 会造成同 modid 重复。

示例编译使用当前主工程导出的真实命名开发类路径，其中 PE 来自 `build/classes/java/main`；并非另行打包一个 PE 副本。最近一次示例产物路径、SHA-256、是否成功安装、类路径及全部 Java 文件指纹见本目录 `build-result.json`。主项目代码变更后先执行 `run/prepare-tests.ps1` 更新主输出及附加模组。

用户自行在项目根目录启动既有的开发客户端：

```powershell
.\gradlew.bat runClient
```

本次制作没有执行该命令，也没有启动 Minecraft、服务端、FML 或 Bootstrap。已有 `run/world` 与原配置均未修改。建议在客户端中新建一个创造模式测试世界，允许作弊；不要为了看示例替换旧世界。

重新编译本附加模组可运行本目录的 `build.ps1`。脚本使用 Java25的javac/jar，默认类路径为本目录 `runtime-classpath.txt`，缺失或显式 `-RefreshClasspath` 时通过run内init脚本导出当前主输出，不改正式Gradle文件、不启动游戏。可用 `-ClasspathFile` 指定其它实际导出。产物保留在 `build/compile-<唯一标识>/`；目标被客户端占用时保留原文件并报告安装失败。只编译可用 `-SkipInstall`。

```powershell
.\run\visual-examples\build.ps1
```

已运行的客户端不会热加载新机器或配方；安装成功后仍需用户自行退出并重启开发客户端。

## 先获取材料

进世界后执行：

```text
/pe_examples kit
```

该命令需权限 2，发放三种控制器、资源输入/输出/混合仓、FE 输入仓、铁块及原版材料。它只发物品，不自动摆放方块。背包不够时余下物品掉在玩家身边。单独获取也可使用：

```text
/give @p pe_visual_examples:press_controller
/give @p pe_visual_examples:mixer_controller
/give @p pe_visual_examples:sorter_controller
/give @p prototype_eng:input_hatch_4
/give @p prototype_eng:output_hatch_4
/give @p prototype_eng:mixed_hatch_4
/give @p prototype_eng:energy_input_hatch_4
```

当前“材料仓”同一公开后端支持物品、流体以及其他 AEKey 类型；没有另造不存在的 `fluid_hatch` 方块 ID。用水桶与物品查看同一个材料仓，就能观察真实的物品/流体布局。混合材料仓的输入与输出为两份独立存储。

## 原有三台机器：每台只需 4 或 5 个真实方块

先让控制器正面朝北、顶部朝上。以控制器位置 `(x,y,z)` 为基准：

| 方块 | 位置 |
|---|---|
| 控制器 | `(x,y,z)` |
| 资源输入仓 | `(x-1,y,z)`，西侧 |
| 资源输出仓 | `(x+1,y,z)`，东侧 |
| 原版铁块 | `(x,y+1,z)`，控制器正上方 |
| FE 输入仓，仅混合机和分选机需要 | `(x,y,z+1)`，控制器后面的南侧 |

朝北时的俯视图：

```text
          北 / 控制器正面
       输入仓  控制器  输出仓
                 FE仓
          南 / 控制器背面
```

铁块在控制器上方，未画在俯视图中。压机不需要 FE 仓。机器之间隔开至少一格，不共用仓室，以便观察各自配方。结构随控制器完整朝向旋转；初次检验按上述朝北布局搭建，可用 F3 确认方向。

任何等级、输入/输出/混合材料仓都能满足对应的材料仓结构位；实际能否输入/输出由仓室方向决定。初次用 kit 中的四阶输入/输出仓，各有 9 槽，足够展示多物品与流体。四阶混合仓每侧默认 4 槽；要看混合界面，可用 `mixed_hatch_4` 替换一侧材料仓。更高阶仓可从 JEI 取出，观察真实滚动和容量；不需要修改 `hatches.cfg`。

右键控制器打开原 PE 控制器 GUI，查看成型状态和运行进度。右键仓室打开原仓室 GUI。若机器未成型，先确认铁块、两侧仓室与 FE 仓位置及控制器朝向；同一结构中的仓室不要放成普通箱子。

## 查看 JEI

1. 在 JEI 搜索 `@pe_visual_examples` 或中文“示例”。三种控制器作为各自池的原生催化剂；也可对沙子、黏土球、燧石等输出按 `R` 查看配方。
2. “开发示例：简单物品配方”使用默认 PE 页，显示物品模块、真实数量和耗时。
3. “开发示例：流体与 FE 配方”使用默认 PE 页，显示水/熔岩槽、mB 数量与 FE 每刻信息。
4. “开发示例：独立 JEI 页面”直接实现公开 `RecipePoolJeiPage`，不继承默认页。查看原生循环进度、物品模块、25% 输出叠字、FE 图标和耗时；悬停随机候选可看权重与“选一个”语义，悬停信息区看完整长文字。

JEI 中的动画只用于配方展示，不代表机器正在运行或仓内电量。实际库存与电量到仓室 GUI 查看。

## 实际运行与可观察配方

资源输入 GUI：放入物品；手持水桶/熔岩桶，右键输入资源槽倒入。需要装出流体时手持空桶，左键流体资源槽；Shift 请求整个容器操作。输出区只允许取出。混合区两边是独立余额，不会自动把其输入内容当作输出显示。

混合机、分选机需 FE。可接真实 FE 供能方块，也可对刚放的 FE 输入仓执行显式开发充电命令：

```text
/pe_examples charge <仓室x> <仓室y> <仓室z>
```

默认通过该仓的真实 Forge FE 端口充入最多 100,000 FE，尊重每次传输与容量限制，回复实际加入量。可追加一个 `1–1,000,000` 的 FE 数量；不会直接修改 NBT。坐标是 FE 仓而非控制器，目标须处于已加载区块且允许接收 FE。

| 机器 | 配方 ID（统一前缀 `pe_visual_examples:`） | 原始输入 → 输出 | 耗时 / FE |
|---|---|---|---|
| 压机 | `crush_stone` | 圆石×2 → 沙子×1 | 2 秒，无 FE |
| 压机 | `cut_iron` | 铁锭×1 → 铁粒×9 | 3 秒，无 FE |
| 压机 | `saw_log` | 橡木原木×1 → 橡木木板×4 | 1 秒，无 FE |
| 混合机 | `wet_clay` | 沙子×1 + 水 250 mB → 黏土球×1 | 3 秒，16 FE/t |
| 混合机 | `lava_stone` | 圆石×1 + 熔岩 250 mB → 黑曜石×1 | 5 秒，32 FE/t |
| 混合机 | `water_transfer` | 水 1000 mB → 水 1000 mB | 2 秒，8 FE/t |
| 分选机 | `gravel_sort` | 沙砾×1 → 燧石/铁粒/金粒中选 1，权重 80/15/5 | 4 秒，16 FE/t |
| 分选机 | `lucky_wash` | 燧石×1 + 水 1000 mB → 25% 产出钻石×1 | 5 秒，16 FE/t |

原有三台机器默认整机并行数为 1，以便一次投料观察完整 START/运行/END。随机与概率结果按真实进程种子决定，单次没出钻石是合法结果。示例配方刻意使用容易取得的原版资源，仅供开发观察，不代表正式平衡。除水转移演示外，输入材料不要一次把几个不同配方的料混在一起，便于判断当前正在处理哪一条。

## 边界场景入口

```text
/pe_examples kit boundary
```

该材料包发放 10 种边界控制器、十阶材料输入/输出/混合仓、四阶能源输入/输出/混合仓，以及足够搭建大结构的铁块和玻璃。控制器物品 ID 为下表机器名加 `_controller`，命名空间统一为 `pe_visual_examples`。对控制器按 `U` 查看对应 JEI 分类；搜索“边界”或 `@pe_visual_examples` 也能找到它们。每个池有自己的控制器催化剂，所有配方都属于真实机器池。

| 机器名 | 配方数 | 配方入口及观察重点 |
|---|---:|---|
| `boundary_catalog` | 64 | `catalog_01`–`catalog_64`：同池大量 JEI 配方、分类切换、16 种染色物品及 1–4 数量。选择卡使 64 条配方都可单独投料运行。 |
| `boundary_wide` | 3 | `wide_16`、`wide_17`、`wide_33`：输入/输出恰好各 16、17、33 槽。默认 JEI 页分别为 1、2、3 页，最后一页保留尾槽；从任一页导入应包含整条配方。 |
| `boundary_choices` | 4 | `choices_wood`：六种木板同槽候选；`choices_same_key`：铁锭 1／8／64 同 key 异数量；`choices_mixed`：铁锭／水／熔岩同槽；`choices_weighted_two`：六种候选中加权选两个输出。 |
| `boundary_amounts` | 9 | `amount_1`、`amount_63`、`amount_64`、`amount_65`、`amount_4096`、`amount_65536`、`amount_2147483648`、`amount_9000000000`、`amount_fluid_long`：堆叠临界、跨槽、long、非物品包装与容量不足。 |
| `boundary_tick` | 3 | `tick_items`、`tick_fluid`、`tick_mixed_same_key`：START／每刻／END 同 key 数量及样板导入总量。 |
| `boundary_energy` | 3 | `energy_consume`、`energy_generate`、`energy_recycle`：FE 输入消耗、输出生成、同配方双向传输；有独立的真实能源输入/输出结构位。 |
| `boundary_parallel` | 4 | `parallel_1`–`parallel_4`：四个有分组限制的固定工位，分别运行 100／180／260／340 tick；共享并行余额 4，每进程数量 1。 |
| `boundary_cross` | 5 | `cross_1`–`cross_4`：四条不同配方同时运行；`cross_repeat_limit2`：20 秒配方最多同时 2 线程，即使整机上限为 4。 |
| `boundary_structure` | 1 | `structure_glass`：7×5×7 铁框玻璃壳，共 170 个真实方块；旋转、缩放、分层预览和实际成型。 |
| `boundary_long_text` | 2 | `long_text_bilingual`：长中英文控制器名、分类名和物品 tooltip，输出 65；`long_text_large_duration`：72,000 tick（1 小时）耗时显示，不要求等其跑完。 |

物品仍走原生 ingredient；水和熔岩走 GenericStack 包装。示例没有伪造新的 AEKey 类型。概率/候选不会附加禁止导入逻辑；候选优先级由当前 AE2S 选择。原独立分选机页也改为同槽候选轮播，页脚长文字可悬停查看。

## 一条命令搭建真实结构

```text
/pe_examples build boundary_wide 0 80 0
```

这条权限 2 命令仅在玩家手动执行时摆放方块，不会在加载世界时自动建机器。位置是控制器坐标，固定正面朝北、顶部朝上。它和运行时结构声明共用同一份方块表，放入真实控制器、材料仓、能源仓和原版结构方块。所有待放位置先检查为已加载、可修改、世界范围内的空气；任一点被占用会整次拒绝，原有建筑不被替换。世界拒绝中途放置时报告已完成数量，不假装成功。

`build` 后按 Tab 可补全 13 台机器，包括原有 `press`、`mixer`、`sorter`。选空旷区域搭建；大壳的范围是控制器两侧各 3 格、上方 4 格、后方 6 格。命令不预充能源或投料，便于先观察“已成型但缺料/缺电”。

除 `boundary_structure` 和 `boundary_energy` 外，所有边界机器均为 5 方块，坐标相对控制器如下：

| 方块 | 相对坐标 |
|---|---|
| 对应控制器 | `(0,0,0)` |
| `prototype_eng:input_hatch_10` | `(-1,0,0)` |
| `prototype_eng:output_hatch_10` | `(1,0,0)` |
| 原版铁块 | `(0,1,0)` |
| `prototype_eng:energy_input_hatch_4` | `(0,0,1)` |

`boundary_energy` 在上述 5 方块后面再加 `prototype_eng:energy_output_hatch_4`，相对坐标 `(0,0,2)`，共 6 方块。材料/能源结构位接受同类别的各级输入、输出或混合仓，运行时按实际方向提供资源；错误地把所有能源仓都换成输入仓会导致生成配方等待输出能力。

右键控制器确认真实“成型”状态，移走上方铁块观察失配，补回后等待结构重新检查。若未成型，先核对坐标与朝向；预览轮廓本身不会在世界放置方块。

## 手动投料、逐刻与分页

```text
/pe_examples stock <材料输入仓x> <y> <z> <配方名> [运行次数1-16] [候选编号1-64]
```

命令通过真实 `ResourceStorage.insert` 投入该示例的固定 START 输入和 `每刻数量 × durationTicks`，同 key 数量以 `AEKey2LongMap` 累加并先做溢出检查。默认运行 1 次、候选 1；输入配方前缀后按 Tab 补全。它会生成用于隔离配方的命名纸张选择卡，无需手工改 NBT。不会清空原有内容、注入输出、改变仓容量或直接改 NBT；槽不足或容量不足逐项报告真实加入数量。部分成功的资源保留在仓中，重试会继续增加，观察时最好使用空仓。

例如上面的 33 槽机器：

```text
/pe_examples charge 0 80 1 100000
/pe_examples stock -1 80 0 wide_33
```

等待 120 tick，输出仓应有 33 种产物；查看十阶材料仓的滚动及混合仓两侧独立显示。在 JEI 中翻到第 2／3 页后再尝试样板导入，核对总输入/输出是否完整。原版样板终端能否容纳所需槽数仍取决于 AE2S 终端能力，示例不绕过它。

候选编号只决定辅助命令放哪种材料，不修改配方的运行选择规则。`choices_wood 1 6` 放第六种木板，`choices_mixed 1 2` 放水；`choices_same_key 1 3` 放 64 铁锭，但运行时仍按“首个可满足分支”选择 1 铁锭，余额 63 是预期结果。JEI 轮播时各候选应保留 1／8／64 的自身数量。

| 逐刻配方 | 一次完整输入（另有选择卡×1） | 一次完整输出 |
|---|---|---|
| `tick_items`，40 tick | 煤×40 | 铁粒×80 + 钻石×1 |
| `tick_fluid`，80 tick | 水 2000 mB | 水 800 mB + 黏土球×1 |
| `tick_mixed_same_key`，60 tick | 红石 `2+1×60=62`；水 `100+5×60=400 mB` | 水 `3×60+20=200 mB` + 铁锭×1 |

以上三条每刻均额外消耗 4 FE。可用 `stock` 一次备齐，也可以只手动放 START 材料、等运行中缺料后继续补充，观察真实暂停与恢复。样板导入应得到表中的完整时长资源总量。

十阶材料仓默认每槽物品 16,384、流体 8,192,000 mB，单向 81 槽、混合每侧 40 槽。`amount_65536` 可观察同物品跨槽；十亿级配方超过默认总容量，专用于 JEI/样板中的 long 数量与真实容量不足反馈，默认仓不能完成它们。不要为此无限复制仓室或改配置。

## FE 输入、输出与混合仓的实际运行

在空地执行：

```text
/pe_examples build boundary_energy 10 80 10
/pe_examples charge 10 80 11 10000
/pe_examples stock 9 80 10 energy_consume
```

材料输入仓在 `(9,80,10)`，输出仓在 `(11,80,10)`；FE 输入仓在 `(10,80,11)`，FE 输出仓在 `(10,80,12)`。每个配方还消耗煤×1、选择卡×1并产出红石×1，便于确认完成次数。

| 配方 | 时间 | FE 输入总计 | FE 输出总计 |
|---|---:|---:|---:|
| `energy_consume` | 80 tick | `512 + 32×80 = 3072` | 0 |
| `energy_generate` | 80 tick | 0 | `64×80 + 1024 = 6144` |
| `energy_recycle` | 40 tick | `256 + 16×40 = 896` | `8×40 + 128 = 448` |

分别投一条配方，开始前后读取两仓 GUI 的实际 FE。生成配方无需充电，能检验未充电时仍可真实输出。两仓都满/空时可观察等待输出空间/输入能源，配方不会因 JEI 动画自行获得能源。

```text
/pe_examples drain 10 80 12 100000
```

`charge`／`drain` 都通过真实 Forge FE 端口，默认最多 100,000 FE，可追加 `1–1,000,000`，最多 4096 次传输并尊重仓室每次上限。`drain` 会丢弃实际抽出的 FE，适合开发清空输出仓；输出仓拒绝 `charge`，输入仓拒绝 `drain`，混合能源仓两者都可用。

配方停止后，将其中一个能源仓替换成 `prototype_eng:energy_mixed_hatch_4`，待结构重新成型，再运行同一配方并比较库存变化。能源交互仓现在有两份独立能源库存：charge 只充输入侧，配方耗能只从输入侧扣；配方产能进入输出侧，drain 只抽输出侧。两根电池及两行数量分别显示当前量/容量。各仓 GUI 的传输上限设置、较大数值输入和方向行为都可以在这些实际端口上观察。

## 四工位与跨配方

```text
/pe_examples build boundary_parallel 20 80 10
/pe_examples charge 20 80 11 100000
/pe_examples stock 19 80 10 parallel_1
/pe_examples stock 19 80 10 parallel_2
/pe_examples stock 19 80 10 parallel_3
/pe_examples stock 19 80 10 parallel_4
```

四种输入是煤、铁锭、金锭、红石，各×1；四个固定工位分别接受自己的配方组，每进程数量固定 1，完成时间错开。控制器 GUI 应能列出四条工作记录。

`boundary_cross` 使用同样布局，把配方名改成 `cross_1`–`cross_4`，观察动态跨配方工作列表。单独 `stock ... cross_repeat_limit2 4` 会加入钻石×4，每次产出绿宝石×1；整机有 4 并行余额，但这条配方最多同时运行 2 线程，剩余材料等待后续轮次。不要把“整机并行数量”和“这条配方的线程上限”当成同一个数。

## 7×5×7 实体结构与预览

```text
/pe_examples build boundary_structure 30 80 10
/pe_examples charge 30 80 11 100000
/pe_examples stock 29 80 10 structure_glass
```

170 方块组成：控制器×1、十阶材料输入仓×1、十阶材料输出仓×1、四阶 FE 输入仓×1、铁块×57、原版玻璃×109。壳体边界 `x=-3..3、y=0..4、z=0..6`，控制器位于前侧下沿中央；每条棱用铁块，面内用玻璃，仓室覆盖指定壳体位置。内部没有额外方块要求。

下图均为从上向下看单层，左为西，首行为北；`I` 铁块、`G` 玻璃、`A` 材料输入、`B` 材料输出、`E` FE 输入、`C` 控制器、`.` 内部：

```text
y=0 底层        y=1、2、3 中间层   y=4 顶层
IIACBII         IGGGGGI            IIIIIII
IGGEGGI         G.....G            IGGGGGI
IGGGGGI         G.....G            IGGGGGI
IGGGGGI         G.....G            IGGGGGI
IGGGGGI         G.....G            IGGGGGI
IGGGGGI         G.....G            IGGGGGI
IIIIIII         IGGGGGI            IIIIIII
```

手搭时按层图和坐标放置即可。配方为沙子×4 → 玻璃×4，200 tick、4 FE/t。可拆掉一块玻璃确认失配、补回确认重新成型，再观察 24 朝向结构预览的原生方块纹理、玻璃与实体遮挡、分层和缩放。旋转控制器后，实际壳体也必须符合旋转后的布局；单独旋转控制器不会搬动世界方块。

## 结构 JEI 样板导入的观察步骤

`boundary_structure` 的玻璃位现在接受原版玻璃或染色玻璃，默认仍是原版玻璃；`build` 命令仍放置上述同一份 170 方块结构。资源仓和能源仓保留其原有等级与方向候选。结构页标题使用机器声明的本地化名称，导入输出仍为该机器的原生控制器×1，其名称在当前本地化机器名后添加后缀：中文为 `{原名}-结构`，英语为 `{original name}-Structure`。

1. 打开支持加工样板导入的 AE 样板终端，在 JEI 搜索 `@pe_visual_examples`，对 `boundary_structure_controller` 按 `R`，进入其结构预览页。
2. 在未作替换的结构页执行样板导入。完整输入应为铁块×57、原版玻璃×109、资源仓候选×2、能源仓候选×1，即 `57+109+2+1=169`；输出为上述带结构后缀的控制器×1。仓室的具体物品由当前候选选择决定，不能把 `build` 命令使用的十阶输入／输出仓误认为声明中唯一允许的仓室。
3. 切到层预览，分别显示底层 `y=0`、中间层 `y=2` 和顶层 `y=4`，再次执行导入。每次仍应包含全结构的 169 个材料，而非当前显示层的 48、24 或 49 个非控制器方块；控制器输出仍为1。层预览只过滤可见高度，不改变结构材料总数，也不是重复切片数量控件。本示例使用固定 `TemplateStructure`，没有可调整次数的 `SliceStructure`。
4. 在 JEI 结构预览中直接点击一个玻璃方块，在左侧候选中选染色玻璃，再导入。当前 JEI 候选入口会替换整种玻璃要求，玻璃材料应变为染色玻璃×109，其余材料不变，输入总量仍为169。
5. 单格与整种替换通过机械构造器对照观察：绑定这台控制器并打开构造器，直接点击预览中的一个玻璃方块，再选染色玻璃，应只有该格变化，完整材料网格显示原版玻璃×108＋染色玻璃×1；双击右侧玻璃材料项，再选染色玻璃，则将该种材料的109个位置全部替换。右侧单击仍只显示线框。构造器的选择保存在其工具中，与 JEI 预览设置独立；结构 JEI 导入读取当前 JEI 预览的选择，不读取构造器工具 NBT，因此构造器的单格或整种替换不会自动改变 JEI 导入结果。

以上是重新编译安装附加模组并由用户重启客户端后的实机观察步骤；源码检查和编译结果不能替代终端导入、候选物品、名称与片层的实际观察。

## 源码与验证边界

- `VisualExamples.java`：真实机器/配方/运行时事件注册，公开默认控制器和独立结构/工位保存绑定。
- `BoundaryExamples.java`：10 类边界机器、98 条新增配方、结构代表方块与显式投料数量计算。
- `ExamplesClient.java`：客户端独立 JEI 页与 native wrapper；公共类不加载 JEI/Minecraft 客户端类型。
- `ExampleText.java`：AE2S `LocalizationEnum`；中英文本在本 addon 自己的资源命名空间。
- `ExamplesCommand.java`：用户手动触发的材料包、空位预检结构搭建、真实材料投料和 Forge FE 充电/抽取辅助。

2026-11-09 本次边界扩展实际 Java 25 `javac` 与 `jar` 成功，已安装到 `run/mods`；五源文件及产物指纹见 `build-result.json`，真实构建日志、写前备份与最终核对见 `build/staging/run-boundary-examples/`。编译脚本显式设置空 sourcepath，隔离依赖 Jar 内嵌源码。额外用一个本任务持有的只读文件句柄模拟目标 Jar 占用，确认构建仍保留新 Jar、安装报告 false、原目标 SHA-256 不变；这不是客户端占用证据。释放该句柄后正常安装。

五个 Java 文件都逐个调用了本机 IDEA MCP `get_file_problems(errorsOnly:false)`，IDE 全部返回“文件不在项目内容根或位于排除目录”。原始结果见 `build/staging/run-boundary-examples/idea-file-problems.json`；错误、警告与弱警告数量均不可得，不能记作全零。本次没有写入 `src/test` 临时副本、修改 IDE 设置或正式构建文件。首次 3 机器版本的构建历史仍保留在 `build/staging/run-visual-examples/compile-first`。

用户于 2026-11-09 16:57 手动启动开发客户端。原始崩溃记录 `run/crash-reports/crash-2026-11-09_16.57.28-client.txt` 显示 `pe_visual_examples` 为 `LC`（已发现并构造），CTM preInit 的 Lumenized `ObfMapping.MCPRemapper.getConfFiles` 映射路径错误阻断了后续初始化。此记录没有验证示例机器/配方注册、JEI 页面或仓室 GUI；准确边界与日志指纹保存在 `build/staging/run-visual-examples/user-startup-boundary.json`。静态检查和编译不等于实机视觉或操作验收，修复开发启动接线后仍需用户自行启动观察。
