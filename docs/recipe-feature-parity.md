# 配方功能与事件迁移核对

2026-11-07，按用户要求重新以具体功能核对，停止优先扩展调度底座。用户再次指出“配方事件”后，独立子代理重查 PM 全仓，详细定义、脚本预设及调用证据见[配方事件复审](pm-recipe-events-audit.md)。验证数量只证明已实现部分，不代表 PM/PN 全部功能迁移完成。

## 预设阶段、回调与实际行为

| 功能 | 当前结论 | 核对依据 |
|---|---|---|
| PM 每类需求的开始、每刻、结束 | 已有真实执行 | 旧 `RecipeRequirementSystem.start/onEnd` 与 `Tickable.acquireTickTransaction`，由 `FactoryRecipeProcessorSystem.executeStage` 调用。新 `RecipeRequirement.acquire` 分派 `start/tick/end`，`RecipeStageExecutor.Acquisition.acquire` 实际逐需求调用，`RecipeProcess.TickSession` 推进 START/TICK/END。 |
| PM 配方进程组件 Pre/Normal/Post | 扩展钩子已有真实派发 | 旧 `RecipeProcessSystem` 继承通用 ComponentSystem，由 `FactoryRecipeProcessorSystem` 对当前进程派发；内置进程 system 的回调为空。新 `RecipeProcess.beginTick` 和会话结束实际调用 `ComponentMap`；外部通过 `RecipeProcessFactory` 给进程安装自己的组件。不能将机器 tick 或空内置回调计成额外的配方预设效果。 |
| PM 物品/流体需求阶段 | 已迁入 AEKey 通用资源实现 | `ResourceRequirementSystem.start/tick/end` 分别处理初始输入、每刻输入/输出及结束输出；概率、动态/模糊输入、随机输出和数量倍率有具体执行逻辑。 |
| PM FE 需求阶段 | 已有独立实现 | `EnergyRequirementSystem.start/tick/end` 使用独立 FE 存储与预留，不造 FE 资源类型。 |
| PM 属性效果与定时检查点 | 分段执行与候选局部回退已接 | `AttributeRequirementSystem` 在配置阶段安装/撤销属性；`CheckpointRequirementSystem` 跨过目标时间后执行子需求的 START/TICK/END，保留进度、嵌套状态与效果清理；子阶段现逐段获取/source apply/expose/可逆 stage，外层最后共同结算，START 修改器对后继获取可见；selector 的候选本身是 checkpoint 时，Blocked/Failure 已按原保存点局部回退并确认后改选，嵌套效果及其他工位前缀有集成验证，见[检查点审计](checkpoint-execution-audit.md)。 |
| PM 选择成功后的配方回调 | Java 回调已接；CT 桥及速度便捷预设未接 | 旧 `SelectiveModifier.apply(ctx)` 在选中子需求 START 提交后调用，CT 提供自定义 lambda 与 `registerProcessSpeedMultiplier`。新 `SelectiveRequirementSystem` 在提交时实际调用 `SelectiveModifierDescriptor.modifier().apply(context)`，可用公开上下文添加属性或 overlay；结束/回滚清理所登记效果。 |
| PM 动态输入匹配条件 | Java 条件及 EXACT/ITEM_ONLY 预设已接；CT 桥未接 | 旧 ItemRequirementSystem 调用注册的 matcher；新 `DynamicInputCandidates` 调用 `group.matcher().matches` 并由 ResourceInputResolver 锁定候选。这是匹配条件，不是配方完成事件。 |
| PM 显式 overlay 回调 | Java 显式入口已接 | 旧 `RecipeOverlayModifierPipeline.apply` 按 ID 调用修改器，但旧普通执行链未自动调用；新 `RecipeOverlayPipeline.apply` 可显式执行并撤销。不能将此入口算作自动触发的 START 事件。 |
| PM 并行数量需求 | 实际效果已迁；旧空类不复制 | 旧 ParallelismRequirementComponent/System 是空壳。实际效果来自扫描时选择 PROCESS_PARALLELISM 和资源/FE 数量缩放，新 scanner/RecipeParallelism/需求系统已有对应逻辑。 |
| PN 给每个配方追加研究/计算条件的扩展回调 | Java 注册与真实阶段消费者已接入 | NetworkRecipeExtensions 按目录/优先级调用回调并一次冻结。NetworkRecipeWork 合并机器默认与配方需求，实际 START/TICK/END 检查选中物理数据中心、按并行量占用 numeric、按机器 UUID 共享 THREAD，并与材料/FE 同阶段补偿；探针不占额度。服务器调度与验证见[配方消费者](network-recipes.md)。 |
| PN 研究启动条件、队列、阶段推进及完成 | 手动及研究站服务器执行、世界会话已接 | ResearchRegistry 注册、权限/真实 startCondition 入队、队列/逐项投料/回执恢复/冻结完成已执行。ResearchWorld/ServerResearch 已绑定单一世界存档、FTB 成员核对、既有 tick 边界及登录退出，并提供 Java 开始/接管入口。ResearchStations/ResearchStationWork 已使用真实站点、共享 lease、完整材料周期与 END grant/completion；原来源 lanes 和 UNKNOWN 恢复共用。研究站自动调度与世界会话保存已接；正 tier 玩家 Java 入队已接；界面/packet 与普通数据库下载已接，物品化下载入口仍缺；奖励领取见下一行，见[研究站执行](research-stations.md)。 |
| PN 研究奖励及事件奖励 | 服务器 Java 领取、世界检查点、明确零效果重试与原生物品保护已接；效果前持久凭据与加载恢复已接；packet/UI 已接 | ResearchWorld.claimReward/ServerResearch.requestReward 调用原 ResearchRewardClaimer 和 RewardEventAction，持有准确玩家会话，按选择/DELIVERING/CONFIRMED/最终状态保存完整世界。未调用或明确零效果可保存 RETRY_PLANNED 后沿原目标续发。Minecraft物品实际交付后核对文件证据，确认后清交付标签。原生交互、死亡重生、光标、装备与漏斗保护已接；第三方直接修改遵守公开查询，效果前 PREPARED/ISSUING 与加载时多来源合并已接；领取 packet/UI 已接，默认未知交付不盲目重发，见[服务器奖励](research-rewards.md)。 |
| PN 研究物品下载事件 | 未迁移 | 旧 ResearchItemDownloadEvent.createOutput 按材料注册，由 ResearchItemDownloadEvaluation 调用，再进入权威下载事务；当前没有对应 download 模块。 |
| PN 计算组件的实际物品供给 | Java 生产链已接 | AEItemKey 精确注册、公开 committed 仓槽读取、每物品上限后按 long 数量累加并合并 declared vector；准确版本证明进入真实同步/planned 配方许可。真实仓负责恢复，未知普通物品不供给。原生 ItemStack 适配仅静态核对，未启动游戏。 |
| CraftTweaker 接口及脚本数据适配 | 按用户要求最后处理 | PM ZenRecipeBuilder 通过 addRequirement/register 声明配方，Zen 参数转换、工厂和 ZSData 脚本数据组件尚未迁移；未发现该 builder 额外提供一套每配方 onStart/onFinish 事件 DSL。Java 功能完整之后再实现 CT，不能以 CT 延后为理由延后其底层 Java 功能。 |

上表的“已迁”指源码中的真实执行职责与已有逻辑测试，不代表游戏中 GUI、世界交互及最终所有兼容链均已验收。旧需求系统里的方法名是阶段钩子，不应混同 Forge 配方注册事件；PN 的 PostInit 配方扩展监听器也不应混同每 tick 事件。机器 tick、加载、成型变化、卸载和破坏归属机器生命周期，不计入配方事件清单。

旧 PM 没有独立于 requirements 的顶层 condition/events 数组，但有每配方选择提交回调、动态匹配条件、检查点/属性效果、进程组件钩子，并且已有选择修改器和动态匹配器的 CT 回调注册。此前“不存在运行时配方回调/DSL”的概括错误；具体预设与新项目映射见复审表。Java 对应调用已存在，CT 桥尚缺。已修正的旧行为包括 checkpoint time=0 原先永不触发、Selective 被拒候选原先可能 commit，以及选择状态/属性恢复问题，不能把保留旧 bug 作为功能对齐标准。

## 后续工作顺序

1. PN 普通配方研究/计算条件已接通，研究界面/packet 与领取已接，普通数据库下载和自动同步已接，继续接物品化下载与特殊磁盘交互，并处理永久破坏后的材料会话边界：先核对旧定义、状态及执行入口，复用可用逻辑，改写 Kotlin 与不必要的桥接层。新接口放在本项目真实职责包内，不照搬 api.pm 或 XxxImpl 镜像。
2. 研究启动、队列/阶段推进、完成、奖励与下载事件逐项接到真实注册、运行和持久化入口。状态说明按“定义、执行、存档、外部扩展”分别给出，不以仅有模型或预算底座冒充完整功能。
3. 在具体功能需要的地方使用已有事务和调度能力；服务器网络机器游标与来源 lanes 已接，纯 prepare、内部工作预算与其他 PN 消费者仍须完成，但不继续脱离功能主线反复细化底座。
4. 继续已确认的结构投影/复杂渲染/每池默认与自定义 JEI 等功能，CraftTweaker 最后。保持纯库定位，不迁入旧开发示例的正式机器、配方或玩法数值。
