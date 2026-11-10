# 外部网络参与者

Java 实现入口为 `api.network.NetworkParticipant`、`NetworkParticipants` 与既有 `NetworkRuntime`。当前阶段已完成代码和行为测试编写；测试执行、完整构建及 MCP build 由主代理统一进行，不能将逐文件检查当作运行验证。

## 原行为与重设计

PN 的 `NetworkDeviceRuntimeIntegrationImpl:203/260/436/443/446` 实际启停参与者 registry，每 tick 捕获快照、把贡献交给设备规划并绑定共享预算。`NetworkParticipantRegistryImpl:113/137/156/255` 提供注册、撤销、活动快照及工作申请；`ParticipantTransactionImpl:40/82` 调用外部 commit/cancel。其材料来源只有 ID/affinity 声明，真实来源操作由参与者回调负责，未发现框架自动取得外部库存的调用链。

PE 将该职责归现有网络会话，默认 `NetworkRuntime.refresh()` 自动捕获已登记参与者，保留原提供附加投影的重载。普通机器的工作凭据也重验参与者供给证明。计算总量继续由原 `NetworkPlanner` 汇总，再应用一次中心修饰器；`NetworkTeamBudget` 继续提供同 tick 固定预算、整机 lease 和原代退款。没有新的 scheduler、队伍存档或预算体系。

修正 PN 的旧缺陷：提交不再只比较原 command/plan；取消不确定或抛错不会被标记成功、退款或清空责任；关闭会尝试全部清理，保留尚未确认的原句柄并允许重试。实际 tick、发布 epoch、参与者版本和工作序号分别管理。

## 外部 Java 接入

外部机器或设备通过 `NetworkParticipants.forServer(server)` 获取当前会话，也可使用独立宿主的 `NetworkRuntime.participants()`。调用 `register(participant)` 保留返回的 Registration，在卸载时关闭该准确句柄。旧句柄迟到关闭不注销同 UUID 的新实例；未知清理仍保留于该会话的 `pendingWork()`。

`NetworkParticipant` 实现稳定 participantId、真实玩家 ownerId、kind、当前 state 和 `capture(tick, epoch)`。只有 ACTIVE 贡献资源和声明工作；INACTIVE/REGISTERED 不贡献，UNREGISTERED 状态须通过登记句柄解除。玩家离线不等于设备失活，继续沿用 FTBLib 持久 owner/team 解析，不增加在线玩家条件。

Capture 分离不可变 Snapshot 与 owner-only `current` 证明。Snapshot 包含资源贡献、每工作单元的完整 numeric 向量、可选材料来源 ID/affinity；不含 World、实体、库存或事务。`current` 必须检测影响身份、资源、工作/来源能力和版本的变化，不能取额外存储锁或改变业务状态。同一实际 tick 允许相同版本的相同能力在新 epoch 重捕获；能力变动或进入新 tick 必须推进版本。

Registration.snapshot 返回当前发布的不可变值，允许交给 worker 规划。规划结果回到 owner 线程，以相同快照调用 `plan(planId, snapshot, workType, sourceId)`。框架校验准确注册及快照、当前 owner/team、工作及来源声明，申请共享整机 lease，再预留整组 numeric。多次工作不重复占同一 UUID 的 lease；每工作单元支付声明向量一次。调用者无需也不能在申请参数中指定队伍。

成功返回的 Work 是原工作准确句柄。`commit()` 只调用一次原参与者；REJECTED 保证没有残留业务效果，直接退原预算。成功回调后仍检查整批供给与当前授权，再提交预算；失权或异常选择原工作取消。重复成功提交返回 ALREADY_COMMITTED；普通 cancel、卸载、刷新和停服不撤回已完成工作。调用者可对准确 Work 显式 `compensate()`，即使跨 tick 也只补偿原代账本。

每次刷新在新发布前取消旧 epoch 的未完成工作；endTick 关闭原账本，但不丢弃未确认清理。一次 tick 内重复刷新不会恢复已经消费的余额。planId 用于同实际 tick 的重复申请检查，pending 的 ID 继续保留；终态句柄在 tick 后由调用者按需保留，框架不以全服永久事务表积累历史宿主。

## 取消和错误恢复

外部 commit/cancel 回调必须在 owner 线程实际返回后才进入下一步，不支持未声明的后台效果。commit 抛错必须保留足以按原 Command 取消的证据；框架不会再次调用 commit。

取消返回 CANCELLED 才确认完整撤销并退款；UNKNOWN 保留原预算、原回调对象和责任并撤销网络发布。cancel 抛错同样保留现场与原异常。后续先调用只读 `cancellationState`，UNKNOWN 等待，CANCELLED 只做尚未完成的预算结算，RETRYABLE 才允许再次调用 cancel。cancel 自身返回 RETRYABLE 也明确允许后续安全重试。不能用一条异常后盲目重新扣料或退款来推测业务状态。

`retryCleanup()` 只处理已接收的原工作；关闭会话若仍 UNKNOWN 会明确失败，准确会话和 pendingWork 保留，再次 close 可继续原清理。成功关闭后旧 Work 仍可显式补偿，因此随后 close 也会重新检查后来加入的原工作责任，不因曾经关闭成功而跳过。关闭后不接受新注册或新网络发布。首次授权复验中的 Capture.current/owner resolver 与后续 commit 回调共用故障清理路径；失败立即记录到 Work.failure、选择原工作取消并保留原异常和清理异常，不能留下 PLANNED 或重放 commit。

## 验证与证据

`NetworkParticipantsTest` 的 17 项行为测试覆盖：默认供给、中心修饰、共享整机 lease、完整向量不足、来源校验和重复 plan、提交/取消幂等、原代补偿、换队与设备失活、普通机器依赖供给失效、UNKNOWN/异常清理、准确旧句柄与替换实例、停服聚合/恢复、版本约束、worker/owner 及回调重入边界。独立复核后新增关闭成功后的补偿责任、首次 proof 异常后准确退款、首次 owner resolver 异常且取消 UNKNOWN、首次复验与清理双异常四项回归。

首次修改前文件、absent-before、Git 状态与本机 IDEA 证据位于 `build/staging/network-participants/`；独立复核修复前原文及新增检查证据位于其 `review-repair/` 子目录。6 个 Java 已逐文件 read_file 刷新并以 get_file_problems(errorsOnly:false) 检查，错误、警告、弱警告均为零；完整源文与磁盘读回一致。未启动 Minecraft 客户端/服务端、未运行 Bootstrap/FML 初始化。最终完整1744 tests/224 suites零失败、错误、跳过，含17项本阶段行为测试；MCP build成功。统一日志/XML见 `build/staging/resumed-goal-plan/unified/third-results/`。
