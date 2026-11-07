# 调度器持有的 planned 执行与清理

本阶段让 `TaskScheduler` 实际持有并驱动完整的 `PlannedExecution`，复用原 ordinary workers 和固定 source lanes。内部 factory 路径已通过真实 FE、source token 和 `PlannedRecipeCoordinator` 测试；服务器已安装 NetworkRecipeExecutionFactory，将带 NetworkRecipeWork 的 CONCURRENT 机器自动接入真实身份、组件游标、PN 预算及来源结算，见[配方消费者](network-recipes.md)。`PLANNED_PARALLEL` 与独立 pure prepare 仍未开放，独立三参 scheduler 不自动安装此工厂。

## 契约与捕获

`api.scheduler.planned.PlannedExecution` 按职责公开四个 owner 方法：`advance(cleanupDeadline)`、`awaitExit()`、`finished()` 和 `cancel(reason)`。scheduler 从首次调用前一直保留同一实例，false 或异常均不能清掉它。`finished` 是纯事实查询，覆盖该执行实际取得的全部回调、资源和所有权；`awaitExit` 只证明所有已接受异步动作实际退出，不能替代事务确认。

内部 `TaskScheduler.PlannedFactory` 接收借用的 `PlannedTaskExecutor` 与一次 forward deadline。factory 只创建尚未执行的 cycle，不获取部分所有权、不运行组件回调或异步动作；返回后 scheduler 先发布 pending 引用，随后首次 advance 才逐一获取并保留具体句柄。factory 保持包内，不公开任意 hosts 提交入口，也不让 API 依赖具体线程池实现。构造器使用 PM 原 5000 ms 默认预算；服务器网络机器工厂已使用这一默认预算，尚无额外配置入口。

`PlannedRecipeCoordinator` 直接实现这一执行契约，`awaitSourceExit` 统一命名为 `awaitExit`。它只拥有一次真实配方工位组，不代表整台机器。正式机器 cycle 必须另外持有准确 `MachineController`/scheduler Registration/`MachineInstance`、所有生命周期句柄、组件 cursor 及各次配方处理波次；一次 recipe coordinator 完成后仍可能有后置 normal、另一波处理器或机器 post。只有这些全部完成或失败终止、清理确认并释放后，机器 cycle 才能 finished。

## tick 顺序与 pending

PM `JavaTaskSchedulerBackend.kt:137–152` 的 MAIN→普通 CONCURRENT→join→planned 顺序保持。未配置 factory 的公共普通构造沿用原行为。有旧 pending 时，scheduler 在交换一次性提交队列、调用新的 prepare/factory 或派发任何普通任务之前恢复它。旧 cycle 只能清理，绝不借新 tick 或新 cleanup deadline 重新前进。

旧 pending 仍未完成时，不执行新业务，不清空尚未消费的一次性任务，也不排空 owner boundary；正常 false 不故障。它准确完成后，本 tick 可以重新走普通 prepare、MAIN/CONCURRENT 和新 factory，无需额外空转 tick。这样既避免普通 worker 等待旧 StorageFence、owner 又在 join 普通 worker 的死锁，也不无故延后已安全的新工作。

新 cycle 的首次 advance 在 owner 上执行；若尚未完成，先撤销前进资格，再证明 source 实际退出，然后做一次有界清理并再次确认退出。跨 tick 恢复只做一次清理尝试，不循环 UNKNOWN。取消以首 reason 为准；取消抛错后没有正常返回证明时，不能继续执行清理 advance，保留现场并重试同一取消请求。

每次 advance 前撤销旧退出证明，只有 awaitExit 正常返回才恢复它。中断实现必须先等实际退出、恢复中断标志再报告；scheduler 保留原异常，清除并记录标志以取得明确的退出确认，最终恢复标志。其他 await 错误同样保留准确 cycle，不假定 source 已退出。异常永久故障普通执行，但清理和 owner boundary 仍可在准确边界继续。

factory 和 finished 查询处于元数据阶段，拒绝登记、递归 tick/close 及 owner boundary 请求。实际 advance/取消回调允许向 owner FIFO 排队；队列要等完整 cycle 和退出证明都结束才执行。普通阶段的新任务仍只进入下一批提交队列。

## 关闭与重试

`tryClose()` 先停止 ordinary admission，撤销未执行 ordinary 提交，并取消/清理 pending；原 worker/source lanes 此时必须存活。未完成返回 false，保留准确 cycle、线程池和 boundary 队列，不把正常 pending 当运行故障。完成后才 shutdown、确认 executor termination、排空卸载/保存队列并关闭 boundary admission。

独立 `closingRequested` 标志保证关闭请求不会被后续 tick 恢复“忘记”：该 tick 即使清完 cycle，也不能开启新业务或执行 boundary；仍须由 tryClose 证明线程池终止后排队清理。`close()` 遵守 AutoCloseable 的已关闭语义，若 tryClose 返回 false 则报告尚有清理；正常 pending 重试应显式使用 tryClose，不用捕获该异常控制流程。实际异常原样报告，session 不得在关闭尚未证实时清空。

服务器现有 stop 仍先 scheduler.close，再 network.close；机器 factory 未安装前仍为原同步路径。正式安装时需要继续连接停止请求、每 server tick 的 epoch 撤销及 session 重试，不能把此内部测试路径宣称为服务器 planned 已开放。

## prepare 句柄

`PlannedTaskExecutor.runPrepare` 新增与 `runOnSourceLanes` 对等的 beforeDispatch 重载，仍使用同一执行器和 Batch 实现。任意任务被接受前，owner 先收到准确 Batch；后续直接派发/时钟异常也能保留实际退出句柄，已发布结果不被迟到返回覆盖。该入口不自动提供纯 kernel 或冻结输入；旧纯 prepare/frozen input/result 扩展仍须迁移。

## 验证范围

真实 FE/source 测试覆盖超时动作忽略中断后的退出等待、whole-cycle post pending 阻挡新业务与卸载、已决定 COMPLETE 的 UNKNOWN 在关闭中保留原 lane、确认后不重放完成/退款、factory 和元数据回调重入、取消失败及 owner 中断。prepare 测试覆盖提前交付 Batch、第二次接受发生直接故障时的实际退出与既有结果。测试和逐文件 IDEA MCP 证据见[实施进度](implementation-status.md)；未运行游戏或服务端。
