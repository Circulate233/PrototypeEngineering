# 配方与组件迁移

`MachineRecipe` 已恢复为按需求职责分组的公开定义。它保存 id、时长、配方组及 `RecipeRequirementType<?> → List<RecipeRequirement<?>>`，不再以固定输入、输出和 FE 字段限定配方。需求类型绑定自己的 `RecipeRequirementSystem<C>`；资源身份继续使用 AE2 的 AEKey/AEKeyType，不再建立 PMKey 的替代类型体系。

本模块是库，不注册正式机器或玩法配方。参数、内容和自定义需求由使用方提供。当前已迁移定义、组件调度、整阶段协调结算及固定资源/FE 的三阶段需求；完整加工循环、高级需求、属性与并行运行仍在迁移中。

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

注册事件只负责收集定义，与机器 tick 事件、需求阶段、进程组件回调是不同机制。

## 已迁移的组件驱动

`api.component.TopologicalComponentMap` 来自 PM 的软依赖图与 Kahn 排序：保留动态添加/替换、依赖延迟出现、删除后重新安装、addBefore/addAfter/addFirst/addTail 以及按修改失效的排序缓存。依赖集合与返回列表使用独立不可变快照，避免调用方修改后绕过缓存失效。

`ComponentType<E,C>` 是普通描述类，绑定工厂和多个系统；组件值无需机械增加一个接口及 Impl。需要外部实现的处理职责保留在 `ComponentSystem` / `MachineSystem` 接口。系统以显式 ResourceLocation ID 声明顺序，替代以运行时 Class 识别系统；无反射扫描。

`ComponentMap` 把系统顺序作用于组件依赖，同组件的多个系统保留声明顺序。系统元数据须保持不变；动态重组通过组件表进行。每个 tick 只取一次完整执行列表，依次执行全部 Pre、全部 Normal、全部 Post。回调中发生替换时，本 tick 的全部阶段仍使用同一旧列表，下个 tick 才采用新列表。系统抛错立即终止后续阶段。

`MachineInstance.onSchedule()` 调用这一分派；`MachineSystem` 默认把阶段适配为 `MachineTickEvent.Pre/Normal/Post`，具体系统也可覆盖阶段方法。此入口尚未接入世界控制器、结构和真实调度器，不能将逻辑调用成功称为机器运行已完成。

## 需求阶段契约与整阶段完成依赖

`RecipeStageExecutor` 按 PM `FactoryRecipeProcessorSystem.executeStage` 的实际行为迁移：

1. 依次调用各类型系统的 start、tick 或 end，收集事务及其 `reservations()`；取得的资源/FE 令牌只交给阶段协调器，单个需求不得自行完成它们。
2. 全部成功后，将全部真实 StorageContext 按固定顺序加锁，校验并 apply 全部令牌，然后按需求顺序执行业务 commit。commit 在此表示可补偿的业务应用，不是最终完成。
3. 业务 commit 全部成功后才依次 complete，释放业务与存储补偿权，再统一解锁和通知。complete 不允许业务拒绝或抛错。
4. 遇到 Blocked/Failure、令牌失效或获取/应用/业务异常时，锁内先逆序 rollback 业务事务，再逆序撤销资源/FE 令牌；包括返回阻塞/失败的那笔。异常保留原错误，继续其余补偿并聚合 suppressed。
5. 完成阶段的契约错误、完成后的通知错误立即暴露，不把已经跨过完成边界的事务当作未提交状态再次回滚。

这里统一了旧接口注释与执行器对 Blocked 的矛盾，以实际整阶段回滚行为为准。资源不足使用 Blocked，契约错误使用异常；捕获只用于异常清理，不作为常规流程。

统一完成边界已经接好，并覆盖跨需求类型、存储和业务状态的补偿。公开需求系统仍须覆盖部分获取产生的全部效果；业务 commit 失败时必须原子或保有完整撤销信息，默认 no-op complete 不会让旧的不可逆业务自动变成可补偿操作。外部回调只访问已加入批次的存储，不得持锁等待另一执行 lane。当前同步批次不是跨 lane/join 的 distributed 提交实现。

`RecipeProcess` 已有 owner、独立组件容器、配方引用和确定性 seed。属性 overlay、需求 overlay、状态持久化、进程组件围绕配方执行的回调时机、多工位扫描及完整开始/推进/结束循环尚未迁完。

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

两个 payload 是可扩展配方模型中的内置需求，不限制外部 RecipeRequirementType。ResourceRequirement 的各列表使用不可变 GenericStack；物品、流体及其他 AEKey 类型共用算法，start 消费 inputs、tick 消费/产出每刻列表、end 产出 outputs。EnergyRequirement 按 PM 的 start/tick/end 输入输出语义保存独立 long FE，不定义 FE key。当前数量是声明的一次执行量，有效并行数、概率和属性缩放将在进程状态迁移时接入，不宣称已具备这些高级行为。

资源同 key 条目先精确聚合，按声明和仓室顺序分配，别名通过真实 context 去重；快照/模拟和确切预留在同一个 context 锁内完成。FE 的单次需求分配也只遍历每个真实来源一次，按未预留余额/空间及当前单次限额取交集，不累计重复模拟同一余额。多个需求共享存储时，后续需求看到前面已经占用的数量/容量；任一需求阻塞会释放整个阶段的占用。

`voidExcessOutputs` 对应 PM 的 ignore_output_full：只预留实际可接收量，允许丢弃放不下的剩余产物；没有任何有效目标时仍阻塞。未启用时必须预留完整产出。输出阻塞时不会留下部分写入，重试需要重新取得阶段令牌。内置原因统一由实现 LocalizationEnum 的 `RecipeText` 提供中英键，不在系统中拼接翻译键。

## 资源存储纠正

用户明确要求：复用 AE2 的类型系统，不以 MEStorage 作为核心存储契约。新存储、事务和预留 API 应独立设计，以保证配方与并发需求；AE/Forge 库存接口仅用于外部适配。详见[存储重构约束](storage-redesign.md)。

独立资源后端已完成：`ResourceHatch` 返回 `ResourceStorage`，`SlottedResourceBuffer` 独立持有余额、输入数量和输出容量预留及补偿状态，AE2/Forge/GUI 库存接口只作适配。共享物理余额的视图使用同一 context，资源批结算按 context 顺序锁定。这是已实现的存储边界，仍须接入需求阶段和真实调度协议。

旧固定资源流程已移出公共配方模型，改名为内部 `ResourceTransferPlan` / `ResourceTransferRun`。它已通过当前 `ResourceHatch` 使用独立 `ResourceStorage`，不再依赖旧核心库存接口；但仍采用直接部分收取、FE 付款余额、输出重试和内存检查点流程，是核对资源守恒的迁移材料，**不是新需求执行器、阶段预留适配器或并发运行基线**。它不再从公开 RecipePool 选择配方。早期 `ResourceTransferNbt` 没有任何生产或测试调用方，也未接入机器保存；已移除这份固定资源格式的死代码。正式配方进程持久化仍须按 PM 的进程/组件职责迁移，并覆盖恢复验收，不能用该原型代替。

不能只把直接库存操作包装成“支持 rollback”就接入阶段事务：源可能失效，输出可能已被其他机器取走，输入退回也可能没有容量。需求迁移须使用独立存储已有的预留、提交和补偿能力，继续明确与 FE、业务状态、宿主失效、通知及 PM/PN 调度的共同边界。

## 高级需求保留范围与迁移依赖

PM 的高级需求是必须继续迁移的行为，固定资源/FE 需求不能替代它们。完整源码映射和旧实现缺陷见[高级需求审计](migration-audit.md#高级需求能力与源码入口)。

- 物品保留 START 固定、模糊和动态匹配输入，END 固定和加权随机输出；流体还保留 TICK 固定输入/输出。概率结合有效并行数和进程属性，支持超过 100% 的概率、按阶段/刻隔离的确定性随机及保守容量检查。
- 模糊候选按声明顺序选择；动态物品输入保留外部 matcher、运行时枚举、候选上限和 JEI 展示候选。解析后的 AEKey、选择器候选和随机结果属于独立进程状态，不能写入共享配方负载。选择及输入获取的回滚必须一致，避免已回滚输入被既有选择标记跳过。
- 加权输出保留每个输出循环按权重无放回选取多个候选；`ignore_output_full` 保留部分产出并丢弃余量的效果。容量预留须覆盖实际确定的 key 和数量，不能只检查数量最大的候选而宣称覆盖所有随机结果。
- 选择性包装器保留按顺序选择首个 START 成功的子需求、全程沿用选择、无可用候选时本次进程禁用但不阻塞，以及外部 modifier 施加进程属性效果。阻塞重试不能遗漏候选输入，也不能提前删除仍应生效的 modifier。
- 需求 overlay 保留按类型和实例 ID 定位、每进程覆写有效需求而不改基础配方，以及外部 overlay applier 和 modifier 扩展。物品/流体 IO、流体每刻 IO、能源速率和满输出策略都是已有覆盖效果。
- 属性、有效并行数、刻状态、解析锁、选择及 modifier 引用、overlay 和保存恢复仍未落地。planned 迁移还需要这些定义和状态的明确快照/投影契约；PM 当前物品/流体 snapshot 拒绝非空 properties，固定消耗投影不能代表高级效果已支持 planned。

依赖顺序为：整阶段资源/FE/业务完成边界与公开来源访问 → 固定内置需求 → 属性、并行数和进程刻状态 → 确定性概率、解析锁、模糊/动态、随机产出、选择器与 overlay → 完整保存恢复、扫描和 planned 集成。实现时保留外部需求、matcher、modifier 和 overlay 扩展职责，修正旧实现的模拟、选择和补偿缺陷；这一排序不取消任何能力。

## 验证与未完成部分

新增行为用例覆盖外部组件的相位顺序、软依赖/替换/重装、异常终止；外部需求经过公开配方池注册后，覆盖同类型/跨类型顺序、部分获取后的整阶段回滚、重试、各阶段分派、进程状态隔离以及获取/回滚/提交错误的传播。旧资源守恒测试保留，不再冒充公开配方链路的验收。

本轮增加公开配方池→MachineHatches→固定资源/FE 三阶段的行为验证：输出阻塞及重试、别名去重、多个需求争用、单来源 FE 操作限额和明确允许的余量丢弃；阶段用例另覆盖业务提交异常补偿和终态通知故障，能源用例覆盖真实并发与跨资源结算。准确数量和 MCP 证据见[实施进度](implementation-status.md)。

尚未完成：高级概率/模糊动态/选择器、属性与需求 overlay、完整进程状态与恢复、多工位扫描、机器生命周期、真实并发/亲和性/planned 预留补偿、结构宿主和 JEI。独立资源/FE 预留与同步整阶段协调已完成，不能继续列为未实现。所有剩余项都是必须继续迁移的功能，不是可选项。当前不启动 Minecraft 客户端或服务端。
