# 配方与组件迁移

`MachineRecipe` 已恢复为按需求职责分组的公开定义。它保存 id、时长、配方组及 `RecipeRequirementType<?> → List<RecipeRequirement<?>>`，不再以固定输入、输出和 FE 字段限定配方。需求类型绑定自己的 `RecipeRequirementSystem<C>`；资源身份继续使用 AE2 的 AEKey/AEKeyType，不再建立 PMKey 的替代类型体系。

本模块是库，不注册正式机器或玩法配方。参数、内容和自定义需求由使用方提供。资源概率、加权输出、检查点、模糊/动态输入、解析锁、嵌套需求路径、选择器和需求 overlay 已完成此前阶段验证。当前严格进程 NBT 保存恢复代码已接入，已通过本阶段验证，准确证据见[实施进度](implementation-status.md)。factory/workslot 宿主恢复、扫描/取消和真实并行调度仍在迁移中。

## 公开注册与需求扩展

`RecipeRegistrationEvent` 仍在 Forge post-init 发出，事件结束后冻结目录。使用方先声明池，再添加配方：

```java
var type = new RecipeRequirementType<MyRequirement>(typeId, myRequirementSystem);
var requirement = type.requirement(requirementId, immutablePayload);
registry.registerPool(poolId, poolTitleTranslationKey);
registry.registerRecipe(poolId, new MachineRecipe(recipeId, durationTicks,
        ObjectArrayList.of(requirement)));
```

`MyRequirement` 和系统由使用方定义。负载必须不可变；随机选择、材料锁、进度和其他变化放在每个 `RecipeProcess` 的组件中。配方按需求类型 ID 稳定排序，同类型内维持声明顺序，与 PM 的 executeStage 一致。配方内需求 ID 唯一；相同类型 ID 不允许绑定不同的类型对象。

嵌套需求使用 `RequirementPath(parent, typeId, requirementId)` 区分完整祖先路径，同一个 child 定义可在不同包装器中拥有独立状态。路径字符串按每个 ID 的长度分帧，不依赖分隔符字符、hash 或对象身份推断边界。RecipeRequirement.acquire 在获取时建立原需求的路径 scope，在该路径上解析一次有效 overlay view，再调用系统；返回事务的 result、reservations、commit、complete、rollback 捕获同一路径，回调结束恢复父 scope。随机盐、属性内部 ID、输入解析锁、选择状态和需求 overlay 都使用完整路径，不能只凭局部 child ID 区分。

注册事件只负责收集定义，与机器 tick 事件、需求阶段、进程组件回调是不同机制。

## 已迁移的组件驱动

`api.component.TopologicalComponentMap` 来自 PM 的软依赖图与 Kahn 排序：保留动态添加/替换、依赖延迟出现、删除后重新安装、addBefore/addAfter/addFirst/addTail 以及按修改失效的排序缓存。依赖集合与返回列表使用独立不可变快照，避免调用方修改后绕过缓存失效。

`ComponentType<E,C>` 是普通描述类，绑定工厂和多个系统；组件值无需机械增加一个接口及 Impl。需要外部实现的处理职责保留在 `ComponentSystem` / `MachineSystem` 接口。系统以显式 ResourceLocation ID 声明顺序，替代以运行时 Class 识别系统；无反射扫描。

`ComponentMap` 把系统顺序作用于组件依赖，同组件的多个系统保留声明顺序。系统元数据须保持不变；动态重组通过组件表进行。每个 tick 只取一次完整执行列表，依次执行全部 Pre、全部 Normal、全部 Post。回调中发生替换时，本 tick 的全部阶段仍使用同一旧列表，下个 tick 才采用新列表。普通 `tick(owner)` 的回调抛错立即终止后续阶段。

单进程循环使用 `tick(owner, body)`：仍只捕获一次回调列表，Pre/Normal 全部成功后才进入业务 body；这两阶段的异常立即终止，尚未进入 body 时不执行 Post。进入 body 后，无论阻塞、业务异常或某个 Post 异常，都会依次执行全部已捕获的 Post 清理。业务原异常保持主异常，清理异常附加为 suppressed；没有业务异常时暴露首个 Post 异常并附加其余错误。

`MachineInstance.onSchedule()` 调用这一分派；`MachineSystem` 默认把阶段适配为 `MachineTickEvent.Pre/Normal/Post`，具体系统也可覆盖阶段方法。此入口尚未接入世界控制器、结构和真实调度器，不能将逻辑调用成功称为机器运行已完成。

## 需求阶段契约与整阶段完成依赖

`RecipeStageExecutor` 按 PM `FactoryRecipeProcessorSystem.executeStage` 的实际行为迁移：

1. 依次调用各类型系统的 start、tick 或 end，收集事务及其 `reservations()`；取得的资源/FE 令牌只交给阶段协调器，单个需求不得自行完成它们。
2. 全部成功后，将全部真实 StorageContext 按固定顺序加锁，校验并 apply 全部令牌，然后按需求顺序执行业务 commit。commit 在此表示可补偿的业务应用，不是最终完成。
3. 业务 commit 全部成功后才依次 complete，释放业务与存储补偿权，再统一解锁和通知。complete 不允许业务拒绝或抛错；外部实现违反契约时，完成链仍尝试其余业务清理、子事务完成和每个资源/FE token 完成，再聚合错误。
4. 遇到 Blocked/Failure、令牌失效或获取/应用/业务异常时，锁内先逆序 rollback 业务事务，再逆序撤销资源/FE 令牌；包括返回阻塞/失败的那笔。异常保留原错误，继续其余补偿并聚合 suppressed。
5. 完成阶段的契约错误和完成后的通知错误在继续其余完成/通知后暴露，不把已经跨过完成边界的事务当作未提交状态再次回滚，也不让较早 complete 异常跳过后续本地清理。

这里统一了旧接口注释与执行器对 Blocked 的矛盾，以实际整阶段回滚行为为准。资源不足使用 Blocked，契约错误使用异常；捕获只用于异常清理，不作为常规流程。

统一完成边界已经接好，并覆盖跨需求类型、存储和业务状态的补偿。公开需求系统仍须覆盖部分获取产生的全部效果；业务 commit 失败时必须原子或保有完整撤销信息，默认 no-op complete 不会让旧的不可逆业务自动变成可补偿操作。外部回调只访问已加入批次的存储，不得持锁等待另一执行 lane。当前同步批次不是跨 lane/join 的 distributed 提交实现。

`RecipeProcess` 的单进程生命周期和进度现在作为最后一笔业务事务参与同一资源/FE 阶段：commit 写入新状态，rollback 恢复阶段前状态。资源完成后的通知异常直接暴露，但已提交的开始标记、成功刻数、进度或完成状态不会退回，避免下一次调用重放已完成阶段。需求 overlay 已接入同一获取入口；严格进程 codec 本阶段已接入，factory/workslot 宿主恢复、多工位扫描和取消协议仍未接入。

## 已接入的固定需求和来源组件

`MachineHatches` 是不可变的已发现仓室绑定，使用方在机器的独占执行边界把它放入 `machine.components()`；包含公开 ResourceHatch、EnergyInput 和 EnergyOutput 列表。内置需求只查询这些公开接口，不强转内置方块实体。实际结构扫描与自动更新绑定仍未接入；发现必须在服务端完成，不能把该组件当作任意线程的世界查询器。

```java
machine.components().put(MachineHatches.TYPE,
        new MachineHatches(resourceHatches, energyInputs, energyOutputs));
var resources = RecipeRequirements.RESOURCES.requirement(requirementId,
        new ResourceRequirement(startInputs, tickInputs, tickOutputs, endOutputs, false));
var energy = RecipeRequirements.ENERGY.requirement(energyRequirementId,
        new EnergyRequirement(startFE, tickInputFE, tickOutputFE, endFE, false));
```

两个 payload 是可扩展配方模型中的内置需求，不限制外部 RecipeRequirementType。ResourceRequirement 的各列表使用不可变 GenericStack；物品、流体及其他 AEKey 类型共用算法，start 消费固定 inputs 并解析 fuzzyInputs/dynamicInputs、tick 消费/产出每刻列表、end 产出 outputs，另带 ResourceChance 和可选 END RandomOutputPool。EnergyRequirement 按 PM 的 start/tick/end 输入输出语义保存独立 long FE，不定义 FE key。两类需求都读取当前进程的 PROCESS_PARALLELISM；资源再按概率得到的执行次数缩放，FE 保持有效并行数量缩放。ENERGY_EFFICIENCY 目前仅声明扩展 ID，不能把数量倍率等同于机器并发槽位数或加工速度。

资源同 key 条目先精确聚合，按声明和仓室顺序分配，别名通过真实 context 去重；快照/模拟和确切预留在同一个 context 锁内完成。FE 的单次需求分配也只遍历每个真实来源一次，按未预留余额/空间及当前单次限额取交集，不累计重复模拟同一余额。多个需求共享存储时，后续需求看到前面已经占用的数量/容量；任一需求阻塞会释放整个阶段的占用。

`voidExcessOutputs` 对应 PM 的 ignore_output_full：只预留实际可接收量，允许丢弃放不下的剩余产物；没有任何有效目标时仍阻塞。未启用时必须预留完整产出。输出阻塞时不会留下部分写入，重试需要重新取得阶段令牌。内置原因统一由实现 LocalizationEnum 的 `RecipeText` 提供中英键，不在系统中拼接翻译键。

## 属性、数量倍率与单进程循环

`MachineInstance.attributes()` 持有机器 root，`RecipeProcess.attributes()` 持有以机器为父层、缺省 base 为 1 的独立 overlay。`MachineAttributes` 按 ResourceLocation 查找属性，局部 base 覆盖父 base，局部同 ID modifier 覆盖继承项，移除局部项后重新显露父项；读值时按 ID 查询当前父状态，没有持有已替换属性实例的缓存。机器 root 的 modifier-only 属性缺省 base 为 0，调用者可在属性未定义时提供有限 fallback。

运算阶段固定为 ADDITION → MULTIPLY_BASE → MULTIPLY_TOTAL，同阶段保留注册顺序。`NumericAttributeModifier` 分别执行 `current + amount`、`current + base * amount`、`current * (1 + amount)`；`AttributeModifier` 保留外部真实 `apply(base, current)` 行为，不能把自定义实现机械转成数值 modifier。外部 modifier 必须不可变且纯计算，ID/operation 保持稳定。base、fallback 和每步结果必须有限，负值由具体消费者按领域规则处理。

`localSnapshot()` 复制两层局部映射并返回不可变视图，保留不可变 modifier 引用；`restoreLocal()` 全量校验后替换，失败不改变原层。它只保存局部 base/modifier，不包含父层和构造缺省值，也不是 NBT 编码或完整保存恢复。属性层由机器/进程独占执行者修改，父层在子进程并发读取期间保持稳定，所有权通过调度 join 转移；没有用 volatile dirty 或缓存写入冒充安全并发读取。

`RecipeParallelism.effective()` 使用进程属性 PROCESS_PARALLELISM，取 floor 并至少为 1；有限大值转换受 int 上限限制。资源和 FE 数量以该倍率缩放，正数量乘法溢出饱和到 Long.MAX_VALUE，非法负数量拒绝。MAX_CONCURRENT_PROCESSES、PROCESS_PARALLELISM、PROCESS_SPEED 是独立职责，ENERGY_EFFICIENCY 仅提供扩展 ID，当前内置需求未使用效率公式。PM 旧 PARALLELISM component/system 已是注释空壳，不恢复成第二套需求；扫描时确定安全有效倍率仍待迁移，并须避免把已受父 modifier 影响的 value 再写入继承相同 modifier 的 base。

`RecipeRequirements.ATTRIBUTES` 绑定 `AttributeRequirement` / `AttributeRequirementSystem`，保留可配置 applyAtStart 和 removeAtEnd。START acquisition 立即施加 modifier，使同阶段后续需求读取更新后的倍率；内部 ID 由完整需求路径和属性 ID 确定，委托外部 modifier 的 operation/apply。START rollback 只删除自己成功添加的 modifier，不恢复整层快照，以免覆盖其他需求在业务 commit/rollback 中做的属性或 base 变化。END acquisition 和业务 commit 都保留 modifier，到全部业务 commit 成功后的 complete 才执行无失败局部清理；这既避免最终输出检查提前降倍率，也使被阻塞或异常的 END 不需要重排恢复 modifier。

`RecipeProcess.tick()` 是同步单进程入口：NEW 先执行 START，成功后检查完成条件；未达时长才执行 TICK，成功后再检查 END。零时长在 START 后直接 END，不获取 TICK。Blocked 保持当前生命周期和进度，下一次重试同一阶段；业务 Failure 进入终态 FAILED，COMPLETED/FAILED 再次 tick 只返回已有结果。进程执行不允许递归或同进程并发，宿主扫描、槽位创建、取消和移除尚未接入。

进度采用 double 的绝对工作刻数，声明 durationTicks 也是工作刻数；每次成功 TICK 增加 max(0, PROCESS_SPEED)，long successfulTicks 只增加 1，提供不受速度缩放的确定性刻时钟。TICK 开始前只读一次速度并冻结 `Tick(progressBefore, progressAfter, indexBefore, indexAfter)`，需求可通过 tickContext 读取；阻塞时该次进度和成功刻数都不前进。资源每刻随机盐使用尚未推进的 successfulTicks，检查点使用冻结的 scaled/unscaled 前后范围；不把绝对 progress 当作 0..1 的完成比例。

## 概率、随机输出与检查点

`api.recipe.probability` 迁入 PM 的 ChanceMath、BinomialSampler、WeightedSampling 和可调 ProbabilityTuning。ResourceChance 默认 100%，可选属性乘数从进程属性读取，缺失为 1、负乘数按 0 处理；定义拒绝非有限或负百分比，乘数结果溢出也显式失败。ChanceMath 的执行次数为 `floor(C/100) * parallelism + Binomial(parallelism, fractional(C/100))`，支持超过 100%，正数量乘法和执行次数的正溢出饱和到 Long.MAX_VALUE。

BinomialSampler 保留 PM 的 gameplay 近似策略：概率大于 0.5 先用对称性；默认关闭小 n 精确 Bernoulli，均值小于 16 时用 Poisson 近似，其余用固定 seed 生成的 2048 项 Gaussian 表，再裁剪到 [0,n]。这不是所有范围的精确二项分布。ProbabilityTuning 提供不可变策略，默认 exactSmallBinomial=false、阈值 1024，可显式启用和调整阈值；默认发布入口不依赖 Forge 配置。RecipeProcess 在构造时捕获一份策略，资源系统显式传入，运行中变更全局策略不会使已有进程的阻塞重试重新抽样。RecipeProcessNbt 本阶段保存 seed、调参和进程状态，已通过本阶段验证；这些字段不代替宿主兼容性与完整组件恢复校验。

资源系统按完整需求路径、阶段和成功刻数隔离随机盐。同一阶段的固定输入/输出共用执行次数；固定容量保留 PM 的 `max(parallelism, ceil(C/100) * parallelism)` 检查。检查量大于实际量时，用真实临时令牌做 preflight，覆盖同 key 与共享槽位的总需求；检查完释放全部临时令牌，再为实际数量重新取得阶段令牌。0% 仍保留完整并行量的固定容量检查，但不实际消耗/产出；不同需求可重复使用已释放的检查容量，实际执行量之间仍受前面真实预留约束。没有所需的有效来源/目标仍阻塞，preflight 不是永久占用或允许实际透支的证明。

RandomOutputPool 在 END 的每个执行循环按整数权重无放回选取最多 pickCount 个候选条目，非正权重忽略；不同条目即使具有同一 AEKey 也可同时选中，再合并数量。新 WeightedSampling 用 `nextLong(totalWeight)` 整数抽签，替代 PM 的 `nextDouble() * totalWeight` 转 long，避免大权重总和的浮点精度损失；保留权重效果，但同 seed 的逐次结果不要求与旧算法相同。输出容量按实际确定的 AEKey 和数量预留，固定保守检查也合并这些真实随机输出，不使用旧 largest-count 候选假设。

若 pickCount 覆盖所有有效候选，所有条目必选，直接聚合并按执行次数批量缩放；其余池保留 PM 的逐执行循环、独立随机盐和无放回抽样效果。非平凡池在大执行次数下仍有逐次工作成本，未来 planned preparation 必须分配工作预算，不能把当前同步实现宣称为已有预算调度。voidExcessOutputs 对实际选中输出继续生效，阻塞重试重算相同确定选择；不另建一套资源身份。

`RecipeRequirements.CHECKPOINT` 绑定 `CheckpointRequirement(time, childRequirement, scaleWithProcess)`。time=0 在 START 执行子需求完整 START/TICK/END，覆盖零时长配方；子 TICK 使用零宽的冻结 Tick 范围，不推进外层时钟。其他检查点在外层 TICK 的 scaled 进度或 unscaled 成功刻数跨过阈值时执行子生命周期。

子生命周期依次获取事务和令牌，Blocked/Failure 立即停止获取后续子阶段；全部令牌上交外层同一阶段统一应用、补偿和完成，子需求不自行结算资源。获取抛错时反向清理已返回的子事务和令牌，保留原异常及清理错误。子 TICK 在 acquisition、result、reservations、commit、complete、rollback 的全部回调都保留其冻结上下文，作用域结束恢复外层上下文。一次触发与阻塞重试由外层已提交时间线保证；本阶段恢复该时间线及子选择/解析状态，不保存每个历史触发刻的完整区间，也不提供取消或扫描状态。

## 模糊动态输入、解析锁与选择器

`FuzzyInputGroup` 保存有序 AEKey 候选和每执行数量，按声明顺序选首个足量 key；空候选组明确不可满足。`DynamicInputGroup` 保存 AEKey pattern、公开 NamedResourceMatcher、数量、可选 displayedCandidates、maxCandidates 和可选稳定全序比较器。ResourceMatcher 是真实可扩展的纯 predicate；NamedResourceMatcher 将稳定 ID 直接绑定到不可变匹配实现，不通过可随时替换的全局注册表查运行中进程。displayedCandidates 仅供展示，不过滤实际来源。

动态候选来自有效输入仓室快照中非零可用的 key，先按真实 context 去重并汇合。每个不同 key 都参与匹配，全扫描匹配结果后按 typeId、resourceId、完整 key NBT 的规范字节排序，再截取 maxCandidates；compound 字段名排序、list 保留顺序，叶子值用公开完整二进制编码，不以 hash、对象身份或可能省略内容的 NBT toString 排序。不同 key 序列化身份相同时拒绝，扩展类型可提供纯、稳定且区分所有不相等 key 的比较器。maxCandidates 只限制返回数量，不能作为总扫描/排序/NBT 内存预算；真实 planned 集成仍需另行分配工作预算。

内置 EXACT 用 AEKey.equals，ITEM_ONLY 保留 PM 的同 item + metadata、忽略 NBT。不能用 AEItemKey.dropSecondary 替代 ITEM_ONLY：该方法重新构造默认 ItemStack，也丢弃 metadata。自定义 matcher 和排序器须在配方/进程生命周期与未来恢复中保持行为稳定。

START 解析通过 `ResourceInputResolver` 计算保守 checkTimes，先计入固定输入，再逐个模糊组、动态组从可用量中扣除前面已经规划的数量，避免多组重复使用同一余额。0% 也按至少完整并行量选择和预检；已有解析锁保持原 key，不因库存顺序变化重新选择。`ResourceInputLocks` 是逻辑选择状态，不是库存互斥锁或数量令牌，键包含完整 RequirementPath、stage、FUZZY/DYNAMIC kind 和 groupIndex。

解析结果先保持 pending；只有实际资源获取全部成功后才安装逻辑锁，即使实际执行次数为 0 也保留成功选择。后续同阶段失败会同时撤销资源令牌和当前获取新增的锁；rollback 只移除自己安装且仍为预期 key 的项，不恢复整张映射或删除原有选择。snapshot/restore 提供排他生命周期边界的内存状态入口，本阶段已接入严格 NBT codec 和跨组件校验，已通过本阶段验证。

`RecipeRequirements.SELECTIVE` 绑定有序 `SelectiveRequirement`。每个候选 START 返回后捕获业务结果和 token 列表，Blocked/Failure 候选立即回滚其业务与令牌，继续尝试后项，不能对拒绝候选 commit。首个成功候选在 acquisition 中建立可见的 provisional selection，使同一复合生命周期随后 TICK/END 已能看到选择；全部候选不可用时建立 index=-1 的 disabled 成功选择，成功 START 后本次进程固定禁用，不因后来的材料变化重新选择。

选择状态按完整路径归进程所有；后续 TICK/END 只派发已选子需求，外层统一结算其令牌。START rollback 撤销本次选择、已获取子业务和该选择自己安装的属性 modifier/需求 overlay 层；START commit 先应用子业务，再按声明顺序调用 SelectiveModifierDescriptor 绑定的真实 SelectiveModifier.apply，并标记提交。SelectiveContext 暴露机器、进程、选择路径、候选索引及 modifier 名称/序号，属性和需求 overlay 效果通过有效 apply 作用域中的 tracked put 记录所有权。内部 modifier/overlay layer ID 长度分帧并包含路径、descriptor 序号和名称，属性包装仍委托外部 AttributeModifier 行为；直接修改其他状态不自动获得该接口的补偿能力。

END 获取/业务 commit 保留选择的属性和需求 overlay 效果，到全部业务成功后的 complete 才完成子事务并清理本选择的局部效果；被阻塞或业务异常的 END 不提前清效果。子 complete 抛错时仍尝试选择清理，外层与检查点完成链继续其余 complete 和资源/FE token 完成，再报告聚合错误；已跨终态边界不回滚或重放。本阶段选择状态已接入严格 NBT 保存恢复，取消和扫描仍未实现。

## 每进程需求 overlay

`api.recipe.overlay` 提供真实公共扩展接口 `RecipeOverlayApplier<C,D>`、不可变 `RecipeOverlayType<C,D>` 和 modifier/descriptor/context。Type 将稳定 ID、准确的 RecipeRequirementType 和纯 applier 直接绑定，不在执行中查询可替换的全局注册表。基础 payload、overlay data 和返回 payload 都必须不可变，applier 不得修改进程、世界或其他层，并须支持从基础 payload 重新组合任意剩余层。内置覆盖字段如下：

| 类型 | 覆盖字段 | 未指定字段与显式值 |
|---|---|---|
| `RESOURCE_IO` / `ResourceRequirementPatch` | inputs、inputsPerTick、outputsPerTick、outputs、voidExcessOutputs | null 继承此前层的字段，空列表明确清空；数量必须为正。chance、randomOutputs、fuzzyInputs、dynamicInputs 保留。物品、流体及其他 AEKey 共用此 patch。 |
| `ENERGY_RATES` / `EnergyRequirementPatch` | inputsPerTick、outputsPerTick、voidExcessOutputs | null 继承，0 明确关闭该刻速率，负数拒绝；START input 和 END output 保留。Boolean false 是有效覆盖值。 |

`RecipeOverlays` 是进程组件，按完整 RequirementPath 定位 canonical requirement，再按写入者自有的非空白 String layerId 保存层。层按首次插入顺序应用；同层替换保持原顺序，删除后重新插入视为新层。put/remove/rollback 在写时从 canonical payload 按剩余层重算 effective view，全部成功才发布新层与新 view；读取和执行热路径不重复运行 applier。撤销只恢复自己那一层的前次内容或移除自己新增的层，不用整 payload 快照覆盖其他写入者。

`RecipeRequirement.definition()` 保留 canonical 不可变定义，`withPayload()` 产生保留身份的不可变 effective view。外部系统 callback 可用收到的有效 view 写同一 target，无需另持原定义；嵌套 target 要传完整路径。仅复制相同 type/ID 另建的 counterfeit 定义仍被拒绝，不能混用同一路径的不同 canonical 定义。每次 acquire 先进入原路径再解析一次，已获取事务的 payload 参数冻结；后续成功写入只影响之后的获取。选择器和检查点的嵌套子需求同样通过该入口。自定义结构 overlay 必须保持 canonical child 引用和结构身份稳定，不能靠重建同 ID child 绕过身份校验。

put/remove 返回 `RecipeOverlays.Change`，写入立即可见，调用者必须最终 rollback 或 complete。同一层的交叠句柄按逆序撤销；rollback 校验当前仍是自身安装版本，再局部撤销并重算其他层。Change.complete 只放弃 undo 权，不删除已安装层；clear 仅允许在没有 pending 句柄的排他生命周期边界清除永久层。外部坏 applier 在撤销重算时抛错，原有效状态和 pending 句柄保留，清理链继续其他项并聚合 fault，不能据此假称清理成功。

`RecipeOverlayPipeline.acquire(process, descriptors)` 是宿主显式入口，按 descriptor 声明顺序调用 modifier，重复 descriptor 也按独立 ordinal 执行。RecipeOverlayContext 只在该次 apply 有效，tracked put 为 descriptor ID/ordinal/layerId 长度分帧命名并收集自有 Change；返回事务可并入宿主阶段，失败逆序补偿全部 tracked writes。`apply()` 立即完成同一事务并放弃 undo；new RecipeProcess 不自动调用 pipeline，宿主决定应用时机。直接经 process()/machine() 改其他状态不自动获得补偿权。

SelectiveContext 的 `putRequirementOverlay` 支持 root 或完整 nested target，tracked 层与属性共用选择器生命周期：START rollback 清自身层，END acquisition/commit 和拒绝重试保留效果，END complete 才移除自身层并清属性。START complete 将已安装的最终层版本转为 `RemovalClaim` 生命周期清理权，并结束阶段 Change undo；RemovalClaim 不阻塞稳定 snapshot，恢复时只认领现有层，END 移除仍校验预期版本。清理会尝试两类效果并聚合错误。本阶段已接入层 snapshot、严格 NBT codec 和选择所有权恢复，已通过本阶段验证；当前新格式不等于旧 PM 存档自动升级或 factory/workslot 宿主恢复。

## 严格进程保存恢复

`api.persistence` 提供 StrictNbt、NbtCodec、资源 key、属性 modifier 和局部属性编解码；`api.recipe.persistence` 的主入口是 `RecipeProcessNbt.builder(canonicalRecipe, revision)`。构建后绑定固定 canonical 配方、显式非空白 revision、资源/属性/组件/overlay codecs 和宿主 validators。encode 拒绝其他 canonical recipe 实例；decode 同时核验格式版本、recipe ID、revision、duration 及完整有序 RequirementPath graph。图由各需求系统的 children() 声明遍历，拒绝循环、重复路径和 effective view 充当 canonical child。

ID、graph 和 duration 只能证明结构身份与显式字段，不能证明任意 Java matcher、comparator、applier、modifier、系统或 codec 函数行为一致。宿主变更这些定义的兼容语义时必须更换 revision；也必须保持 canonical child 引用及纯 applier 的剩余层重组能力，不能仅以相同 ID 声称旧状态兼容。

NBT 读取要求确切类型，不使用数字强转或缺字段默认值。StrictNbt 校验列表声明类型和每个元素类型、规范 ID、有限浮点、Boolean byte 0/1，拒绝未知字段；组件、依赖、属性/modifier、选择/锁、层 ID/order 等唯一位置拒绝重复。所有已保存组件、缺失 dependent 的 pending 依赖类型、overlay data 和外部属性 modifier 都须显式 codec 覆盖，未知、歧义或不匹配时失败，不跳过条目。encode 输出及传给外部 decode/restore 的 mutable tags 在边界复制，避免共享调用者输入。

保存要求进程独占执行边界，并在编码前后检查：没有执行中的 tick/路径上下文、open requirement transaction、pending overlay stage Change 或未完成 selector START。抛异常的 tick、事务终结/清理故障会使进程拒绝保存，即使生命周期已推进也不能把该次调用当作认证保存点。预留令牌和半提交阶段不能跨存档恢复。状态保留 seed、ProbabilityTuning、double 绝对 progress、long successfulTicks、started、生命周期及结构化结果；属性只保存本地 base override/modifier，父机器属性和世界由宿主提供。

未启动进程中由宿主预种的解析锁属于不可保存的准备状态；本 codec 不持久化尚未成功完成 START 的半准备选择。

decode 返回新的未发布 RecipeProcess，恢复顺序如下：

1. 隔离输入，核验配方/revision/graph/duration，解析状态、局部属性、全部组件条目及有效依赖，拒绝未知/重复数据。
2. 按已保存的真实 assembly 顺序创建全部组件，再恢复有效依赖及尚未安装 dependent 的 pending 约束；不依赖当前 type 默认装配顺序替代旧状态。
3. 按依赖拓扑恢复各组件数据。selector load 额外依赖 overlay 先恢复，这条加载约束不改保存的 tick 依赖图。组件 factory/codec 不得改 assembly、修改旧进程/owner 或发布世界效果；恢复前后核对真实装配。
4. 运行 BuiltinProcessValidation、各组件 codec.validate 及宿主 validators，最后确认稳定边界，全部通过才返回。宿主之后决定何时将新进程加入工位。

ResourceKeyNbt 保存显式 AEKeyType ID 与 key data，先解析准确类型再调用该类型 loadKeyFromTag，拒绝未知类型、null 或类型不匹配结果，不补缺失内容为其他 key；资源条目数量用正 long，合法重复资源条目保留。overlay 保存 canonical target path、layerId、long order、准确 type 与 immutable data，全部核验并重算后替换新组件状态。选择器保存 committed/index/completed 和自有属性/overlay 引用，恢复只绑定已安装 modifier 的准确对象与现有层清理权，不重新跑 modifier、pipeline 或 START，不再次扣料。

BuiltinProcessValidation 校验可证明的内置范围：路径、START 锁类别、稳定资源组匹配、选择索引、未执行分支、已执行分支所需状态和效果唯一所有权。自定义 overlay 必须配置宿主 validator；曾改变资源组或 checkpoint/selector 结构的历史，不能靠当前 payload 或已移除的层推测，宿主须提供历史完整性依据。嵌套正阈值 checkpoint 只看到外层那一个触发 tick，完整历史区间没有保存；仅 time=0、可证明重合或互斥的阈值可强制推断，其余激活完整性由宿主验证。外部 wrapper/component 须声明 children、codec、恢复依赖和跨组件 validator，不能借内置 walker 宣称任意扩展已验证。

本阶段只实现严格单进程状态 codec 和新实例验收，已通过本阶段验证。factory/workslot 装配与宿主发布、扫描/取消、机器/世界恢复及真实 planned 尚未完成；PN owner/epoch、lease 和 ready authority 不从旧存档恢复，宿主须重新取得运行权限。当前格式也不自动读取或升级 PM 的旧 NBT。

## 资源存储纠正

用户明确要求：复用 AE2 的类型系统，不以 MEStorage 作为核心存储契约。新存储、事务和预留 API 应独立设计，以保证配方与并发需求；AE/Forge 库存接口仅用于外部适配。详见[存储重构约束](storage-redesign.md)。

独立资源后端已完成：`ResourceHatch` 返回 `ResourceStorage`，`SlottedResourceBuffer` 独立持有余额、输入数量和输出容量预留及补偿状态，AE2/Forge/GUI 库存接口只作适配。共享物理余额的视图使用同一 context，资源批结算按 context 顺序锁定。固定需求已接入同步整阶段结算，真实调度协议仍待迁移。

旧固定资源流程已移出公共配方模型，改名为内部 `ResourceTransferPlan` / `ResourceTransferRun`。它已通过当前 `ResourceHatch` 使用独立 `ResourceStorage`，不再依赖旧核心库存接口；但仍采用直接部分收取、FE 付款余额、输出重试和内存检查点流程，是核对资源守恒的迁移材料，**不是新需求执行器、阶段预留适配器或并发运行基线**。它不再从公开 RecipePool 选择配方。早期 `ResourceTransferNbt` 没有任何生产或测试调用方，也未接入机器保存；已移除这份固定资源格式的死代码。正式进程持久化采用本阶段 RecipeProcessNbt 的进程/组件 codec，不能用该固定资源原型代替。

不能只把直接库存操作包装成“支持 rollback”就接入阶段事务：源可能失效，输出可能已被其他机器取走，输入退回也可能没有容量。需求迁移须使用独立存储已有的预留、提交和补偿能力，继续明确与 FE、业务状态、宿主失效、通知及 PM/PN 调度的共同边界。

## 高级需求保留范围与迁移依赖

PM 的高级需求是必须继续迁移的行为，固定资源/FE 需求不能替代它们。完整源码映射和旧实现缺陷见[高级需求审计](migration-audit.md#高级需求能力与源码入口)。

- 物品保留 START 固定、模糊和动态匹配输入，END 固定和加权随机输出；流体还保留 TICK 固定输入/输出。概率结合有效并行数和进程属性，支持超过 100% 的概率、按阶段/刻隔离的确定性随机及保守容量检查。
- 模糊候选按声明顺序选择；动态物品输入保留外部 matcher、运行时枚举、候选上限和 JEI 展示候选。解析后的 AEKey、选择器候选和随机结果属于独立进程状态，不能写入共享配方负载。选择及输入获取的回滚必须一致，避免已回滚输入被既有选择标记跳过。
- 加权输出保留每个输出循环按权重无放回选取多个候选；`ignore_output_full` 保留部分产出并丢弃余量的效果。容量预留须覆盖实际确定的 key 和数量，不能只检查数量最大的候选而宣称覆盖所有随机结果。
- 选择性包装器保留按顺序选择首个 START 成功的子需求、全程沿用选择、无可用候选时本次进程禁用但不阻塞，以及外部 modifier 施加进程属性效果。阻塞重试不能遗漏候选输入，也不能提前删除仍应生效的 modifier。
- 需求 overlay 保留按类型和实例 ID 定位、每进程覆写有效需求而不改基础配方，以及外部 overlay applier 和 modifier 扩展。物品/流体 IO、流体每刻 IO、能源速率和满输出策略都是已有覆盖效果。
- 属性 root/overlay、数量倍率、单进程刻状态、可补偿的属性需求及资源 chance/随机输出/检查点、模糊动态/路径/逻辑解析锁/选择器/需求 overlay 已经接入并完成此前验证。本阶段严格进程 NBT、组件装配与自有效果恢复已接入，已通过本阶段验证；factory/workslot 宿主恢复仍未落地。planned 迁移还需要这些定义、调参和状态的明确投影及工作预算契约；PM 当前物品/流体 snapshot 拒绝非空 properties，固定消耗投影不能代表高级效果已支持 planned。

下一步补 factory/workslot 宿主恢复、扫描/取消与 planned 集成。实现时保留外部需求、matcher、modifier、codec/validator 和 overlay 扩展职责；这一排序不取消任何能力。

## 验证与未完成部分

新增行为用例覆盖外部组件的相位顺序、软依赖/替换/重装、异常终止；外部需求经过公开配方池注册后，覆盖同类型/跨类型顺序、部分获取后的整阶段回滚、重试、各阶段分派、进程状态隔离以及获取/回滚/提交错误的传播。旧资源守恒测试保留，不再冒充公开配方链路的验收。

此前公开配方池→MachineHatches→固定资源/FE 三阶段的行为验证覆盖输出阻塞及重试、别名去重、多个需求争用、单来源 FE 操作限额和明确允许的余量丢弃；阶段用例另覆盖业务提交异常补偿和终态通知故障，能源用例覆盖真实并发与跨资源结算。准确数量和 MCP 证据见[实施进度](implementation-status.md)。

属性与单进程生命周期、资源概率/随机输出/检查点、模糊动态/路径/解析锁/选择器、完成链及需求 overlay 已完成此前阶段的逻辑构建验证、逐类 IDEA MCP 全问题检查和 MCP build_project。本阶段严格进程持久化已通过 gradlew.bat spotlessApply build、逐类 IDEA MCP 全问题检查和 MCP build_project。测试范围、准确数量和报告见[实施进度](implementation-status.md)。这些证据不替代游戏、factory/workslot 宿主恢复或真实调度集成验证。

尚未完成：factory/workslot 与机器/世界宿主恢复、多工位扫描/取消、机器生命周期、真实并发/亲和性/planned 预留补偿与工作预算、结构宿主和 JEI。当前单进程 codec 不能据此宣称完整加工宿主、旧 PM 存档升级或多线程调度完成。所有剩余项都是必须继续迁移的功能，不是可选项。当前不启动 Minecraft 客户端或服务端。
