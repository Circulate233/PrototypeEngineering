# 配方功能与事件迁移核对

2026-11-07，按用户要求重新以具体功能核对，停止优先扩展调度底座。用户再次指出“配方事件”后，独立子代理重查 PM 全仓，详细定义、脚本预设及调用证据见[配方事件复审](pm-recipe-events-audit.md)。验证数量只证明已实现部分，不代表 PM/PN 全部功能迁移完成。

## 预设阶段、回调与实际行为

| 功能 | 当前结论 | 核对依据 |
|---|---|---|
| PM 每类需求的开始、每刻、结束 | 已有真实执行 | 旧 `RecipeRequirementSystem.start/onEnd` 与 `Tickable.acquireTickTransaction`，由 `FactoryRecipeProcessorSystem.executeStage` 调用。新 `RecipeRequirement.acquire` 分派 `start/tick/end`，`RecipeStageExecutor.Acquisition.acquire` 实际逐需求调用，`RecipeProcess.TickSession` 推进 START/TICK/END。 |
| PM 机器及进程组件 Pre/Normal/Post | 已有真实执行 | 旧 `MachineSystem` 默认转机器 tick 事件，`RecipeProcessSystem` 继承通用 ComponentSystem。新 `MachineSystem` 保留事件适配，`ComponentMap` 和 `RecipeProcess` 实际执行组件回调。不是只有接口。 |
| PM 物品/流体需求阶段 | 已迁入 AEKey 通用资源实现 | `ResourceRequirementSystem.start/tick/end` 分别处理初始输入、每刻输入/输出及结束输出；概率、动态/模糊输入、随机输出和数量倍率有具体执行逻辑。 |
| PM FE 需求阶段 | 已有独立实现 | `EnergyRequirementSystem.start/tick/end` 使用独立 FE 存储与预留，不造 FE 资源类型。 |
| PM 属性、检查点、选择器 | 已有具体系统 | `AttributeRequirementSystem`、`CheckpointRequirementSystem`、`SelectiveRequirementSystem` 保留各自适用阶段、嵌套需求、状态与效果清理。不能把三者都描述成简单固定 IO。 |
| PM 并行数量需求 | 实际效果已迁；旧空类不复制 | 旧 ParallelismRequirementComponent/System 是空壳。实际效果来自扫描时选择 PROCESS_PARALLELISM 和资源/FE 数量缩放，新 scanner/RecipeParallelism/需求系统已有对应逻辑。 |
| PM 加载、成型变化、卸载、破坏回调 | 已有具体生命周期入口 | `MachineLifecycleListener` 四方法由 MachineLifecycle 发布，普通控制器接线已存在；这不是“研究完成”事件。 |
| PN 给每个配方追加研究/计算条件的扩展回调 | 未迁移 | 旧 `NetworkRecipeExtensionListener.extend`、有序 registry.publish/context.freeze/index 及 createWorkGate 是具体扩展链。新项目只有通用配方注册和网络共同 gate/账本，尚无对应的逐配方研究门槛及资源消费者。 |
| PN 研究启动条件、队列、阶段推进及完成 | 定义/资格/完成传播已有执行；队列与研究站未接 | ResearchRegistry 已接注册事件，ResearchEligibility 真执行 startCondition，TeamResearchCompletion 按双来源创建等级/奖励资格；队列、手动研究、研究站推进和服务器存档链仍缺。不能将这些可调用步骤视为完整研究流程。 |
| PN 研究奖励及事件奖励 | Java 领取/恢复核心已有执行；服务器入口未接 | ResearchRewardClaimer 在实际领取时调用 RewardEventAction.deliver，保留选择、片段和checkpoint；Minecraft物品adapter可调用。领取 packet/UI、权威存档服务及完整物品交互冻结尚未接入，默认未知交付不盲目重发。 |
| PN 研究物品下载事件 | 未迁移 | 旧 ResearchItemDownloadEvent.createOutput 按材料注册，由 ResearchItemDownloadEvaluation 调用，再进入权威下载事务；当前没有对应 download 模块。 |
| PN 计算组件的实际物品供给 | 仅快照/规划/账本底座 | 旧 DeviceRuntimeRegistry→ComponentInventory→ContributionCalculator 有真实 ItemStack 组件注册、读取和供给计算；当前 resourcesPerTick 数据快照与账本不能代替这条组件执行链。 |
| CraftTweaker 接口及脚本数据适配 | 按用户要求最后处理 | PM ZenRecipeBuilder 通过 addRequirement/register 声明配方，Zen 参数转换、工厂和 ZSData 脚本数据组件尚未迁移；未发现该 builder 额外提供一套每配方 onStart/onFinish 事件 DSL。Java 功能完整之后再实现 CT，不能以 CT 延后为理由延后其底层 Java 功能。 |

上表的“已迁”指源码中的真实执行职责与已有逻辑测试，不代表游戏中 GUI、世界交互及最终所有兼容链均已验收。旧需求系统里的方法名是阶段钩子，不应混同 Forge 配方注册事件；PN 的 PostInit 配方扩展监听器也不应混同每 tick 事件。

旧 PM 没有独立于 requirements 的顶层 condition/events 数组，但有每配方选择提交回调、动态匹配条件、检查点/属性效果、进程组件钩子，并且已有选择修改器和动态匹配器的 CT 回调注册。此前“不存在运行时配方回调/DSL”的概括错误；具体预设与新项目映射见复审表。Java 对应调用已存在，CT 桥尚缺。已修正的旧行为包括 checkpoint time=0 原先永不触发、Selective 被拒候选原先可能 commit，以及选择状态/属性恢复问题，不能把保留旧 bug 作为功能对齐标准。

## 后续工作顺序

1. 优先迁移 PN 的实际配方研究/计算条件和研究业务：先核对旧定义、状态及执行入口，直接复用可用逻辑，改写 Kotlin 与不必要的桥接层。新接口放在本项目真实职责包内，不照搬 api.pm 或 XxxImpl 镜像。
2. 研究启动、队列/阶段推进、完成、奖励与下载事件逐项接到真实注册、运行和持久化入口。状态说明按“定义、执行、存档、外部扩展”分别给出，不以仅有模型或预算底座冒充完整功能。
3. 在具体功能需要的地方使用已有事务和调度能力；机器 planned 接线、纯 prepare 与 PN 权限仍必须完成，但不继续脱离功能主线反复细化底座。
4. 继续已确认的结构投影/复杂渲染/每池默认与自定义 JEI 等功能，CraftTweaker 最后。保持纯库定位，不迁入旧开发示例的正式机器、配方或玩法数值。
