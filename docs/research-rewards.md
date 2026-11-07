# 服务器研究奖励领取

奖励沿用 PN 的永久玩家资格、有序组选择、事件幂等键和物品片段状态机。研究完成只创建 UNCLAIMED 资格；选择奖励组即开始实际领取，旧 PN 的 REWARD_CHOICE 与 REWARD_CLAIM 也进入同一个执行方法。当前服务器 Java 领取已接入，packet 和研究界面仍待继续。

## 世界所有者与玩家请求

`ServerResearch.requestReward(player, researchId, level, groupId, resultConsumer)` 使用现有 MAIN 提交队列。旧研究/source 工作先结清，执行时重新验证准确 server、主世界和在线玩家对象；客户端不能指定另一个领取人、物品数量或交付计划。奖励资格永久属于玩家，不要求仍在原队伍，也不要求研究来源尚未被遗忘或下载消耗。

`ResearchWorld.claimReward` 在原世界操作边界获取准确的 PlayerResearchState.RewardSession，再借给 ResearchRewardClaimer。逐次领取检查点只允许这一个会话拥有的 busy 玩家快照，其他玩家/队伍/队列的未结算写入仍禁止保存。外部事件不能重入其他世界操作；领取中产生的普通 World Save 请求延后，由准确领取检查点覆盖。

全部玩家、队伍、队列和材料会话继续保存在同一 ResearchWorldSnapshot / ResearchWorldNbt 中，不增加另一份奖励 writer。写入失败保留完整 pendingSave，下一次操作先重写同一快照，不能先换组、推进其他研究或修改成员。会话在异常后释放，已选组、已完成 child 和片段状态保留。

`ResearchRewardClaimer.Request.current` 是原玩家会话的只读证明。在新效果之前及计划完成后重新核对；失效返回 STALE_CONTEXT，停止后续发放。已发生的效果仍完成其准确物品存档和领取检查点，不因玩家状态变化丢掉恢复义务。事件仍须持久去重 RewardEventKey；外部异常原样传播，只有明确 RETRY_LATER 是普通业务暂停。

## 物品确认与证据清理

每个片段保持固定资源、数量、目标槽或掉落位置。先保存完整选择和计划，再保存 DELIVERING，实际交付、保存目标物品，最后保存 CONFIRMED。恢复先观察真实片段；只有明确缺失证明才能重新交付，加载范围内没有看见不等于全局不存在。

确定没有发生交付的尝试现在保存为 RETRY_PLANNED，保留原片段 ID、完整标签、数量和目标。仅两种路径可以进入：本次刚保存 DELIVERING（或已取得权威缺失证明），但在调用 deliver 之前玩家证明失效；或者 deliver 正常返回零数量且没有实体身份。下一次执行仍先检查原目标、保存 DELIVERING，之后才发放，不重新选组或生成计划。重试记录写入失败时，原 ResearchWorld.pendingSave 持有完整快照，后续操作必须先重写它。

异常、null、部分数量、冲突实体、已经观察到的效果以及扫描未知均不能转入 RETRY_PLANNED。Minecraft 适配器只把实际写入前的目标不可用当作零效果；World.spawnEntity 返回 false 时，Forge 回调已经见过带标签实体，保留其实体身份作为不确定回执，不能据取消生成推定回调没有转移物品。外部适配器也必须遵守这一零效果契约。

`RewardItemDeliveryAdapter.release` 在 CONFIRMED 世界检查点成功之后调用，只清理交付标签或临时保护，绝不补发缺失物品。默认实现无需标签清理，Minecraft 适配器清理真实栈。清理失败不会把确认状态退回 DELIVERING；再次领取以及 ALREADY_CLAIMED 会重做幂等清理。登录时补清玩家已确认片段，带标签掉落实体后续加载时通过同一 MAIN 队列合并清理请求。

清理后的奖励恢复原业务 NBT，可正常堆叠并参与 AEKey 精确匹配。不能在仅内存确认或保存失败时提前去掉标签，否则恢复将失去准确交付证据。

## 实际物品存档凭据

背包片段使用原 SaveHandler 写入玩家数据，再 force 并读取准确 UUID.dat，校验玩家 UUID、有效且不重复的槽位、唯一片段及完整 tagged payload。单人宿主先经现有 PlayerList 保存以刷新 hostPlayerData，再调用原 saveWorldInfoWithPlayer 写 level.dat，force 并校验 Data.Player；只验证 UUID.dat 不足以覆盖单人恢复。

掉落使用原 AnvilChunkLoader.saveChunk 保存实际加载的目标 chunk，等待既有文件 IO 退出，再 force 原 region 文件并用新的 RegionFile 读取落盘记录。必须匹配 chunk 坐标、minecraft:item、实体 UUID、完整物品及未确认保护。不存在、损坏、重复或不同内容都不能转为 CONFIRMED。这个路径无需保存整个世界；其他代码在领取中触发 World Save 时，仍由世界会话负责延后普通检查点。

未确认的新掉落保存原 Age、PickupDelay 和 Invulnerable，临时暂停老化、拾取及普通伤害；确认后恢复原值并清标记。保存的临时保护也参与磁盘核对，防止重载后在确认前自然消失。非标准玩家存档处理器或非 Anvil 掉落目标在发放之前明确拒绝，外部平台可实现 RewardItemDeliveryAdapter 提供自己的准确凭据。

IO 和确认仍在真实服务器所有者线程逐片段完成，使用现有文件协议，不提供研究文件与 vanilla 文件的原子提交。没有可靠缺失证明时，崩溃发生在 DELIVERING 保存与实际插入之间也会保留未知状态；后续恢复保护不能以“原槽空了”直接补发。

## 旧平台实现的实际问题

旧 PN 的领取平台入口位于 `impl/sync/PrototypeNetworkServerPacketExecutionImpl.java:751`，两类请求在同文件 148–149 行进入同一发奖方法；客户端回应仍是等待 UI 的 sink。现有奖励核心、世界 codec 和单一 writer 可直接复用，无需再造组/claim/fragment 类型。

旧 Minecraft adapter 的 reservedInventorySlots 仅协调一次计划，不是跨 tick 交互锁。旧 Mod 的 container、pickup、toss、use 保护针对研究下载，不能算成奖励冻结已经实现。旧“原槽为空／原加载区块无实体就重发”不足以证明全局缺失，新适配器继续默认不接受这种证明。

普通 `saveAllPlayerData` 和 chunk writer 可能吞掉 IO 错误，仅方法返回不能证明物品已经可恢复；单人宿主还可能从 level.dat 的 Data.Player 恢复而非 UUID.dat。平台确认必须核对实际保存入口中的准确玩家/实体身份和唯一 tagged leaf，不能宣称研究文件与 vanilla 存档跨文件原子。

## 交互保护源码复核（尚未实现）

以下核对 PN 的 `impl/download/ResearchItemDownloadAccessGateImpl.java` 及当前依赖 `build/moddev/artifacts/cleanroom-0.6.13-alpha-sources.jar`，不是保护已经接入的证明。

| 路径 | 实际行为及迁移边界 |
|---|---|
| 容器点击 | 旧 PN 170–174 行的 SWAP 在目标属于箱子时漏查 hotbar，CLONE 始终放行。Container 代理思路可复用，但这两处分支不能照搬。 |
| 副手与创造写槽 | NetHandlerPlayServer 734–740 行直接交换持有物，1417–1428 行直接写 inventoryContainer；均绕过 openContainer.slotClick。关闭及附魔也有独立入口。 |
| 连接与主线程 | NetworkManager.channel() 是公开 API，可以承载有限入口门禁；Netty 不得读取库存，判断必须在 MAIN 核对准确玩家和原连接。重生会复用连接并创建新玩家，已排入 vanilla 队列的任务也需考虑，不能只按 UUID 认证。 |
| 死亡 | EntityPlayerMP 549–554 行在 PlayerDropsEvent 之前删除消失诅咒物品并清空库存；取消掉落事件不能保住原物品。不能以长期取消死亡替代恢复协议。 |
| 漏斗与漏斗矿车 | TileEntityHopper 450–470 行直接复制物品、插入并 setDead/setItem；EntityMinecartHopper 159–173 行复用此路径，不检查拾取延迟，也不触发玩家拾取事件。现有掉落保护不覆盖这些转移。 |

下一段先接真实玩家入口门禁、断线/重生处理及拒绝后的同步，并在协议中明确死亡和漏斗边界。玩家文件 PREPARED 不能单独证明 region 中的掉落未生成；任何可用的未发出凭据都必须在效果前持久建立、保持单调并覆盖实际恢复入口。未完成这些条件前，默认缺失证明继续关闭。

## 剩余接线与验证范围

领取 packet、研究视图与 GUI、跨 tick 未确认库存片段的完整交互保护和可靠缺失证明仍需继续实现。未知交付保持恢复状态，不能为了让领取返回成功而重新生成物品。下载及其物品回执沿自己的操作链迁移，不与奖励证据混用。

世界集成测试使用真实 ResearchWorld、ResearchRewardClaimer 和 ResearchWorldNbt，覆盖有序事件/物品、选择与各交付阶段保存失败、准确 pending 重写、重载观察、确认后清理失败不补发、会话隔离、重入、延迟保存、原玩家证明失效和永久资格保留。Minecraft 物品调用按实际依赖源码和 IDEA MCP 检查；没有启动游戏、Bootstrap 或 FML。阶段完整构建、平台文件验证和逐文件诊断由[实施进度](implementation-status.md)记录。

服务器奖励阶段新增 24 项行为测试；完整构建 975 tests / 132 suites，零失败/错误/跳过。9 个阶段 src Java 加 Tags 共 10 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-rewards-server-final.json。原 23 份修改保留，未启动游戏或服务端。

确定零效果恢复阶段新增 5 项行为测试，覆盖重试记录保存失败、准确 pending 重写、NBT 重载、不确定回执、观察后失权和状态迁移限制。完整构建 980 tests / 132 suites 全通过；7 个 src Java 加 Tags 共 8 文件 IDEA MCP 全级零诊断，build_project 成功。证据 build/reports/idea/research-reward-no-effect-final.json。未启动游戏或服务端，原 23 份修改保留。
