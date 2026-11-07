# 工位阶段批次

`MachineWorkSlots.prepareTickBatch()` 捕获本轮真实 occupied slot 和其中的 `RecipeProcess`，不沿用 PM `RegistryBackedMachinePlannedExecution` 的空 process 占位。`TickBatch` 在首个回调前登记到宿主，固定创建线程、准确 slot/process、初始 State 和 batch 对象身份。它不授予 World、队伍或预算权限。

## 获取与推进

prepare 不运行组件或需求，立即限制宿主的 admission、普通 tick、snapshot/restore、detach、cancel 和 quiescent 检查。被捕获的 process 也不能通过直接 tick、保存或更换宿主绕过批次。`begin()` 按工位顺序运行实际进程的 pre/tick，失败保留此前已创建会话；后续未开始的工位不补跑回调。

宿主回调作用域覆盖 session/stage、需求事务和同步源结算：一个工位的 pre、post、acquire、业务及恢复回调不能进入同宿主其他进程。raw RecipeStageExecutor/requirement 获取入口同样检查当前 Stage 的真实 acquiring 作用域，prepared/pre 阶段不能绕过 round 提交 IO。多宿主整体作用域现由prepareTickGroup/TickGroup持有：所有host验证后一起安装lease，子batch完成前后都不能被其他host回调重入，全部子batch结束才统一释放。

`nextRound()` 为每个仍可运行的进程创建至多一个当前 Stage，前一轮任何 handle 未结算都拒绝推进。由调用方 acquire 真实需求，再结算当轮；`currentStages()` 保留部分创建的阶段，供异常与取消路径收束。START→一次 TICK→END 沿既有 TickSession，0 速度不会重复 TICK。

正常 Blocked/Failure 不停止其他工位。驱动应先准确补偿并结算 rejected stage，再只把 SUCCESS stages 交给 `Stage.tryAcquireBatch`；不能把 rejected stage 混入将执行 COMPLETE 的批次。后者仍按原 source lane apply/resolve，owner 业务提交与源终态证明由 ResourceBatch 负责。

显式 `RecipeExecutor.DEFAULT` 表示内置生命周期；原自定义 `RecipeExecutor` 继续走同步入口，必须返回前完成全部执行。调用批次入口时若配置了自定义同步 executor，会在执行前拒绝，避免跳过使用方的实际逻辑。默认工位声明和构造器使用同一个 DEFAULT 实例。

## 结束、故障与取消

`stop()` 只停止后续轮次。尚未 acquire 的 Stage 用 `discard()`，已 acquire 的 Stage 按原事务准确补偿；取消不应为了释放 prepared handle 而再调用需求。

`tryFinish()` 在有当前阶段时返回 false，不提前 post。当前轮全部结算后，逐个尝试所有已开始会话的 post，并同步健康工位结果；健康的 COMPLETED/FAILED/CANCELLED 进程解除挂接，Blocked 或 RUNNING 继续保留。清理故障继续尝试其他工位并聚合异常。post 已执行但局部 transaction 仍未确认时保留 batch，重试不再 post。

postAttempted 使用 TickSession.postStarted 的实际入口证明；未进入 post 就遭拒绝时不会把后续清理误标为已完成。

`recordFailure` 停止后续轮次、标记宿主与实际活跃会话，不把尚未开始的工位假装成已执行。发生异常后，通过 `pendingTickBatch()` 取得同一批次；`finished()` 只证明宿主 lease 已真实关闭，不代表故障被清除。故障进程保留在槽内供诊断，恢复确认不会恢复健康或自动重试配方。所有进程已不活动时，即使 post 抛错也可准确关闭批次。

`RecipeProcess.quiescent()` / `persistenceReady()` 是局部状态事实；前者不等于宿主已释放，后者也不是保存权限。正式边界仍使用 require 方法并检查宿主/机器约束。`WorkSlot` 是 owner 管理的 live 对象，result 仍读取已挂接进程当前状态；延迟 setState 不提供跨线程发布隔离，GUI 和序列化必须在原 join/宿主边界后读取。

## 剩余接线

此入口和TickGroup已由[真实阶段协调器](planned-recipe-coordinator.md)显式驱动，尚未开启 `PLANNED_PARALLEL`。真实TaskScheduler/服务器、纯prepare扩展、owner/team/epoch/预算消费及取消/退休join仍待接入。不能把阶段/工位协调测试通过等同于正式服务器调度完成。

来源、已确认旧实现缺陷见 [planned 审计](planned-migration-audit.md)，进程会话见 [阶段会话](recipe-tick-sessions.md)，验证记录见 [实施进度](implementation-status.md)。
