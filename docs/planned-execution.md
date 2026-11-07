# Planned 执行屏障基础

本阶段移植 PM 的 runner、取消注册和单调 deadline，为正式 planned coordinator 提供执行基础。TaskScheduler 的 PLANNED_PARALLEL 仍明确拒绝；尚未把它降级为普通 Runnable，也未声称两次 join、source 事务和真实配方已经全部接通。原行为及适配缺口见[迁移审计](planned-migration-audit.md)。

## 取消与 deadline

PlannedCancellationToken 保留首个 Reason。注册、准确注销与取消在同一锁线性化，回调在锁外依登记顺序执行；相同 Consumer 的两份注册有各自句柄。取消已取得回调时，迟到 close 不撤回该次通知；已取消 token 的新注册同步收到原 reason。

一个回调失败仍执行其余回调，最后汇总抛出原 RuntimeException/Error，不吞错。正常取消是显式状态，不用 throwIfCancelled 充当常规控制流。多字段注册协议使用锁及 volatile reason，没有逐字段 Atomic 包装。

PlannedDeadline 保存 startedAtNanos 和正 timeoutNanos，以 now-start 的 elapsed 差计算剩余时间，支持 System.nanoTime 的负值及 long 绕回。时钟倒退等负 elapsed 视为失效；Duration 转纳秒溢出直接拒绝。一个 runner 调用共享一份 deadline，不为每个 Future 重新计时。

## 有序结果与实际退出

内部 PlannedTaskExecutor 借用已有 worker 和 serial source lane executors，不创建或关闭线程池。runPrepare 调 worker，runOnSourceLanes 沿 PM UTF-16 FNV-1a 选择稳定 lane。同来源的 resolve/query/compensation 必须使用同一 affinity；补偿任务可不带 cancellation token。

每批用一把锁共同管理任务接受、实际开始、结果发布与停止快照，用批级 CountDownLatch 分别表示全部结果已确定、全部用户动作不再可能执行。任务结果保留调用方顺序：Success、Failure 或 Stopped(reason, started)。stopReason 为空也可能存在 Failure，coordinator 必须检查每项结果。

提交接受、QUEUED 转 RUNNING 及 action 返回后的结果发布都检查共享 deadline。即使 owner 等待线程尚未被唤醒，过期排队动作也不能进入，过期返回也不能被接受为成功；后者保留为已开始的不确定结果。

停止时，NEW/QUEUED 任务被标记为已知未执行，后来排到的 wrapper 不调用 action；RUNNING 任务为不确定结果并收到 interrupt 信号。已经发布的 Success/Failure 保留，迟到返回不能覆盖 Stopped。此处不调用 Future.cancel；interrupt 仍不构成实际退出证明。

source重载的beforeDispatch先交付准确Batch再接受任务；直接派发/时钟异常会完整stop/freeze后原样抛出，已保留handle仍可确认实际退出。wrapper入口/发布异常不丢finish计数。

Batch.quiescent 与 awaitQuiescence 提供额外退出证明。awaitQuiescence 被中断仍等待动作退出，之后恢复调用方中断标志并报告错误；不能提前归还机器或资源所有权。runner 等待结果时被中断返回明确 INTERRUPTED 快照并保留标志，不把它伪装成 timeout。

executor 的提交拒绝变成对应 Failure，其他已接受动作仍收束到有序结果。不确定 source 的实际结果必须由后续 source 查询/补偿协议确认；runner 不会凭丢弃迟到返回值就释放资源。取消快照与真实退出之间的 Batch 必须由后续 coordinator/retirement 持续追踪。

## 接线边界

本工具仅管理执行机制，generic Job 不证明任意对象深度不可变。正式 coordinator 必须在 owner 冻结独立输入，prepare 只读冻结数据；source mutation 使用专门 reservation/resolve 协议。不能把含 World/TileEntity 的现有 callback 直接投进 prepare。

后续按审计接 discover/snapshot/plan/reserve、prepare 屏障、owner decide、source resolve 屏障、有序 owner commit、全批 complete 和 pending compensation。跨lane的StorageFence/ResourceBatch已提供短物理锁、跨屏障独占、延迟complete和准确token恢复，见[资源批次](deferred-resource-batches.md)；业务query、真实RecipeProcess[阶段会话](recipe-tick-sessions.md)与[工位批次](work-slot-batches.md)已接；[真实阶段协调器](planned-recipe-coordinator.md)已可显式调用；服务器调度、独立纯prepare、预算消费与退休接线仍待完成。完整验证数量及 MCP 证据记在实施进度。
