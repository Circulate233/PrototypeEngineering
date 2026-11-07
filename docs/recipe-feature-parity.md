# 配方功能与事件迁移核对

2026-11-07，按用户要求重新以具体功能核对，停止优先扩展调度底座。当前源码检查点为 `f1ada8d`；593 项测试和 337 文件 MCP 零诊断只证明已实现部分，不代表 PM/PN 全部功能迁移完成。

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
| PN 研究启动条件、队列、阶段推进及完成 | 未迁移业务链 | 旧 ResearchDefinition.startCondition 由 TeamResearchViewCalculator 实际调用，TeamResearchStart、ResearchStationTick/Runtime、研究队列和持久状态有具体行为；当前没有对应 research/queue/station 模块。网络预算或通用 RecipeProcess 不能代替这些业务。 |
| PN 研究奖励及事件奖励 | 未迁移 | 旧 PlayerResearchState 在完成研究后创建未领取资格，实际领取才由 ResearchRewardClaim 调用 RewardEventAction.deliver；EventRewardGroup 组织事件奖励。不是研究完成即自动发奖。当前没有对应执行入口。 |
| PN 研究物品下载事件 | 未迁移 | 旧 ResearchItemDownloadEvent.createOutput 按材料注册，由 ResearchItemDownloadEvaluation 调用，再进入权威下载事务；当前没有对应 download 模块。 |
| PN 计算组件的实际物品供给 | 仅快照/规划/账本底座 | 旧 DeviceRuntimeRegistry→ComponentInventory→ContributionCalculator 有真实 ItemStack 组件注册、读取和供给计算；当前 resourcesPerTick 数据快照与账本不能代替这条组件执行链。 |
| CraftTweaker 接口及脚本数据适配 | 按用户要求最后处理 | PM ZenRecipeBuilder 通过 addRequirement/register 声明配方，Zen 参数转换、工厂和 ZSData 脚本数据组件尚未迁移；未发现该 builder 额外提供一套每配方 onStart/onFinish 事件 DSL。Java 功能完整之后再实现 CT，不能以 CT 延后为理由延后其底层 Java 功能。 |

上表的“已迁”指源码中的真实执行职责与已有逻辑测试，不代表游戏中 GUI、世界交互及最终所有兼容链均已验收。旧需求系统里的方法名是阶段钩子，不应混同 Forge 配方注册事件；PN 的 PostInit 配方扩展监听器也不应混同每 tick 事件。

旧 PM 没有额外的每配方 condition/runtime event 字段，内置进程组件的 system 也没有遗漏的实际 tick 效果；相应扩展通过需求系统和通用进程组件实现。已修正的旧行为包括 checkpoint time=0 原先永不触发、Selective 被拒候选原先可能 commit，以及选择状态/属性恢复问题，不能把保留旧 bug 作为功能对齐标准。

## 后续工作顺序

1. 优先迁移 PN 的实际配方研究/计算条件和研究业务：先核对旧定义、状态及执行入口，直接复用可用逻辑，改写 Kotlin 与不必要的桥接层。新接口放在本项目真实职责包内，不照搬 api.pm 或 XxxImpl 镜像。
2. 研究启动、队列/阶段推进、完成、奖励与下载事件逐项接到真实注册、运行和持久化入口。状态说明按“定义、执行、存档、外部扩展”分别给出，不以仅有模型或预算底座冒充完整功能。
3. 在具体功能需要的地方使用已有事务和调度能力；机器 planned 接线、纯 prepare 与 PN 权限仍必须完成，但不继续脱离功能主线反复细化底座。
4. 继续已确认的结构投影/复杂渲染/每池默认与自定义 JEI 等功能，CraftTweaker 最后。保持纯库定位，不迁入旧开发示例的正式机器、配方或玩法数值。
