# 机器生命周期与宿主边界

本阶段迁入 PM 的加载、成型变化、控制器破坏与卸载事件，并将其接到现有机器组件、扫描、工位和 NBT。新项目仍为库；没有注册正式控制器、机器或配方。实际结构匹配、调度注册、两次 planned join 和 PN 权限尚待接入。

## 公开职责

- `MachineLifecycleListener` 可由机器组件直接实现，也可注册到 `MachineLifecycleRegistry`。不再另造仅用于标记的 lifecycle component 接口。
- `MachineLifecycleRegistry` 是实际注册表类，提供 GLOBAL 或独立实例。按对象身份去重，注册和取消在同一锁内完成；静态 `Subscription` 只取消自己的注册。写端用 fastutil 保存条目并发布不可变快照，派发时不持注册表锁。
- `MachineLifecycleHost` 提供宿主身份、世界和服务端线程校验，允许外部控制器实现。`ControllerLifecycleHost` 是已有 TileEntity 的适配器，校验服务端线程与绑定的维度/位置；不会注册方块或调度任务。
- `MachineLifecycle` 管理一次实例的边界，构造时绑定到 `MachineInstance`，不允许替换或跨机器共享。`MachineIdentity` 复制不可变 BlockPos，仅是类型/位置身份，不是执行许可。
- `MachineLifecycleEvent` 的四种记录共享固定的 identity/formed 上下文。Loaded 保留 FRESH/RESTORED 区分；卸载还可表明 LOAD_FAILED。world/server 访问只供同步服务端监听，不能保留给工作线程。

组件按依赖顺序先调用，全局监听按注册顺序后调用；两组参与者在首个回调前统一捕获。回调修改组件或注册表只影响下一次普通发布。加载、成型回调立即传播首个错误；破坏、卸载属于清理，尝试所有捕获监听并以 suppressed 聚合后续错误。

## 宿主调用与普通配方

| 边界 | 宿主与框架行为 |
|---|---|
| 创建/恢复 | 完成全部组件 NBT，再调用 `load(FRESH/RESTORED)`；只有加载成功后宿主才可注册调度。恢复的工位没有自动获得 formed 或 PN 权限。 |
| 加载 | 一个实例只加载一次。回调失败自动关闭实例并向同一捕获参与者发送 LOAD_FAILED 卸载，避免先前已注册的外部运行状态泄漏；清理须容忍未完成/未执行的 load。 |
| 成型 | 宿主在独占边界传入发现的 `MachineHatches`；先替换绑定，再发布实际 formed 值变化。已成型时重建绑定不重复发布 true→true。 |
| 结构失效 | 清 MachineHatches，变为未成型，暂停扫描和工位系统；保留普通进程及历史 IO。其他机器组件仍执行，不能把网络设备等职责一并停掉。 |
| 普通卸载/失效 | 先关闭执行门，再卸载监听；不 cancel、不 END、不退历史 IO。稳定工位可保存，由新实例恢复并重新验证结构后续跑。 |
| 控制器破坏 | 先关闭工作门；依次尝试 broken 监听、已捕获的本机工作槽 cancelAll、INVALIDATED 卸载。无论前项失败都尝试后项；取消仅清自有效果，不退历史消耗。重复破坏/卸载不重放。 |

结构匹配已通过 `StructureRefresh` 接到生命周期：`refreshStructure` 在 TRANSITION gate 内复核请求、当前事实和公开仓室，最后一起安装匹配树及绑定；失去稳定性的候选等待重试，真正失配才清绑定。当前异常故障机器，已经失去配置权限的异常仍报告但不故障替代配置；同朝向仍成型的重新发现更新仓室而不重复成型事件。实际控制器 TileEntity 与调度器的调用仍待接线，详见[结构说明](structures.md)。附加 `RecipeScanningSystem.ready` 仍只约束 admission；loaded/formed/broken/faulted 的公共门同时约束扫描和 `WorkSlotProcessingSystem`。低层 `RecipeProcess.tick`、`MachineWorkSlots.tickProcesses` 等仍要求调用方拥有并确认合法的执行权，不能绕过世界宿主自行调度。

## 失败、并发与保存

`MachineInstance.onSchedule` 对已绑定宿主经过 `MachineLifecycle`；未绑定实例用于独立库逻辑，不携带世界许可。已卸载实例不再执行组件。实际 tick/callback 异常使绑定实例不可继续执行或保存；普通配方 Blocked 仍可在合法状态重试。

共享 VarHandle 对实例内的执行/生命周期阶段做 CAS 和 release/acquire 发布，拒绝重入及重叠。生命周期还检查宿主服务端线程、工作槽和进程没有未完成 acquisition。普通 TaskScheduler 已实现真实 worker/lane join，错误和中断都在接受的动作退出后才归还 owner；MAIN 尚未派发 worker 时允许登记/注销其他控制器。CAS 门本身仍不等待 worker，planned 超时、owner/epoch 和跨机器共享世界安全尚需专门协议，详见[调度说明](scheduling.md)。

`requireQuiescent` 与可保存状态分开：已经抛错但不再持有活动阶段的进程，仍允许宿主清外部注册；它仍不能被保存、重跑或冒充已补偿。`MachineWorkSlotsNbt` 在编解码边界检查绑定机器状态；保存检查本身不是覆盖整个序列化过程的锁，调用方必须已经持有独占所有权。外部 codec 仍须遵守纯编解码契约。

异常组件图不能阻止全部卸载：若正常拓扑捕获失败，清理保留最后一次有效组件参与者，并按对象身份补充当前无排序装配及当前全局监听；已经取消的全局订阅不会恢复，也不会由旧快照持续引用。可识别的本机槽继续处理，外机槽不得取消。原捕获错误与清理错误最终照常抛出，实例保持故障；这条路径只用于异常清理，不能作为正常排序替代，也不宣称已经恢复依赖顺序。

## 源码复核依据

| 旧实现 | 保留目的与修正 |
|---|---|
| PM `MachineBlockEntity.kt:285–347` | load 后注册、卸载注销的生命周期；普通卸载不清配方。本项目当前提供接入边界，真实调度注册仍待迁移。 |
| PM `MachineInstanceImpl.kt:349–399` | 保留 loaded/formed/broken/unloaded 区分。修复 load 失败仅改 UNLOADED 而不清先前监听、卸载直到 finally 才关 active 的问题。 |
| PM `MachineLifecycleDispatcher.kt` | 保留组件在前/全局在后的顺序，移除重复 marker 与 Impl 层；清理阶段改为全部尝试。 |
| PM `MachineLifecycleRegistryImpl.kt:12–16` | 原 CopyOnWrite 列表的 none→add 不是原子操作；新注册表在同一锁内去重并插入，旧 Subscription 不误删重新注册。 |
| PM `FactoryRecipeProcessorSystem.kt:28–35` 与 `RegistryBackedMachinePlannedExecution.kt:76–85` | 普通处理器缺 formed 门，而 planned 已明确 unformed=PAUSED；现为普通扫描/推进补门，保留已运行进程。 |
| PN `NetworkDeviceRuntimeIntegrationImpl.java:348–390,733–757` | FRESH/RESTORED 用于身份恢复/实际放置者初始化；卸载/破坏清 station/source/device/planned 运行注册，不是退历史配方消耗。 |
| PN `NetworkDeviceRuntimeComponentImpl.java:81–100` | 仅身份持久化，恢复把工作状态/epoch 重置为待发现；后续仍须从当前宿主重新授予权限。 |

PM planned 的 prepare join 在 `PlannedParallelCoordinator.kt:102`、resolve join 在 140，中间主线程 decide、之后 commit；它们与普通 concurrent join 不同。结构 predicate 已改为 worker 只读捕获快照，并在服务端发布前复核；真实调度 join、owner/epoch 以及世界宿主注册没有由这个读取协议自动完成。

## 验证范围

17 项新增聚合测试覆盖监听身份与并发注册/取消、事件捕获顺序、加载失败清理、破坏/卸载聚合与幂等、跨线程/重入/重叠、结构暂停及旁路组件、工位卸载保存与新实例恢复不重 START、取消不 END/退款、活动预留拒绝、faulted 进程清理及损坏组件图的异常清理。没有常量/哈希/getter 专用测试，没有初始化 Bootstrap/FML 或运行游戏。

结构发布另有 16 项读取证明/宿主接线测试，普通调度另有真实 worker/join/MAIN 生命周期测试；完整构建与逐文件本机 IDEA MCP 证据以 [实施进度](implementation-status.md) 为准。实际 TileEntity 自退出/恢复及能力发现的游戏验证、PN 当前身份/队伍/epoch、正式 planned 与补偿仍是必须完成的后续项。
