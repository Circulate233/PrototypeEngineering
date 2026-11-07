# PM/PN planned 执行迁移审计

来源为 `F:/clone/PrototypeMachinery/src/main/kotlin` 与 `F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network`。以下是实际源码行为；旧 planned 配方适配器不等于完整配方生命周期。当前只完成执行屏障基础，正式 coordinator、source 结算及真实配方接线仍待迁移。

## 原执行协议

PM `impl/scheduler/backend/JavaTaskSchedulerBackend.kt:139` 顺序为 MAIN、普通 CONCURRENT join、planned。`impl/scheduler/planned/PlannedParallelCoordinator.kt:72` 开始先恢复 pending compensation；未恢复则本轮不 discover。

正常路径是 owner 稳定 discover → source snapshot / frozen input / deterministic allocation / exclusive reserve → prepare worker 和第一次结果屏障 → owner decide 与准确 token/lifecycle 校验 → source lane commit/rollback 和第二次结果屏障 → owner 按顺序提交 operation → 全批成功后 ledger.completeBatch。单项 staging 失败撤其 partial，继续其他项；prepare/decision/resolve/commit 失败撤整个 staged 批。补偿顺序为已提交 operation 逆序、operation staging 逆序、已 resolve source 逆序、未 resolve reservation 逆序，source 补偿仍走原 lane。

prepare kernel 只读取闭合不可变数据，返回不可变 payload；PreparedResult 的身份等元数据由 coordinator 组装。source lane 执行的则是真实物料 mutation。两者不能混同；普通线程池和 planned source lane 也不能互相替代。

## 超时、迟到与退出

PM `PlannedExecutionRunner.kt:265` 对尚未开始的任务返回已知未执行，对已开始任务立即返回 Uncertain。Future.cancel 和 Thread.interrupt 只发送停止信号；`249` 丢弃迟到返回值。因此超时结果屏障不是实际 worker 退出证明。

prepare 的不确定结果仅在 kernel 真正纯时可丢弃。source 不确定时，必须保留准确 reservation、source 身份及补偿记录，并在同一 serial lane 查询实际状态。Reserved→release，Committed→准确 handle.rollback，RolledBack→确认，InFlight→保留 pending；过期不能直接释放独占资源。prepare、resolve、补偿各有共享批次 deadline，reservation 另有更早的有效期。

planned source lane 沿用 `StableLaneIndex.kt` 的 UTF-16 FNV-1a；同来源提交、查询、补偿保持同 lane。不能用普通 affinity 联通组动态合并后的 lane 替代这一约束。新项目已有 accepted worker 实际 join 和退休边界，接入时必须同时保留结果屏障与真实退出证明。

## 不能原样继承的缺陷

| 旧源码 | 已确认问题与迁移要求 |
|---|---|
| Coordinator.execute:102、140 | runner 直接抛错或 outcome 数量错误时，外层只有清理 activePlans，没有整批 rollback。旧所谓 runner exception 测试实际返回 Failure，不能证明直接 throw 安全。新 coordinator 必须完整接住并收束已预留资源。 |
| Coordinator.discoverStable:445 | discovery rollback 失败仅记录日志/附加异常，没有加入 pending；新执行必须保留真实待清理状态。 |
| Item/Fluid/Energy source 与 Coordinator recovery | ROLLBACK_CONFIRMATION_PENDING 被查询为 InFlight，recovery 又不调用已有 handle 的确认重试，可能永久 pending。新 query 必须区分 mutation 未结束与确认待重试。 |
| ItemResourceStoragePlannedConsumableSourceImpl:148 | 短提取失败发生在实际提取量计入 consumed 之前，补偿会漏掉这部分；authorizeRollback 位于恢复 try 外也可能滞留 IN_FLIGHT。必须按实际 mutation 记录补偿。 |
| PlannedExecution deadline | 错误要求 System.nanoTime 非负并用绝对加法；新 deadline 用开始值与 elapsed 差，支持负值/绕回。 |
| Coordinator reservation 校验 | sourceRevision 在后续 ExclusiveReservationLedger.reserve:145 已被校验；不能把 coordinator 未就地校验误报成整条生产链漏洞。 |

## 配方适配的真实完成度

PM `RegistryBackedMachinePlannedExecution.kt:243` 的 PlannedWorkSlot.process 始终为空；每 tick 按游标选择配方 factory，未运行传统 RecipeProcess。PN `NetworkMachineRecipePlannedOperationFactory.java:340` 的 prepare 只验证输入并返回 operation/sequence；`358` 的 commit 仅提交计算资源 receipt，源码明确没有 device work transaction API。没有产物、时长或进程推进。

旧 ItemRequirementComponent 仅投 START inputs；Fluid/Energy 把初始与每 tick 输入放进同个 operation，projector 又强制非空 INPUT。不能把整张配方每 tick 投影一次，也不能 source 先扣物料后 owner 再调用 RecipeProcess.tick，否则会重复消费。空组过滤、output-only、概率、overlay、动态选择、检查点、选择器和真实 START/TICK/END 都必须沿新项目已有语义。

新 MachineWorkSlots 已允许注入 RecipeExecutor；计划应来自实际进程当前阶段和冻结 Tick。保留现有 RecipeProcess、RequirementTransaction、ResourceRequirementSystem、ResourceTransactions，将独立准备输入/结果与准确可逆事务接入 planned，不重新制造固定输入输出配方引擎。

现有 ResourceTransactions 在单线程按 StorageContext.order 持有全部 ReentrantLock，完成 canApply/apply/业务 commit/complete 或 rollback 后才解锁，且明确禁止锁内等待其他 lane。不能 owner 持锁等 source worker，也不能 source apply 后放锁、跨 join 才做 owner commit。正式 source 结算需要与现有独占/补偿边界一致的阶段适配；如果先留 owner 原子结算，必须如实说明第二阶段只验证/预留，不能声称旧物理 source commit 已迁移。

同样，单 operation 调完整 RecipeStageExecutor.execute 会提前 complete，后续 operation 失败时无法整批补偿。必须补可延迟 complete 的阶段/批次边界。NetworkRuntime.WorkPermit 的预算 receipt 可参与现有业务事务，但 commit=false 必须拒绝并补偿；machine/process/stage/version、team/tick/epoch/source 和工作序号也必须一起验证。仅 worker current() 不够。

这些仍是后续实现要求，不能由[执行屏障](planned-execution.md)通过测试来替代。整体范围继续包括正式 planned、实际配方与研究消费、完整参与者及 source 补偿协议。
