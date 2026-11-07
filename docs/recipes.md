# 配方与组件迁移

`MachineRecipe` 已恢复为按需求职责分组的公开定义。它保存 id、时长、配方组及 `RecipeRequirementType<?> → List<RecipeRequirement<?>>`，不再以固定输入、输出和 FE 字段限定配方。需求类型绑定自己的 `RecipeRequirementSystem<C>`；资源身份继续使用 AE2 的 AEKey/AEKeyType，不再建立 PMKey 的替代类型体系。

本模块是库，不注册正式机器或玩法配方。参数、内容和自定义需求由使用方提供。高级需求、严格进程 NBT、工作槽/整组恢复、基础 admission/首刻 preparation 和受限固定 TICK/END 预筛/资源索引已完成此前阶段验证，准确证据见[实施进度](implementation-status.md)。显式 CANCELLED、业务 FAILED 清理、宿主取消协调、内置效果所有权及进程 NBT Version 2/取消回执已接入并完成此前验证；机器/世界生命周期、普通并发调度与控制器结构接线也已接入，自动扫描组件见本文。PN 权限、正式 planned 与游戏运行证据仍待后续完成。

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

单进程循环和 `tick(owner, body)` 共用 `beginTick/finish` 会话：仍只捕获一次回调列表，Pre/Normal 全部成功后才进入业务 body；这两阶段的异常立即终止，尚未进入 body 时不执行 Post。进入 body 后，无论阻塞、业务异常或某个 Post 异常，都会依次执行全部已捕获的 Post 清理。业务原异常保持主异常，清理异常附加为 suppressed；没有业务异常时暴露首个 Post 异常并附加其余错误。

`MachineInstance.onSchedule()` 调用这一分派；`MachineSystem` 默认把阶段适配为 `MachineTickEvent.Pre/Normal/Post`，具体系统也可覆盖阶段方法。此入口已接入普通调度与控制器宿主，结构成型/退休门限统一限制配方扫描和工位处理；PN 权限、正式 planned 和游戏运行验证仍须继续迁移。

## 需求阶段契约与整阶段完成依赖

`RecipeStageExecutor` 按 PM `FactoryRecipeProcessorSystem.executeStage` 的实际行为迁移：

1. 依次调用各类型系统的 start、tick 或 end，收集事务及其 `reservations()`；取得的资源/FE 令牌只交给阶段协调器，单个需求不得自行完成它们。
2. 全部成功后，将全部真实 StorageContext 按固定顺序加锁，校验并 apply 全部令牌，然后按需求顺序执行业务 commit。commit 在此表示可补偿的业务应用，不是最终完成。
3. 业务 commit 全部成功后才依次 complete，释放业务与存储补偿权，再统一解锁和通知。complete 不允许业务拒绝或抛错；外部实现违反契约时，完成链仍尝试其余业务清理、子事务完成和每个资源/FE token 完成，再聚合错误。
4. 遇到 Blocked/Failure、令牌失效或获取/应用/业务异常时，锁内先逆序 rollback 业务事务，再逆序撤销资源/FE 令牌；包括返回阻塞/失败的那笔。异常保留原错误，继续其余补偿并聚合 suppressed。
5. 完成阶段的契约错误和完成后的通知错误在继续其余完成/通知后暴露，不把已经跨过完成边界的事务当作未提交状态再次回滚，也不让较早 complete 异常跳过后续本地清理。

这里统一了旧接口注释与执行器对 Blocked 的矛盾，以实际整阶段回滚行为为准。资源不足使用 Blocked，契约错误使用异常；捕获只用于异常清理，不作为常规流程。

统一完成边界已经接好，并覆盖跨需求类型、存储和业务状态的补偿。公开需求系统仍须覆盖部分获取产生的全部效果；业务 commit 失败时必须原子或保有完整撤销信息，默认 no-op complete 不会让旧的不可逆业务自动变成可补偿操作。外部回调只访问已加入批次的存储，不得持锁等待另一执行 lane。同步路径仍整段持锁；真实阶段现可经TickSession/Stage交给跨lane ResourceBatch，获取顺序、延迟post及准确恢复见[阶段会话](recipe-tick-sessions.md)，真实协调及原来源 lanes 接线见[配方协调](planned-recipe-coordinator.md)。

`RecipeProcess` 的单进程生命周期和进度现在作为最后一笔业务事务参与同一资源/FE 阶段：commit 写入新状态，rollback 恢复阶段前状态。资源完成后的通知异常直接暴露，但已提交的开始标记、成功刻数、进度或完成状态不会退回；该进程随后拒绝 tick，不重放已完成阶段。阶段 rollback 成功只证明本次补偿，不使发生异常的原进程恢复执行资格。需求 overlay 已接入同一获取入口，严格进程 codec 已完成此前验证；当前工作槽宿主复用这些稳定边界，整组恢复已通过此前工作槽阶段验证，基础多工位 admission 已验证，当前固定预筛/资源索引扩展已验证，当前显式取消与失败清理已接入，已通过本阶段构建与 MCP 检查。

## 已接入的固定需求和来源组件

`MachineHatches` 是不可变的已发现仓室绑定，使用方在机器的独占执行边界把它放入 `machine.components()`；包含公开 ResourceHatch、EnergyInput 和 EnergyOutput 列表。内置需求只查询这些公开接口，不强转内置方块实体。控制器结构刷新已在 owner 捕获、worker 计算、完整 join 后发布新绑定；发现必须在服务端完成，不能把该组件当作任意线程的世界查询器。入口见[世界接线](controller-integration.md)。

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

`RecipeParallelism.effective()` 使用进程属性 PROCESS_PARALLELISM，取 floor 并至少为 1；有限大值转换受 int 上限限制。资源和 FE 数量以该倍率缩放，正数量乘法溢出饱和到 Long.MAX_VALUE，非法负数量拒绝。MAX_CONCURRENT_PROCESSES、PROCESS_PARALLELISM、PROCESS_SPEED 是独立职责，ENERGY_EFFICIENCY 仅提供扩展 ID，当前内置需求未使用效率公式。PM 旧 PARALLELISM component/system 已是注释空壳，不恢复成第二套需求；当前扫描使用 resolved base 防父 modifier 重复应用，并探测真实 START，已通过此前扫描阶段构建与 MCP 检查，见“有界扫描与 START 探测”。

`RecipeRequirements.ATTRIBUTES` 绑定 `AttributeRequirement` / `AttributeRequirementSystem`，保留可配置 applyAtStart 和 removeAtEnd。START acquisition 立即施加 modifier，使同阶段后续需求读取更新后的倍率；内部 ID 由完整需求路径和属性 ID 确定，委托外部 modifier 的 operation/apply。START rollback 只删除自己成功添加的 modifier，不恢复整层快照，以免覆盖其他需求在业务 commit/rollback 中做的属性或 base 变化。END acquisition 和业务 commit 都保留 modifier，到全部业务 commit 成功后的 complete 才执行无失败局部清理；这既避免最终输出检查提前降倍率，也使被阻塞或异常的 END 不需要重排恢复 modifier。

`RecipeProcess.tick()` 是同步单进程入口，先调用 requirePersistenceBoundary，再检查终态：NEW 先执行 START，成功后检查完成条件；未达时长才执行 TICK，成功后再检查 END。零时长在 START 后直接 END，不获取 TICK。正常业务 Blocked 保持当前生命周期和进度，原进程下一次重试同一阶段；业务 Failure 在阶段 rollback 后进入 FAILED 并自动 abort。稳定且没有此前异常的 COMPLETED/FAILED/CANCELLED 再次 tick 只返回已有结果。执行中发生 RuntimeException/Error 后，原进程的后续 tick 在入口拒绝，即使已终态也不会推进或重放；open acquisition、递归和同进程并发同样拒绝。当前工作槽负责受检挂接、逐槽驱动和终态移除，扫描基础已验证，当前固定预筛/资源索引扩展已验证，当前显式取消与失败清理已接入，已通过本阶段构建与 MCP 检查。

进度采用 double 的绝对工作刻数，声明 durationTicks 也是工作刻数；每次成功 TICK 增加 max(0, PROCESS_SPEED)，long successfulTicks 只增加 1，提供不受速度缩放的确定性刻时钟。TICK 开始前只读一次速度并冻结 `Tick(progressBefore, progressAfter, indexBefore, indexAfter)`，需求可通过 tickContext 读取；阻塞时该次进度和成功刻数都不前进。资源每刻随机盐使用尚未推进的 successfulTicks，检查点使用冻结的 scaled/unscaled 前后范围；不把绝对 progress 当作 0..1 的完成比例。

## 概率、随机输出与检查点

`api.recipe.probability` 迁入 PM 的 ChanceMath、BinomialSampler、WeightedSampling 和可调 ProbabilityTuning。ResourceChance 默认 100%，可选属性乘数从进程属性读取，缺失为 1、负乘数按 0 处理；定义拒绝非有限或负百分比，乘数结果溢出也显式失败。ChanceMath 的执行次数为 `floor(C/100) * parallelism + Binomial(parallelism, fractional(C/100))`，支持超过 100%，正数量乘法和执行次数的正溢出饱和到 Long.MAX_VALUE。

BinomialSampler 保留 PM 的 gameplay 近似策略：概率大于 0.5 先用对称性；默认关闭小 n 精确 Bernoulli，均值小于 16 时用 Poisson 近似，其余用固定 seed 生成的 2048 项 Gaussian 表，再裁剪到 [0,n]。这不是所有范围的精确二项分布。ProbabilityTuning 提供不可变策略，默认 exactSmallBinomial=false、阈值 1024，可显式启用和调整阈值；默认发布入口不依赖 Forge 配置。RecipeProcess 在构造时捕获一份策略，资源系统显式传入，运行中变更全局策略不会使已有进程的阻塞重试重新抽样。RecipeProcessNbt 保存 seed、调参和进程状态，已通过此前严格进程阶段验证；这些字段不代替宿主兼容性与完整组件恢复校验。

资源系统按完整需求路径、阶段和成功刻数隔离随机盐。同一阶段的固定输入/输出共用执行次数；固定容量保留 PM 的 `max(parallelism, ceil(C/100) * parallelism)` 检查。检查量大于实际量时，用真实临时令牌做 preflight，覆盖同 key 与共享槽位的总需求；检查完释放全部临时令牌，再为实际数量重新取得阶段令牌。0% 仍保留完整并行量的固定容量检查，但不实际消耗/产出；不同需求可重复使用已释放的检查容量，实际执行量之间仍受前面真实预留约束。没有所需的有效来源/目标仍阻塞，preflight 不是永久占用或允许实际透支的证明。

RandomOutputPool 在 END 的每个执行循环按整数权重无放回选取最多 pickCount 个候选条目，非正权重忽略；不同条目即使具有同一 AEKey 也可同时选中，再合并数量。新 WeightedSampling 用 `nextLong(totalWeight)` 整数抽签，替代 PM 的 `nextDouble() * totalWeight` 转 long，避免大权重总和的浮点精度损失；保留权重效果，但同 seed 的逐次结果不要求与旧算法相同。输出容量按实际确定的 AEKey 和数量预留，固定保守检查也合并这些真实随机输出，不使用旧 largest-count 候选假设。

若 pickCount 覆盖所有有效候选，所有条目必选，直接聚合并按执行次数批量缩放；其余池保留 PM 的逐执行循环、独立随机盐和无放回抽样效果。非平凡池在大执行次数下仍有逐次工作成本，未来 planned preparation 必须分配工作预算，不能把当前同步实现宣称为已有预算调度。voidExcessOutputs 对实际选中输出继续生效，阻塞重试重算相同确定选择；不另建一套资源身份。

`RecipeRequirements.CHECKPOINT` 绑定 `CheckpointRequirement(time, childRequirement, scaleWithProcess)`。time=0 在 START 执行子需求完整 START/TICK/END，覆盖零时长配方；子 TICK 使用零宽的冻结 Tick 范围，不推进外层时钟。其他检查点在外层 TICK 的 scaled 进度或 unscaled 成功刻数跨过阈值时执行子生命周期。

子生命周期通过 RequirementContinuation 逐段获取；前段在持续 fence 内由原来源线程 apply/expose，业务可逆 stage 后才获取后段。Blocked/Failure 停止后续获取；全部令牌仍上交外层同一阶段统一补偿或完成，子需求不自行结算资源。额外 StorageContext 由系统 storageContexts 预声明。selector 候选自身包含 continuation 时，候选后续 Blocked/Failure 先局部逆补偿再改选；准确边界见[检查点审计](checkpoint-execution-audit.md)。获取抛错时反向清理已返回的子事务和令牌，保留原异常及清理错误。子 TICK 在 acquisition、result、reservations、commit、complete、rollback 的全部回调都保留其冻结上下文，作用域结束恢复外层上下文。一次触发与阻塞重试由外层已提交时间线保证；严格进程 codec 恢复该时间线及子选择/解析状态，不保存每个历史触发刻的完整区间，不保存扫描游标；本阶段进程取消终态与回执另见取消章节。

## 模糊动态输入、解析锁与选择器

`FuzzyInputGroup` 保存有序 AEKey 候选和每执行数量，按声明顺序选首个足量 key；空候选组明确不可满足。`DynamicInputGroup` 保存 AEKey pattern、公开 NamedResourceMatcher、数量、可选 displayedCandidates、maxCandidates 和可选稳定全序比较器。ResourceMatcher 是真实可扩展的纯 predicate；NamedResourceMatcher 将稳定 ID 直接绑定到不可变匹配实现，不通过可随时替换的全局注册表查运行中进程。displayedCandidates 仅供展示，不过滤实际来源。

动态候选来自有效输入仓室快照中非零可用的 key，先按真实 context 去重并汇合。每个不同 key 都参与匹配，全扫描匹配结果后按 typeId、resourceId、完整 key NBT 的规范字节排序，再截取 maxCandidates；compound 字段名排序、list 保留顺序，叶子值用公开完整二进制编码，不以 hash、对象身份或可能省略内容的 NBT toString 排序。不同 key 序列化身份相同时拒绝，扩展类型可提供纯、稳定且区分所有不相等 key 的比较器。maxCandidates 只限制返回数量，不能作为总扫描/排序/NBT 内存预算；真实 planned 集成仍需另行分配工作预算。

内置 EXACT 用 AEKey.equals，ITEM_ONLY 保留 PM 的同 item + metadata、忽略 NBT。不能用 AEItemKey.dropSecondary 替代 ITEM_ONLY：该方法重新构造默认 ItemStack，也丢弃 metadata。自定义 matcher 和排序器须在配方/进程生命周期与未来恢复中保持行为稳定。

START 解析通过 `ResourceInputResolver` 计算保守 checkTimes，先计入固定输入，再逐个模糊组、动态组从可用量中扣除前面已经规划的数量，避免多组重复使用同一余额。0% 也按至少完整并行量选择和预检；已有解析锁保持原 key，不因库存顺序变化重新选择。`ResourceInputLocks` 是逻辑选择状态，不是库存互斥锁或数量令牌，键包含完整 RequirementPath、stage、FUZZY/DYNAMIC kind 和 groupIndex。

解析结果先保持 pending；只有实际资源获取全部成功后才安装逻辑锁，即使实际执行次数为 0 也保留成功选择。后续同阶段失败会同时撤销资源令牌和当前获取新增的锁；rollback 只移除自己安装且仍为预期 key 的项，不恢复整张映射或删除原有选择。snapshot/restore 提供排他生命周期边界的内存状态入口，严格 NBT codec 和跨组件校验已通过此前严格进程阶段验证。

`RecipeRequirements.SELECTIVE` 绑定有序 `SelectiveRequirement`。平铺候选仍在 START 获取时依次捕获业务结果和 token，Blocked/Failure 先退款再改选，成功项建立 provisional selection。候选树可能返回 continuation 时，Selecting 通过公开 RequirementAlternatives 交给外层执行器：候选获取前建立原批次 Savepoint，完整嵌套 tail 成功后才安装选择和 modifier；中途 Blocked/Failure 局部逆补偿并确认后再试后项，不退款外层成功前缀。全部候选不可用时建立 index=-1 的 disabled 成功选择，成功 START 后本次进程固定禁用，不因后来的材料变化重新选择。

外部系统若可能返回 continuation，须用 continuedAcquisition 声明；usesContinuations 同时检查结构 children，使候选边界在任何效果执行前确定。此声明须覆盖动态 payload/overlay 的可能性，不获取资源或修改状态。RequirementAlternatives.startsAlternative 是纯查询，rejectAlternative 只在完整补偿确认后推进原游标，不获取资源或重放回调。故障与 UNKNOWN 保留原清理义务，不能当作普通业务拒绝继续选择。

选择状态按完整路径归进程所有；后续 TICK/END 只派发已选子需求，外层统一结算其令牌。START rollback 撤销本次选择、已获取子业务和该选择自己安装的属性 modifier/需求 overlay 层；START commit 先应用子业务，再按声明顺序调用 SelectiveModifierDescriptor 绑定的真实 SelectiveModifier.apply，并标记提交。SelectiveContext 暴露机器、进程、选择路径、候选索引及 modifier 名称/序号，属性和需求 overlay 效果通过有效 apply 作用域中的 tracked put 记录所有权。内部 modifier/overlay layer ID 长度分帧并包含路径、descriptor 序号和名称，属性包装仍委托外部 AttributeModifier 行为；直接修改其他状态不自动获得该接口的补偿能力。

END 获取/业务 commit 保留选择的属性和需求 overlay 效果，到全部业务成功后的 complete 才完成子事务并清理本选择的局部效果；被阻塞或业务异常的 END 不提前清效果。子 complete 抛错时仍尝试选择清理，外层与检查点完成链继续其余 complete 和资源/FE token 完成，再报告聚合错误；已跨终态边界不回滚或重放。选择状态已接入严格 NBT 保存恢复并完成此前验证，扫描基础已验证，当前固定预筛/资源索引扩展已验证，当前显式取消与失败清理已接入，已通过本阶段构建与 MCP 检查。

## 每进程需求 overlay

`api.recipe.overlay` 提供真实公共扩展接口 `RecipeOverlayApplier<C,D>`、不可变 `RecipeOverlayType<C,D>` 和 modifier/descriptor/context。Type 将稳定 ID、准确的 RecipeRequirementType 和纯 applier 直接绑定，不在执行中查询可替换的全局注册表。基础 payload、overlay data 和返回 payload 都必须不可变，applier 不得修改进程、世界或其他层，并须支持从基础 payload 重新组合任意剩余层。内置覆盖字段如下：

| 类型 | 覆盖字段 | 未指定字段与显式值 |
|---|---|---|
| `RESOURCE_IO` / `ResourceRequirementPatch` | inputs、inputsPerTick、outputsPerTick、outputs、voidExcessOutputs | null 继承此前层的字段，空列表明确清空；数量必须为正。chance、randomOutputs、fuzzyInputs、dynamicInputs 保留。物品、流体及其他 AEKey 共用此 patch。 |
| `ENERGY_RATES` / `EnergyRequirementPatch` | inputsPerTick、outputsPerTick、voidExcessOutputs | null 继承，0 明确关闭该刻速率，负数拒绝；START input 和 END output 保留。Boolean false 是有效覆盖值。 |

`RecipeOverlays` 是进程组件，按完整 RequirementPath 定位 canonical requirement，再按写入者自有的非空白 String layerId 保存层。层按首次插入顺序应用；同层替换保持原顺序，删除后重新插入视为新层。put/remove/rollback 在写时从 canonical payload 按剩余层重算 effective view，全部成功才发布新层与新 view；读取和执行热路径不重复运行 applier。撤销只恢复自己那一层的前次内容或移除自己新增的层，不用整 payload 快照覆盖其他写入者。

`RecipeRequirement.definition()` 保留 canonical 不可变定义，`withPayload()` 产生保留身份的不可变 effective view。外部系统 callback 可用收到的有效 view 写同一 target，无需另持原定义；嵌套 target 要传完整路径。仅复制相同 type/ID 另建的 counterfeit 定义仍被拒绝，不能混用同一路径的不同 canonical 定义。每次 acquire 先进入原路径再解析一次，已获取事务的 payload 参数冻结；后续成功写入只影响之后的获取。选择器和检查点的嵌套子需求同样通过该入口。RecipeOverlays 在每层 apply 后，将系统 children() 返回的直接子需求与原 canonical payload 比较，要求数量及同序 child.definition() 对象身份不变；重建同 ID child 或重排 selector 分支都会在发布前拒绝。保留 canonical definition 身份的 effective child view 允许使用。此检查只覆盖直接 children，不递归证明任意外部 payload 内部变化或副作用；本阶段新增校验已通过本阶段构建与 MCP 检查。

put/remove 返回 `RecipeOverlays.Change`，写入立即可见，调用者必须最终 rollback 或 complete。同一层的交叠句柄按逆序撤销；rollback 校验当前仍是自身安装版本，再局部撤销并重算其他层。Change.complete 只放弃 undo 权，不删除已安装层；clear 仅允许在没有 pending 句柄的排他生命周期边界清除永久层。外部坏 applier 在撤销重算时抛错，原有效状态和 pending 句柄保留，清理链继续其他项并聚合 fault，不能据此假称清理成功。

`RecipeOverlayPipeline.acquire(process, descriptors)` 是宿主显式入口，按 descriptor 声明顺序调用 modifier，重复 descriptor 也按独立 ordinal 执行。RecipeOverlayContext 只在该次 apply 有效，tracked put 为 descriptor ID/ordinal/layerId 长度分帧命名并收集自有 Change；返回事务可并入宿主阶段，失败逆序补偿全部 tracked writes。`apply()` 立即完成同一事务并放弃 undo；new RecipeProcess 不自动调用 pipeline，宿主决定应用时机。直接经 process()/machine() 改其他状态不自动获得补偿权。

SelectiveContext 的 `putRequirementOverlay` 支持 root 或完整 nested target，tracked 层与属性共用选择器生命周期：START rollback 清自身层，END acquisition/commit 和拒绝重试保留效果，END complete 才移除自身层并清属性。START complete 将已安装的最终层版本转为 `RemovalClaim` 生命周期清理权，并结束阶段 Change undo；RemovalClaim 不阻塞稳定 snapshot，恢复时只认领现有层，END 移除仍校验预期版本。清理会尝试两类效果并聚合错误。层 snapshot、严格 NBT codec 和选择所有权恢复已通过此前严格进程阶段验证；当前工作槽整组恢复复用该 codec，新格式不提供旧 PM 存档自动升级。

## 严格进程保存恢复

`api.persistence` 提供 StrictNbt、NbtCodec、资源 key、属性 modifier 和局部属性编解码；`api.recipe.persistence` 的主入口是 `RecipeProcessNbt.builder(canonicalRecipe, revision)`。构建后绑定固定 canonical 配方、显式非空白 revision、资源/属性/组件/overlay codecs 和宿主 validators。encode 拒绝其他 canonical recipe 实例；decode 同时核验格式版本、recipe ID、revision、duration 及完整有序 RequirementPath graph。图由各需求系统的 children() 声明遍历，拒绝循环、重复路径和 effective view 充当 canonical child。

ID、graph 和 duration 只能证明结构身份与显式字段，不能证明任意 Java matcher、comparator、applier、modifier、系统或 codec 函数行为一致。宿主变更这些定义的兼容语义时必须更换 revision；也必须保持结构定义稳定及纯 applier 的剩余层重组能力，不能仅以相同 ID 声称旧状态兼容。每层 overlay 的直接 children 校验同样用于 put/remove/undo 和恢复重算，但不能代替任意外部行为的兼容性证明。

NBT 读取要求确切类型，不使用数字强转或缺字段默认值。StrictNbt 校验列表声明类型和每个元素类型、规范 ID、有限浮点、Boolean byte 0/1，拒绝未知字段；组件、依赖、属性/modifier、选择/锁、层 ID/order 等唯一位置拒绝重复。所有已保存组件、缺失 dependent 的 pending 依赖类型、overlay data 和外部属性 modifier 都须显式 codec 覆盖，未知、歧义或不匹配时失败，不跳过条目。encode 输出及传给外部 decode/restore 的 mutable tags 在边界复制，避免共享调用者输入。

保存要求进程独占执行边界，并在编码前后检查：没有执行中的 tick/路径上下文、open requirement transaction、pending overlay stage Change 或未完成 selector START。抛异常的 tick、事务终结/清理故障会使进程拒绝保存及后续 tick，即使生命周期已推进或已终态也不能把该次调用当作认证保存点。首次异常前后完成的阶段补偿或通知清理，不代表允许原进程重试；正常业务 Blocked 仍可重试。预留令牌和半提交阶段不能跨存档恢复。状态保留 seed、ProbabilityTuning、double 绝对 progress、long successfulTicks、started、生命周期及结构化结果；属性只保存本地 base override/modifier，父机器属性和世界由宿主提供。

未启动进程中由宿主预种的解析锁属于不可保存的准备状态；本 codec 不持久化尚未成功完成 START 的半准备选择。

decode 返回新的未发布 RecipeProcess，恢复顺序如下：

1. 隔离输入，核验配方/revision/graph/duration，解析状态、局部属性、全部组件条目及有效依赖，拒绝未知/重复数据。
2. 按已保存的真实 assembly 顺序创建全部组件，再恢复有效依赖及尚未安装 dependent 的 pending 约束；不依赖当前 type 默认装配顺序替代旧状态。
3. 按依赖拓扑恢复各组件数据。selector load 额外依赖 overlay 先恢复，这条加载约束不改保存的 tick 依赖图。组件 factory/codec 不得改 assembly、修改旧进程/owner 或发布世界效果；恢复前后核对真实装配。
4. 运行 BuiltinProcessValidation、各组件 codec.validate 及宿主 validators，最后确认稳定边界，全部通过才返回。宿主之后决定何时将新进程加入工位。

ResourceKeyNbt 保存显式 AEKeyType ID 与 key data，先解析准确类型再调用该类型 loadKeyFromTag，拒绝未知类型、null 或类型不匹配结果，不补缺失内容为其他 key；资源条目数量用正 long，合法重复资源条目保留。overlay 保存 canonical target path、layerId、long order、准确 type 与 immutable data，全部核验并重算后替换新组件状态。选择器保存 committed/index/completed 和自有属性/overlay 引用，恢复只绑定已安装 modifier 的准确对象与现有层清理权，不重新跑 modifier、pipeline 或 START，不再次扣料。

BuiltinProcessValidation 校验可证明的内置范围：路径、START 锁类别、稳定资源组匹配、选择索引、未执行分支、已执行分支所需状态和效果唯一所有权。自定义 overlay 必须配置宿主 validator；曾改变资源组或 checkpoint/selector 结构的历史，不能靠当前 payload 或已移除的层推测，宿主须提供历史完整性依据。嵌套正阈值 checkpoint 只看到外层那一个触发 tick，完整历史区间没有保存；仅 time=0、可证明重合或互斥的阈值可强制推断，其余激活完整性由宿主验证。外部 wrapper/component 须声明 children、codec、恢复依赖和跨组件 validator，不能借内置 walker 宣称任意扩展已验证。

严格单进程状态 codec 和新实例验收已通过此前阶段验证。当前工作槽整组恢复在该入口之后统一挂接，见下一节，已通过此前工作槽阶段验证；扫描基础、固定预筛/资源索引扩展、显式取消与失败清理已完成此前验证。机器装配与控制器 NBT 恢复已接，实机恢复验证和正式 planned 尚未完成。PN owner/epoch、lease 和 ready authority 不从旧存档恢复，宿主须重新取得运行权限。当前格式也不自动读取或升级 PM 的旧 NBT。

## 工作槽宿主、驱动与整组恢复

当前 [MachineWorkSlots](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/workslot/MachineWorkSlots.java)、[WorkSlotDefinition](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/workslot/WorkSlotDefinition.java)、[WorkSlot](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/workslot/WorkSlot.java)、[RecipeExecutor](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/process/RecipeExecutor.java)、[WorkSlotProcessingSystem](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/machine/WorkSlotProcessingSystem.java) 与 [MachineWorkSlotsNbt](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/workslot/MachineWorkSlotsNbt.java) 已接入并通过此前工作槽阶段逻辑构建、逐类 IDEA MCP 全问题检查及 MCP build_project。准确范围、数量和报告由[实施进度](implementation-status.md)维护；未进行游戏运行验证。

工作槽按声明顺序建立，不允许重名；空布局建立隐式 `main`，使用传入的机器默认配方组。定义中的组保持不可变声明顺序，`isCore` 与 `restartPolicy` 保存到 runtime/NBT；两者当前只是元数据。默认 `LOCKED_RETRY` 保持阻塞进程，`RESCAN` 的冷却、丢弃与重新扫描尚未实现；旧 PM 也只声明该策略，没有运行路径，不能据枚举名称宣称已支持重扫。

```java
var host = new MachineWorkSlots(machine, definitions, defaultGroups, RecipeProcess::tick);
machine.components().put(MachineWorkSlots.TYPE, host);
var process = new RecipeProcess(machine, recipe, seed);
boolean admitted = host.startIn("main", process); // 只挂接，不执行 START
```

`slots()` 提供有序只读列表，WorkSlot 只公开读取，修改统一经过宿主；`activeProcesses()` 返回独立只读快照。`start` 按顺序选择首个兼容空槽，`startIn` 指定槽位。两者要求进程处于稳定边界、owner 是同一机器、尚未挂接且非终态；未知槽名抛错，已占用、当前容量满或配方组不兼容返回 false。`WorkSlotDefinition.accepts` 统一直接 admission、扫描和恢复的兼容规则：非空槽位组与配方组须有交集，空白名单接受全部配方（包括无组配方）。空布局的隐式 `main` 若机器默认组也为空，使用同一全局语义。挂接同时设置 `RecipeProcess.host()`，同一进程不能被另一宿主或第二槽重复拥有。

`capacity()` 从机器当前 `MAX_CONCURRENT_PROCESSES` 读取非负整数并裁剪到槽位总数，只影响新工作进入。属性降低不会驱逐已提交工作，所有旧槽继续 tick，恢复也允许超过当前进入限额；结构容量仍要求保存的槽数、有序名称及完整定义与当前布局精确相等。这个数量与进程 IO 倍率、加工速度分别处理。

`MachineWorkSlots.TYPE` 安装普通组件系统 WorkSlotProcessingSystem，onTick 先核对 component.owner 与 machine，并以 `machine.canProcessRecipes()` 检查成型/退休门限，再调用 `tickProcesses()`。每个已占用非终态槽只调用所选同步 RecipeExecutor 一次，默认执行 `RecipeProcess.tick`；宿主随后读取结果，不再推进生命周期。外部 driver 必须在返回前完成进程回调及资源结算。PM 原可变 executor 列表后再由处理器推进，接口存在但本轮源码检索未发现任何注册实现；新单 driver 消除扩展实现可能触发的双推进风险，额外处理仍通过公开组件系统扩展。普通调度、世界宿主和结构门限已接；PN 运行权限与正式 planned 仍须继续迁移。

Blocked 保留同一进程及选择/进度，下次重试；正常 COMPLETED/FAILED/CANCELLED 解除双向挂接并腾空槽，保留结构化 ProcessResult，业务 Failure 不阻止其他槽执行。恢复出的终态进程下一 tick 直接解除挂接，不调用 executor。RuntimeException/Error 立即终止本次遍历并使宿主 faulted，后续 tick、挂接/解除和 snapshot/NBT 保存均拒绝；不能在部分槽已完成后重跑整批。当前 `detach(process)` 要求稳定边界，仅解除挂接并交还生命周期所有权，不执行 END、不退款，**不是取消**。本阶段 host.cancel/cancelAll 另行协调显式终态清理；faulted host 仍可清理其他稳定进程，但不会因此恢复 tick/save 权限，见“显式取消与失败清理”。

`MachineWorkSlotsNbt(revision, recipeBindings)` 绑定非空白布局 revision 与唯一 recipe ID → RecipeProcessNbt。Version 1 保存有序槽的 Name、Groups、Core、RestartPolicy、结构化 Result 和可选 Process。codec 使用当前宿主布局与 executor，revision 同时承担其兼容行为；不从存档恢复调度器或执行权限。encode 在前后 snapshot 核对宿主未被外部 codec 改动。

恢复先复制输入并校验 exact NBT、版本/revision、槽数/顺序/完整定义、重复名称/组以及所有 recipe binding，再把每个进程完整 decode 成新实例。整组全部通过并确认旧宿主未变化后，才调用 `host.restore`；该方法再次验证 owner、组、按对象身份唯一的进程、结果一致性、稳定边界与跨宿主挂接，再一起解除旧挂接并建立新双向挂接。最后条目失败也不会先清空或部分发布旧宿主。外部 codec/factory/validator 必须遵守无旧宿主/世界副作用的契约，不能靠事后 snapshot 撤销任意外部写入。机器/世界恢复、旧 PM 格式升级及 PN epoch/lease/ready authority 恢复不在此格式内；运行权仍须重新发现并取得。

旧 planned 的 [createPlannedSlots](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/planned/RegistryBackedMachinePlannedExecution.kt:142) 另造 [PlannedWorkSlot](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/planned/RegistryBackedMachinePlannedExecution.kt:245)，其 process 永远 null、非 null setter 抛错，仅消费 name/groups 与槽位 identity。当前 WorkSlot 是传统进程宿主实际状态类，无需为复现这种假槽机械增加接口。未来 planned context 应复用稳定 WorkSlotDefinition 等元数据并提供真实 planned 状态及所有权，不能假称该状态承载 RecipeProcess；真实 planned 的功能、扩展与多线程能力仍完整保留待迁移。

## 有界扫描与 START 探测

当前 [RecipeCatalog](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeCatalog.java)、[RecipeScanner](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanner.java)、[RecipeCandidateIndex](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeCandidateIndex.java)、[RecipeProcessFactory](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeProcessFactory.java)、[RecipeParallelismConstraint](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeParallelismConstraint.java)、[ScanLimits](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/ScanLimits.java) 与 [RecipeScanningSystem](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/machine/RecipeScanningSystem.java) 已接入，已通过此前扫描阶段构建与 MCP 检查。旧 PM 的组、索引与非单调风险及当前修正见[扫描与索引审计](migration-audit.md#扫描索引与并行探测审计)。

RecipeCatalog 冻结配方池/声明顺序和 canonical 配方实例，相同 ID 的不同对象拒绝；重复使用同一实例只收录一次。`RecipeRegistry.freeze()` 先完整建立池快照及共享 catalog，再通过一个 volatile Frozen 值一起发布；catalog 构造失败时池和目录均不可见、注册仍开放。registry 的池按 ID 排序，池内保留配方声明优先级；独立构造 catalog 则按传入池顺序。

查询组为空时取整个 canonical 目录，包括无组配方；非空时取组 union，再按目录顺序返回。每个外部 index 都获得同一不可变完整 scope 和同一组集合；空 groups 同样表示全局，不得自行解释为空候选。index 返回 null 不作筛选、empty 是硬空集，其余结果按 canonical 实例交集，不改变目录顺序。伪造、外部或越组候选拒绝。index 必须纯，且保留没有自身需求类型的通用候选，不能把 energy-only 配方从 item index 丢掉。ResourceRecipeIndex 使用同一空组语义；显式 Footprint 与外部索引完整性仍由调用方负责，见下一节。

RecipeScanner 绑定一个 MachineWorkSlots，在独占执行边界按目录顺序扫描，选择首个空且组兼容槽。若任一槽为空白名单，扫描 scope 为全局；否则 scope 为全部槽组的 union，具体 admission 仍按所选槽的兼容规则。当前容量满返回 FULL，同 recipe ID 已在任一槽运行则跳过。每次 scan 最多一次成功 admission，调用方每个机器 tick 最多调用一次。结果包含 ADMITTED/YIELDED/EXHAUSTED/FULL/PAUSED 和本次 candidate/probe 数，达到限制让出，退休后完整回滚返回 PAUSED；扫描异常标记 scanner faulted，后续 scan 拒绝。

ScanLimits 只限制每次检查的候选数和并行 probe 次数。游标和候选列表/搜索边界可跨调用保留，搜索固定 seed 与 ProbabilityTuning，每个 amount 都经外部 factory 构造 fresh 未公开 NEW 进程，核对 machine、canonical recipe、seed/tuning、未挂接和稳定边界；不跨 tick 保留资源 reservation、候选进程或运行权限。机器父并行上限改变会重建当前搜索。一次 pass 逐 probe 观察活动存储，候选/index 快照与上界不代表跨 tick 原子视图，也不保证动态存储下的全局最大值。

默认对 base amount 从上界向 1 逐一递减探测，支持非单调需求。只有显式 `Predicate<MachineRecipe> monotonicStart` 保证 factory 局部 modifier、全部 preparation、约束、需求和存储组成的**完整 probe** 成功集为连续前缀时才二分，不能根据 type ID 自动推断。搜索界使用 long，amount=Integer.MAX_VALUE 时加减不会回绕；二分得到的 best 在 admission 前重新构造并完整探测，旧成功不等于预留权。重探测失败则下次重新观察，真实 START 始终具有最终决定权。

非单调反例继续成立：A=5、B=8，模糊首组候选 [A,B] 每次需 4，第二组仅 A 每次需 2；k=1 首组取 A4 后第二组失败，k=2 首组改取 B8 后第二组 A4 成功。因此默认穷举，显式单调保证覆盖整个探测，不能只证明某个固定 IO 子检查单调。

候选的 PROCESS_PARALLELISM 使用 `MachineAttributes.setResolvedBase(amount)`：amount 包含机器父层已经计算的基线，屏蔽祖先 modifier 的二次应用，仍保留 factory 安装的本地 modifier。amount 不包含本地 modifier，不能反解任意外部函数；实际有效资源数量继续由 RecipeParallelism 读取本地运算结果。普通 setBase 恢复继承 modifier，clearBase 重新显露父层；snapshot/NBT 保存 InheritModifiers，局部属性格式 Version 2 精确读取该 Boolean，拒绝缺 base 却屏蔽继承的状态，旧 Version 1 不自动升级。该语义已通过此前扫描阶段验证。

whole-recipe 约束为纯检查/安全上界，逐个检查整个配方，不按 requirement type 跳过未知实现；上界不得排除任何可行 base amount，必须汇总共享余额/容量。约束通过也必须调用 [RecipeStageExecutor.probeStart](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/recipe/RecipeStageExecutor.java) 获取真实 START 需求事务与资源/FE token，再将全部获取逆序 rollback，包括拒绝项；平铺需求不调用 commit/complete，也不执行 TICK/END；continued START 的内部子阶段会可逆 stage，以准确观察前序材料/空间，最后全部 rollback，永不 complete 或保留生命周期推进。异常继续清理并使候选不能保存，scanner 故障停止后续运行。所有回调须保留完整 undo；pure prepare 仍待后续接入。

**自定义组件投影：** 当前 [RecipeScanPreparation](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanPreparation.java) 与 [RecipeScanProbes](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanProbes.java) 已接入并补充行为用例，已通过此前扫描阶段构建与 MCP 检查。scanner 的八参数构造最后接受有序 preparations，原七参数构造默认空列表。每次完整 probe 先按顺序 prepare，再运行全部约束和真实 START acquisition/rollback；所有结果均逆序 rollback preparation，包括拒绝项。通过后 admission 保留投影前状态，即 factory 初始化与 scanner resolved base，真实首 tick 的组件回调只正常执行一次，不重复安装投影效果。

preparation 只允许可逆进程局部状态，无世界/旧 owner 副作用，返回事务的 reservations 必须为空；commit/complete 永不调用。partial acquisition 仍须遵守正常可逆事务契约。若违约暴露 resource/FE token，协调器先完整释放并清理各层，再报告契约异常；一层 rollback 抛错仍继续其他层，聚合错误并禁止候选保存。prepare 返回 Blocked/Failure 时不执行后续准备或检查，但仍撤销自身及已获取层；真实 START 回滚完成后才撤销准备状态。

probe 不直接调用进程组件 Pre/Normal/Post；真实 RecipeProcess.tick 在 START 前会执行 Pre/Normal。空 preparation 的默认探测仍要求 factory 初始化后即可确定首次 START 行为；组件回调会改变属性、overlay 或需求状态时，外部须提供与首次回调等价的确定性、可逆局部 preparation，否则探测可能假阳性/假阴性。该接口提供显式支持入口，不能自动验证任意自定义 factory 的等价性；普通回调没有自动补偿，不能直接拿组件 tick 模拟。actual START 始终具有最终决定权，世界副作用或不可逆首刻行为仍不属于这种局部投影契约，相关扩展与真实 planned 职责继续保留。

RecipeScanningSystem 显式 `runBefore` WorkSlotProcessingSystem，onTick 核对 scanner owner，并检查 `machine.canProcessRecipes()` 与 ready Predicate。ready 只决定本次 admission 是否扫描，不暂停既有工位，也不提供 PN 权限或实现 RESCAN。当前 FixedIoScanConstraint 补充受限固定 IO 的独立 TICK/END 预筛，已验证；START 或各预筛阶段成功均不能证明完整首 tick 或后续生命周期可行。组件声明及恢复见下一节。

当前 candidate/probe 计数不限制目录 union/sort、index lookup、factory、constraint、动态全扫描/排序/key NBT、随机逐次抽样和嵌套生命周期内部工作，也不是 wall-clock/deadline 预算。数量倍率缩放资源，速度改变绝对进度，成功刻数仍每次成功 TICK 加 1；真实 planned 仍须两次 join、稳定 affinity lane 与 PN owner/epoch/向量预算，不能持锁跨 lane 等待。

## 自动扫描组件声明与恢复

[RecipeScanning](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanning.java) 提供显式组件 factory。只声明 `MachineWorkSlots.TYPE` 仍是手动挂接模式，不会自动扫描。需要自动 admission 时，下游先声明工位，再声明 scanner：

```java
var scanner = RecipeScanning.component(scannerId, RecipeRegistry.INSTANCE::catalog,
        new ScanLimits(candidateBudget, probeBudget));
var type = MachineType.builder(typeId, displayName, structureId)
        .component(MachineWorkSlots.TYPE)
        .component(scanner)
        .build();
var assembly = MachineAssembly.builder(type, revision)
        .workSlots(workSlotCodec)
        .recreated(scanner)
        .build();
```

catalog supplier 到机器装配时才解析，必须处于完整配方 freeze 之后。组件 factory 按实际声明/保存顺序创建，因此工位必须先安装；系统依赖只约束执行顺序，不是 factory 依赖。缺失或后置工位、外部 factory 返回 null 或绑定另一宿主都会拒绝装配。系统排序确保本 tick admission 后再驱动工位，首次真实 START/TICK 可在同一 scheduled tick 执行。

另一重载接受 `Function<MachineWorkSlots, RecipeScanner>`、ScanLimits 和 ready Predicate，可提供自定义 process factory、constraints、indexes、单调断言、seed 和 preparations；所有既有 scanner 扩展合同继续适用。readiness 关闭只停止新 admission，已运行工位仍受自身 lifecycle/结构门限驱动，不能将该 predicate 当作 PN 对全部工作授权的实现。

工位使用既有 `MachineWorkSlotsNbt` codec 保存进程，scanner 用 `recreated`，不保存游标、候选、搜索状态或临时预留。恢复先创建新的工位宿主和 scanner，再恢复完整进程；新 scanner 指向新宿主，搜索从目录开头重新开始，已保存的 START 不重放。`AutomaticRecipeProcessingTest` 覆盖显式/仅工位声明、同 tick admission/处理、readiness、factory 宿主与顺序拒绝、晚解析 catalog、freeze 失败不半发布以及恢复进度/新扫描游标；本阶段最终构建/MCP 证据由[实施进度](implementation-status.md)记录。

## 固定 IO 预筛与声明资源索引

当前 [FixedIoScanConstraint](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/FixedIoScanConstraint.java)、[RecipeStageExecutor.probeStage](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/recipe/RecipeStageExecutor.java)、[ResourceRecipeIndex](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/ResourceRecipeIndex.java) 与 RecipeCatalog.recipes() 已接入，已通过此前固定预筛/索引阶段构建与 MCP 检查。此前固定预筛/索引阶段 240 项逻辑测试通过、182 文件零诊断，报告 `build/reports/idea/fixed-io-index-final.json`；准确进度见[实施进度](implementation-status.md)。

默认三参数 RecipeScanner 安装 FixedIoScanConstraint；显式七/八参数构造按调用方提供的 constraints/indexes 配置，不自动补该约束或资源索引。预筛只接受 flat 内置 ResourceRequirement/EnergyRequirement；资源 chance 必须精确 ALWAYS（100%、无乘数），且无随机输出、模糊/动态输入，进程已装组件的 type 不带任何 system 回调。包装器、外部需求、高级资源或组件回调可能改变后续行为时，整个固定预筛返回无拒绝意见，仍保留真实 START 和运行期处理。已有 overlay 先解析 effective payload，不按旧 canonical 数量预筛。

支持范围内，TICK 输入/输出和 END 输出各用一次真实整阶段 acquisition，所有资源/FE 预留共享正常 batch 余额/容量，随后全 rollback；不 commit/complete，也不调用组件回调或前一阶段。零时长不探测 TICK，无该阶段 IO 不作额外探测；输出 voidExcessOutputs 遵循实际需求系统。`probeStage(TICK)` 为 acquisition 与 rollback 冻结同一个首刻 Tick 上下文，但不推进状态；它不是从 START 提交开始的生命周期模拟，外部调用者负责先建立该孤立阶段所需状态。

同一物理 StorageContext 的输入可能释放后续输出空间，因此固定预筛以对象身份识别输入/输出共享 context，资源不按 AEKeyType 分开判断，跨类型也可能共用槽容量。存在 START 输入可能释放 TICK 输出空间时跳过**整 TICK 阶段**；START 或有效 TICK 输入可能释放 END 输出空间时跳过**整 END 阶段**，资源或 FE 任一共享场景足以跳过该阶段，其余阶段照常检查。普通混合仓的输入/输出为独立 buffer 时不跳过。这个保守跳过避免按尚未释放的当前容量误拒绝，不宣称已投影未来容量。

各阶段只独立观察当前余额：START 每次需2、TICK 每次需3，当前量8、k=2 时各自探测需4/6 都能通过，真正 START 消耗4后首 TICK 仍会 Blocked。该预筛保留 PM 的当前量/容量过滤效果并修正整阶段共享与回滚，不保证完整首 tick。高级需求、外部回调、跨阶段联合模拟与内部工作预算仍需要自己的明确契约。

ResourceRecipeIndex 构建 AEKey → recipe 的冻结倒排 postings，按调用方显式 `Footprint(canonicalRecipe, mandatoryInputs)` 的必要下界合同筛选。每个 footprint 必须声明**任何成功 START 前**公开输入快照中必然已存在的 key/最小数量，覆盖全部 seed、并行数、概率策略、局部 modifier、factory、preparation 和 overlay；较早 START 回调不能制造声明 key。可能变小、可选、替换或消失的输入应省略 footprint，相关假设改变须重建或禁用索引。未声明或空输入声明的配方始终作为通用候选保留，包括 energy-only、无资源、动态/选择器和外部覆写配方。

`ResourceRecipeIndex.forFixedInputs(catalog, stableInputs)` 在调用方 Predicate 明确保证完整 Footprint 合同后提取固定 START 输入。仅支持 flat canonical 内置 Resource/Energy，资源 chance 为100%且 attribute=null，无模糊/动态组；selector/checkpoint/属性需求/自定义类型等不受支持定义即使 Predicate=true 也保持通用候选。END 随机输出不影响声明的 START 必需 key，因此允许；TICK/END IO 不进入此索引。Predicate 仍须覆盖 factory、preparation 与 overlay 的所有变化，类型识别本身不构成稳定输入证明。

footprint 绑定目录 canonical 对象，外部/重复 recipe 拒绝；相同完整 AEKey 的正数量汇总饱和到 Long.MAX_VALUE，非正量拒绝，不同 AEKeyType/NBT 身份分别保留。饱和值是必要下界，合法全配方跨需求总量超过 long 不会因启用索引而拒绝注册，也不据饱和结果证明完整数量可行。lookup 在给定 group/scope 内匹配每个必需 key 的全部下界并恢复目录顺序，始终提供非 null 意见。只读有效输入仓室 snapshot；相同 context 每 key 取最大可用量，跨 context 饱和汇总，避免别名重复计数；snapshot context 不一致或负量显式失败。没有输入仓室也保留所有通用候选。索引不获取预留、不扣资源，不证明后续 START 成功、最大并行数或执行工作量。

## 显式取消与失败清理

当前 [RecipeProcess.cancel](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/process/RecipeProcess.java)、[RecipeAbortCleanup](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/recipe/RecipeAbortCleanup.java)、[ProcessAbortListener](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/process/ProcessAbortListener.java) 和 [MachineWorkSlots.cancel/cancelAll](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/workslot/MachineWorkSlots.java) 已接入；选择器/解析锁、需求属性所有权与取消回执保存校验也已有实现，本阶段已通过本阶段构建与 MCP 检查。此前阶段证据不覆盖这些新增路径，最终证据由[实施进度](implementation-status.md)维护。

`process.cancel()` 使用可翻译的默认 Failure，`cancel(Failure)` 接受调用方原因；未挂接进程直接取消，已挂接进程经 host 协调。取消要求独占、没有执行上下文/open transaction/pending 清理故障的稳定边界；先进入 CANCELLED，保留 started、double progress 和 long successfulTicks，并公开该 Failure 后清理。已稳定终态返回 false，不重放清理。业务 START/TICK/END 返回 Failure 时，原阶段先完整 rollback，再进入 FAILED 并运行同一 abort wave；保留原 Failure 和已提交进度，不转换成默认取消原因。取消不调用 END、不产终产物、不退此前已经提交的 IO，也不把清理当成历史资源补偿。

abort 在任何清理回调前捕获组件依赖顺序；先按执行逆序调用 root requirement.cancel，再按组件依赖逆序调用组件实例实现的 ProcessAbortListener.onAborted。RequirementSystem.cancel 是公开终态清理扩展，使用同一完整 RequirementPath 和当前 effective view，无返回事务，也不能 Blocked；包装器负责只传播给实际活动 child。当前 selector 按已选且未完成 child 传播，组件 listener 随后清选择效果。每个已捕获参与者都尝试，异常聚合；外部实现只释放自有效果，不创建新效果、写世界、退款或等待另一 lane。正常进程 tick 的已捕获 Post 清理仍按原规则执行，不能在终态重新安装已撤销所有权。

host.cancel 成功清理并再次确认稳定边界后解除双向挂接、释放槽位并保留结果；不属于该槽宿主的进程不被取消。cancelAll 按槽逆序尝试全部占用槽，个别失败继续其他槽，返回新进入 CANCELLED 的数量；已经稳定终态的槽也会释放但不计入该数量。清理抛错保留故障进程/槽用于诊断，标记 process 不可保存及 host faulted，不允许重试取消清理或重跑已完成效果。faulted host 可在未执行时继续取消其他稳定进程，poisoned 进程仍拒绝；host 不复活、不恢复保存或调度权限。

本阶段原 14 项异常后重试用例保留首次调用的退款、时钟和计数证据，后续调用改验入口拒绝、不推进及不重放；正常 Blocked 的同进程重试仍单独保留。该调整和每层直接 children 身份/顺序校验均已通过本阶段构建与 MCP 检查，不能沿用此前阶段报告作为完成证据。

ResourceInputLocks 实现 listener，仅清 chosen key 身份，不拥有库存、不退款。SelectiveState 按逆 selection map 清理，每个选择先处理 overlay RemovalClaim 再处理 modifier，各效果族内部逆序；两个效果族和其他选择都继续清理，保留成功移除的 AbortReceipt 并清活动选择。此顺序没有宣称跨效果族的全局安装逆序。其他 writer 替换的对象或层不被强删，所有权不符显式抛错；不把清理失败写成合法存档。

[RequirementAttributeState](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/process/RequirementAttributeState.java) 按完整需求路径/attribute 跟踪准确安装对象，以公开 RenamedAttributeModifier 委托原 modifier 的真实行为；START rollback、END complete 和 abort 只移除自身安装，其他 factory/外部同 ID 效果不被认领或清空。END 捕获原安装对象而不依赖后来 overlay 的属性 ID，abort 逆序释放并记录缺席回执。此前 AttributeRequirementSystem 的 private 包装 modifier 不在默认 codec 支持范围，本阶段改为已有公开 renamed codec 并绑定真实所有权，外部 delegate 仍须准确 codec 覆盖。

RecipeProcessNbt 当前格式 Version 2 保存 CANCELLED/FAILED 和显式取消回执；这与此前 MachineAttributesNbt 的局部属性 Version 2 是不同格式。内置 codec 保存 RequirementAttributeState 的 Owned/Aborted 引用及 selector AbortReceipts；恢复绑定现有准确 modifier/layer 身份并验证已移除效果缺席，不运行 START、modifier、END 或 abort callback。BuiltinProcessValidation 检查已知 canonical 路径、去重、效果所有权与回执缺席；FAILED/CANCELLED 必须无活动内置锁、选择和 owned 属性，其他生命周期不得有 abort receipt。外部历史 overlay/状态仍需宿主 validator，不能靠内置检查证明任意扩展的历史。旧进程 Version 1 不自动升级。

普通卸载、结构失效和破坏策略已接生命周期：普通卸载保存工作而不取消，结构失效暂停而不清槽，控制器破坏才取消。控制器运行时退出先退休，当前事务完整结束，在新的 START/TICK/END 和后续工位边界停止；扫描中已获取预留和 preparation 完整回滚后返回 PAUSED，不 admission。健康退休实例仍可保存并交给新实例恢复。实际清理等待 MAIN 回调返回或全部 worker join，详见[控制器运行时](machine-controller.md)。真实 planned 取消只补偿本次未完成批次，仍须 source 反序补偿、COMPENSATION_PENDING/恢复及 PN 准确 after-image 等独立协议；它与本进程清理共享资源契约边界，但不能合并成同一个取消机制。

## 资源存储纠正

用户明确要求：复用 AE2 的类型系统，不以 MEStorage 作为核心存储契约。新存储、事务和预留 API 应独立设计，以保证配方与并发需求；AE/Forge 库存接口仅用于外部适配。详见[存储重构约束](storage-redesign.md)。

独立资源后端已完成：`ResourceHatch` 返回 `ResourceStorage`，`SlottedResourceBuffer` 独立持有余额、输入数量和输出容量预留及补偿状态，AE2/Forge/GUI 库存接口只作适配。共享物理余额的视图使用同一 context，资源批结算按 context 顺序锁定。固定需求已接入同步整阶段结算，真实调度协议仍待迁移。

旧固定资源流程已移出公共配方模型，改名为内部 `ResourceTransferPlan` / `ResourceTransferRun`。它已通过当前 `ResourceHatch` 使用独立 `ResourceStorage`，不再依赖旧核心库存接口；但仍采用直接部分收取、FE 付款余额、输出重试和内存检查点流程，是核对资源守恒的迁移材料，**不是新需求执行器、阶段预留适配器或并发运行基线**。它不再从公开 RecipePool 选择配方。早期 `ResourceTransferNbt` 没有任何生产或测试调用方，也未接入机器保存；已移除这份固定资源格式的死代码。正式进程持久化采用 RecipeProcessNbt 的进程/组件 codec，工作槽格式在其外层整组发布，不能用该固定资源原型代替。

不能只把直接库存操作包装成“支持 rollback”就接入阶段事务：源可能失效，输出可能已被其他机器取走，输入退回也可能没有容量。需求迁移须使用独立存储已有的预留、提交和补偿能力，继续明确与 FE、业务状态、宿主失效、通知及 PM/PN 调度的共同边界。

## 高级需求保留范围与迁移依赖

PM 的高级需求是必须继续迁移的行为，固定资源/FE 需求不能替代它们。完整源码映射和旧实现缺陷见[高级需求审计](migration-audit.md#高级需求能力与源码入口)。

- 物品保留 START 固定、模糊和动态匹配输入，END 固定和加权随机输出；流体还保留 TICK 固定输入/输出。概率结合有效并行数和进程属性，支持超过 100% 的概率、按阶段/刻隔离的确定性随机及保守容量检查。
- 模糊候选按声明顺序选择；动态物品输入保留外部 matcher、运行时枚举、候选上限和 JEI 展示候选。解析后的 AEKey、选择器候选和随机结果属于独立进程状态，不能写入共享配方负载。选择及输入获取的回滚必须一致，避免已回滚输入被既有选择标记跳过。
- 加权输出保留每个输出循环按权重无放回选取多个候选；`ignore_output_full` 保留部分产出并丢弃余量的效果。容量预留须覆盖实际确定的 key 和数量，不能只检查数量最大的候选而宣称覆盖所有随机结果。
- 选择性包装器保留按顺序选择首个 START 成功的子需求、全程沿用选择、无可用候选时本次进程禁用但不阻塞，以及外部 modifier 施加进程属性效果。阻塞重试不能遗漏候选输入，也不能提前删除仍应生效的 modifier。
- 需求 overlay 保留按类型和实例 ID 定位、每进程覆写有效需求而不改基础配方，以及外部 overlay applier 和 modifier 扩展。物品/流体 IO、流体每刻 IO、能源速率和满输出策略都是已有覆盖效果。
- 属性 root/overlay、数量倍率、单进程刻状态、可补偿的属性需求及资源 chance/随机输出/检查点、模糊动态/路径/逻辑解析锁/选择器/需求 overlay、严格进程 NBT/组件装配与自有效果恢复已经接入并完成此前验证。当前工作槽宿主与整组恢复已接入，已通过此前工作槽阶段验证。planned 迁移还需要这些定义、调参和状态的明确投影及工作预算契约；PM 当前物品/流体 snapshot 拒绝非空 properties，固定消耗投影不能代表高级效果已支持 planned。

显式取消与终态所有权保存已完成验证；宿主生命周期、结构失效暂停和控制器破坏边界见[机器生命周期](machine-lifecycle.md)。真实世界结构宿主与普通调度已接，下一步继续 PN 权限与正式 planned。实现时保留外部需求、process factory、约束、matcher、modifier、codec/validator 和 overlay 扩展职责；这一排序不取消任何能力。

## 验证与未完成部分

新增行为用例覆盖外部组件的相位顺序、软依赖/替换/重装、异常终止；外部需求经过公开配方池注册后，覆盖同类型/跨类型顺序、部分获取后的整阶段回滚、正常 Blocked 重试、各阶段分派、进程状态隔离以及获取/回滚/提交错误的传播。异常后的首次补偿证据与后续 tick 拒绝调整见取消章节，已通过本阶段统一验证。旧资源守恒测试保留，不再冒充公开配方链路的验收。

此前公开配方池→MachineHatches→固定资源/FE 三阶段的行为验证覆盖输出阻塞及重试、别名去重、多个需求争用、单来源 FE 操作限额和明确允许的余量丢弃；阶段用例另覆盖业务提交异常补偿和终态通知故障，能源用例覆盖真实并发与跨资源结算。准确数量和 MCP 证据见[实施进度](implementation-status.md)。

属性与单进程生命周期、资源概率/随机输出/检查点、模糊动态/路径/解析锁/选择器、完成链、需求 overlay 和严格进程持久化已完成此前阶段的逻辑构建验证、逐类 IDEA MCP 全问题检查和 MCP build_project。此前工作槽宿主、单 driver 与整组 NBT 恢复通过 gradlew.bat spotlessApply build、逐类 IDEA MCP errorsOnly:false 检查及 MCP build_project，零失败/错误/跳过，IDEA 零错误、警告及弱警告且无超时；报告 `build/reports/idea/work-slots-final.json`，测试范围和准确数量见[实施进度](implementation-status.md)。此前基础扫描、首刻局部 preparation、START 探测与 resolved-base/NBT Version 2 已通过扫描阶段构建与 MCP 检查；此前扫描阶段 230 项测试通过，178 文件零诊断，报告 `build/reports/idea/recipe-scanning-final.json`。当前固定预筛/资源索引扩展已通过此前固定预筛/索引阶段构建与 MCP 检查；此前固定预筛/索引阶段 240 项测试及 182 文件 MCP 零诊断证据见 `build/reports/idea/fixed-io-index-final.json`。当前显式取消、失败所有权清理及进程 NBT Version 2/回执已通过本阶段构建与 MCP 检查；本阶段 260 项测试通过，188 文件 MCP 零诊断，报告 `build/reports/idea/process-abort-final.json`；不替代游戏或真实调度集成验证。

机器宿主生命周期已接入扫描/工位/保存：普通卸载保留、恢复等待新 load 及结构、失效暂停、破坏取消；普通并发/亲和性调度、控制器 NBT 与结构宿主、显式自动扫描均已接，准确阶段证据见[实施进度](implementation-status.md)。尚未完成：真实 TileEntity/World 交互验证、RESCAN 执行策略、PN 权限与运行补偿、正式 planned 预留补偿与内部工作预算、完整投影/预览/复杂渲染和 JEI。固定 IO 预筛只覆盖受限 flat 场景与独立当前量/容量，局部 preparation 不能自动证明任意外部组件与首次 tick 等价，也不覆盖世界副作用或不可逆行为；当前实现不能据此宣称完整机器运行、旧 PM 存档升级或 planned 完成。所有剩余项都是必须继续迁移的功能，不是可选项。当前不启动 Minecraft 客户端或服务端。
