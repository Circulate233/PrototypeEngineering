# 手动研究执行与恢复

本阶段迁移 PN 的 tier 0 手动研究。真实 Java 入口为 `api.research.manual.ManualResearch`，配合现有 `TeamResearchStart`、`TeamResearchQueue`、`TeamResearchCompletion`。会话、材料推进、玩家库存执行和恢复各有具体实现；服务器存档所有者、事件/网络入口仍待接入，不把可调用执行链称为游戏全链完成。

## 保留的业务

- `TeamResearchStart.start` 先完成权限、资格与真实开始条件检查。`ManualResearch.begin` 绑定其准确入队结果、请求者与队伍，保存时队列和会话一起写入；不消耗材料。
- 只有队头推进。每次有效 `advance` 从每个未完成材料项各取 1 个，无固定 duration。物料身份为 `ManualMaterialKey(requirementId, inputIndex)`，不是资源身份：同一种物品分别需要 2、4 个时，前两次各取 2 个，后两次各取 1 个，各项进度独立。
- `ManualMaterials` 从完整静态 START 需求编译；tier 0 定义仍限制 AEItemKey 输入。AEKey 数量汇总使用 `AEKey2LongMap.addTo`，进度与序列溢出在修改前检查。编译结果按准确队列目标复用。
- 离线、换队或 1200 tick 未推进时暂停；显式暂停和同队在线成员接管保留已投进度。接管不重跑开始条件。世界恢复将 ACTIVE 改为 PAUSED/WORLD_RESTORED，其他恢复状态保持原样。
- 最终投料意图先冻结成员、研究等级基线和完成时间。完成后调用现有冻结传播，提升个人或直属队伍来源并创建 UNCLAIMED 资格；此处不调用奖励交付。

沿用 PN `ManualResearchExecutionImpl.planAdvance`、`ManualResearchSessionStateImpl`、`ManualResearchMaterialReceiptStoreImpl` 和 `ManualResearchRuntimeImpl` 的业务与恢复顺序。去掉 PM 附属 planned source 的身份桥接、接口/Impl 镜像，以及旧字符串拼接材料 ID；手动库存操作在服务器 owner 线程同步执行。

## 世界与玩家的记录顺序

1. 世界保存 `MATERIAL_COMMIT_PENDING`：准确 operation/contributor/queue key、逐项增量和完整期望进度。最终步骤同时冻结完成信息。
2. 玩家保存 PREPARED 回执与完整库存 before。
3. 再次核对 before 和全部材料，扣取；玩家保存 COMMITTED 与完整 after。
4. 世界保存 `MATERIAL_COMMITTED`，再保存 `MATERIAL_SETTLED_RECEIPT_PENDING` 与结算后的进度。
5. 玩家确认 SETTLEMENT_CONFIRMED，删除准确回执；世界切回 ACTIVE 或 `COMPLETION_PENDING`。
6. 冻结传播后保存 `COMPLETION_PROPAGATED`；移除准确队头后保存 `QUEUE_HEAD_REMOVED`；最后删除会话并保存。

`ManualResearchSessionNbt` 严格检查版本、字段类型、重复项、材料定义边界与完成信息。`ManualResearchMaterialReceiptNbt` 保存玩家证据。expectedWork 在准备时就完整冻结，世界和玩家使用同一个意图，不再保留旧实现中两种不同的 expectedWork 表示。

正常缺料先由 `hasMaterials` 返回，不创建 PREPARED，避免旧流程把缺料库存固定为 before 后无法接受补料。`consume` 仍须再次全量检查，不能用预检代替真正扣取的验证。

缺失回执或 before/after 不一致不构成“尚未扣取”的证明，恢复不会再次扣料。同一进程内，runtime 额外保留准确 operation 的“尚未调用 consume”证明，供首次世界检查点失败后继续准备；这个证明不写入存档，重载不能据此推断没有消费。

所有外部 callback/checkpoint 异常原样抛出。世界检查点失败时保留准确 `CheckpointState`，后续操作先重存，不能先推进新业务。重试前后核对会话、队列、队伍与受影响玩家来源；其他 writer 或外部修改使所有权失效时拒绝写入旧快照。已 COMMITTED 的玩家回执只重存/结算，不重扣。最终会话删除的保存失败也保留可取得的完成结果。

## 扩展与宿主责任

`ManualResearchInventory` 是外部库存实现接口；`MinecraftManualResearchInventory` 绑定真实在线 `EntityPlayerMP`，通过 AEItemKey 和完整 NBT 比较取料，保存该玩家自己的回执。第三方实现须遵守同步、全量预检、准确回执与异常后保留证据的契约。

`ManualResearch.Checkpoint.persist(CheckpointState)` 必须把所给会话、队列、直属研究和受影响个人研究作为一次世界状态写入。players 是要合并的受影响来源，不能拿它替换全世界玩家表。此接口不是已经注册好的 WorldSavedData 服务，也不提供跨玩家文件与世界文件的文件系统原子保证。

`Context` 由服务器捕获当前成员、在线玩家和当前性证明，不能使用客户端自报事实。时间沿用统一持久服务器时间线。自动服务器入口负责每会话每 tick 调用一次 `advance`；保留 PN 执行接口“每次调用推进”的语义，不把也记录 begin/takeover/pause 的 lastActivity 当成 tick 去重器。

仍须接入研究存档所有者、FTB 成员变化、服务器 tick、玩家登录退出及操作入口；队列 prune 必须先处理会话持有的库存/完成恢复义务，不能先删队头丢现场。研究站与下载是后续独立业务。没有添加正式研究内容或玩法数值。

## 验证边界

逻辑测试使用现有自定义 AEKey 验证逐项材料算法；执行测试在包内替换材料编译入口，运行真实会话、队列、完成传播和恢复状态机。公开构造仍固定使用正式研究定义，未放宽 AEItemKey 限制。真实非空 ItemStack 的构造会触发 Minecraft Bootstrap，按项目要求不在纯 JVM 测试中启动它；真实背包交互与存档 IO 的实机验收仍未进行。构建及 IDEA MCP 结果记录在实施进度中。
