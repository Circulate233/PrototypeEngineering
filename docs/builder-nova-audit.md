# 自动构建器与 NovaEngineering-Core 对照

对照日期：2026-11-13。PE 基线提交 `774083e`，Nova 基线提交 `80582307f7c53846c2c1fa37b605c754198ffb0d`；按当前工作树源码审查，PE 构建器有接手前的格式/重排等未提交修改，Nova 本次引用的构建器文件无本地修改。本轮仅核对和记录，不实现下面的差距，不启动 Minecraft、Bootstrap/FML 或运行构建器。

## 结论与缺口

| 能力 | Nova 实际实现 | PE 当前实现与缺口 |
|---|---|---|
| ME 物品/流体取料 | `UsingAE` 通过玩家无线终端获取网络，背包不够时从 ME 提取物品/流体，使用玩家 ActionSource。 | **缺少。** `StructureBuildTasks.payment/fluidPayment` 只读取玩家库存与一层 ItemHandler/流体容器；ME 总成能为机器供料，不等于构建器已接入网络。 |
| 缺料发起 AE 合成 | `AutoAECrafting` 挑选可合成替代项，`AutoCraftingQueue` 排队计算合成计划并打开确认 GUI；用户确认后提交任务、切到下一项。 | **缺少。** 无构建器缺料到 crafting service/合成确认的消费者，也无可合成、不可合成、等待状态展示。Nova 并非无确认地全部自动提交，更不能宣称合成完成后自动恢复原搭建任务。 |
| 开工前完整缺料检查与报告 | `checkAllItems` 在背包副本/ME SIMULATE 上核对物品和流体，发送 `PktAssemblyReport`；`NeedAllIngredient` 可阻止缺料时开工。 | **缺少。** GUI 两处材料清单按结构数量统计，是完整 BOM，不扣背包库存或已安装块。任务直接建立，每目标临时找付款，失败目标持续重试；没有整批缺料报告/齐料门槛。 |
| 自动选择可用替代材料及保留合法已有块 | 遍历 `BlockInformation` 的匹配状态与实际可用材料；已存在任意合法匹配块返回 ALREADY_EXISTS。 | **部分具备，但有行为缺口。** PE 有全局/单格持久选择；未显式选择时用第一项，不按库存自动找替代项。`operate` 仅 `current.equals(desired)` 跳过，已有另一种合法候选也会被清除并换成当前选择。需区分显式要求更换与未选择时保留合法块。 |
| 障碍处理策略 | 默认拒绝替换不匹配实体方块；`IgnoreFluids` 只允许替换已有液体，需求中的流体仍最后搭建。 | **行为不同，缺可选策略。** PE 先取得所需材料，再沿保护/BreakEvent 清除任何可拆障碍；没有保留实体障碍/允许覆盖液体的玩家选项。不能把 Nova 的 IgnoreFluids 误解为跳过需求流体。 |
| 自动投影推进 | 从最低未完成层开始显示；逐层复核匹配、修复之前已完成层的变化；全部匹配或离锚点32格自动结束，仍可手动层/全量循环。 | **部分具备。** PE 有贴图投影、手动层/全量循环、绑定失效与30秒到期；当前构建器投影 `hideDisplayedMatches=false`、`scanChecksPerTick=0`，不会按搭建进度跳到下一层或完成后自动结束。30秒是用户已有明确要求，应保留，可另加自动推进模式。 |
| 构建速度与批量配置 | `BuildQuantity`、`BuildSpeed` 是服务器配置，当前默认100次/20刻节拍；执行代码未区分 PlayerTick phase，不直接将此默认换算为每秒吞吐。 | **缺少配置能力。** PE 固定 PlayerTick END 每刻8次尝试，已完成条目也消耗尝试预算；无构建器配置类或作者调节入口。 |
| 外部模组结构适配 | `setConstructors(Block,meta→DynamicMachine)`，当前 `RegistryAssembly` 条件注册星辉、Mekanism 反应堆/蒸馏塔、Botania 聚合板/传送门及 PackagedAstral 等结构。 | **缺少直接适配入口和这些兼容。** PE 对方块必须找到 `MachineControllerHost`，不能直接把普通外部方块绑定到库内已有结构并建造；单独结构预览/Java结构注册不等于自动构建入口已接通。可先设计通用 block/state→structure 适配，具体整合内容归 addon。 |
| 多套工具配置档案 | `IConfigurableItem.getProfileCount()` 返回5，沿 DE 工具配置系统保存/切换参数。 | **未提供。** PE 有当前绑定/材料/单格/切片持久状态，但不是5套可切换设置档案。此项是便利功能和外部 GUI 依赖，应与核心缺口分开评估。 |

优先建议：先接通玩家授权的 ME 取料、准确缺料/齐料门槛，再补合成确认队列；随后处理自动候选/保留已有合法块及障碍策略。自动投影、速率配置、外部结构适配和多档案可独立推进，不能通过直接复制旧 appeng API 解决。PE 固定 AE2S `ae2.api.*`/AEKey，库存数量应保持 AEKey2LongMap 与事务/溢出/来源校验。

## 已有能力与不应记作缺口的差异

- 动态切片/子结构展开已经具备，PE 每个 child occurrence 可独立保存次数；Nova DynamicPatternSize 是统一参数按各 pattern 的 min/max 夹取。不能把更细的次数功能记为缺失。
- 物品及1000mB流体搭建、普通块先于流体/重力块、已有准确目标跳过、创造免材料、Forge 放置/破坏事件与退款路径已有。PE 还支持一层嵌套库存、实际流体恢复、拆卸和工具档案、暂停/恢复；Nova 本次 `AssemblyBlockArray` 只有建造，无对应拆卸/暂停工作链。
- 绑定/打开方式不同：Nova 普通右键控制器投影、潜行右键直接搭建；PE 普通右键异控制器绑定、同控制器打开、潜行绑定并打开，GUI 按钮开始建造/拆卸。这是用户已明确指定的行为，不应当成漏迁移并擅自改回。
- 两者都没有可靠的跨退出世界保存完整搭建任务：Nova logout/changeDim清除；PE logout/unload清除运行 Task，仅工具状态/材料选择保留。该项不是相对 Nova 的缺口。
- 本次 Nova `AssemblyBlockArray.placeBlock` 只写 blockstate；虽然 MMCE 另一个 `MachineAssembly` 类有 TileEntity NBT 写入，本工具未调用其该路径。PE 的完整 tileNbt 当前也只用于 pick/payment 展示，放置没有应用到新 TileEntity。不能混用 MMCE 其他构建器代码宣称 Nova 本工具有此功能。
- 创造栏分组、全局 GUI、材质渲染及其余无关 Mixin 不在本次审查范围。

## 额外确认的边界

Nova 在开始前检查整组坐标是否超世界建造高度；PE `StructureBuildPlan.add` 有 int 溢出保护，但任务没有整组世界高度预检，越界 `setBlockState=false` 会回到重试。这是明确的可用性缺口，可随整批预检补齐。

Nova 的 NeedAllIngredient 和缺料报告只有开始时检查，不是多目标预留/事务；其缺料目标用一个 missing 总数跳过，事件取消又经异步放置后回收，不能作为 PE 事务正确性的现成替代。报告中的“已有”均为源码接线确认，不是实机通过声明。

## 阅读与证据

Nova 入口：

- [工具配置与开工](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/common/item/ItemMachineAssemblyTool.java:205)：UsingAE、AutoAECrafting、NeedAllIngredient及5档案。
- [材料预检与报告](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/common/util/NEWMachineAssemblyManager.java:100)。
- [实际候选/ME取料及障碍策略](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/common/util/AssemblyBlockArray.java:237)。
- [合成队列](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/common/util/AutoCraftingQueue.java:74)与[确认提交](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/common/container/ContainerNEWCraftConfirm.java:26)。
- [自动投影层](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/client/util/NEWBlockArrayPreviewRenderHelper.java:99)、[外部结构注册](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/common/registry/RegistryAssembly.java:311)、[服务器节拍配置](F:/clone/NovaEngineering-Core/src/main/java/github/kasuminova/novaeng/NovaEngCoreConfig.java:802)。

PE 入口：

- [构建计划和尝试循环](../src/main/java/com/circulation/prototype_eng/structure/tool/StructureBuildTasks.java:63)、[清障和付款](../src/main/java/com/circulation/prototype_eng/structure/tool/StructureBuildTasks.java:191)、[背包/流体付款](../src/main/java/com/circulation/prototype_eng/structure/tool/StructureBuildTasks.java:261)。
- [绑定要求](../src/main/java/com/circulation/prototype_eng/item/StructureToolItem.java:94)、[全量BOM](../src/main/java/com/circulation/prototype_eng/client/preview/StructurePreviewPanel.java:435)、[投影选项](../src/main/java/com/circulation/prototype_eng/client/preview/StructurePreviewPanel.java:304)、[30秒和手动层循环](../src/main/java/com/circulation/prototype_eng/client/preview/ProjectionInteractions.java:74)。

接手前文档、逐文件SHA256及源码快照：`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261113/builder-nova-audit/`。本轮未改Java、资源或Nova工作树，未开子代理/启动游戏/推送；文档审查无需编译与Java IDEA检查，不沿用历史测试证明这些尚缺功能。
