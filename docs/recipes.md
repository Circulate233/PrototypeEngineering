# 配方与组件迁移

`MachineRecipe` 已恢复为按需求职责分组的公开定义。它保存 id、时长、配方组及 `RecipeRequirementType<?> → List<RecipeRequirement<?>>`，不再以固定输入、输出和 FE 字段限定配方。需求类型绑定自己的 `RecipeRequirementSystem<C>`；资源身份继续使用 AE2 的 AEKey/AEKeyType，不再建立 PMKey 的替代类型体系。

本模块是库，不注册正式机器或玩法配方。参数、内容和自定义需求由使用方提供。当前已迁移定义、组件调度和阶段事务分派；完整加工循环、内置需求、属性与并行运行仍在迁移中。

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

## 已迁移的需求阶段事务

`RecipeStageExecutor` 按 PM `FactoryRecipeProcessorSystem.executeStage` 的实际行为迁移：

1. 依次调用各类型系统的 start、tick 或 end，收集已获取的事务。
2. 全部成功才按正序 commit。
3. 遇到 Blocked 或业务 Failure 时停止获取，按反序 rollback 所有已获取事务，包括返回阻塞/失败的那笔。
4. 获取阶段抛出错误时反向清理已有事务，并继续抛出原错误；清理错误附加为 suppressed，不会吞掉。
5. commit 必须成功；违反契约立即抛错并停止，不能假装已提交事务还能按未提交事务回滚。

这里统一了旧接口注释与执行器对 Blocked 的矛盾，以实际整阶段回滚行为为准。资源不足使用 Blocked，契约错误使用异常；捕获只用于异常清理，不作为常规流程。

`RecipeProcess` 已有 owner、独立组件容器、配方引用和确定性 seed。属性 overlay、需求 overlay、状态持久化、进程组件围绕配方执行的回调时机、多工位扫描及完整开始/推进/结束循环尚未迁完。

## 资源存储纠正

用户明确要求：复用 AE2 的类型系统，不以 MEStorage 作为核心存储契约。新存储、事务和预留 API 应独立设计，以保证配方与并发需求；AE/Forge 库存接口仅用于外部适配。详见[存储重构约束](storage-redesign.md)。

旧固定资源流程已移出公共配方模型，改名为内部 `ResourceTransferPlan` / `ResourceTransferRun`。保留原有部分收取、同源去重、FE 余额、输出重试及内存检查点代码，便于核对资源守恒；它仍依赖旧仓室接口，是待替换的迁移材料，**不是新需求执行器、事务适配器或并发运行基线**。它不再从公开 RecipePool 选择配方。早期 `ResourceTransferNbt` 没有任何生产或测试调用方，也未接入机器保存；已移除这份固定资源格式的死代码。正式配方进程持久化仍须按 PM 的进程/组件职责迁移，并覆盖恢复验收，不能用该原型代替。

不能把现有 MEStorage 操作包装成“支持 rollback”就接入阶段事务：源可能失效，输出可能已被其他机器取走，输入退回也可能没有容量。独立存储必须明确预留、提交、补偿、失效和通知边界，连同 PM/PN 调度协议一起迁移。

## 验证与未完成部分

新增行为用例覆盖外部组件的相位顺序、软依赖/替换/重装、异常终止；外部需求经过公开配方池注册后，覆盖同类型/跨类型顺序、部分获取后的整阶段回滚、重试、各阶段分派、进程状态隔离以及获取/回滚/提交错误的传播。旧资源守恒测试保留，不再冒充公开配方链路的验收。

尚未完成：独立存储后端及内置资源/FE 需求、属性与需求 overlay、完整进程状态与恢复、多工位扫描、机器生命周期、真实并发/亲和性/planned 预留补偿、结构宿主和 JEI。它们都是必须继续迁移的功能，不是可选项。当前不启动 Minecraft 客户端或服务端。
