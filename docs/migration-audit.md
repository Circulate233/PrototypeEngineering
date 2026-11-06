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

## 修正顺序与验证依据

1. 从旧组件装配、调度、事件和生命周期调用链建立新 Java API；明确哪些是扩展接口、哪些是普通实现类，避免机械的接口/Impl 配对。
2. 改造 MachineRecipe，保留需求描述和扩展入口；把资源身份迁到 AE2，把内置资源处理从执行器中分离。
3. 恢复进程组件回调与 start/tick/end 需求执行；统一阻塞、失败、提交、回滚、持久化语义，再决定现有收支代码哪些可以复用。
4. 用独立外部组件/需求测试实际回调顺序、阻塞与重试、失败后停止执行、状态恢复。仅创建类或编译成功不能证明扩展可用。
5. 配方池与 JEI 读取修正后的同一模型，再完成默认和自定义页面。所有使用样例保持独立，主产物不注册玩法内容。

这份审查记录缺口和源码证据，不表示缺失能力已经实现。后续进度须以具体代码、调用路径和行为证据更新。
