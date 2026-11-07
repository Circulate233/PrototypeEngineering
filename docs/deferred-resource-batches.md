# 跨 source lane 的资源批次结算

这是正式 planned 的存储结算基础，尚未接入 TaskScheduler 的 PLANNED_PARALLEL、真实 RecipeProcess 或网络预算消费。它复用现有 ResourceReservation 和资源/FE 缓存；没有新建固定输入输出配方系统。原实现与缺口见 [planned 迁移审计](planned-migration-audit.md)。

## 独占与物理锁

StorageFence.tryAcquire 按 StorageContext 身份去重并排序，尝试取得全部短物理锁；任一 context 忙或已有 fence 时返回 null，不等待、不留下部分 claim。成功后，逻辑独占跨 source worker 与 owner 的执行间隙保留，实际 ReentrantLock 只在每次 access 内持有。

owner access 自动按顺序锁定全部 enrolled contexts；source access 只锁一个准确 context。原 buffer、资源/FE adapter、保存与恢复继续调用共享 context.lock/requireLock，无 scope 的普通读写等待整个 fence 释放。owner 无 scope 访问自己的 fence、scope 嵌套、新建批次、访问外域、提前释放 scope 自带锁均拒绝。scope 退出会诊断并解开额外泄漏的重入锁，保留原错误及清理错误。

StorageContext.lockAll 用于普通多域访问：遇到争用先释放已取得的域，再等待，不能带着前一个域等待另一 fence。ResourceTransactions、混合仓恢复/失效与 AE 库存 beginBatch 已使用该入口。外部扩展同样需要遵守全域排序及封闭访问契约，不能在持锁或 scope 中等待另一执行 lane。

活跃 fence 内的 changed 只更新 revision/dirty。直接 publishChanges 也不能抢先发布或清除 dirty。release 在全部域的短锁下捕获本次 dirty、解除所有 claim，再解锁、唤醒普通访问，最后由创建 owner 发布捕获的通知。后来的普通 reader/writer 不会抢走这些通知。全部通知都会尝试，通知异常不复活已经解除的独占。

## 资源与业务状态

ResourceBatch.tryAcquire 冻结准确 tokens 与按 context 分组的 Sources；null 时，调用方仍拥有全部 acquisition 的清理义务。成功后每个 Source 沿 context 生命周期稳定的 affinity 使用同一串行 lane，调用 apply、resolve 和后续确认。执行器仍由上层调度器提供，ResourceBatch 不创建或关闭线程池。

每个 source 在独占 scope 内先检查本组全部 canApply，再依顺序 apply。一个 source 部分应用后抛错时，所有原 token 都保留，不能只补偿已返回成功的 token。异步 rollback 决策立即阻止尚未进入的 apply；已进入动作继续被计数，owner 的业务 rollback 必须等待它们真正退出。

owner commit 只接受全部 source 已应用并退出的批次。进入全部域 scope 后，先检查域有效性与授权，再执行业务 commit，然后再次检查；拒绝或 commit 抛错决定 rollback。全部成功后先记录不可逆 COMPLETE 决策，再调用业务 complete。此后无论业务或 source complete 抛错，都不能转成 rollback。

rollback 先在 owner scope 撤销业务，再将准确 source tokens 沿原 lane 逆序回滚；complete 则沿原 lane 正序完成。每个 token 的成功终态单独记录，不因另一 token 出错而重放。finish 只有在业务、全部 token 与实际 source 动作都已确认终态时才释放 fence。非法持锁/嵌套 scope 的调用在修改状态前拒绝，不会把未执行的清理误标为永久 pending。

## 失败查询与后续接线

ResourceReservation.resolution 是可选的准确恢复证明；默认 UNKNOWN。内置资源和 FE reservation 在共享锁下报告 RESERVED/APPLIED/COMPLETED/ROLLED_BACK。终态回调抛错后，批次查询准确 token：已经到目标终态就确认且不重放；仍在可重试的准确状态才允许后续重试；UNKNOWN 保留 CONFIRMATION_PENDING，不能被当成仍在执行，也不能盲目重放。一次 resolve 即使有错误也尝试其余 tokens，最后聚合抛出真实错误。

目前三个业务 Runnable 沿用同步事务契约，没有错误后的精确 query 能力。业务 rollback/complete 抛错只尝试一次，保留未确认状态和 fence，禁止保存/假完成；后续真实阶段适配需要按 requirement callback 维护 query/确认职责。现有 RecipeProcess.ScopedRequirement 在失败后立即关闭的行为还不能直接用于 planned pending 重试。

失效当前沿普通 context.lock：其他线程等待 fence，owner 必须在本批 scope 内执行 invalidate；失效不释放准确 token，rollback 仍可退款。正式退休/卸载接线需要将取消决策、实际退出、scope 内失效与最终清理按顺序连接，不能在 owner scope 外直接调用并等待自身。

源码审计另发现旧 revision 算术边界：reserve/apply/release 每步只检查 revision 未到 MAX，未预留后续终态清理的递增额度。接正式 planned 前需为已获得的补偿权保证额度，不能由普通修改或失效耗尽。当前批次会保留失败而不提前释放 fence，但这不等于已经解决耗尽后的恢复。

后续实际配方按当前阶段组成 round batch，每 process 每轮至多一个阶段；整批 complete 后再获取同 tick 后继 START/TICK/END，保留 selection、modifier、overlay、概率和检查点依赖。组件 pre/tick/post 没有 undo 契约，需要一次性 tick session 保留回调义务；不能把任意回调声称为整批可逆。阶段 lease/version、工位批次驱动、owner/team/epoch/WorkPermit 与预算许可仍须一起接线。

## 验证范围

测试使用真实线程、资源/FE buffer 和共享 context，覆盖并行 source、阻塞读写、物理退出、部分应用/业务失败的整批补偿、权限撤销、unknown 确认及准确重试、不可逆完成、跨域锁与嵌套 scope 拒绝后的恢复、通知抢跑和异常聚合。没有游戏、Bootstrap/FML 初始化或 getter/hash 专项测试。最终构建和全文件 MCP 证据见 [实施进度](implementation-status.md)。
