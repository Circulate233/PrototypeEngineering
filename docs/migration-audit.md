# PM 事件、组件与配方迁移审查

早期 `MachineRecipe` 的固定 input/output/FE 字段及 `RecipeRun` 直接流程遗漏了 PM 的事件、组件、需求系统和进程扩展，不能视为有效迁移。目前已纠正公共配方定义，并迁移组件调度、需求阶段核心及独立资源存储；完整进程、内置需求和多线程调度仍未完成。JEI 接入继续等待这些基础收敛。

## 旧代码实际调用链

| 职责 | 源码入口 | 已确认行为 | 当前新实现 |
|---|---|---|---|
| 机器组件调度 | [MachineInstanceImpl.onSchedule](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:249) | 从 orderedTickEntries 取得有序组件/系统，依次执行全部 Pre、Normal、Post 阶段。 | 已有 ComponentMap 与 MachineInstance.onSchedule 的逻辑分派；未接世界/调度器。 |
| 机器事件适配与扩展 | [MachineSystem](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/component/system/MachineSystem.kt:80)、[MachineTickEvent](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/event/MachineTickEvent.kt:13) | 默认 onPreTick/onTick/onPostTick 分别构造 tick 事件并调用 onEvent；系统还可处理自定义 MachineEvent。 | 已迁移 MachineSystem 默认事件适配和开放 MachineEvent；注册事件与其无关。 |
| 配方扫描与处理装配 | [FactoryRecipeProcessorComponentType](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/type/FactoryRecipeProcessorComponentType.kt:15) | 同一处理器组件挂接扫描系统和执行系统。 | ComponentType 支持多系统；实际扫描器和处理器装配尚未迁移。 |
| 配方声明 | [MachineRecipe](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/MachineRecipe.kt:42) | 保存按 RecipeRequirementType 分组的 RecipeRequirementComponent；资源需求只是其中一类。 | 已改为按职责分组的 RecipeRequirement，泛型绑定负载与系统；旧固定字段移出 API。 |
| 需求阶段 | [RecipeRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/component/system/RecipeRequirementSystem.kt:64)、[executeStage](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:236) | start / acquireTickTransaction / onEnd；执行器对整阶段收集事务，成功提交，阻塞/失败反向回滚。 | 已迁移 RecipeStageExecutor 与外部需求系统契约；尚未迁移内置需求及 overlay。 |
| 进程组件回调 | [tickProcessComponents](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:171)、[RecipeProcessSystem](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/process/component/system/RecipeProcessSystem.kt:11) | 每个配方进程拥有独立组件，组件系统执行 pre/tick/post。 | 已有独立泛型组件容器；围绕进程执行的具体时机尚未接入。 |
| 进程状态 | [RecipeProcess](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/process/RecipeProcess.kt:45)、[RecipeProcessImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/RecipeProcessImpl.kt:20) | owner、组件、属性 overlay、状态、随机 seed、组件持久化。 | 已有 owner、配方、独立组件与 seed；属性、完整状态与持久化尚未迁移。 |
| 生命周期 | [MachineLifecycleListener](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/lifecycle/MachineLifecycleListener.kt:11)、[MachineLifecycleDispatcher](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/lifecycle/MachineLifecycleDispatcher.kt) | 加载、卸载、成型变化、控制器破坏分别发布；组件监听和全局监听有顺序。 | 缺失。 |

机器 tick 的实际驱动入口是组件回调。MachineSystem 的默认实现会转发给 onEvent，但覆盖 onTick 的具体系统可直接处理 tick；例如 FactoryRecipeProcessorSystem 就采用后者。不能仅凭事件接口存在，宣称旧项目已经有统一任意事件发布总线。配方阶段与进程组件回调也不应误称为 Forge 注册事件。

## 应保留的能力与应修正的旧实现

- 除用户明确取消或替换的部分外，全部 PM/PN 功能效果及多线程性能能力必须保留；未实现是迁移缺口，不是可选功能。每项实现必须先确认旧源码及其目的。
- 保留机器/进程组件与系统的装配关系、阶段调度、事件处理及生命周期边界。Java 25 重写可以简化类和包的组织，不能删掉扩展职责。
- 保留配方的可扩展需求集合、需求系统和进程状态。AEKey/AEKeyType 替换 PMKey 等资源身份；RecipeRequirementType 描述执行职责，二者不等价。
- 输入、输出和 FE 处理应成为内置需求处理的一部分，不能由执行器认识几个固定字段并排除其他需求。
- 旧 MachineInstanceImpl 的 runPhase 会捕获并记录系统错误后继续；旧 executeStage 的 rollbackAll 用 runCatching 吞掉回滚错误。这些行为与新项目的立即失败要求冲突，不迁入。
- RecipeRequirementSystem 注释存在“Blocked 应 commit”的表述，而 FactoryRecipeProcessorSystem 实际会对阻塞阶段整体回滚。新契约必须统一，不能同时复制矛盾说明。
- 旧进程组件恢复会跳过无法解析的组件类型，部分恢复代码吞异常。新持久化不能静默丢失已选择保留的状态。
- 当前部分收取/付款缓存可以作为资源操作研究材料，但不能替代需求阶段的提交契约；是否采用、如何参与回滚，必须在需求系统实现中明确验证。
- 存储 API 必须独立设计，不以 MEStorage 为核心契约；AE2 只提供资源类型及外部集成能力。目前已用 ResourceStorage/SlottedResourceStorage 和独立 SlottedResourceBuffer 替换原后端，预留/补偿的具体语义、旧账本 finalize 缺口和 PN 生命周期风险见[存储审查](storage-redesign.md)。这不代表 planned 调度集成已完成。

本轮组件实现沿用软依赖与 Kahn 排序，改用 fastutil 和独立不可变依赖快照；系统顺序使用显式 ID。重建派生系统索引避免组件替换后残留旧系统关系。图仍由单一执行所有者维护，未来通过调度屏障转移所有权；不以增加 volatile 标志冒充并发容器。

## 高级需求能力与源码入口

以下来自 PM 源码的只读审计，是必须保留的能力清单，不表示新项目已实现。新的 `RecipeRequirement<C>` 已统一实例 ID、类型、不可变负载和 START/TICK/END 派发；运行时变化归 `RecipeProcess` 的组件。当前正在实现跨资源/FE/业务的整阶段完成边界、`MachineHatches` 组件及固定内置需求，高级能力仍待接入。

| 能力 | 源码入口 | 必须保留的行为 |
|---|---|---|
| 物品阶段 IO | [ItemRequirementComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/ItemRequirementComponent.kt:29)、[ItemRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:30) | START 消耗固定、模糊和动态输入；END 产出固定和随机输出；物品 TICK 本身无操作。可跨多个来源/目标满足数量，固定与已解析同 key 需求聚合。 |
| 流体阶段 IO | [FluidRequirementComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/FluidRequirementComponent.kt:35)、[FluidRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/FluidRequirementSystem.kt:155) | START 固定/模糊输入，TICK 固定输入和输出，END 固定/随机输出；每刻随机盐包含进程刻索引。旧消耗源用 PortMode.OUTPUT、产出目标用 PortMode.INPUT，迁移须按端口与机器的视角正确映射。 |
| 概率、属性和并行数 | [RequirementPropertyKeys](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/advanced/RequirementPropertyKeys.kt:9)、[ChanceMath](F:/clone/PrototypeMachinery/src/main/kotlin/api/util/probability/ChanceMath.kt:20)、[effectiveChancePercent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:477) | chance 默认 100%，乘可选 chanceAttribute 的进程属性值，缺失属性乘数为 1；执行次数为 floor(chance/100)×并行数 + Binomial(并行数, 小数部分)，支持超过 100%。系统容量检查取并行数与 ceil(chance/100)×并行数中的较大值，低概率/零概率仍保留保守检查；保留确定性阶段随机。 |
| 模糊和动态匹配 | [FuzzyInputGroup](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/advanced/FuzzyInputGroup.kt:8)、[DynamicItemInputGroup](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/advanced/DynamicItemInputGroup.kt:16)、[ItemRequirementMatcherRegistry](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/advanced/ItemRequirementMatcherRegistry.kt:13) | 模糊候选按声明顺序选首个足量候选，使用组数量；动态输入从可枚举来源取得候选，按外部 matcher 过滤，保留候选上限与可选 JEI 展示候选。内置 item_only 比较物品和 meta，exact 还比较 NBT；未知 matcher 不能视为成功。 |
| 解析锁与恢复 | [RequirementResolutionProcessComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/component/RequirementResolutionProcessComponent.kt:22)、[物品锁 ID](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:404) | 按需求、阶段、组及模糊/动态类别记录所选 key；容量检查成功后写锁，事务回滚恢复旧锁。即使概率采样执行次数为零，也可能需要记录并回滚解析效果。保存恢复包含锁定 key，迁移为 AEKey 编码并明确校验，不静默丢失已保留状态。 |
| 随机输出与满输出策略 | [RandomOutputPool](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/advanced/RandomOutputPool.kt:14)、[物品 END](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:204)、[流体 TICK](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/FluidRequirementSystem.kt:281) | 每个输出循环按权重无放回选择 pickCount 个候选，忽略非正权重；按所选候选数量产出。ignore_output_full 允许部分插入或零插入并丢弃余量后成功，但没有任何目标仍阻塞。 |
| 选择性需求和 modifier | [SelectiveRequirementComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/SelectiveRequirementComponent.kt:29)、[SelectiveRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/SelectiveRequirementSystem.kt:30)、[SelectiveContext](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/selective/SelectiveContext.kt:15)、[SelectiveStateProcessComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/component/SelectiveStateProcessComponent.kt:22) | 包装任意候选需求，选首个 START 成功者并全程沿用；没有可用候选则本次进程禁用该选择而不阻塞。提交时通过外部注册的 modifier 施加进程属性效果；保存选择索引及 modifier 引用，结束时清理效果。 |
| 每进程需求 overlay | [RecipeOverlayProcessComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/component/RecipeOverlayProcessComponent.kt:92)、[OverlayApplier](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/overlay/RecipeRequirementOverlayApplier.kt:20)、[executeStage](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:250)、[RecipeOverlayModifierPipeline](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/modifier/RecipeOverlayModifierPipeline.kt:12) | 按 typeId + componentId 保存覆写，执行时解析有效组件，不修改基础配方。内置 applier 覆盖物品/流体 IO、流体每刻 IO、能源每刻速率及 ignore_output_full；保留外部 applier、modifier 与稳定调用顺序。 |

## 高级需求旧实现缺陷与迁移边界

- **选择器 START 重试漏消耗风险。** [SelectiveRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/SelectiveRequirementSystem.kt:49) 在 acquisition 时写 selectedIndex（56），START rollback 只撤销 modifier 和子事务（95–99），不撤销选择索引。后续需求阻塞使输入回滚后，再次 START 因既有索引直接成功，可能跳过已回滚的输入。选择及获取状态必须一致回滚。其 END rollback 还会清除已生效 modifier（150–152），输出阻塞后的重试会丢失仍应生效的属性效果；清理时机应绑定真正的进程结束。
- **流体 TICK 模拟重复使用余额/容量。** [输入模拟](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/FluidRequirementSystem.kt:198)及输出模拟（214）逐条模拟，未聚合也未为前项预留。同 key 两项各需 100、可用量 150 时，两项都可通过检查而实际执行失败。迁移须按确定 key 聚合或让整批预留覆盖全部需求。
- **随机输出最大数量检查不能证明容量安全。** [worstCaseKeys](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:499) 只取数量最大的 pickCount 个 key；能容纳这些 key 不代表能容纳另一些随机 key，槽位和 key 限制也不能由总量推导。保留加权效果，容量预留覆盖实际确定输出或采用明确有效的所有候选容量界。
- **动态候选先截断再排序。** [enumerateDynamicCandidates](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:440) 达到上限便停止枚举（443–452），随后才排序（457）；来源遍历顺序可能影响候选子集。确定性承诺须包含截断前的来源/候选顺序与稳定 key 比较。
- **旧补偿忽略实际恢复量。** [rollbackExtracted/rollbackInserted](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/ItemRequirementSystem.kt:513) 调用 unchecked 插入/提取但忽略返回数量；流体也有同类代码。不能复制“尽力退回”便宣称资源守恒。新需求应使用已有预留/补偿协议，并让无法完成的补偿显式失败；不能先公开输出再依赖撤回仍可被外部取得的资源。
- **逐需求完成不能替代整阶段完成。** 既有阶段分派先 acquisition 后逐事务 commit；资源、FE 与业务/选择状态须具有共同的完成和失败边界，不能让较早需求独自最终完成后仍遇到后项失败。当前正在实施这一边界，不在本审计中写作已完成或已验证。
- **旧 planned 投影覆盖不足。** [ItemRequirementComponent.snapshot](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/ItemRequirementComponent.kt:42)及[FluidRequirementComponent.snapshot](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/FluidRequirementComponent.kt:52) 拒绝全部非空 properties，投影仅涉及固定输入及流体每刻输入。概率、模糊、动态、随机、选择与 overlay 仍需要显式快照、有效定义和状态投影契约，不能因固定消耗可投影就宣称完整 planned 迁移完成。

先建立整阶段完成边界和公开来源访问，再接固定内置需求；之后补属性、有效并行数、刻状态与确定性解析，接入概率/模糊动态/随机输出/选择器/overlay，最后完成保存恢复、扫描和 planned 适配。需求定义和系统可跨进程共享，变化必须归各进程；真实调度继续遵守 PM 的两次 join、稳定 affinity lane 与 PN owner/epoch/预算边界。以上是依赖排序，所有高级效果和外部扩展职责仍在保留范围。

## 修正顺序与验证依据

1. 从旧组件装配、调度、事件和生命周期调用链建立新 Java API；明确哪些是扩展接口、哪些是普通实现类，避免机械的接口/Impl 配对。
2. 改造 MachineRecipe，保留需求描述和扩展入口；把资源身份迁到 AE2，把内置资源处理从执行器中分离。
3. 恢复进程组件回调与 start/tick/end 需求执行；统一阻塞、失败、提交、回滚、持久化语义，再决定现有收支代码哪些可以复用。
4. 用独立外部组件/需求测试实际回调顺序、阻塞与重试、失败后停止执行、状态恢复。仅创建类或编译成功不能证明扩展可用。
5. 配方池与 JEI 读取修正后的同一模型，再完成默认和自定义页面。所有使用样例保持独立，主产物不注册玩法内容。

这份审查记录缺口和源码证据，不表示缺失能力已经实现。后续进度须以具体代码、调用路径和行为证据更新。
