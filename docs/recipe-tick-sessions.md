# 配方阶段与 tick 会话

本阶段把已有 `RecipeStageExecutor` 获取/结算路径提取为可保留的 acquisition，并让普通 `RecipeProcess.tick()` 与后续 planned 驱动共享同一阶段游标。依据是 PM `FactoryRecipeProcessorSystem` 的 START、完成前检查、TICK、完成后检查及 finally POST；没有另写固定 IO 配方循环。

## 阶段所有权

`RecipeProcess.beginTick()` 在执行组件 pre/tick 之前登记会话。已终态或暂停时返回 null，组件不运行。会话固定进程、创建线程和准确工位宿主；每个阶段另有单调版本与准确 State 身份。版本不从存档恢复为调度权限。

`TickSession.nextStage()` 先创建 `Stage` lease，再由 `Stage.acquire()` 执行真实需求。即使没有需求或全部返回 SUCCESS，也不能重入 tick、取消、保存或提前 finish。阶段保留获取的原 RequirementPath 和冻结 TICK；START 结算后才冻结 TICK，保证属性、selector 与 overlay 的生效顺序。一次会话最多尝试一个 TICK，速度为 0 也不会循环重试。零时长执行 START→END，已完成进度的 RUNNING 进程仅尝试 END。Blocked/Failure 结束本轮，下一次调度再尝试未成功阶段。

尚未 acquire 的阶段可由 owner 调用 `discard()`，只释放 lease 并结束本轮，不为取消而执行真实需求。已 acquire 的阶段必须结算。

获取先保留 transaction，再读取 reservations/result；成功、拒绝、抛错都封闭已知库存，生命周期 transition 始终最后。SUCCESS singleton 不重复导出。reservations 列表自身抛错意味着库存可能不完整，不能凭已知部分全部完成就宣布清理成功。

## 同步与跨 lane 结算

普通 `tick()` 使用 `Stage.settleSynchronously()`，仍通过 `ResourceTransactions` 持全部源锁，先资源 apply、业务 commit，再固定完成方向；失败先业务、后逆序源补偿。内部观察记录成功终态和准确 query，通知异常不会把已完成状态回滚。`resolveSynchronously()` 只确认或重试有准确证明的终态操作，不重放 commit。

跨 lane 驱动先对每个进程获取当前一个阶段，再用 `Stage.tryAcquireBatch(stages)` 一次转交整个 round 的原始 token 和业务事务。空闲域争用返回 null，所有 handle 仍由调用方持有；成功后绑定同一准确 `ResourceBatch`，不能再走同步路径。原 token identity 不被 wrapper 掩盖，共享物理 context 仍归同一 source lane。

调用方沿 batch 的 source affinity 应用/确认资源，在 owner 上执行业务提交或补偿。Rejected acquisition 只能补偿。必须等业务与全部源的准确终态、实际动作退出和 fence 释放后，`Stage.settleBatch(failure)` 才能确认这个阶段；尚未释放时返回 false。`ResourceBatch.finished()` 是实际 release 证明，finish 的通知异常不抹掉这一证明。传入该批任何 source、owner、finish 异常，停止后续阶段并保留进程故障标记。

批次确认完成后才能进入下一 round；不能对 source 扣资源后再调用整段 `process.tick()`。已定 COMPLETE 的批次即使通知抛错也不得改为 rollback。

## post 与故障恢复

`ComponentMap.beginTick()` 固定一次 ordered dispatch，pre/tick 全成功后返回；`finish` 在创建线程至多一次执行全部 captured post。pre/tick 抛错仍不进入 post，保持 PM 原边界。

普通 `RecipeProcess.tick()` 在进入业务后发生异常，仍立即执行 post，原异常为 primary，post 异常附加为 suppressed。若资源或业务确认仍 UNKNOWN，保留活动会话及当前阶段，post 已运行并不证明 quiescent。恢复 owner 通过 `pendingTick().currentStage()` 取得准确清理句柄，确认后 finish 不会重做 post。

planned 调用方在当前 stage 未确认时不能 finish，会话保留 post obligation。源/业务全部确认后运行 post；故障恢复不清除 `persistenceFailure`，不能自动恢复保存或执行资格。已终态后的 notification/post 异常可处于 quiescent，但仍是故障进程。

## 仍待接入

此处提供真实阶段与会话，不等于正式 planned 调度已经完成。`PLANNED_PARALLEL` 继续明确拒绝。[工位批次入口](work-slot-batches.md)已接。discover/plan/reserve、prepare/resolve 屏障、owner/team/epoch 许可与真实预算消费、取消及退休实际 join 仍需接入。普通 `RecipeExecutor` 继续遵守返回前完成的同步契约。

验证数量与逐 Java 文件 IDEA MCP 证据记录在 [实施进度](implementation-status.md)。不运行 Minecraft、服务端或 Bootstrap/FML 测试初始化。
