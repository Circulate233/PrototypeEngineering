# Planned 执行屏障与正式机器宿主

PM 的 runner、取消注册和单调 deadline 已用于真实阶段协调器。块一已在服务器 CONCURRENT 网络配方和研究材料消费者接通纯 prepare、实际 owner 工作预算及原 source 结算屏障；2A 已将正式 PLANNED_PARALLEL 与公共三参 scheduler 的独立机器宿主接到同一真实执行流程；扫描内部工作预算属于 2B。原行为及适配缺口见[迁移审计](planned-migration-audit.md)。

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

真实消费者已复用 StorageFence/ResourceBatch 的跨屏障独占、延迟 complete 和准确 token 恢复，见[资源批次](deferred-resource-batches.md)、[阶段会话](recipe-tick-sessions.md)、[工位批次](work-slot-batches.md)及[真实阶段协调器](planned-recipe-coordinator.md)。正式模式与独立机器入口已由 2A 接通；discover/扫描内部预算仍待 2B。完整验证数量及 MCP 证据记在实施进度。

## 正式宿主与当前性

公开 `api.scheduler.planned.PlannedMachineTask` 仅增加 `@Nullable MachineInstance plannedMachine()`。宿主读取在 owner，不能执行回调、取得生命周期/资源义务或登记第二 owner；Instance 返回自身，Controller 返回实际已加载机器，外部 ScheduledTask 包装实现同一接口。正式注册必须交付已有 lifecycle 的真实机器，Runnable 和无契约的正式任务拒绝；普通 MAIN/CONCURRENT 行为保持。

公共三参 TaskScheduler 及未显式指定 factory 的构造默认接 `MachineRecipeExecutionFactory`，显式内部 custom factory 保留。独立路径不访问服务器静态 PN 服务；服务器只注入普通 CONCURRENT 网络 admission/refresh。MAIN 完成、普通 CONCURRENT 全部 join 后才 capture/执行正式机器，重复同机别名在任一 gate 前拒绝，准确 pending 在 beginScheduled 之前发布。

捕获凭据包括原 Registration 身份、active、宿主 mode、原 MachineInstance/lifecycle 与底层 machine mode；失权阻止后继前向工作，清理仍沿原凭据。独立 scheduler 的实际 tickGeneration/tickOpen 或服务器原 tick 证明只属于该 capture。`tick(Runnable)` 是可重复的当前授权准备回调；默认入口冻结原 callback，在 formal owner 组件边界调用时保留 CAPTURING_PLANNED 的递归、登记和 owner-boundary 限制。每次刷新返回后重新检查 current，再执行 dispatch.begin/advance/finish 或设置 begun/cursorDone，不能因刷新内 inactive/换机/换模式而多执行一个组件。旧 pending 先确认实际退出及原义务，不获得新 callback、期限或前向授权。

执行体复用 MachineLifecycle、ComponentMap 原游标、MachineWorkSlots 暂停、PlannedRecipeCoordinator 和 source lanes，不新增配方引擎。资源/FE 普通同步 continuation、PN numeric 并行缩放及 THREAD 整机 1 保持；UNKNOWN、卸载和 tryClose 沿原确认/补偿/实际退出路径。2A 完整测试与逐文件 MCP 证据见[实施进度](implementation-status.md)，客户端/服务器游戏运行未验收。

## 块一纯 prepare 与工作预算

`RecipeRequirementSystem.prepare` 返回 owner 绑定的 `RequirementPreparation`；只有其 `RecipePureComputation` 进入既有 `runPrepare`。内置资源 kernel 只持不可变需求、完成的数量捕获和纯数量/候选游标，不持机器、World、来源 context、permit、lease 或预留 token。有效 overlay 在需求实际到达时解析；普通外部 start/tick/end 回调仍在 owner 同步执行，其内部嵌套需求保留同步语义。

`PlannedRecipeCoordinator` 在派发前保留准确 prepare Batch，等待动作实际退出后只接受原有序结果，accepted Success 才发布纯事实。进程缓存只保存纯计算状态，不能保存绑定或授权；重试/跨 tick 要重新捕获当前来源和工作许可，精确内容证明后才能复用纯事实，终态清除且不写入 NBT。取消或迟到结果不能修改已经接受的缓存进度，发布异常也不重放。

`RecipeWorkBudget` 在内置循环实际访问处扣减：owner 捕获来源/数量/revision，合并当前余额，复制和缩放需求，选择、预检、真实资源/FE 预留及锁安装；worker 执行概率、随机逐次抽样、动态完整匹配/规范 NBT 编码/排序及缓存内容证明。小切片保持游标，透明暂停仍属于原获取段，不制造 stage/append/expose。首次 `Stage.acquire()` 的同步模式与 `acquirePreparedWithinBudget` 的纯 prepare 模式分别保留到其后续子段。

机器 `MachineRecipeExecution` 和研究 `ResearchStationWork.drive` 只在 `workYielded`、原 deadline 未过期且原 attempt 仍 current 时继续。工作暂停不续期、不重充数值额度、不换授权；UNKNOWN 来源/业务结果继续等待确认。外部 `ResourceStorage` 默认 snapshot、matcher、comparator 和 opaque callback 是合作式单次回调边界，预算不能强行中断其内部实现；外部来源可用 `beginQuantityCapture` 提供增量捕获。
