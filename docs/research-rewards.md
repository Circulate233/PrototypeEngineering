# 服务器研究奖励领取

奖励沿用 PN 的永久玩家资格、有序组选择、事件幂等键和物品片段状态机。研究完成只创建 UNCLAIMED 资格；选择奖励组即开始实际领取，旧 PN 的 REWARD_CHOICE 与 REWARD_CLAIM 也进入同一个执行方法。当前服务器 Java 领取、packet 和研究界面已接入，见[研究界面](research-gui.md)。

## 世界所有者与玩家请求

`ServerResearch.requestReward(player, researchId, level, groupId, resultConsumer)` 使用现有 MAIN 提交队列。旧研究/source 工作先结清，执行时重新验证准确 server、主世界和在线玩家对象；客户端不能指定另一个领取人、物品数量或交付计划。奖励资格永久属于玩家，不要求仍在原队伍，也不要求研究来源尚未被遗忘或下载消耗。

`ResearchWorld.claimReward` 在原世界操作边界获取准确的 PlayerResearchState.RewardSession，再借给 ResearchRewardClaimer。逐次领取检查点只允许这一个会话拥有的 busy 玩家快照，其他玩家/队伍/队列的未结算写入仍禁止保存。外部事件不能重入其他世界操作；领取中产生的普通 World Save 请求延后，由准确领取检查点覆盖。

全部玩家、队伍、队列和材料会话继续保存在同一 ResearchWorldSnapshot / ResearchWorldNbt 中，不增加另一份奖励 writer。写入失败保留完整 pendingSave，下一次操作先重写同一快照，不能先换组、推进其他研究或修改成员。会话在异常后释放，已选组、已完成 child 和片段状态保留。

`ResearchRewardClaimer.Request.current` 是原玩家会话的只读证明。在新效果之前及计划完成后重新核对；失效返回 STALE_CONTEXT，停止后续发放。已发生的效果仍完成其准确物品存档和领取检查点，不因玩家状态变化丢掉恢复义务。事件仍须持久去重 RewardEventKey；外部异常原样传播，只有明确 RETRY_LATER 是普通业务暂停。

## 物品确认与证据清理

每个片段保持固定资源、数量、目标槽或掉落位置。先保存完整选择和计划，调用 prepare 持久保存玩家 PREPARED，再保存世界 DELIVERING；适配器持久保存并回读 ISSUING 后才实际交付，保存目标物品后再保存 CONFIRMED。恢复先观察真实片段；只有明确未发出证明才能重新交付，加载范围内没有看见不等于全局不存在。

确定没有发生交付的尝试保存为 RETRY_PLANNED，保留原片段 ID、完整标签、数量和目标。允许的来源为：本次刚保存 DELIVERING 但尚未调用 deliver；deliver 正常返回零数量且没有实体身份；恢复扫描取得准确未发出证明。第三条也先持久保存 RETRY_PLANNED，再统一经过原目标检查、prepare 和 DELIVERING，不直接跳入发放，也不重新选组或生成计划。prepare 异常或回调后失权不会进入新的 DELIVERING。重试记录写入失败时，原 ResearchWorld.pendingSave 持有完整快照，后续操作必须先重写它。

异常、null、部分数量、冲突实体、已经观察到的效果以及扫描未知均不能转入 RETRY_PLANNED。Minecraft 适配器只把实际写入前的目标不可用当作零效果；World.spawnEntity 返回 false 时，Forge 回调已经见过带标签实体，保留其实体身份作为不确定回执，不能据取消生成推定回调没有转移物品。外部适配器也必须遵守这一零效果契约。

`ItemDeliveryAdapter.release` 在 CONFIRMED 世界检查点成功之后调用，只清理交付标签或临时保护，绝不补发缺失物品。默认实现无需标签清理，Minecraft 适配器清理真实栈。清理失败不会把确认状态退回 DELIVERING；再次领取以及 ALREADY_CLAIMED 会重做幂等清理。登录时补清玩家已确认片段，带标签掉落实体后续加载时通过同一 MAIN 队列合并清理请求。

清理后的奖励恢复原业务 NBT，可正常堆叠并参与 AEKey 精确匹配。不能在仅内存确认或保存失败时提前去掉标签，否则恢复将失去准确交付证据。

## 实际物品存档凭据

背包片段使用原 SaveHandler 写入玩家数据，再 force 并读取准确 UUID.dat，校验玩家 UUID、有效且不重复的槽位、唯一片段及完整 tagged payload。单人宿主先经现有 PlayerList 保存以刷新 hostPlayerData，再调用原 saveWorldInfoWithPlayer 写 level.dat，force 并校验 Data.Player；只验证 UUID.dat 不足以覆盖单人恢复。

掉落使用原 AnvilChunkLoader.saveChunk 保存实际加载的目标 chunk，等待既有文件 IO 退出，再 force 原 region 文件并用新的 RegionFile 读取落盘记录。必须匹配 chunk 坐标、minecraft:item、实体 UUID、完整物品及未确认保护。不存在、损坏、重复或不同内容都不能转为 CONFIRMED。这个路径无需保存整个世界；其他代码在领取中触发 World Save 时，仍由世界会话负责延后普通检查点。

未确认的新掉落保存原 Age、PickupDelay 和 Invulnerable，临时暂停老化、拾取及普通伤害；确认后恢复原值并清标记。保存的临时保护也参与磁盘核对，防止重载后在确认前自然消失。非标准玩家存档处理器或非 Anvil 掉落目标在发放之前明确拒绝，外部平台可实现 ItemDeliveryAdapter 提供自己的准确凭据。

IO 和确认仍在真实服务器所有者线程逐片段完成，使用现有文件协议，不提供研究文件与 vanilla 文件的原子提交。未发出证明只覆盖下面的 PREPARED 边界；已经进入 ISSUING 却没有观察到物品时仍保留未知，不能以“原槽空了”直接补发。

## 效果前凭据与加载恢复

`RewardDeliveryJournal` 复用玩家 `ForgeData / PlayerPersisted / prototype_eng / reward_delivery_receipts`，与手动材料凭据并列。每条记录保存完整片段身份、父奖励、数量、物品 NBT、目标和 PREPARED/ISSUING；不另造 AEKey 类型、claim 或世界文件。严格检查版本、重复 ID、嵌入身份、数量及目标，最多 4096 条；读写深拷贝并保留手动凭据和其他命名空间。

prepare 即使发现相同的内存 PREPARED 也重新执行保存和核验，因为上一次写入可能失败。issue 先将内存改为 ISSUING，再调用原玩家 writer，并回读核对完整 journal 和受保护光标；单人宿主要求 UUID.dat 与 level.dat/Data.Player 都成功。保存、核验、会话或内存后验失败均不进入库存插入/掉落回调，也不把 ISSUING 回退。只有已经持久保存的 RETRY_PLANNED 才能重新准备同一片段；直接重复 deliver 被拒绝。

恢复扫描在任何保存之前读取真实文件。仅当当前玩家、UUID.dat 以及单人宿主 Data.Player 都保存完全匹配的 PREPARED，才返回该片段的未发出证明。缺少旧版本凭据不构成证明；ISSUING 也不构成证明。外部 AbsenceProof 仍是独立扩展，默认不提供额外证明。

玩家读取 NBT 时还必须合并两份文件，防止单人加载旧 PREPARED 后，普通保存覆盖另一文件里较新的 ISSUING。现有 EntityPlayerMixin 在 readEntityFromNBT 的 HEAD 调用恢复：此时 Entity 已加载 ForgeData，尚未调用 Forge 玩家加载事件；以 GameProfile 推导 UUID 并检查存档身份，不能信任尚未由 EntityPlayer 修正的实体 UUID。所有来源先按完整身份校验再合并；任一来源为 ISSUING 或缺少该条记录，合并结果为 ISSUING；只有全部必需来源一致 PREPARED 才保留它。该步骤只更新内存，不保存、不发物品。正常重生 copyFrom 和玩家维度切换不重走此读取路径，后续已授权的 RETRY_PLANNED 不会被每次普通保存误升级。

确认清理先校验凭据与片段完全相同，在物品清理后重新读取当前 journal，只移除自己的记录；缺失记录幂等，其他奖励和手动数据保留。已确认世界状态仍是清理的前提。部分文件保存失败可以留下保守的未知状态，本协议不承诺自动判定所有崩溃窗口。

## 旧平台实现的实际问题

旧 PN 的领取平台入口位于 `impl/sync/PrototypeNetworkServerPacketExecutionImpl.java:751`，两类请求在同文件 148–149 行进入同一发奖方法；客户端回应仍是等待 UI 的 sink。现有奖励核心、世界 codec 和单一 writer 可直接复用，无需再造组/claim/fragment 类型。

旧 Minecraft adapter 的 reservedInventorySlots 仅协调一次计划，不是跨 tick 交互锁。旧 Mod 的 container、pickup、toss、use 保护针对研究下载，不能算成奖励冻结已经实现。旧“原槽为空／原加载区块无实体就重发”不足以证明全局缺失，新适配器继续默认不接受这种证明。

普通 `saveAllPlayerData` 和 chunk writer 可能吞掉 IO 错误，仅方法返回不能证明物品已经可恢复；单人宿主还可能从 level.dat 的 Data.Player 恢复而非 UUID.dat。平台确认必须核对实际保存入口中的准确玩家/实体身份和唯一 tagged leaf，不能宣称研究文件与 vanilla 存档跨文件原子。

## 原生玩家入口与物品保护

已核对 PN 的 `impl/download/ResearchItemDownloadAccessGateImpl.java` 及当前依赖 `build/moddev/artifacts/cleanroom-0.6.13-alpha-sources.jar`。旧容器代理缺少副手/创造写槽入口，网络拦截又无法追回已经进入 vanilla 主线程队列的请求，因此使用必要的 Mixin，在原方法实际执行处保护物品；不替换容器、网络队列、玩家库存或掉落实体类型。`use_mixins=true`，配置为 `prototype_eng.mixins.json`。

| 路径 | 已接入的保护及范围 |
|---|---|
| 点击、副手、创造、使用、挖掘、附魔与配方书 | NetHandlerPlayServer 的十个入口在原 PacketThreadUtil 切回 MAIN 后、效果前检查执行时的真实玩家/连接。任一库存或光标含交付证据时拒绝相关操作；创造包传入的交付标签始终拒绝，防止已清标签后的旧快照重放。检查原玩家 41 槽和光标，修正旧 SWAP/CLONE 遗漏。 |
| 拒绝同步与关闭 | 使用原交易确认、真实窗口/光标、手持槽和已加载方块同步。只有外部容器槽中仍有标签时才拒绝关闭；玩家槽和受保护光标允许正常关闭。Container.onContainerClosed 在丢弃/清空前保留受保护光标，服务器主动关闭和登出也走同一边界。客户端关闭请求完成后补发真实保留光标，不重开 GUI。 |
| Forge 玩家事件与使用中物品 | 拾取、交互、攻击、破坏及物品使用 Start/Tick/Stop 在效果前拒绝。Cleanroom 取消 UseItem Tick 会返回负时长并继续完成，所以先清 active hand；Finish 已是效果之后且不可取消，不用作保护。 |
| 死亡与重生 | 不取消玩家死亡。跳过受保护栈的消失诅咒删除和 dropAllItems 清空，物品仍在原玩家真实库存，可写死亡存档。copyFrom 在 Forge Clone 前预检全部目标后移交原栈并清旧槽；keepInventory/keepEverything 不再复制一份。 |
| 光标存档与确认 | 原版不保存光标；仅受保护光标写入玩家根 NBT 的 PrototypeEngineeringRewardCursor。读取、重生移交、领取 scan/commit/release 均支持它；UUID.dat 和单人 Data.Player 的核验共同检查库存/光标唯一性，并与完整真实光标一致，即使它属于另一片段也不能损坏或缺失。 |
| 库存更新与装备损耗 | 有证据时暂停库存 item/armor 更新回调，不取消玩家 tick。ItemStack.damageItem 统一保护玩家工具、盾牌、砧击头盔及鞘翅耐久；Forge ISpecialArmor 和荆棘的特殊损耗另行保护，保留玩家伤害、减伤及反伤。经验拾取事件在经验修补之前暂停，避免直接改变待确认工具的损伤值。手动研究始终排除保护栈，并与调用方额外保护条件组合。 |
| 掉落与漏斗 | 受保护 EntityItem 暂停更新、自定义物品更新、合并和伤害；TileEntityHopper 的实际提取入口拒绝保护栈，漏斗矿车共用该路径。确认清标签后原实体恢复运行，原临时 Age/PickupDelay/Invulnerable 按已保存值恢复。 |

`RewardItemProtection.isProtected` / `hasProtectedInventory` 是独立 API 包中的共享查询。标签存在即保护，包括 CONFIRMED 尚未清理或格式异常的证据；查询不代表资格或交付成功。外部模组直接修改 ItemStack、自己的能力端口或自定义实体流程必须遵守该查询，不能把原生入口保护宣称成任意模组写入的全局拦截。

登录、重生、换维度及掉落后加载会补清已确认片段。清理先执行研究 checkpoint；只读 snapshot 可能含写入失败的内存 CONFIRMED，不能直接据此释放标签。繁忙时延后，保存异常保留原证据，原请求仍由已有 MAIN 调度队列管理。

保护不提供跨文件原子提交或全局缺失证明。新凭据通过“所有物品效果之前先持久 ISSUING”的执行顺序证明 PREPARED 尚未发出；它不是扫描玩家文件来猜测 region 中有没有掉落。效果已进入后的缺失仍未知。

## 剩余接线与验证范围

领取 packet、研究视图与 GUI 已接入同一服务器服务，见[研究界面](research-gui.md)。原生交互保护及效果前持久凭据已接，第三方直接物品操作仍依赖公开契约。未知交付保持恢复状态，不能为了让领取返回成功而重新生成物品。下载及其物品回执沿自己的操作链迁移，不与奖励证据混用。

世界集成测试使用真实 ResearchWorld、ResearchRewardClaimer 和 ResearchWorldNbt，覆盖有序事件/物品、选择与各交付阶段保存失败、准确 pending 重写、重载观察、确认后清理失败不补发、会话隔离、重入、延迟保存、原玩家证明失效和永久资格保留。Minecraft 物品调用按实际依赖源码和 IDEA MCP 检查；没有启动游戏、Bootstrap 或 FML。阶段完整构建、平台文件验证和逐文件诊断由[实施进度](implementation-status.md)记录。

服务器奖励阶段新增 24 项行为测试；完整构建 975 tests / 132 suites，零失败/错误/跳过。9 个阶段 src Java 加 Tags 共 10 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-rewards-server-final.json。原 23 份修改保留，未启动游戏或服务端。

确定零效果恢复阶段新增 5 项行为测试，覆盖重试记录保存失败、准确 pending 重写、NBT 重载、不确定回执、观察后失权和状态迁移限制。完整构建 980 tests / 132 suites 全通过；7 个 src Java 加 Tags 共 8 文件 IDEA MCP 全级零诊断，build_project 成功。证据 build/reports/idea/research-reward-no-effect-final.json。未启动游戏或服务端，原 23 份修改保留。

奖励原生保护阶段新增 2 项实际 NBT 文件行为测试，覆盖光标持久化、跨库存/光标重复、改变/缺失/损坏及单人宿主恢复。完整构建 982 tests / 132 suites 全通过；19 个 src Java 加 Tags 共 20 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，build_project 成功。生产 Jar 已包含配置、10 个 Mixin 及生成 refmap；未运行游戏，注入点依据实际源码/字节码与编译映射核验。证据 build/reports/idea/research-reward-protection-final.json。原 23 份修改保留。


奖励效果前凭据阶段新增 27 项行为测试，覆盖 prepare/世界检查点顺序、保存失败与失权、完整 NBT 凭据、双文件未发出证明及加载合并后普通保存不降级。完整构建 1009 tests / 134 suites 全通过；11 个 src Java 加 Tags 共 12 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，build_project 成功。实际玩家加载时序与生产 Jar 的 readEntityFromNBT 映射已核对；未启动游戏。证据 build/reports/idea/research-reward-receipts-final.json。原 23 份修改保留。
