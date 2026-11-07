# 真实配方跨 lane 协调

`PlannedRecipeCoordinator` 持有一次调度 tick 的全部工位与进程会话，复用 PM 的 owner 有序获取/决策、source 固定 lane 和整批补偿原则。它执行已有 RecipeProcess 的当前阶段，不使用旧 PM 的空 process 工位，也不把整张配方每 tick 重新投料。

## 捕获与回调所有权

构造时通过 `MachineWorkSlots.prepareTickGroup` 先验证全部宿主、默认 executor、实际 slot/process，再一起安装 lease。此时不执行 pre、需求或 source。验证后一个宿主失败时，不留下前面宿主的部分占用。

TickGroup 使用共享 callbackProcess 作用域。同组任一机器的 pre、acquire、业务、确认或 post 回调，都不能进入其他宿主的进程。单个子 TickBatch 完成后仍保留 host lease，所有子批次准确结束才统一解除，避免后一个 post 重入已提前释放的机器。组控制入口负责保留部分 begin/nextRound 结果和完成前的所有清理义务。

## 一个真实阶段轮次

`advance(cleanupDeadline)` 由创建线程调用。第一次调用开始各实际进程的 pre/tick，随后按宿主、工位顺序获得每个进程当前一个 Stage。Stage 获取仍运行已有 Java 需求系统、概率/selector/overlay/checkpoint 与冻结 Tick；这些可访问进程局部状态的回调不会被假装成纯 worker kernel。

正常 Blocked/Failure 先在独立资源批次中准确补偿该阶段，再获取后一个工位，避免被拒绝者留下的预留导致后一个可行工位无故缺料。其他成功阶段的预留继续保留，全部获取后合成一个 ResourceBatch。

成功批次沿准确 context 分组，在原 source lane 应用资源；结果返回后仍须证明所有实际动作已退出。owner 在全域 fence scope 下校验授权、提交全部真实业务及末尾进程状态、再次校验授权，再固定 COMPLETE 方向。随后在原 source lane 完成资源终态，全部准确确认后 release fence、发布通知，并由准确 bound batch 确认 Stage。下一轮才能冻结后继阶段。

零资源阶段仍保留业务和进程 transition，不因没有 source 而跳过 owner 边界。零速度和零时长沿既有阶段游标，一 tick 不会反复执行 TICK。

## 失败、取消与 pending

实际 acquire、source、business 或通知异常停止后续轮次，保存原异常并收束已获取阶段。生命周期转移与所有已成功业务同处于整批可补偿边界，后一个 commit 失败会先逆序恢复业务，再沿原 lane 逆序恢复 tokens。已决定 COMPLETE 的批次不因 complete/notification 异常改为 rollback。

原始 token identity 不被适配包装隐藏。库存完整时可交给批次；异常导致库存不完整时只能尝试已知部分的本地清理，无法证实的部分保持 pending，不能假造 finished。

正常取消、授权失效、预留冲突和 deadline 到期使用明确 reason。原 forward deadline 不随恢复调用刷新；新的 cleanupDeadline 只允许查询/补偿/终态确认，不恢复前进资格。UNKNOWN 不重放 commit，不猜测完成；`advance` 返回 false 时调用方必须保留同一个 coordinator、宿主、源及句柄。实际故障抛出后也必须保留这个清理对象，恢复确认不会恢复进程健康。

执行器在任何任务可进入前调用 beforeDispatch，使协调器先保存准确 Batch。派发/时钟等直接异常仍冻结完整结果，未开始项永久惰性，RUNNING 保留实际退出义务。`awaitExit()` 只证明用户动作不再可能执行；它不代替 ResourceBatch query/complete/rollback 或 post。退休必须随后通过 cleanup advance 完成这些义务。

source 结果屏障的 Stopped、Future 完成/取消、线程 interrupt 都不是提交或退出证明。仍运行时不补偿它正在操作的资源。owner 被中断后的必要 source 清理会临时清除并最终恢复中断标记，不借此继续配方前进。

`ResourceBatch.readyToFinish` 提供当前终态就绪查询；真正释放仍由 finish 同锁重查。`Stage.settled` 是准确 lease 关闭证明，独立于其 tick 的 post obligation。通知抛错后 fence 已释放时仍可以准确 ack，不能为重做通知而重做 IO。

## 尚未宣称接通的部分

本阶段协调器运行真实多机器阶段、source 应用/确认屏障和 pending 恢复，但尚未安装到真实 TaskScheduler/服务器生命周期，`PLANNED_PARALLEL` 继续拒绝。旧 PM 的独立纯 prepare kernel、冻结输入/结果扩展、discover/plan 内部工作预算仍需迁移；当前 source 应用和终态确认屏障不等同于旧纯 prepare 阶段。

传入的 authorization 在 owner 检查，不自动扣减 PN numeric/lease 预算；正式 WorkPermit、队伍/tick/epoch、来源与工作序号及预算 receipt 仍须接入。尤其不能继承旧 PN 在提交后仍要求 receipt 为 RESERVED 的错误生命周期探针。实际机器取消/卸载/关闭必须先撤新工作、join 实际退出、完成 pending 与 post，再释放宿主。

来源和缺陷见 [planned 审计](planned-migration-audit.md)，基础协议见 [工位批次](work-slot-batches.md)、[资源批次](deferred-resource-batches.md)与[阶段会话](recipe-tick-sessions.md)。测试及 MCP 证据记录在 [实施进度](implementation-status.md)。
