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

新库必须把条件和计算 receipt 接到现有真实 `RecipeProcess.Stage`，与物料/FE 同阶段结算；已有账本不是消费者完成的证明。计费阶段及并行缩放不能谎称旧实现已确定，已向用户核对新契约。活动 DC 研究快照、设备默认条件与逐配方条件的注册/合并/执行仍待完成。

## 启动与权限

旧 `PrototypeNetworkServerPacketExecutionImpl.researchStart`：正 tier 先检查活动中心、活动数据中心等级及最高研究站等级；tier0 无此设备门槛。`TeamResearchStartImpl` 再做 START_RESEARCH 权限、黑名单及入队。**入队不扣材料或 numeric。**

默认权限来自 `NetworkPermissionPolicyImpl`：同队普通成员可以开始研究；并非只有队长。服务器操作员可操作其他队伍；黑名单修改需本队队长/管理员或服务器操作员。个人遗忘仍只能针对本人、本人当前队伍，管理他人使用独立管理操作。

旧开始逻辑只相信 view 的 startable 缓存，计算预览时 requester=null、资源为空。这不能满足按请求者/资源定义的真实 startCondition。新 `TeamResearchStart` 已在实际入队前运行权限、结构资格及带真实 actor/资源的条件，并在回调后重验 owner 证明和队列 epoch；条件异常原样传播。

## 手动研究

- 无固定 duration；每次有效推进从每个尚未满足的材料项各扣一单位，manualWork 各增加一。开始和接管本身不扣料。
- 离线、换队和 1200 tick 无活动暂停；重载 ACTIVE 自动转 PAUSED，必须由当前在线同队成员接管，保留已投进度。
- 旧持久链可复用：世界 MATERIAL_COMMIT_PENDING → 玩家 PREPARED（原背包）→ 材料提交 → 玩家 COMMITTED（后背包）→ 世界 MATERIAL_COMMITTED → manualWork 结算与 receipt 确认。
- 最终完成先冻结成员/等级基线/时间，COMPLETION_PENDING → COMPLETION_PROPAGATED → QUEUE_HEAD_REMOVED → 删除 session。未知材料结果不能重新扣一次。

手动 session、真实玩家物料事务及这些持久阶段尚未迁完；仅有队列入队入口不能称手动研究已完成。

## 研究站与队列

- 每队每 tick 只对队头做一个 segment。选同维度的合格站，各自拿 logical lease；numeric 使用独立研究供给，不扣普通配方余额。
- 每种资源独立授予 `min(available, perStationLimit × leasedStationCount, remainingDemand)`。一种 numeric 不足不会阻塞其他维度累计。
- 每个成功 segment 都提交一份材料列表，由多个站的 sources 合摊，不按站数复制整份材料。
- 最后一个 segment 还须传播个人/队伍等级与奖励资格；源已被下载等操作占用时保留原工作，不能提前发完成结果。
- `TeamResearchQueue` 已迁移实际排队、进度规划、grant、prune、补偿和 NBT。其 plan 仅属于产生它的准确 queue 实例，恢复存档不恢复 plan 或运行权限。
- 成员变化后的保留只看 next-level/前置/互斥/黑名单，不能用包含 startCondition 的预览缓存重新取消已开始研究。
- 所有队列时间使用统一、可持久恢复的服务器时间线，例如主世界 totalWorldTime。旧各维度 world time 混用会误判倒退/重复；重启归零的 tickCounter 也不适合作为持久去重时间。

旧 station commit 顺序为 queue grant → completion propagation → PM material commit；材料未准确 COMMITTED 才补偿逻辑。旧层只有 markDirty，没有完整跨库存/世界写前日志，不能宣称存档原子；UNKNOWN 时清理 executor 的旧行为也会丢失恢复义务。

旧材料投影丢掉 item properties/output，合并 fluid/FE 不同阶段输入，并且不执行正常需求生命周期。新库应复用完整 `RecipeRequirement` 与真实阶段事务，不复建那套投影 DTO。站点选择、真实材料/计算/队列的共同提交及服务器存档绑定仍待接入。
