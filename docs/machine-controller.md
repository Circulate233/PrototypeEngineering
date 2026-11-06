# 控制器运行时与延后清理

`api.machine.lifecycle.MachineController` 管理一个控制器实例代的装配、保存、调度登记和退出。它复用 `MachineAssembly`、`MachineLifecycle` 与真实 `TaskScheduler`，保留 PM 的 load 成功后登记、普通卸载保存进度、破坏取消工位，以及 PN 清理旧代注册的目的。旧实现的问题和源码入口见[迁移审查](machine-controller-migration-audit.md)。本阶段提供可供真实 TileEntity 持有的运行时；后续的 TileEntity、Forge 注册、方向、放置者与显示接线见[控制器集成](controller-integration.md)。

## 装配与保存

宿主在服务器线程构造并持有 controller，再调用 `load(saved)`；`null` 表示 fresh，NBT 表示 restored。同一个 controller 只接受一次加载。构造不创建机器组件，`load` 经调度器边界完成附宿主装配、完整恢复和 load 回调，成功后才登记 controller 对象。登记使用对象身份，因此同一位置旧 controller 的卸载不会注销新 controller。

MAIN 回调内请求 load 会排队，回调返回后才装配；新增 MAIN 机器在下一 tick 执行，新增 CONCURRENT 机器可进入当前 tick 后续并发捕获。load 回调也可以请求自身退出，实际清理等装配回调返回后执行。注册失败会退休并卸载已成功加载的机器，finally 尝试注销，保留主异常并聚合清理异常；故障 controller 不再加载或执行。

`save()` 只允许服务器线程调用，调用方须已取得 worker 真实退出后的独占边界。尚未装配或装配失败而未返回机器时，保存复制的原始 NBT；fresh 未装配返回 null。传入和返回的暂存 NBT 都复制，排队 load 前被停止也不会丢弃原数据。已经装配的实例始终调用完整受检查 codec；故障不能回退到旧暂存 NBT。此入口不替实际 TileEntity 定义二次 `readFromNBT` 策略。

## 停止与清理

`unload(CHUNK_UNLOAD/INVALIDATED)` 与 `controllerBroken()` 可由 owner 或 worker 请求。先让调度器接受边界动作，再设置停止请求并调用 `MachineLifecycle.retire()`；不合法的元数据回调请求会在改变机器状态前被拒绝。退出原因按 CHUNK_UNLOAD、INVALIDATED、BROKEN 升级，同一 MAIN 回调内先卸载再破坏会在回调结束后执行一次破坏清理。

退休是单向 volatile 标志，只撤销新业务，不代表 worker 已退出。已经进入的事务获取、提交、回滚与终态清理完整结束；新的 START/TICK/END、后续工位、候选探测和 admission 停止。探测中的预留和局部 preparation 完整回滚，scanner 返回 PAUSED。新结构发布被拒绝，清空旧结构仍可执行。健康退休机器仍可保存；退休标志不写入 NBT，也不能在旧实例上重置。

清理只在 scheduler owner 的边界执行：IDLE 可立即执行，MAIN 等当前回调返回，并发阶段等所有已接受 worker 真实 join。普通卸载保留已提交进度；破坏依次执行 broken、cancelAll、unloaded，不执行 END 或退历史 IO。清理失败仍尝试注销；重复退出不重放，不复活旧实例。清理已开始后到达的重复破坏不会另开一轮取消。

`runAtBoundary` 使用独立 FIFO，普通调度 fault 不丢弃已接受的清理动作；动作失败继续尝试后续动作并聚合。关闭时先证明 owned executor 全部终止，再清队列并在同一锁内关闭接收；未证明退出则保留队列和资源供 close 重试，不能提前清理。详见[调度边界](scheduling.md)。

## 验证与剩余接线

逻辑测试覆盖完整恢复后登记、注册失败清理聚合、MAIN 自卸载升级破坏并取消真实工位、两个真实 worker 退出后清理、元数据请求拒绝、排队 load/NBT 隔离、普通保存恢复、同位置新旧代和故障不复活。退休测试检查实际阶段提交/暂停、预留回滚及拒绝迟到结构；scheduler 测试检查 FIFO、故障、中断与 executor 终止证明。完整数量及 MCP 逐文件结果见[实施进度](implementation-status.md)。

实际空构造 TE、二次 NBT、新代恢复、零声明通用 TE 注册、24 向、结构调度/失稳重试、放置者与独立显示和默认模型已接入，见[控制器集成](controller-integration.md)。仍需完成持久身份/搬运/owner权限、GUI/工具/预览与复杂渲染。正式 planned 的双 join、PN owner/team/epoch/向量预算也未由普通清理队列实现。按用户约束未启动游戏或 Bootstrap/FML 测试初始化。
