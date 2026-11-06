# PM 事件、组件与配方迁移审查

早期 `MachineRecipe` 的固定 input/output/FE 字段及 `RecipeRun` 直接流程遗漏了 PM 的事件、组件、需求系统和进程扩展，不能视为有效迁移。目前已纠正公共配方定义，迁移组件调度、独立资源/FE 存储、整阶段协调和固定内置需求；本轮属性、数量倍率与同步单进程循环已实现并通过本轮逻辑验证。高级需求、完整保存恢复、扫描/取消和多线程调度仍未完成。JEI 接入继续等待这些基础收敛。

## 旧代码实际调用链

| 职责 | 源码入口 | 已确认行为 | 当前新实现 |
|---|---|---|---|
| 机器组件调度 | [MachineInstanceImpl.onSchedule](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:249) | 从 orderedTickEntries 取得有序组件/系统，依次执行全部 Pre、Normal、Post 阶段。 | 已有 ComponentMap 与 MachineInstance.onSchedule 的逻辑分派；未接世界/调度器。 |
| 机器事件适配与扩展 | [MachineSystem](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/component/system/MachineSystem.kt:80)、[MachineTickEvent](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/event/MachineTickEvent.kt:13) | 默认 onPreTick/onTick/onPostTick 分别构造 tick 事件并调用 onEvent；系统还可处理自定义 MachineEvent。 | 已迁移 MachineSystem 默认事件适配和开放 MachineEvent；注册事件与其无关。 |
| 配方扫描与处理装配 | [FactoryRecipeProcessorComponentType](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/type/FactoryRecipeProcessorComponentType.kt:15) | 同一处理器组件挂接扫描系统和执行系统。 | ComponentType 支持多系统；实际扫描器和处理器装配尚未迁移。 |
| 配方声明 | [MachineRecipe](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/MachineRecipe.kt:42) | 保存按 RecipeRequirementType 分组的 RecipeRequirementComponent；资源需求只是其中一类。 | 已改为按职责分组的 RecipeRequirement，泛型绑定负载与系统；旧固定字段移出 API。 |
| 需求阶段 | [RecipeRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/requirement/component/system/RecipeRequirementSystem.kt:64)、[executeStage](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:236) | start / acquireTickTransaction / onEnd；执行器对整阶段收集事务，成功提交，阻塞/失败反向回滚。 | 已有固定资源/FE/属性需求和统一资源/业务完成边界；本轮已接入属性需求，高级需求及需求 overlay 尚未迁移。 |
| 进程组件回调 | [tickProcessComponents](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:171)、[RecipeProcessSystem](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/process/component/system/RecipeProcessSystem.kt:11) | 每个配方进程拥有独立组件，组件系统执行 pre/tick/post。 | 已接单进程循环的 pre/tick/body/post，列表只捕获一次；进入 body 后执行全部 Post 清理，保持业务原异常。 |
| 进程状态 | [RecipeProcess](F:/clone/PrototypeMachinery/src/main/kotlin/api/recipe/process/RecipeProcess.kt:45)、[RecipeProcessImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/RecipeProcessImpl.kt:20) | owner、组件、属性 overlay、状态、随机 seed、组件持久化。 | 已有 owner、配方、独立组件、seed、root/overlay 属性及同步生命周期；进度同阶段提交并可补偿，完整状态保存恢复尚未迁移。 |
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

## 属性、并行数量与单进程循环映射

下表记录本轮已实现的源码行为，准确验证结果由实施进度维护。同步单进程入口不代表多工位扫描、取消、保存恢复或真实 planned 调度已经完成。

| 职责 | PM 源码入口 | 新实现与保留边界 |
|---|---|---|
| 属性三运算与真实扩展 | [MachineAttributeModifier](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/attribute/MachineAttributeModifier.kt:40)、[MachineAttributeModifierImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/attribute/MachineAttributeModifierImpl.kt:11) | `AttributeModifier` 保留外部纯函数 apply；`NumericAttributeModifier` 保留 ADDITION、MULTIPLY_BASE、MULTIPLY_TOTAL 的公式，按三阶段和同阶段注册顺序执行。ID、operation 和实现须不可变，所有计算值须有限。 |
| root、overlay 和局部快照 | [MachineAttributeInstanceImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/attribute/MachineAttributeInstanceImpl.kt:35)、[OverlayMachineAttributeMapImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/attribute/OverlayMachineAttributeMapImpl.kt:61)、[RecipeProcessImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/process/RecipeProcessImpl.kt:24) | `MachineAttributes` 按 ResourceLocation 实时解析父层的 base/modifier，局部覆盖后可清除以显露父层；机器 root 与进程 overlay 分离。localSnapshot/restoreLocal 复制、校验两层局部映射并保留不可变 modifier 引用；不包含父层或构造缺省值，不等于 NBT 编码/完整恢复。 |
| 有效数量倍率 | [RecipeParallelism](F:/clone/PrototypeMachinery/src/main/kotlin/api/util/RecipeParallelism.kt:16)、[旧 PARALLELISM 空壳](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/ParallelismRequirementComponent.kt:3)、[扫描选值](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeScanningSystem.kt:203) | 新 RecipeParallelism 从进程 PROCESS_PARALLELISM 取 floor 并至少为 1，正数量乘法饱和到 Long.MAX_VALUE；资源/FE 三阶段都已接入。MAX_CONCURRENT_PROCESSES、数量倍率和 PROCESS_SPEED 独立，ENERGY_EFFICIENCY 暂仅声明扩展 ID。旧空壳需求不恢复；安全扫描选值仍待实现。 |
| 属性需求的获取与清理 | [AttributeModifierRequirementComponent](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/AttributeModifierRequirementComponent.kt:19)、[AttributeModifierRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/AttributeModifierRequirementSystem.kt:24) | 新 AttributeRequirement 保留 applyAtStart/removeAtEnd。START 获取时生效，rollback 仅撤销自己成功添加的 modifier；END 在全部业务 commit 成功后的 complete 局部清理，使最终输出仍按有效倍率获取，阻塞/异常不必恢复或重排 modifier。内部 ID 包装仍委托外部 operation/apply。 |
| 单进程 START/TICK/END | [FactoryRecipeProcessorSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:44)、[进度推进](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:90) | 新 RecipeProcess.tick 保留 START 后、TICK 前后的完成检查，零时长 START 后直接 END；Blocked 重试同阶段，Failure 终态，COMPLETED/FAILED 不重放。double progress 为绝对工作刻数，long successfulTicks 每成功 TICK 加 1；只读一次 speed 并冻结 TICK 前后上下文。生命周期/进度作为最后一笔业务事务同阶段 commit/rollback，完成通知异常不重放已提交阶段。 |
| 进程回调与异常清理 | [处理器回调边界](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:37)、[Post finally](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:149) | 新 ComponentMap.tick(owner, body) 一次捕获 Pre/Normal/Post。Pre/Normal 错误在 body 前立即终止；body 已进入则逐个执行全部 Post，body 原异常保持主异常，Post 错误附加 suppressed。普通无 body 的 tick 仍立即失败。 |

属性层由独占执行者修改，父层在子进程并发读取期间保持稳定，真实调度需在 join 转移所有权；新属性读值不写 dirty/cache，不依赖 ConcurrentHashMap 加 volatile 保证整个计算原子。

新源码阅读入口：[MachineAttributes](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/attribute/MachineAttributes.java)、[AttributeModifier](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/attribute/AttributeModifier.java)、[RecipeParallelism](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/process/RecipeParallelism.java)、[AttributeRequirementSystem](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/recipe/requirement/AttributeRequirementSystem.java)、[RecipeProcess](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/process/RecipeProcess.java)、[ComponentMap](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/component/ComponentMap.java)。

## 属性与时间线旧缺陷

- **扫描有效值二次放大与 int 边界。** [FactoryRecipeScanningSystem.parallelLimit](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeScanningSystem.kt:128) 将机器已应用 modifier 的 PROCESS_PARALLELISM value 作为上限，再[写入进程 base](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeScanningSystem.kt:208)，进程继承相同父 modifier 时再次计算倍率。例：机器 base=2、总乘数=2，选得有效量 4 后写成 overlay base，继承乘数使 value 变为 8。新扫描不得重复应用已计入的 modifier。[二分查找成功分支](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeScanningSystem.kt:179) 的 mid+1 在 mid=Int.MAX_VALUE 时递增溢出，可能使搜索边界失效；扫描尚未迁移。
- **流体随机刻索引停滞。** [FluidRequirementSystem.tickIndex](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/system/FluidRequirementSystem.kt:438) 把绝对工作进度 clamp 到 0..1 后乘 duration，progress 达到 1 就长期返回 duration，后续刻重复同一随机盐。未来高级流体需求应使用冻结 TICK 上下文中的 long 成功刻索引，保证阻塞重试可重复、成功刻之间可推进；本轮未实现高级 chance。
- **检查点零时刻遗漏及速度读取不一致。** [CheckpointRequirementSystem](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/CheckpointRequirementSystem.kt:49) 以 current < target && next >= target 触发（67），初始 current=target=0 时不触发 time=0；scaled 检查点自行读取 speed（55），处理器在需求执行后再读 speed（94），若阶段改变属性，两者可能采用不同推进量。新冻结 Tick 前后上下文已提供一致时间线，但检查点实现、零时刻与恢复语义仍待迁移。
- **volatile dirty 丢失失效及父实例引用过期。** [MachineAttributeInstanceImpl.value](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/attribute/MachineAttributeInstanceImpl.kt:60) 计算后直接 dirty=false（66），计算期间另一次修改设置的 dirty=true 可被覆盖，缓存便保留旧值；volatile 字段和并发 modifier map 不组成完整计算协议。[OverlayMachineAttributeMapImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/attribute/OverlayMachineAttributeMapImpl.kt:64) 首次创建子视图时固定保存父 instance（67），父属性替换或后来补入时可继续读过期父实例。新属性通过 ID 查询稳定父层，不写缓存；并发父层修改仍必须受所有权屏障约束。
- **自定义 modifier 转数值导致行为丢失。** [AttributeModifierRequirementSystem.toInternal](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/AttributeModifierRequirementSystem.kt:93) 从 amount/operation 新建数值实现，未委托原 apply。新内部 ID 包装保留外部 AttributeModifier 的真实 operation/apply，不将接口扩展降成几种固定公式。
- **END 过早移除及交叠业务的整层回滚。** PM [属性 END](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/AttributeModifierRequirementSystem.kt:60) 获取阶段即移除 modifier（70），后续输出按降低后的值检查。新 END 只在所有业务 commit 成功后的 complete 清理。START 若用获取时的整层快照 rollback，会抹去较早需求在 commit 中做的属性变化，再执行其 rollback 可能双重撤销；新属性需求只撤销自己成功添加的 modifier，局部快照 API 不替代交叠事务的局部补偿。
- **阶段后更新状态会重放已完成效果。** PM [START 标记](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:46)、[TICK 进度](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/system/FactoryRecipeProcessorSystem.kt:91) 和 END 移除槽位都在 execute 返回后更新。若资源/业务已经完成，而完成通知使 execute 抛错，外层状态仍未推进，下一次可能重放开始、每刻消耗或结束产出。新生命周期/进度在资源同阶段业务 commit 内写入且可补偿，通知报错不退回已跨过完成边界的状态；宿主槽位移除和取消仍待接入。

## 高级需求能力与源码入口

以下来自 PM 源码的只读审计，是必须保留的完整能力清单，不表示每个高级行为已实现。新的 `RecipeRequirement<C>` 已统一实例 ID、类型、不可变负载和 START/TICK/END 派发；运行时变化归 `RecipeProcess`。整阶段边界、`MachineHatches`、固定资源/FE 需求已接入，本轮新增属性、数量倍率和同步单进程循环；高级 chance、模糊动态、随机输出、解析锁、选择器、需求 overlay、检查点及完整持久化仍待接入。

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
- **逐需求完成不能替代整阶段完成。** 旧阶段分派先 acquisition 后逐事务 commit；资源、FE 与业务/选择状态须具有共同的完成和失败边界，不能让较早需求独自最终完成后仍遇到后项失败。新同步整阶段协调已接入，本轮生命周期/进度也在此边界内提交和补偿；高级选择状态尚未接入，真实 planned 提交协议未完成。
- **旧 planned 投影覆盖不足。** [ItemRequirementComponent.snapshot](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/ItemRequirementComponent.kt:42)及[FluidRequirementComponent.snapshot](F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/FluidRequirementComponent.kt:52) 拒绝全部非空 properties，投影仅涉及固定输入及流体每刻输入。概率、模糊、动态、随机、选择与 overlay 仍需要显式快照、有效定义和状态投影契约，不能因固定消耗可投影就宣称完整 planned 迁移完成。

整阶段边界、公开仓室绑定、固定需求及同步属性/单进程时间线已经实现。后续接确定性概率/解析锁/模糊动态/随机输出/选择器/需求 overlay/检查点，再完成保存恢复、扫描/取消和 planned 适配。需求定义和系统可跨进程共享，变化必须归各进程；真实调度继续遵守 PM 的两次 join、稳定 affinity lane 与 PN owner/epoch/预算边界。以上是依赖排序，所有高级效果和外部扩展职责仍在保留范围。

## 修正顺序与验证依据

1. 从旧组件装配、调度、事件和生命周期调用链建立新 Java API；明确哪些是扩展接口、哪些是普通实现类，避免机械的接口/Impl 配对。
2. 改造 MachineRecipe，保留需求描述和扩展入口；把资源身份迁到 AE2，把内置资源处理从执行器中分离。
3. 恢复进程组件回调与 start/tick/end 需求执行；统一阻塞、失败、提交、回滚、持久化语义，再决定现有收支代码哪些可以复用。
4. 用独立外部组件/需求测试实际回调顺序、阻塞与重试、失败后停止执行、状态恢复。仅创建类或编译成功不能证明扩展可用。
5. 配方池与 JEI 读取修正后的同一模型，再完成默认和自定义页面。所有使用样例保持独立，主产物不注册玩法内容。

这份审查记录缺口和源码证据，不表示缺失能力已经实现。后续进度须以具体代码、调用路径和行为证据更新。
