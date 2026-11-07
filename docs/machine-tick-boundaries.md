# 可暂停的机器组件与生命周期边界

正式 planned 接线需要在机器的准确组件位置暂停，并在资源清理期间持续持有生命周期所有权。本阶段提供 `ComponentMap.captureDispatch`、`MachineLifecycle.beginScheduled` 和 `MachineWorkSlots.quiescent`，普通同步入口保持原行为。它们尚未安装进 `MachineInstance`、`TaskScheduler` 或服务器；`PLANNED_PARALLEL` 仍明确拒绝。

## 旧实现核对与组件顺序

PM `impl/MachineInstanceImpl.kt:249–280` 的普通路径按全部 pre、全部 normal、全部 post 调用；后续回调继续执行来自逐项吞异常。新项目已按用户要求移除吞错，所以机器采用 fail-fast。旧 `impl/scheduler/backend/JavaTaskSchedulerBackend.kt:209–211` 的 planned 路径只调用 coordinator，`MachineInstanceImpl.kt:304–309` 只委托 discover，并没有运行这套机器组件回调；不能把这个缺口作为跳过现有组件的依据。

扫描和配方处理均发生在 normal 阶段。当前 `RecipeScanningSystem.runBefore` 保证扫描先于 `WorkSlotProcessingSystem`，扫描本 tick 刚接纳的进程应参与后续处理。不能先运行整个 `onSchedule` 再补 planned，也不能把所有后置 normal 组件提前到配方完成之前。后续接线应在准确的处理器回调处消费一次回调义务、捕获真实工位组并暂停，成功结算后才继续后置 normal 和机器 post。

机器和配方进程的异常语义不同：机器 pre/normal/post 首错停止后续回调；配方进程的 post/事务补偿有独立的保留和聚合契约。deferred 处理器失败后应终止机器 cursor，由准确 coordinator 完成配方清理，不擅自补跑机器 post。

## 捕获的组件 dispatch

`captureDispatch(owner)` 无回调地冻结同一份组件值、系统顺序及依赖图。`TickDispatch.begin()` 一次运行全部 pre；`advance()` 只进入下一个 normal callback，返回 false 表示没有剩余 normal；`finish()` 仅在 normal 全部消费后运行 post，首错即停。游标在 callback 入口前推进，失败后进入不可恢复的终止状态，不能重放失败项。`abort()` 可终止尚未开始或暂停中的 dispatch，不执行 post。

同一 cursor 的 begin/advance/finish/abort 均限定创建线程，callback 内不能递归控制 cursor。回调期间的组件替换、移除和依赖编辑只影响下一份 dispatch。普通 `ComponentMap.tick` 仍走原同步路径，配方进程的 `beginTick` 仍保留原 post 聚合行为。

cursor 只管理组件回调，不代表 worker 退出或资源事务完成。正常 callback 若将工作交给 deferred coordinator，调用方必须保留该 coordinator，在其准确 `finished()` 之后才能继续 cursor；失败时先终止 cursor，同时保留事务清理职责。cursor 本身不提供跨机器排他，也不允许绕过生命周期或工位 lease。

## 持续的机器执行所有权

`MachineLifecycle.beginScheduled()` 在真实 owner 上取得原共享 VarHandle 的 RUNNING gate，并捕获本机工作槽。取得句柄后，暂停期间也不能普通执行、保存、改变结构或卸载。未加载、破坏或退休实例不启动新工作；已故障实例保持原拒绝行为。

`ScheduledExecution.run` 只允许有效实例继续前进，退休或故障后返回 false；`cleanup` 仍允许准确 owner 进行既有清理。实际回调异常原样报告并使机器永久故障；句柄和 gate 继续保留，不能在调用段的 finally 无条件释放。`recordFailure` 用于记录 deferred 工作传回的实际故障。

`tryFinish()` 按对象身份累计最初捕获和每次检查观察到的工作槽，确认全部后才释放；后加工作槽一旦被观察，即使再次移除或替换也不能隐藏其清理义务。正常 pending 返回 false，不借异常充当控制流；确认全部 quiescent 后才释放 gate。`MachineWorkSlots.quiescent` 检查宿主执行、activeBatch 和真实 process；错误的宿主身份仍报错。该查询是事实，不授予保存权限，也不自动解除 TickGroup 的 lease。

句柄同样要求准确创建线程且拒绝 callback 重入。它不负责自动运行机器 cursor 的 post，也不自动 join source；调用方必须先让 cursor 正常完成或失败终止，再证明 worker 与资源清理完成后请求释放。跨机器全部先捕获、低层工位操作的共享 callback scope 和准确调度身份将在真实接线阶段实现。

## 接线前必须处理的调度问题

- `TaskScheduler.tick` 当前只 join 普通 Future，然后排空 owner boundary。真实 planned 的退出证明、资源结算和机器 cursor 必须一起纳入边界，不能把 timeout 结果当实际退出。
- pending 的 `ResourceBatch` 仍持 `StorageFence`，普通 worker 可能等待它。下一 tick 必须在任何新业务之前恢复旧 pending；否则 owner join 普通 worker、worker 等 fence、owner 无法清理，构成死锁。任意 Runnable 没有完整资源声明，不能只凭 affinity 假定无冲突。
- `close` 不能先关闭 source lanes 再清理。UNKNOWN 未解决时保留 scheduler/session 和原句柄；确认清理后才终止线程池并排空卸载队列。
- `MachineController` 目前把清理异常视为失败并注销；真实接线需使正常 pending 保留停止请求，不能伪装已卸载，也不能把 pending 当永久故障。
- 跨 server tick 保留的工作只可清理，不借新 epoch/publication 或新 cleanup deadline 恢复前进。PN 的 `WorkPermit.current` 不等于全部 Capture 的 owner 重验，更不等于 receipt 已消费额度。
- 独立纯 prepare/frozen input/result、PN 预算消费者及正式注册仍待迁移。当前 source apply/terminal 两次屏障不替代纯 prepare，也不允许只解除 mode 拒绝就声称完成。

## 验证范围

组件测试覆盖暂停顺序、同一捕获图、回调错误后禁止重放、外部 deferred 失败终止及外线程/重入保护。生命周期测试覆盖跨暂停的 gate、退休后清理、未确认进程以及原有/后加工作槽被移除后的保留、实际故障不复活。完整构建与本机 IDEA MCP 的逐文件证据见[实施进度](implementation-status.md)。不启动 Minecraft、服务端或 Bootstrap/FML，也不使用桌面 IDE 检查。
