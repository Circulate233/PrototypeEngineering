# PN 研究执行与逐配方计算消费审计

2026-11-07。检查当前本地 PN 工作树（HEAD `6a82c8c16ec44adc7f7f3793575fe0d43befd9d1`，包含其已有未提交源码）及 PM 的实际调用者。以下区分真实行为、旧集成缺口和新库尚待接入的部分。

## 逐配方研究与计算

- `NetworkRecipeExtensionRegistryImpl` 确实在 PostInit 按优先级/注册顺序逐配方调用 listener，并原子发布冻结索引。研究同 ID 取最大等级，数值计算资源 exact sum；没有内置玩法 listener。
- 设备与配方的研究门槛合并取 max；计算消耗合并 exact sum。逻辑线程不放进数值向量。
- 研究门槛读**活动数据中心实际存储的研究**。`NetworkDeviceRuntimeIntegrationImpl.java:551–576` 从选中 DC 的 storage snapshot 取 storedResearch，再交给机器运行时；不能用个人等级、云研究或 storedResearchCount 替代。
- 唯一实际配方消费者是 `NetworkMachineRecipePlannedOperationFactory.java:81–131`：检查 DC 研究、申请机器 lease、预留原始向量、创建 PM operation。`:358–365` owner commit 仅确认 numeric receipt。
- 真实频率是每 server tick、每个 planned 工位选中的候选 operation 一次；没有乘 duration 或 parallel，也没有识别真实 START/TICK/END。
- PM planned source 确实扣库存，但 `RegistryBackedMachinePlannedExecution.kt:247–250` 的 process 恒 null；没有实际时长推进、END 输出或完整需求生命周期。投影会把 fluid/FE START 与 perTick 输入一起扣，可能反复投 START 材料。不能把这条链硬复制为完整配方迁移。
- 普通 PN 设备组件 system=null，applyWorkState 只保存枚举；未发现普通配方消费扩展条件的入口。

新库必须把条件和计算 receipt 接到现有真实 `RecipeProcess.Stage`，与物料/FE 的实际工作协调；已有账本不是消费者完成的证明。用户已确认：算力等 PN 资源的占用随配方并行量缩放；并发资源按整机去重，不论并行量和工作线程数均只占 1。旧桥接链没有完整使用该缩放不能成为删减规则的理由。此前把 planned 技术调度与游戏并发资源混称、用“计费”概括占用的问法不准确。现已实现活动 DC 真实内容快照、设备默认条件与逐配方条件的注册/合并/实际阶段执行，见[配方消费者](network-recipes.md)；旧桥接缺口仍作为迁移审计保留。

## 启动与权限

旧 `PrototypeNetworkServerPacketExecutionImpl.researchStart`：正 tier 先检查活动中心、活动数据中心等级及最高研究站等级；tier0 无此设备门槛。`TeamResearchStartImpl` 再做 START_RESEARCH 权限、黑名单及入队。**入队不扣材料或 numeric。**

默认权限来自 `NetworkPermissionPolicyImpl`：同队普通成员可以开始研究；并非只有队长。服务器操作员可操作其他队伍；黑名单修改需本队队长/管理员或服务器操作员。个人遗忘仍只能针对本人、本人当前队伍，管理他人使用独立管理操作。

旧开始逻辑只相信 view 的 startable 缓存，计算预览时 requester=null、资源为空。这不能满足按请求者/资源定义的真实 startCondition。新 `TeamResearchStart` 已在实际入队前运行权限、结构资格及带真实 actor/资源的条件，并在回调后重验 owner 证明和队列 epoch；条件异常原样传播。

## 手动研究

- 无固定 duration；每次有效推进从每个尚未满足的材料项各扣一单位，manualWork 各增加一。开始和接管本身不扣料。
- 离线、换队和 1200 tick 无活动暂停；重载 ACTIVE 自动转 PAUSED，必须由当前在线同队成员接管，保留已投进度。
- 旧持久链可复用：世界 MATERIAL_COMMIT_PENDING → 玩家 PREPARED（原背包）→ 材料提交 → 玩家 COMMITTED（后背包）→ 世界 MATERIAL_COMMITTED → manualWork 结算与 receipt 确认。
- 最终完成先冻结成员/等级基线/时间，COMPLETION_PENDING → COMPLETION_PROPAGATED → QUEUE_HEAD_REMOVED → 删除 session。未知材料结果不能重新扣一次。

手动 session、真实玩家物料事务和服务器单一存档链现已迁入，见[服务器研究](server-research.md)；研究站已按完整材料和真实来源契约接入自动调度及世界保存。

## 研究站与队列

- 每队每 tick 只对队头做一个 segment。选同维度的合格站，各自拿 logical lease；numeric 使用独立研究供给，不扣普通配方余额。
- 每种资源独立授予 `min(available, perStationLimit × leasedStationCount, remainingDemand)`。一种 numeric 不足不会阻塞其他维度累计。
- 每个成功 segment 都提交一份材料列表，由多个站的 sources 合摊，不按站数复制整份材料。
- 最后一个 segment 还须传播个人/队伍等级与奖励资格；源已被下载等操作占用时保留原工作，不能提前发完成结果。
- `TeamResearchQueue` 已迁移实际排队、进度规划、grant、prune、补偿和 NBT。其 plan 仅属于产生它的准确 queue 实例，恢复存档不恢复 plan 或运行权限。
- 成员变化后的保留只看 next-level/前置/互斥/黑名单，不能用包含 startCondition 的预览缓存重新取消已开始研究。
- 所有队列时间使用统一、可持久恢复的服务器时间线，例如主世界 totalWorldTime。旧各维度 world time 混用会误判倒退/重复；重启归零的 tickCounter 也不适合作为持久去重时间。

旧 station commit 顺序为 queue grant → completion propagation → PM material commit；材料未准确 COMMITTED 才补偿逻辑。旧层只有 markDirty，没有完整跨库存/世界写前日志，不能宣称存档原子；UNKNOWN 时清理 executor 的旧行为也会丢失恢复义务。

旧材料投影丢掉 item properties/output，合并 fluid/FE 不同阶段输入，并且不执行正常需求生命周期。新库应复用完整 `RecipeRequirement` 与真实阶段事务，不复建那套投影 DTO。站点选择和真实材料/计算/队列的共同提交现已由显式 Java 消费者接入，服务器调度与世界会话保存也已接入，见[研究站执行](research-stations.md)。

## 服务器接线复核（2026-11-07）

用户已确认 R29：每次有效研究推进都完整执行材料 START/TICK/END。显式消费者与验证现见[研究站执行](research-stations.md)，以下保留接线审计约束。旧代码只能证明每次 segment 支付一份投影材料，不能证明其已经支持这些阶段；不引入研究时长或把整项研究改成仅付一次 START。

- 旧站点流程先于普通配方申请共享 lease。新 ServerTaskSchedulers 的研究 PlannedExecution 在普通 network.refresh 前，站点必须在旧 pending 恢复、worker 实际退出后的 owner 边界取得当前网络发布；不能撤销仍待结算的旧凭据。
- 旧站点先按维度轮换，再在维度内按 UUID 排序；筛选同队、已加载、成型、等级达标站点，所有获准站分摊一份材料，站数只影响独立资源的阶段上限。
- 研究读取 WorkPermit.numericResources 并申请整机 lease，不调用扣普通配方余额的 reserve。队列已有每资源独立 grant、准确队头和持久时间去重，直接复用。
- 完整材料不能简单同时 acquire 三个阶段再一起 commit：SelectiveRequirementSystem 在 START commit 才安装 modifier，后续 TICK/END 必须看到其效果。CheckpointRequirementSystem 的三阶段聚合也不能充当这种动态顺序已正确的证据。实际资源 apply 顺序、输出对后续输入的可见性和外部回调同样需要核对，不能只提前运行业务回调就声称完整等价。
- 材料使用真实主站进程及冻结合摊来源，不改主站组件，不自动附加普通配方的 MachineRecipeRequirements。来源接口、准确阶段会话和 pending 恢复现已复用；START/TICK各自完成后才获取下一阶段，END同批提交grant/completion，历史成功阶段不重扣或退款。
- 当前 ResourceReservation 只提供运行期终态证明，默认 storageIdentity 是 StorageContext；没有可持久材料回执。世界 writer 在队列/来源事务全部终态确认后才能保存。失败保留原 pendingSave；这不能宣称 vanilla chunk 库存与研究文件跨文件原子，更不能按余额相等猜重载结果。旧层本就没有这项原子保证，不为它凭空制造通用分布式存档框架。

## 个人遗忘与管理员等级接线复核（2026-11-09）

旧PN个人遗忘按阈值删除本人当前队伍个人后缀，不收费；管理员绝对等级独立于个人遗忘，PLAYER可离线，TEAM稳定ID可为空/历史，0删除。旧TeamForget同步文件为空，不据此发明命令或退料行为。新库本阶段按用户完整范围接Java→原Server MAIN→原AE容器/默认GUI，ordinary DATABASE_AND_ITEM→PLAYER、ITEM_ONLY→TEAM，specialOnly拒绝，实际operator/角色/owner proof由服务端核对。

原source down-only/download预留契约不放宽；上调使用原completion receipt但不传个人奖励等级，不执行奖励/开始事件，完整原领取证据保留。ManualResearch.blocksSourceReduction读取真实完成意图与冻结recipients，World遍历保留旧队伍；效果前未知与付清后冻结传播仍走原恢复义务。等级调整不立刻prune/退款，原协调先结算后清除失效队列。

V5准确receipt owns/baseline guard处于precommit rollback保护内，committed IO故障保留after与原pendingSave并释放自身receipt；原窗口finalized清理覆盖新旧所有ACT入队/异步/反馈失败。operations页隐藏重叠底部动作，原普通页仍可达；V2有界wire保护后续GuiSync，旧ACT可选Integer null省略保持原JSON。

固定V1–V5/earliest-before、真实原失败/修正和最终497 tests/54 suites受影响、1579 tests/200 suites正常完整build、13Java本机IDEA MCP全级零/完整读回一致/MCPbuild证据见 `build/staging/research-actions/`。独立V5审计报告 `build/staging/research-actions-plan/audit-final-v5.md`，SHA 5DFDFB7977C77F9B5EEF420A52B18B4D4FAECEA86B71E67531DA861BCD771DA5；静态闭环不冒充实机World/FTB/GUI/packet验收，未启动Minecraft或服务端。
