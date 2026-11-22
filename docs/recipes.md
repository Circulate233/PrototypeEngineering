# 配方与组件迁移

`MachineRecipe` 已恢复为按需求职责分组的公开定义。它保存 id、时长、配方组及 `RecipeRequirementType<?> → List<RecipeRequirement<?>>`，不再以固定输入、输出和 FE 字段限定配方。需求类型绑定自己的 `RecipeRequirementSystem<C>`；资源身份继续使用 AE2 的 AEKey/AEKeyType，不再建立 PMKey 的替代类型体系。

本模块是库，不注册正式机器或玩法配方。参数、内容和自定义需求由使用方提供。高级需求、进程 NBT、工作槽/整组恢复、基础 admission/首刻 preparation 和受限固定 TICK/END 预筛/资源索引已完成此前阶段验证，准确证据见[实施进度](implementation-status.md)。显式 CANCELLED、业务 FAILED 清理、宿主取消协调、内置效果所有权及进程 NBT/取消回执已接入并完成此前验证；机器/世界生命周期、普通并发调度与控制器结构接线也已接入，自动扫描组件见本文。PN 配方条件已接真实消费者，见[网络消费者](network-recipes.md)；正式 PLANNED_PARALLEL、独立宿主自动接线及游戏运行证据仍待后续完成。

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

`MachineInstance.onSchedule()` 调用这一分派；`MachineSystem` 默认把阶段适配为 `MachineTickEvent.Pre/Normal/Post`，具体系统也可覆盖阶段方法。此入口已接入普通调度与控制器宿主，结构成型/退休门限统一限制配方扫描和工位处理；正式 PLANNED_PARALLEL、独立宿主自动接线和游戏运行验证仍须继续迁移。

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

## 内置纯 prepare 与实际工作预算

块一已在真实 CONCURRENT 网络配方与研究材料消费者使用 `RecipeRequirementSystem.prepare`、`RequirementPreparation` 和 `RecipePureComputation`。owner 绑定保留需求路径及当前有效 overlay，独立 kernel 经既有 `runPrepare` 只计算冻结数据；普通外部需求 callback 和其内部嵌套调用仍在 owner 同步执行。平铺 selector 保留原 reserve/rollback/modifier 时机，检查点真实子生命周期和 source 段仍按原顺序推进。

`RecipeWorkBudget` 在实际工作处扣减并保留游标。资源 owner 增量捕获每个来源/数量/revision，复制、合并、缩放需求，选择、预检、预留及安装锁；FE 按来源访问计费，包括空或无效来源。内置 `SlottedResourceBuffer.beginQuantityCapture` 每次复制一条数量，revision 改变时丢弃部分捕获再续办，完成后交出独立不可变事实；`snapshotFor(key)` 避免每次预留复制整张数量表。概率、随机逐次抽样、动态完整匹配/规范编码/排序和缓存证明在纯 worker 中按实际条目或内部步骤计费。

透明预算暂停仍属于同一获取段，保留真实预留和当前游标，不人工 stage/append/expose。最终预留前再次证明当前捕获版本；新来源事实不在原候选证明内时阻塞重试。普通网络及研究材料工作只在原 deadline、原 attempt 仍 current 时继续 work yield；取消退还全部准确持有预留，UNKNOWN 等待原来源/业务确认。旧 `Stage.acquire()` 后续子段保留同步模式，纯 prepare 入口后续子段保留纯计算模式。

进程本地缓存只保存纯计算状态，精确需求内容及来源数量证明通过后才复用；当前 permit、lease、来源与预留不进入缓存，跨 tick 必须重捕获当前授权，终态清除且不写 NBT。外部 snapshot、matcher、comparator 和 opaque callback 是合作式单次回调边界，使用方需保持既有纯/稳定契约，预算不抢占其内部实现。正式 PLANNED_PARALLEL、独立宿主和扫描内部预算已由块二接入并验证，见[执行边界](planned-execution.md)。

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

`ResourcePresenceRequirement` 使用 `RecipeRequirements.RESOURCE_PRESENCE`，在 START 检查普通输入的存在数量，立即释放临时预留，不执行扣料、不按并行扩大。固定、fuzzy、dynamic 和来源分配共用正常输入解析，禁止输出、逐 tick 输入、概率或随机输出。JEI 显示非消耗条件，加工样板只保留实际消费材料。浇铸模具与 Extruder 流体门槛使用该需求，完整规则见[非消耗材料条件](machine-recipe-pools.md#非消耗材料条件)。

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

2026-11-17 概率采样改为在 double 精度范围内符合二项分布的整批采样，删除 Poisson 近似和 2048 项 Gaussian 表。1 并行在任何调参与并行算法初始化之前直接执行一次 `nextDouble() < p`；资源纯准备的 1 并行入口也直接调用 ChanceMath，不创建并行采样 Cursor。零概率、必然概率及整数倍概率无需随机调用。

自动策略先按并行次数选择：2–4 并行直接累加 Bernoulli；5–6 并行且 p 在 [0.125,0.875] 内也直接累加。50% 概率的 2–384 并行优先对随机位做 `bitCount`，每个完整 long 同时代表 64 次独立试验，最后不足一字时按实际位数截取，余量不超过 32 位用 int。其余情况把 p>0.5 转为抽失败次数，再取补数；`n×min(p,1-p)<10` 使用单个均匀随机数的二项分布反演，否则使用 Hörmann BTRD 拒绝采样。BTRD 中心区域单次均匀数即可快速接受，其他候选检查概率比；均值不低于 10 时平均工作量不随并行数线性增长。算法选择、参数计算位于循环之外，保留热路径，局部符号选择使用 copySign。

反演使用 `exp(n×log1p(-p))` 保留极低但非零概率，下降尾部低于 double 精度时停止，避免 CDF 舍入残差触发长尾循环；BTRD 的 n+1、Stirling 校正和候选转换保留必要的宽化，越界候选在转 int 前拒绝。同步与预算 Cursor 共用采样内核；参数初始化、每个随机字、逐次试验、反演递推、BTRD 候选和短概率比乘法分别计费，切片暂停保留 RNG、候选及递推进度。这里的一次抽样指每个需求阶段只产生一个整批成功次数，BTRD 内部允许多个均匀随机数；同阶段固定输出复用该次数。不得因预算不足、拒绝或重试改用近似结果。

ProbabilityTuning 保留不可变策略与已有存档字段，默认 exactSmallBinomial=false、阈值 1024；false 现在选择上述自动精确策略，true 请求阈值以内的直接 Bernoulli，但上限固定为 64，防止巨大阈值把百万并行降为线性逐次试验。RecipeProcess 在构造时捕获策略，资源系统显式传入；运行中修改全局策略不会改变已有进程抽样，RecipeProcessNbt 继续保存 seed、调参和进程状态。新旧算法同 seed 的数值不保证相同，同版本同条件的重试、同步/切片与恢复仍保持确定。BTRD/反演参考 Boost.Random，署名与 Boost 1.0 许可证保存在源码和产物 `META-INF/licenses/Boost-Random-LICENSE`。

资源系统按完整需求路径、阶段和成功刻数隔离随机盐。同一阶段的固定输入/输出共用执行次数；固定容量保留 PM 的 `max(parallelism, ceil(C/100) * parallelism)` 检查。检查量大于实际量时，用真实临时令牌做 preflight，覆盖同 key 与共享槽位的总需求；检查完释放全部临时令牌，再为实际数量重新取得阶段令牌。0% 仍保留完整并行量的固定容量检查，但不实际消耗/产出；不同需求可重复使用已释放的检查容量，实际执行量之间仍受前面真实预留约束。没有所需的有效来源/目标仍阻塞，preflight 不是永久占用或允许实际透支的证明。

RandomOutputPool 在 END 的每个执行循环按整数权重无放回选取最多 pickCount 个候选条目，非正权重忽略；不同条目即使具有同一 AEKey 也可同时选中，再合并数量。新 WeightedSampling 用 `nextLong(totalWeight)` 整数抽签，替代 PM 的 `nextDouble() * totalWeight` 转 long，避免大权重总和的浮点精度损失；保留权重效果，但同 seed 的逐次结果不要求与旧算法相同。输出容量按实际确定的 AEKey 和数量预留，固定保守检查也合并这些真实随机输出，不使用旧 largest-count 候选假设。

若 pickCount 覆盖所有有效候选，所有条目必选，直接聚合并按执行次数批量缩放；其余池保留 PM 的逐执行循环、独立随机盐和无放回抽样效果。块一纯 prepare 路径已对抽样及聚合的真实内部工作分配预算，暂停保留原确定游标；同步入口继续同步执行。voidExcessOutputs 对实际选中输出继续生效，阻塞重试保持相同确定选择；不另建一套资源身份。

`RecipeRequirements.CHECKPOINT` 绑定 `CheckpointRequirement(time, childRequirement, scaleWithProcess)`。time=0 在 START 执行子需求完整 START/TICK/END，覆盖零时长配方；子 TICK 使用零宽的冻结 Tick 范围，不推进外层时钟。其他检查点在外层 TICK 的 scaled 进度或 unscaled 成功刻数跨过阈值时执行子生命周期。

子生命周期通过 RequirementContinuation 逐段获取；前段在持续 fence 内由原来源线程 apply/expose，业务可逆 stage 后才获取后段。Blocked/Failure 停止后续获取；全部令牌仍上交外层同一阶段统一补偿或完成，子需求不自行结算资源。额外 StorageContext 由系统 storageContexts 预声明。selector 候选自身包含 continuation 时，候选后续 Blocked/Failure 先局部逆补偿再改选；准确边界见[检查点审计](checkpoint-execution-audit.md)。获取抛错时反向清理已返回的子事务和令牌，保留原异常及清理错误。子 TICK 在 acquisition、result、reservations、commit、complete、rollback 的全部回调都保留其冻结上下文，作用域结束恢复外层上下文。一次触发与阻塞重试由外层已提交时间线保证；严格进程 codec 恢复该时间线及子选择/解析状态，不保存每个历史触发刻的完整区间，不保存扫描游标；本阶段进程取消终态与回执另见取消章节。

## 模糊动态输入、解析锁与选择器

`FuzzyInputGroup` 保存有序 AEKey 候选和每执行数量，按声明顺序选首个足量 key；空候选组明确不可满足。`DynamicInputGroup` 保存 AEKey pattern、公开 NamedResourceMatcher、数量、可选 displayedCandidates、maxCandidates 和可选稳定全序比较器。ResourceMatcher 是真实可扩展的纯 predicate；NamedResourceMatcher 将稳定 ID 直接绑定到不可变匹配实现，不通过可随时替换的全局注册表查运行中进程。displayedCandidates 仅供展示，不过滤实际来源。

动态候选来自有效输入仓室快照中非零可用的 key，先按真实 context 去重并汇合。每个不同 key 都参与匹配，全扫描匹配结果后按 typeId、resourceId、完整 key NBT 的规范字节排序；compound 字段名排序、list 保留顺序，叶子完整编码，long array 经最小只读 Mixin 访问，不以 hash、对象身份或可能省略内容的 NBT toString 排序。块一 worker 保留全部已排序匹配，owner 先过滤当前非零可用项，再截取 maxCandidates 并判断数量；匹配、规范编码和排序内部工作均可按预算暂停。不同 key 序列化身份相同时拒绝，扩展类型可提供纯、稳定且区分所有不相等 key 的比较器。maxCandidates 只限制候选数量，不是总扫描/排序/NBT 内存预算；扫描消费者内部预算已由块二 B 接入同一真实游标，外部合作回调内部仍遵守原契约。

内置 EXACT 用 AEKey.equals，ITEM_ONLY 保留 PM 的同 item + metadata、忽略 NBT。不能用 AEItemKey.dropSecondary 替代 ITEM_ONLY：该方法重新构造默认 ItemStack，也丢弃 metadata。自定义 matcher 和排序器须在配方/进程生命周期与未来恢复中保持行为稳定。

START 解析通过 `ResourceInputResolver` 计算保守 checkTimes，先计入固定输入，再逐个模糊组、动态组从可用量中扣除前面已经规划的数量，避免多组重复使用同一余额。0% 也按至少完整并行量选择和预检；已有解析锁保持原 key，不因库存顺序变化重新选择。`ResourceInputLocks` 是逻辑选择状态，不是库存互斥锁或数量令牌，键包含完整 RequirementPath、stage、FUZZY/DYNAMIC kind 和 groupIndex。

解析结果先保持 pending；只有实际资源获取全部成功后才安装逻辑锁，即使实际执行次数为 0 也保留成功选择。后续同阶段失败会同时撤销资源令牌和当前获取新增的锁；rollback 只移除自己安装且仍为预期 key 的项，不恢复整张映射或删除原有选择。snapshot/restore 提供排他生命周期边界的内存状态入口，严格 NBT codec 和跨组件校验已通过此前严格进程阶段验证。

`RecipeRequirements.SELECTIVE` 绑定有序 `SelectiveRequirement`。平铺候选仍在 START 获取时依次捕获业务结果和 token，Blocked/Failure 先退款再改选，成功项建立 provisional selection。候选树可能返回 continuation 时，Selecting 通过公开 RequirementAlternatives 交给外层执行器：候选获取前建立原批次 Savepoint，完整嵌套 tail 成功后才安装选择和 modifier；中途 Blocked/Failure 局部逆补偿并确认后再试后项，不退款外层成功前缀。全部候选不可用时建立 index=-1 的 disabled 成功选择，成功 START 后本次进程固定禁用，不因后来的材料变化重新选择。

外部系统若可能返回 continuation，须用 continuedAcquisition 声明；usesContinuations 同时检查结构 children，使候选边界在任何效果执行前确定。此声明须覆盖动态 payload/overlay 的可能性，不获取资源或修改状态。RequirementAlternatives.startsAlternative 是纯查询，rejectAlternative 只在完整补偿确认后推进原游标，不获取资源或重放回调。故障与 UNKNOWN 保留原清理义务，不能当作普通业务拒绝继续选择。

选择状态按完整路径归进程所有；后续 TICK/END 只派发已选子需求，外层统一结算其令牌。START rollback 撤销本次选择、已获取子业务和该选择自己安装的属性 modifier/需求 overlay 层；START commit 先应用子业务，再按声明顺序调用 SelectiveModifierDescriptor 绑定的真实 SelectiveModifier.apply，并标记提交。SelectiveContext 暴露机器、进程、选择路径、候选索引及 modifier 名称/序号，属性和需求 overlay 效果通过有效 apply 作用域中的 tracked put 记录所有权。内部 modifier/overlay layer ID 长度分帧并包含路径、descriptor 序号和名称，属性包装仍委托外部 AttributeModifier 行为；直接修改其他状态不自动获得该接口的补偿能力。

Java 速度便捷入口直接复用上述效果链：SelectiveContext.addProcessSpeed(modifierId, amount) 添加 NumericAttributeModifier.ADDITION，mulProcessSpeed(modifierId, factor) 使用 MULTIPLY_TOTAL 的 factor−1，因此0.8变慢、1.2变快。独立 SelectiveModifiers.processSpeedAddition(id, modifierId, amount) / processSpeedMultiplier(id, modifierId, factor) 返回已绑定真实指令的 SelectiveModifierDescriptor，直接放入 SelectiveRequirement.modifiers，构造时验证非空名称/有限数值，无全局注册表。多个descriptor的同名modifier按已有内部ID隔离，同apply同名写保持替换；运算仍按ADDITION→MULTIPLY_BASE→MULTIPLY_TOTAL。零/负倍率允许沿原TICK进度max(0,PROCESS_SPEED)处理。START rollback、END complete/取消与严格numeric NBT恢复复用原局部所有权，恢复不重放指令；CT脚本桥仍最后接入。2026-11-09新增5项真实选择/进度/局部覆盖/故障回滚/恢复END与取消行为，受影响79/10、正常完整build1568/198、3 Java本机MCP全级零及完整读回一致，证据 `build/staging/java-recipe-presets/`。

END 获取/业务 commit 保留选择的属性和需求 overlay 效果，到全部业务成功后的 complete 才完成子事务并清理本选择的局部效果；被阻塞或业务异常的 END 不提前清效果。子 complete 抛错时仍尝试选择清理，外层与检查点完成链继续其余 complete 和资源/FE token 完成，再报告聚合错误；已跨终态边界不回滚或重放。选择状态已接入严格 NBT 保存恢复并完成此前验证，扫描基础已验证，当前固定预筛/资源索引扩展已验证，当前显式取消与失败清理已接入，已通过本阶段构建与 MCP 检查。

## 每进程需求 overlay

`api.recipe.overlay` 提供真实公共扩展接口 `RecipeOverlayApplier<C,D>`、不可变 `RecipeOverlayType<C,D>` 和 modifier/descriptor/context。Type 将稳定 ID、准确的 RecipeRequirementType 和纯 applier 直接绑定，不在执行中查询可替换的全局注册表。基础 payload、overlay data 和返回 payload 都必须不可变，applier 不得修改进程、世界或其他层，并须支持从基础 payload 重新组合任意剩余层。内置覆盖字段如下：

| 类型 | 覆盖字段 | 未指定字段与显式值 |
|---|---|---|
| `RESOURCE_IO` / `ResourceRequirementPatch` | inputs、inputsPerTick、outputsPerTick、outputs、voidExcessOutputs | null 继承此前层的字段，空列表明确清空；数量必须为正。chance、randomOutputs、fuzzyInputs、dynamicInputs 保留。物品、流体及其他 AEKey 共用此 patch。 |
| `ENERGY_RATES` / `EnergyRequirementPatch` | inputsPerTick、outputsPerTick、voidExcessOutputs | null 继承，0 明确关闭该刻速率，负数拒绝；START input 和 END output 保留。Boolean false 是有效覆盖值。 |

`RecipeOverlays` 是进程组件，按完整 RequirementPath 定位 canonical requirement，再按写入者自有的非空白 String layerId 保存层。层按首次插入顺序应用；同层替换保持原顺序，删除后重新插入视为新层。put/remove/rollback 在写时从 canonical payload 按剩余层重算 effective view，全部成功才发布新层与新 view；读取和执行热路径不重复运行 applier。撤销只恢复自己那一层的前次内容或移除自己新增的层，不用整 payload 快照覆盖其他写入者。

内置 `ResourceRequirement.canonicalStacks` 验证并冻结一次 IO；私有规范化列表只包装自身独占副本。`ResourceRequirementPatch` 复用已冻结的变更列表及未变 IO/group 列表，单独修改 voidExcess 不复制全部条目。纯事实缓存的 owner 匹配只比较标量和列表尺寸；四组 IO、模糊/动态候选及随机池的精确内容证明在纯 worker 按条目计费，共享不可变列表身份可直接复用。同尺寸 IO 变化不能复用旧数量结果，原 overlay 写入/局部撤销顺序保持不变。

`RecipeRequirement.definition()` 保留 canonical 不可变定义，`withPayload()` 产生保留身份的不可变 effective view。外部系统 callback 可用收到的有效 view 写同一 target，无需另持原定义；嵌套 target 要传完整路径。仅复制相同 type/ID 另建的 counterfeit 定义仍被拒绝，不能混用同一路径的不同 canonical 定义。每次 acquire 先进入原路径再解析一次，已获取事务的 payload 参数冻结；后续成功写入只影响之后的获取。选择器和检查点的嵌套子需求同样通过该入口。RecipeOverlays 在每层 apply 后，将系统 children() 返回的直接子需求与原 canonical payload 比较，要求数量及同序 child.definition() 对象身份不变；重建同 ID child 或重排 selector 分支都会在发布前拒绝。保留 canonical definition 身份的 effective child view 允许使用。此检查只覆盖直接 children，不递归证明任意外部 payload 内部变化或副作用；本阶段新增校验已通过本阶段构建与 MCP 检查。

put/remove 返回 `RecipeOverlays.Change`，写入立即可见，调用者必须最终 rollback 或 complete。同一层的交叠句柄按逆序撤销；rollback 校验当前仍是自身安装版本，再局部撤销并重算其他层。Change.complete 只放弃 undo 权，不删除已安装层；clear 仅允许在没有 pending 句柄的排他生命周期边界清除永久层。外部坏 applier 在撤销重算时抛错，原有效状态和 pending 句柄保留，清理链继续其他项并聚合 fault，不能据此假称清理成功。

`RecipeOverlayPipeline.acquire(process, descriptors)` 是宿主显式入口，按 descriptor 声明顺序调用 modifier，重复 descriptor 也按独立 ordinal 执行。RecipeOverlayContext 只在该次 apply 有效，tracked put 为 descriptor ID/ordinal/layerId 长度分帧命名并收集自有 Change；返回事务可并入宿主阶段，失败逆序补偿全部 tracked writes。`apply()` 立即完成同一事务并放弃 undo；new RecipeProcess 不自动调用 pipeline，宿主决定应用时机。直接经 process()/machine() 改其他状态不自动获得补偿权。

SelectiveContext 的 `putRequirementOverlay` 支持 root 或完整 nested target，tracked 层与属性共用选择器生命周期：START rollback 清自身层，END acquisition/commit 和拒绝重试保留效果，END complete 才移除自身层并清属性。START complete 将已安装的最终层版本转为 `RemovalClaim` 生命周期清理权，并结束阶段 Change undo；RemovalClaim 不阻塞稳定 snapshot，恢复时只认领现有层，END 移除仍校验预期版本。清理会尝试两类效果并聚合错误。层 snapshot、严格 NBT codec 和选择所有权恢复已通过此前严格进程阶段验证；当前工作槽整组恢复复用该 codec，新格式不提供旧 PM 存档自动升级。

## 严格进程保存恢复

`api.persistence` 提供 NbtCodec、资源 key、属性 modifier 和局部属性编解码；`api.recipe.persistence` 的主入口是 `RecipeProcessNbt.builder(canonicalRecipe, revision)`。构建后绑定固定 canonical 配方、显式非空白 revision、资源/属性/组件/overlay codecs 和宿主 validators。encode 拒绝其他 canonical recipe 实例；decode 同时核验格式版本、recipe ID、revision、duration 及完整有序 RequirementPath graph。需求系统必须返回固定、无环且路径唯一的定义图。进程按需建立一次 RecipeDefinitionIndex，后续保存、恢复和内置组件复用该索引。

ID、graph 和 duration 只能证明结构身份与显式字段，不能证明任意 Java matcher、comparator、applier、modifier、系统或 codec 函数行为一致。宿主变更这些定义的兼容语义时必须更换 revision；也必须保持结构定义稳定及纯 applier 的剩余层重组能力，不能仅以相同 ID 声称旧状态兼容。每层 overlay 的直接 children 校验同样用于 put/remove/undo 和恢复重算，但不能代替任意外部行为的兼容性证明。

NBT 读取使用原生 getter，资源 ID 和 UUID 使用原生解析器。保存格式版本、定义修订及资源结算需要的检查保留。所有已保存组件、依赖、overlay 和外部属性 modifier 都须具有对应 codec，未知类型和歧义会报告错误。编码数据直接传递，codec 必须遵守只读输入和已完成输出的 Javadoc 约束。

保存要求进程独占执行边界，并在编码入口检查：没有执行中的 tick/路径上下文、open requirement transaction、pending overlay stage Change 或未完成 selector START。抛异常的 tick、事务终结/清理故障会使进程拒绝保存及后续 tick，即使生命周期已推进或已终态也不能把该次调用当作认证保存点。首次异常前后完成的阶段补偿或通知清理，不代表允许原进程重试；正常业务 Blocked 仍可重试。预留令牌和半提交阶段不能跨存档恢复。状态保留 seed、ProbabilityTuning、double 绝对 progress、long successfulTicks、started、生命周期及结构化结果；属性只保存本地 base override/modifier，父机器属性和世界由宿主提供。

未启动进程中由宿主预种的解析锁属于不可保存的准备状态；本 codec 不持久化尚未成功完成 START 的半准备选择。

decode 返回新的未发布 RecipeProcess，恢复顺序如下：

1. 直接读取输入，核验配方/revision/graph/duration，解析状态、局部属性、全部组件条目及有效依赖，拒绝未知/重复数据。
2. 按已保存的真实 assembly 顺序创建全部组件，再恢复有效依赖及尚未安装 dependent 的 pending 约束；不依赖当前 type 默认装配顺序替代旧状态。
3. 按依赖拓扑恢复各组件数据。selector load 额外依赖 overlay 先恢复，这条加载约束不改保存的 tick 依赖图。组件 factory/codec 不得改 assembly、修改旧进程/owner 或发布世界效果，框架不在回调前后重复比较装配图。
4. 运行 BuiltinProcessValidation、各组件 codec.validate 及宿主 validators，全部通过才返回。宿主之后决定何时将新进程加入工位。

ResourceKeyNbt 保存显式 AEKeyType ID 与 key data，先解析准确类型再调用该类型 loadKeyFromTag，拒绝未知类型或无法解码的资源数据，不补缺失内容为其他 key；资源条目数量用正 long，合法重复资源条目保留。overlay 保存 canonical target path、layerId、long order、准确 type 与 immutable data，全部核验并重算后替换新组件状态。选择器保存 committed/index/completed 和自有属性/overlay 引用，恢复只绑定已安装 modifier 的准确对象与现有层清理权，不重新跑 modifier、pipeline 或 START，不再次扣料。

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

`MachineWorkSlotsNbt(revision, recipeBindings)` 绑定非空白布局 revision 与唯一 recipe ID → RecipeProcessNbt。Version 1 保存有序槽的 Name、Groups、Core、RestartPolicy、结构化 Result 和可选 Process。codec 使用当前宿主布局与 executor，revision 同时承担其兼容行为；不从存档恢复调度器或执行权限。编码读取一次宿主快照；codec 必须遵守独占访问和只修改自身状态的要求。

恢复直接读取原生 NBT，并校验版本/revision、槽数/顺序/完整定义、重复名称/组以及所有 recipe binding，再把每个进程完整 decode 成新实例。整组全部通过后，才调用 `host.restore`；该方法再次验证 owner、组、按对象身份唯一的进程、结果一致性、稳定边界与跨宿主挂接，再一起解除旧挂接并建立新双向挂接。最后条目失败也不会先清空或部分发布旧宿主。外部 codec/factory/validator 必须遵守无旧宿主/世界副作用的契约，框架不在每个 codec 回调后比较完整宿主状态。机器/世界恢复、旧 PM 格式升级及 PN epoch/lease/ready authority 恢复不在此格式内；运行权仍须重新发现并取得。

旧 planned 的 [createPlannedSlots](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/planned/RegistryBackedMachinePlannedExecution.kt:142) 另造 [PlannedWorkSlot](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/planned/RegistryBackedMachinePlannedExecution.kt:245)，其 process 永远 null、非 null setter 抛错，仅消费 name/groups 与槽位 identity。当前 WorkSlot 是传统进程宿主实际状态类，无需为复现这种假槽机械增加接口。未来 planned context 应复用稳定 WorkSlotDefinition 等元数据并提供真实 planned 状态及所有权，不能假称该状态承载 RecipeProcess；真实 planned 的功能、扩展与多线程能力仍完整保留待迁移。

## 有界扫描与 START 探测

当前 [RecipeCatalog](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeCatalog.java)、[RecipeScanner](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanner.java)、[RecipeCandidateIndex](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeCandidateIndex.java)、[RecipeProcessFactory](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeProcessFactory.java)、[RecipeParallelismConstraint](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeParallelismConstraint.java)、[ScanLimits](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/ScanLimits.java) 与 [RecipeScanningSystem](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/machine/RecipeScanningSystem.java) 已接入，已通过此前扫描阶段构建与 MCP 检查。旧 PM 的组、索引与非单调风险及当前修正见[扫描与索引审计](migration-audit.md#扫描索引与并行探测审计)。

RecipeCatalog 冻结配方池/声明顺序和 canonical 配方实例，相同 ID 的不同对象拒绝；重复使用同一实例只收录一次。`RecipeRegistry.freeze(MachineTypeRegistry.INSTANCE.types())` 先整批核验机器静态池引用、建立池反查、池快照及共享 catalog，再通过一个 volatile Frozen 值一起发布；catalog 构造失败时池和目录均不可见、注册仍开放。registry 的池按 ID 排序，池内保留配方声明优先级；独立构造 catalog 则按传入池顺序。

自动扫描先捕获机器返回的 canonical 配方池选择；查询组为空时取这些池内的全部 canonical 配方，包括无组配方；非空时取这些池内的组 union，再按目录顺序返回。每个外部 index 都获得同一不可变完整 scope 和同一组集合；空 groups 表示不进一步过滤已有池 scope；空池选择仍是零候选，不能回退全局目录。无 type 的独立低层宿主默认使用明确传入 catalog 的全部池。index 返回 null 不作筛选、empty 是硬空集，其余结果按 canonical 实例交集，不改变目录顺序。伪造、外部或越组候选拒绝。index 必须纯，且保留没有自身需求类型的通用候选，不能把 energy-only 配方从 item index 丢掉。ResourceRecipeIndex 使用同一空组语义；显式 Footprint 与外部索引完整性仍由调用方负责，见下一节。

RecipeScanner 绑定一个 MachineWorkSlots，在独占执行边界按目录顺序扫描，选择首个空且组兼容槽。若任一槽为空白名单，扫描 scope 为本 pass 选定池内的全部配方；否则 scope 为这些池内全部槽组的 union，具体 admission 仍按所选槽的兼容规则。当前容量满返回 FULL，同 recipe ID 已在任一槽运行则跳过。每次 scan 最多一次成功 admission，调用方每个机器 tick 最多调用一次。结果包含 ADMITTED/YIELDED/EXHAUSTED/FULL/PAUSED 和本次 candidate/probe 数，达到限制让出，退休后完整回滚返回 PAUSED；扫描异常标记 scanner faulted，后续 scan 拒绝。

ScanLimits 独立限制每次检查的候选数和并行 probe 次数；块二 B 的 RecipeWorkBudget 另按目录、索引、factory、属性/约束、probe、来源和原结算的实际内部访问计费。游标和候选列表/搜索边界可跨调用保留，搜索固定 seed 与 ProbabilityTuning，每个 amount 都经外部 factory 构造 fresh 未公开 NEW 进程，核对 machine、canonical recipe、seed/tuning、未挂接和稳定边界；纯暂停不跨 tick 保留资源 reservation、候选进程或运行权限；有部分预留/UNKNOWN 时准确 ScanAttempt 与原 token 留作清理，失权后不能继续前向。机器父并行上限改变会重建当前搜索。一次 pass 逐 probe 观察活动存储，候选/index 快照与上界不代表跨 tick 原子视图，也不保证动态存储下的全局最大值。

默认对 base amount 从上界向 1 逐一递减探测，支持非单调需求。只有显式 `Predicate<MachineRecipe> monotonicStart` 保证 factory 局部 modifier、全部 preparation、约束、需求和存储组成的**完整 probe** 成功集为连续前缀时才二分，不能根据 type ID 自动推断。搜索界使用 long，amount=Integer.MAX_VALUE 时加减不会回绕；二分得到的 best 在 admission 前重新构造并完整探测，旧成功不等于预留权。重探测失败则下次重新观察，真实 START 始终具有最终决定权。

非单调反例继续成立：A=5、B=8，模糊首组候选 [A,B] 每次需 4，第二组仅 A 每次需 2；k=1 首组取 A4 后第二组失败，k=2 首组改取 B8 后第二组 A4 成功。因此默认穷举，显式单调保证覆盖整个探测，不能只证明某个固定 IO 子检查单调。

候选的 PROCESS_PARALLELISM 使用 `MachineAttributes.setResolvedBase(amount)`：amount 包含机器父层已经计算的基线，屏蔽祖先 modifier 的二次应用，仍保留 factory 安装的本地 modifier。amount 不包含本地 modifier，不能反解任意外部函数；实际有效资源数量继续由 RecipeParallelism 读取本地运算结果。普通 setBase 恢复继承 modifier，clearBase 重新显露父层；snapshot/NBT 保存 InheritModifiers，局部属性格式 Version 2 精确读取该 Boolean，拒绝缺 base 却屏蔽继承的状态，旧 Version 1 不自动升级。该语义已通过此前扫描阶段验证。

whole-recipe 约束为纯检查/安全上界，逐个检查整个配方，不按 requirement type 跳过未知实现；上界不得排除任何可行 base amount，必须汇总共享余额/容量。约束通过也必须调用 [RecipeStageExecutor.probeStart](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/recipe/RecipeStageExecutor.java) 获取真实 START 需求事务与资源/FE token，再将全部获取逆序 rollback，包括拒绝项；平铺需求不调用 commit/complete，也不执行 TICK/END；continued START 的内部子阶段会可逆 stage，以准确观察前序材料/空间，最后全部 rollback，永不 complete 或保留生命周期推进。异常继续清理并使候选不能保存，scanner 故障停止后续运行。所有回调须保留完整 undo；pure prepare 仍待后续接入。

**自定义组件投影：** 当前 [RecipeScanPreparation](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanPreparation.java) 与 [RecipeScanProbes](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/recipe/scanning/RecipeScanProbes.java) 已接入并补充行为用例，已通过此前扫描阶段构建与 MCP 检查。scanner 的八参数构造最后接受有序 preparations，原七参数构造默认空列表。每次完整 probe 先按顺序 prepare，再运行全部约束和真实 START acquisition/rollback；所有结果均逆序 rollback preparation，包括拒绝项。通过后 admission 保留投影前状态，即 factory 初始化与 scanner resolved base，真实首 tick 的组件回调只正常执行一次，不重复安装投影效果。

preparation 只允许可逆进程局部状态，无世界/旧 owner 副作用，返回事务的 reservations 必须为空；commit/complete 永不调用。partial acquisition 仍须遵守正常可逆事务契约。若违约暴露 resource/FE token，协调器先完整释放并清理各层，再报告契约异常；一层 rollback 抛错仍继续其他层，聚合错误并禁止候选保存。prepare 返回 Blocked/Failure 时不执行后续准备或检查，但仍撤销自身及已获取层；真实 START 回滚完成后才撤销准备状态。

probe 不直接调用进程组件 Pre/Normal/Post；真实 RecipeProcess.tick 在 START 前会执行 Pre/Normal。空 preparation 的默认探测仍要求 factory 初始化后即可确定首次 START 行为；组件回调会改变属性、overlay 或需求状态时，外部须提供与首次回调等价的确定性、可逆局部 preparation，否则探测可能假阳性/假阴性。该接口提供显式支持入口，不能自动验证任意自定义 factory 的等价性；普通回调没有自动补偿，不能直接拿组件 tick 模拟。actual START 始终具有最终决定权，世界副作用或不可逆首刻行为仍不属于这种局部投影契约，相关扩展与真实 planned 职责继续保留。

RecipeScanningSystem 显式 `runBefore` WorkSlotProcessingSystem，onTick 核对 scanner owner，并检查 `machine.canProcessRecipes()` 与 ready Predicate。ready 只决定本次 admission 是否扫描，不暂停既有工位，也不提供 PN 权限或实现 RESCAN。当前 FixedIoScanConstraint 补充受限固定 IO 的独立 TICK/END 预筛，已验证；START 或各预筛阶段成功均不能证明完整首 tick 或后续生命周期可行。组件声明及恢复见下一节。

candidate/probe 计数与内部工作额度独立。块二 B 的 ScanAttempt 将目录 union/声明顺序、index 交集、factory 增量创建、属性继承/overlay、constraint/START probe 与 admission 接到原 planned owner；资源/动态排序/key NBT、随机抽样与嵌套需求复用块一纯 kernel 和内部预算。来源捕获、StorageFence claim、ResourceBatch 标量/事务预检和 Slotted 原槽/部分日志按访问续办，保留必要溢出预检和精确 partial token。退款先全量检查，再逐原 part 重验，UNKNOWN 或真实未退出保留清理义务。captureOnlyPreparation 仅许可纯捕获穿过等待，同轮捕获完成后才派发，FE/opaque 保持原序；每次后继 prepare 返回后重验 current/deadline。数量倍率缩放资源，速度改变绝对进度，成功刻数仍每次成功 TICK 加 1；旧同步 scan/acquire 与后续子段保持。真实 planned 继续沿两次 join、稳定 affinity lane 与 PN owner/epoch/向量预算，不持锁跨 lane 等待。外部默认 snapshot/index/factory/constraint、matcher/comparator/opaque callback 的内部为合作边界；本阶段不能证明任意组件首次 tick 等价投影。

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
- 属性 root/overlay、数量倍率、单进程刻状态、可补偿的属性需求及资源 chance/随机输出/检查点、模糊动态/路径/逻辑解析锁/选择器/需求 overlay、进程 NBT/组件装配与自有效果恢复已经接入并完成此前验证。当前工作槽宿主与整组恢复已接入，已通过此前工作槽阶段验证。块一真实消费者已使用这些定义和状态的纯事实及内部预算游标；正式模式、独立宿主和扫描消费者已由块二接线并验证。PM 当前物品/流体 snapshot 拒绝非空 properties，固定消耗投影不能代表任意高级外部效果已支持 planned。

显式取消与终态所有权保存已完成验证；宿主生命周期、结构失效暂停和控制器破坏边界见[机器生命周期](machine-lifecycle.md)。真实世界结构宿主、普通调度和 PN 配方权限已接，块二已接正式 PLANNED_PARALLEL、独立宿主与扫描内部预算，剩余迁移继续按功能核对。实现时保留外部需求、process factory、约束、matcher、modifier、codec/validator 和 overlay 扩展职责；这一排序不取消任何能力。

## 验证与未完成部分

新增行为用例覆盖外部组件的相位顺序、软依赖/替换/重装、异常终止；外部需求经过公开配方池注册后，覆盖同类型/跨类型顺序、部分获取后的整阶段回滚、正常 Blocked 重试、各阶段分派、进程状态隔离以及获取/回滚/提交错误的传播。异常后的首次补偿证据与后续 tick 拒绝调整见取消章节，已通过本阶段统一验证。旧资源守恒测试保留，不再冒充公开配方链路的验收。

此前公开配方池→MachineHatches→固定资源/FE 三阶段的行为验证覆盖输出阻塞及重试、别名去重、多个需求争用、单来源 FE 操作限额和明确允许的余量丢弃；阶段用例另覆盖业务提交异常补偿和终态通知故障，能源用例覆盖真实并发与跨资源结算。准确数量和 MCP 证据见[实施进度](implementation-status.md)。

属性与单进程生命周期、资源概率/随机输出/检查点、模糊动态/路径/解析锁/选择器、完成链、需求 overlay 和严格进程持久化已完成此前阶段的逻辑构建验证、逐类 IDEA MCP 全问题检查和 MCP build_project。此前工作槽宿主、单 driver 与整组 NBT 恢复通过 gradlew.bat spotlessApply build、逐类 IDEA MCP errorsOnly:false 检查及 MCP build_project，零失败/错误/跳过，IDEA 零错误、警告及弱警告且无超时；报告 `build/reports/idea/work-slots-final.json`，测试范围和准确数量见[实施进度](implementation-status.md)。此前基础扫描、首刻局部 preparation、START 探测与 resolved-base/NBT Version 2 已通过扫描阶段构建与 MCP 检查；此前扫描阶段 230 项测试通过，178 文件零诊断，报告 `build/reports/idea/recipe-scanning-final.json`。当前固定预筛/资源索引扩展已通过此前固定预筛/索引阶段构建与 MCP 检查；此前固定预筛/索引阶段 240 项测试及 182 文件 MCP 零诊断证据见 `build/reports/idea/fixed-io-index-final.json`。当前显式取消、失败所有权清理及进程 NBT Version 2/回执已通过本阶段构建与 MCP 检查；本阶段 260 项测试通过，188 文件 MCP 零诊断，报告 `build/reports/idea/process-abort-final.json`；不替代游戏或真实调度集成验证。

机器宿主生命周期已接入扫描/工位/保存：普通卸载保留、恢复等待新 load 及结构、失效暂停、破坏取消；普通并发/亲和性调度、控制器 NBT 与结构宿主、显式自动扫描均已接，准确阶段证据见[实施进度](implementation-status.md)。块一 pure prepare、内置实际预算和真实网络/研究材料运行补偿已完成，证据见[实施进度](implementation-status.md)。尚未完成：真实 TileEntity/World 交互验证、RESCAN 执行策略、完整投影/预览/复杂渲染和 JEI 额外转移/书签/结构联动。块二正式模式/独立宿主/扫描内部预算及 JEI 每池默认与独立自定义页面已实现验证。固定 IO 预筛只覆盖受限 flat 场景与独立当前量/容量，局部 preparation 不能自动证明任意外部组件与首次 tick 等价，也不覆盖世界副作用或不可逆行为；当前实现不能据此宣称完整机器运行、旧 PM 存档升级或 planned 完成。所有剩余项都是必须继续迁移的功能，不是可选项。当前不启动 Minecraft 客户端或服务端。

## 机器声明与动态配方池

R32 已实现并完成本阶段验证。新增 14 项行为；focused 37 tests / 4 suites、额外受影响 92 tests / 12 suites、两组旧扫描夹具复验 12 tests / 2 suites 全通过；正常完整 `gradlew.bat build --console=plain` 为 1391 tests / 176 suites，failure/error/skipped 均 0。19 Java 本机 IDEA MCP `get_file_problems(errorsOnly:false)` 全级零诊断、完整项目文件读回与磁盘 19/19 一致，MCP `build_project` 成功；21 源码/资源/测试文件构建前后和最终 MCP SHA 稳定。准确写前、固定候选及修正 delta、原失败/最终日志与 XML、最终统一清单见 `build/staging/machine-recipe-pools/verification/`；未推送，未启动 Minecraft 或服务端。

机器用 `MachineType.Builder.recipePool(poolId)` 声明池，`recipePoolIds()` 暴露不可变静态声明；池通过 `machines()` 保留同一冻结事实派生的 canonical MachineType 列表。实际 CommonProxy 在 RecipeRegistrationEvent 后使用冻结机器声明一起冻结 RecipeRegistry。机器事件早于配方事件，允许前向 addon 引用；未知引用、重复机器和同 recipe ID 的不同定义整批拒绝，失败时仍可纠正注册后重试。

`MachineInstance.getRecipePools(RecipeCatalog)` 是框架实际调用的唯一可重写动态入口，默认有 type 时仅解析静态声明池，零声明不自动扫描；无 type 的独立库使用传入目录全池。`MachineAssembly.Builder.instanceFactory` 在 create 与 restore 两条入口都调用，校验准确 type、未附 lifecycle、空组件图，再进入原装配/形成/恢复流程。自定义实例可以从传入 catalog 的 `pools()` 选择任意 canonical 池，不限静态子集；伪造同 ID 或其他目录的池显式拒绝。

一个逻辑扫描 pass 捕获一次稳定只读池快照，池枚举与跨池 canonical 配方去重沿原 RecipeWorkBudget 续办；fresh 等价 List 不导致反复从零取样。选择枚举顺序不改 priority，仍用 catalog 既有冻结池/配方顺序。同一 pass 内动态变化在下一 pass 生效：ADMITTED、EXHAUSTED 或 `RecipeScanner.reset()` 后重新采样，FULL/PAUSED 保留纯游标但不续资源/执行权限。主动刷新先 cancel/结算旧 attempt 的 probe/source 义务，释放 active attempt 后才 reset；reset 拒绝仍活动、正在执行或 faulted 的 scanner。

scanner 的同步 startIn 与 bounded beginAdmission 均把准确 `RecipeCatalog.Selection` 交付原工位准入，检查选池成员与 canonical recipe；选择只限制本次新的目录准入。原无 catalog 的显式 start/startIn/beginAdmission 仍是外部直接进程操作入口。旧运行、恢复、detach 或直接启动进程不因自动扫描范围变化被停止、重新归类或历史退款。资源、FE、研究、计算、多线程和来源结算继续原执行器。

## 逻辑线程控制器与整机共享并行

2026-11-11最新线程行为见[配方执行策略](recipe-execution-policy.md)：新控制器默认FULL_ASYNC；四策略独立选择候选发现与机器工作，内部ExecutionMode仍路由原正式planner。多线程完成后的单项重试、跨配方公平轮转、多池实际组合及owner-only需求例外已接入，下面旧阶段关于运行时默认PLANNED_PARALLEL指其调度路由，不表示业务回调始终主线程。

公开 `RecipeController`、`MultithreadController` 与 `CrossRecipeController` 是实际机器运行时基准；通过原 `MachineAssembly.instanceFactory(MultithreadController::new)` / `CrossRecipeController::new` 选择。`MachineWorkSlots.TYPE` 调用 owner 的 `createRecipeHost`，默认 `RecipeScanning.component` 调用 owner 的 `createRecipeScanner`。这两处 factory、控制器类和 protected `defaultExecutionMode()` 均保留继承扩展。普通 `MachineInstance` 默认仍为 CONCURRENT；两种具体控制器默认 PLANNED_PARALLEL，原自动 `MachineScheduling.TYPE` 装配/保存采用实际运行时选择。显式 `MachineType.Builder.executionMode(...)`、恢复的 scheduling component 和外部 override 优先，纯计算与 source work 使用既有 worker/lane/coordinator。

多线程控制器为每个声明的 `WorkSlot` 保留独立 pool selection、group scope、candidate cursor、search seed 与纯 factory/check 游标。无权利的让步轮到后继工位；已经占用的工位和未成型门限继续沿原宿主规则处理。跨配方控制器按目录顺序继续 A、B 等候选，成功后仍完成当前轮；整轮有准入且仍有共享余量时开始下一轮并重新采样 pool selection，同一配方可在其逻辑线程上限内再次准入。整轮无新增可行准入才穷尽；candidates/probes/工作预算在所有工位和轮次间共享，不能每成功一次刷新预算。

整机 `PROCESS_PARALLELISM` 是新候选的基础建议余额，所有已登记宿主的附着进程与已完成 pending claim 按真实 `RecipeParallelism.effective` 合计。`MachineRecipe.maxConcurrentThreads` 按同机器同 canonical recipe ID 的进程数控制准入；并行数量与逻辑进程数量分别计数，投机未完成候选不占 claim。`RecipeParallelismSelection.select(process)` 在基础建议绑定后、完整 START probe 前执行；`fixed(quantity)` 和自定义本地 callback 复用现有过程属性。事件或 factory modifier 最终数量超过基础余额仍可通过完整要求/资源/FE/PN 检查后准入，记录实际数量，使之后余额为零；不把最终数量裁回基础建议，也不在真实 START 重放此选择回调。

普通 `MachineAttributes.ValueCapture` 只跟踪本层与读取过的祖先；兄弟进程的局部编辑不互相撤销。共享用量另捕获家族变化与整机 roster revision；变动时重捕实际用量，基础建议确实改变才重造纯 candidate tuple。纯让步只保留未发布目录、工位选择、factory/无权利 check 进度；START、预留、PN permit、source resolution 和未退出 worker 仍由原 attempt 收尾。目录穷尽前执行新的预算化 live proof，库存/绑定改变后重取候选，避免旧 subset 漏掉新增输入。

跨配方宿主按需追加 `pe_cross/<counter>` 工位，公开 slots 视图不可修改；声明工位不使用该运行时命名空间。工位编解码不保存格式版本号，动态布局保存 Dynamic 与 NextSlot，先解析整组后发布，计数器保持单调；append 与公开 restore（包括恢复空列表）均撤销旧 vacancy/roster 证明。降低共享额度或工位容量不驱逐已有工作。RESCAN 与 isCore 的扩展策略仍按后续阶段处理。

2026-11-12跨配方运行改为[统一批次](cross-recipe-batches.md)：本轮准入后封闭全部实际宿主，按原时长×最终并行数加权得到共同时间，运行期间不补入、所有成员共同END。TICK输入和输出两侧均相同才能合批，固定IO与FE原总量按共同时间精确分摊。START后的数量改写、共同速度、阻塞整批回滚及准确批次保存沿原planner执行；上述按目录继续准入只用于空载收集本批成员。

当前共同验证入口：`build/staging/controller-shared-parallel/`、`build/staging/recipe-pool-strategies/` 与 `build/staging/controller-pool-plan/review.md`。最终状态见实施进度；实际游戏 World/网络包未启动验收。

## 配方池接口与简单配方索引

普通配方创建和导入遍历回调共用 RecipeBuilder。材料可按需求 ID 通过 addInput/addOutput/addInputPerTick/addOutputPerTick 追加，通过对应 remove 方法按索引删除；START 输入索引按固定、fuzzy、dynamic 组排列，后两者整组删除，END 输出索引包含固定输出和随机候选。clearInputs/clearOutputs 清空指定或全部顶层材料，不递归改动独立嵌套时序。energy(id, input, inputPerTick, outputPerTick, output) 调整四阶段 FE，保留既有来源分配和溢出设置。requirement/resources/energy 查询当前定义，replaceRequirement/removeRequirement 编辑整项。每次编辑产生正常新需求定义，保留未改方向、概率、事件及准确绑定，源配方与此前构建结果不受后续编辑影响。Java/ZS 签名、查询与错误规则见[普通配方编辑](recipe-imports.md#编辑普通配方)。

所有 `RecipePool` 均可通过 `withConfiguration/withRequirements/withAuxiliaryInputs/withEvent` 添加公共材料、完整需求、阶段事件及配方设置。普通池、组合池和独立惰性模组导入池共用该接口，具体创建方法、来源组、回调和保存要求见[配方池公共配置与模组配方导入](recipe-imports.md)。下述 Simple 原始 Entry 的限制只约束基础索引定义，公共完整需求由通用配置添加。

每个导入池通过 `poolType(RecipePoolType.SINGLE_INPUT/GENERAL)` 选择真实底层池，ZS 使用 `.poolType("single_input"/"general")`。各来源按实际主输入形状设置默认类型，完整 70 池清单见[机器目录](machine-recipe-pools.md)；设置和读取类型不初始化来源，首次初始化之后禁止修改。创建回调的首个参数为可编辑配方，其余参数由各池单独定义，例如熔炉的 `float experience`、匠魂熔炼的两个温度整数；匠魂合金只有配方参数。熔炉保留原生小数经验。省略回调时使用 `null`，不调用默认返回 true 的函数，也不转换仅供回调使用的数据。Java 自定义来源构造器接收惰性的 `Supplier<List<MachineRecipe>>`，`withoutContext` 接收 builder 列表和可选 `Predicate<RecipeBuilder>`，无回调时默认 `null`。

混合提供者使用[CompositeRecipePool与异步发现](recipe-execution-policy.md#多个提供者的组合配方池)。新增纯库存预算游标`beginMatch`供Default/Simple/组合复用，实际多池Selection已走组合策略入口；异步`CandidatePass`只产生候选，main收据和freshness后才建进程并准入。组合16项、单项重试64项缓存有界；首次冷组合在发现worker编译，主线程只捕获provider引用。

`RecipePool` 现在是公开接口，保留 `id/translationKey/recipes/machines`，`prepareRecipe(recipe)` 用于注册前应用池的公共声明，默认返回原对象；实现必须保留 ID，并原样返回已准备的配方。默认通用实现为 `DefaultRecipePool`；`registerPool` 仍创建通用池。独立外部实现通过自己的 `freeze(recipes, machines)` 保留策略与配置，返回完整不可变 canonical 实例。注册表先校验 ID、标题、全部配方及静态机器的准确对象与顺序，再统一发布目录和反查；不会把自定义池改成通用具体类。当前注册仍是一次冻结，没有新增运行时配方重载协议。

2026-11-11新增两个只读库存匹配入口：`match(AEKey2LongMap inventory)`返回首个canonical库存候选或null，`match(AEKey2LongMap inventory, int parallelism)`返回按原priority排列的不可变`List<RecipeInventoryMatch>`。每项包含准确配方对象与正int的`maximumParallelism`，表示该配方单独使用此库存时的canonical固定输入数量上界；各项不预留/分摊共享材料，也不证明FE、条件、权限和完整START可行。普通对象结果列表允许JDK接口，数量表和索引计数仍严格使用AEKey2LongMap/fastutil primitive映射。

Default/Simple两种池缓存同一固定输入索引供公开库存API复用；实际多线程/跨配方扫描继续通过预算化`beginCandidates`取候选，保留同一库存匹配规则、数量上界及freshness证明，再进入原扫描/START。通用池仅在owner完整输入路径可证明稳定时启用索引，否则保持本池原顺序fallback；未知、自定义、动态需求保留候选。Simple原有按库存key/数量阈值postings的稀疏扫描保持。单配方库存API直接查必需key；池只有一个输入key时，实际扫描用`beginQuantityCapture(key)`调用`snapshotFor(key)`，不复制无关库存。别名同context同key取max、独立context饱和汇总以及完整绑定/revision复核不变。

仅stock CrossRecipeController/MultithreadController、STANDARD factory、无preparation/最终数量selector/并行modifier且完整输入稳定时，scanner才以库存数量上界缩小原搜索范围。例如父并行100万、库存10且每次需5，只需一次真实admission probe即可选择2；FE/输出/机器条件仍可拒绝。未知子类或数量改写保留原完整搜索，最终selector/factory改变数量的既有效果不裁减。同配方来自多个池时上界取最宽松值；Long.MAX_VALUE饱和余额不据此给出有限数量上界。校验恰好用完本轮预算时保留已完成proof，下一tick继续扫描，避免稳定小预算反复校验而停滞；最终发布/穷尽前仍复核live事实。

`beginCandidates(machine, groups)` 是实际扫描调用的公开策略入口，返回原 `RecipeCandidateIndex.Cursor`。null（包括 cursor 的 null result）只回退本池完整且组兼容的配方，empty 表示本池无候选；外部结果须是本池 canonical 子集，且不得漏掉其职责下仍可能成功的配方。目录冻结时保存成员、组与原 priority；每个 pass 只采样所选 canonical 池，不先枚举它们的全量配方。各池意见先 union、准确去重并按原 priority 排序，再经过现有 external index intersection；只访问本池 fallback 或实际返回结果，不扫描全目录造 scope。`Selection.accepts` 保留目录、机器和准确配方成员约束；预算 yield 保留游标，`restartLiveIndexes` 同时重取池策略与外部索引的 live 观察。

`SimpleRecipePool.Entry.create(id, durationTicks, input, outputs, groups)` 声明单个固定 START 输入和任意数量固定 END 输出，`outputs` 为只读冻结的 `List<GenericStack>`，可为空；单输出的 `GenericStack` 便捷重载保留。`Entry.outputs()` 返回全部输出，`Entry.of`、canonical freeze、外部JEI页绑定及CT `Pool.simple` 均保留完整列表。一个输入条目不等于只消耗一个物品，输入/输出仍使用原AEKey与long数量。原始 `Entry` 不接受 TICK IO、概率/属性概率、随机输出、fuzzy/dynamic、checkpoint、selector 或其他需求。相同AEKey的配方保留原priority；索引只按固定输入的真实库存键和数量阈值访问postings，输出数量不影响匹配。最后仍进入原factory、要求/事务、共享并行、研究/计算与START准入，索引不消费材料或授予执行权限。默认选择[单输入专用JEI页](jei.md#2026-11-12-单输入多输出与动态材料布局)，有效外部页面或自定义页仍优先。

`SimpleRecipePool.forRecipes(id, titleKey, recipes)` 接收完整定义，导入池的 `SINGLE_INPUT` 使用该入口。转换结果必须恰好包含一个平铺的 START 主输入，可以是固定输入、fuzzy 输入组或 dynamic 输入组；允许其他完整需求和输出，固定输入满足稳定性条件时建立稀疏索引，不能证明固定下界时沿原匹配与完整准入处理。公共辅助输入独立配置，不改变主输入计数。多个主输入、TICK 资源输入或 `SELECTIVE/CHECKPOINT` 嵌套输入会抛出异常，禁止自动创建通用池。需要这些输入形状时，必须在首次初始化前把导入池显式设为 `GENERAL`。`freeze` 保留传入配方的准确实例和绑定，禁止重新转换来源或重跑创建回调。

`SimpleRecipePool.withAuxiliaryInputs(List<GenericStack>)` 在池上独立声明公共辅助输入，返回新池，所有现有配方均在 START 消耗这些材料。例如声明 1 mB 水后，每执行一份配方都另需 1 mB 水；N 并行消耗 N mB。辅助材料支持任意已注册 AEKey 类型，与普通固定输入使用相同的倍率、来源分配、缺料等待和事务；相同键的主输入与辅助材料累加，材料不足或数量溢出时禁止部分付款。`match`、`beginMatch`、实际稀疏候选发现及其数量上界只看原主输入，辅助材料的完整可行性在 START 检查。重复调用替换整份辅助列表，传空列表清除辅助材料，不会层层追加。

必须先设置辅助材料，再使用返回池的 `recipes()` 绑定 revision、codec、网络要求或其他按准确配方对象登记的行为；辅助材料变化会生成新的完整配方对象，原池和原始 Entry 不变。通过 `registerRecipe` 后续追加的原始单输入定义，会由注册表自动调用池的 `prepareRecipe` 并应用公共辅助材料。需要为追加定义绑定 revision、codec 或准确对象回调时，先显式调用 `pool.prepareRecipe(rawSingleInputRecipe)`，将返回对象用于绑定和注册；已经准备的完整定义原样返回，不重复追加。准备运行时替换定义也必须先调用该方法，再提交完整定义与对应 codec。`freeze` 保留传入完整配方的准确对象并校验辅助列表，禁止通过缺少辅助材料的定义绕过全池费用。`Entry.of` 继续用于原始单输入声明。更改辅助配置后必须按既有保存规则更新 revision 并保留所需旧 codec；ZS `.auxiliaryInputs(Resource[])` 自动为返回池重绑原 codec，保留自定义组件和校验函数。

正常公开使用为 `registry.register(new SimpleRecipePool(poolId, titleKey, entries))`，并在原 `MachineAssembly.instanceFactory` 选择 `MultithreadController::new` 或 `CrossRecipeController::new`。三参池沿 owner 的 `supportsStableFixedInputLowerBounds()` 查询全部实际 registered scanner/输入路径：新默认控制器的已知标准安全配置可以启用索引；未知 factory、preparation、输入 provider/overlay 或可改变输入的 local callback 自动保留本池候选。四参 `Predicate<MachineInstance>` 供外部作者显式承担完整固定输入下界保证，必须覆盖 factory、概率、modifier、preparation、overlay 与先行 START 效果，不能只根据需求形状推断。事件和输入变换能力保持可用；不能证明稳定时走正常 fallback，缺失输入被实际 factory 改成 optional 的配方仍可准入。

数量捕获复用原 `ResourceStorage.QuantityCapture`、共享 `StorageContext` 的同键 max 与不同 context 的饱和累加（`AEKey2LongMap.addTo`）。所有检查过的 hatch，包括当时 invalid/null/无效 storage 的跳过项，均参加最终 current proof；同列表中后来可用的输入会撤销旧意见。fresh 临时 adapter 按 `inputStorageIdentity`、context、有效性和原 quantity revision 核对，不要求每次返回同一 wrapper 对象。完整目录/JEI 与原静态机器绑定保持不裁剪。本阶段源码、准确 before 与实际行为/MCP/完整 build 验证证据见 `build/staging/recipe-pool-strategies/` 及 `build/staging/controller-shared-parallel/`；最终状态见本文末段与实施进度，真实 World、结构及 JEI 的后续验收保持独立。

`RecipeCandidateIndex.Cursor.beginValidation()` 与 `RecipeCatalog.CandidatePass.beginValidation()` 返回可保留的 `Validation`：`advance(budget)` 返回 true 只表示核验结束，再读取 `current()` 判断本次证明是否仍有效。内置简单池与资源索引逐 binding/已完成 quantity capture 收取预算；外部旧 cursor 的默认实现以一个 credit 调用其 cooperative `current()`。尚未发布数量的 partial capture 由自己的 revision 游标继续或重取，不能把它的 incomplete 状态当作整池失效。未完成 discovery 在新 tick 续办核验，仍有效时保留 partial 索引进度；纯候选事实不授予来源权限，目录穷尽前再次预算化核验 live 意见，失效才清候选、search 和 cursor 并重取。纯让步必须先完整结束原 probe/kernel/source 义务，不能只丢弃已获得预留的 START session。

本阶段最终行为与验证：两controller的真实worker并发及final over-cap资源/FE/PN、纯计算跨tick续办/公平轮换/live变更/空dynamic restore均已覆盖。focused70 tests/9 suites、实际完整build重跑1620 tests/206 suites全零；本机IDEA逐文件全级零和完整读回一致，最终MCP build成功。真实失败与最终日志/XML/最早before/精确提交见 `build/staging/controller-shared-parallel/` 与 `build/staging/recipe-pool-strategies/`。结构、JEI及RESCAN/isCore未据此验收；显式旧ResourceRecipeIndex同context临时adapter freshness另有待修P2，当前两默认scanner未配置该index。

2026-11-14 本轮保存清理：RecipeOverlayNbt 使用静态编解码，绑定表在构建时建立一次；默认资源和属性 codec 复用单例。ResourceInputLocks 的保存读取直接借用当前 Map，恢复接收解码完成的可修改 Map。ResourceTransferRun.Snapshot 的数组只读借用；恢复交接后原运行实例必须停止执行。代码不在 DTO 构造器或 getter 中复制数组。
