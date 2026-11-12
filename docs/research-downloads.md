# 普通物品化研究下载与恢复

普通数据库下载与物品转换是两条真实服务器通路。数据库下载只把当前云研究写入活动物理数据库；普通物品转换调用材料注册的 `createOutput`，消费准确槽位的一件材料和冻结来源的等级费用，再交付完整原生产物。两者均保留永久奖励资格，不把数据库内容作为可扣减的研究来源。

## 旧 PN 语义与本次接线

已对照旧 `ResearchItemDownloadExecutionImpl` 的真实请求、来源冻结、玩家材料凭据和恢复顺序。`DATABASE_AND_ITEM` 的目标完成等级只需不高于活动数据库已存等级，同时满足数据库研究 tier 门槛；冻结全体当前成员，每人原始个人等级均须不少于 `itemDownloadLevelCost`，各扣同一费用。不能要求每人的等级达到下载目标，也不能额外要求当前云等级达到数据库已存目标。`ITEM_ONLY` 要求目标不高于队伍直属等级，费用仅从直属来源扣除。费用默认 1，可配置为 0，与输出研究完成等级独立。

普通转换拒绝 `specialOnly`，在扣料前核对黑名单和目标阶段互斥规则。当前活动物理数据库的 owner 必须属于捕获成员；FTB 成员、角色权限、实际 actor/window、原数据库实例及 publication 证明由服务器重新捕获。全体冻结成员的真实空来源在费用为 0 时也会先创建，不能用缺失来源代替预约。

## 公开入口与真实调用

- `ResearchItemDownloadRegistry` 按完整材料 AEItemKey 注册回调。`CommonProxy` 在研究注册表 freeze 后发布 `ResearchItemDownloadRegistrationEvent` 并冻结下载注册；不注册正式配方或游戏数值。
- `ResearchItemDownloadContext` 提供真实 actor/server/world、准确活动数据库 UUID、队伍、当前 actor world tick、目标等级、冻结单件材料及 operation UUID。回调只运行于主线程真实 actor 能力上下文。
- `ServerResearch.requestItemDownload` 通过既有 MAIN 队列重捕获请求授权，默认 `ResearchContainer`/`ResearchGui` 的物品下载动作绑定最后绘制的研究、原队伍/数据库、当前选中快捷栏槽和 nonce。Java 平台入口可使用准确的 0..40 槽。
- `ResearchWorld.requestItemDownload` 持有唯一世界 writer；新 actor+nonce 才运行事件，成功后冻结完整 native Count=1 模板与独立 long 数量，先持久化操作再允许材料效果。相同 nonce 和相同 intent 重试同一操作；不同 intent 返回冲突，不能再运行回调或另建操作。
- `ResearchWorld.recoverItemDownloads`、`pendingDownloadOperations` 和 `downloadReceipts` 供服务器边界、登录及后续物品/实体加载恢复。`abortItemDownload` 仅在完整 before 库存证明材料未扣时允许明确终止。

授权 `current` 在材料提交前重新核对 FTB、权限、物理数据库及 actor/window。World 自己核对原来源 before、预约和黑名单/互斥规则；不能把读取阶段的 cloud snapshot 证明用于 World 已初始化来源或持有短 writer 的阶段。材料提交后使用独立 `actorCurrent` 控制交付能力，换队或原数据库变化不抹去已承担的原来源义务。

## 唯一存档与提交顺序

下载材料的记录只限制同一玩家同时开始另一笔研究扣料，不限制玩家的普通操作。`COMMITTED` 记录证明该笔材料已扣；恢复时保存当前库存及原记录，禁止再次扣料，也不要求玩家库存长期保持旧 after 镜像。扣料尚未提交时仍检查本次 before 和准确槽位；库存变化导致该次研究返回失败或恢复状态，不冻结玩家，也不改写死亡或重生行为。

当前 Schema 5 的 `ResearchWorldSnapshot` 同时包含所有玩家/队伍、手动/站点会话、pending 下载及 terminal receipt，以及独立研究盘操作。`ResearchWorldNbt` 严格读写这一明确 schema，没有第二份下载或研究盘世界存档，也不回退旧 schema。以下普通转换的业务阶段保持原协议。

1. `PREPARED` 保存原 intent、成员/个人或直属来源 before/after、材料完整 native tag/ForgeCaps 与库存 before/after、输出模板和数量。按 researchId 预约，其他研究仍可推进。
2. 保存 `materialArmed` 后才写玩家 PREPARED 凭据；消费前必须完整 before 相等，只扣准确槽一件。真实库存写入观察到完整 after 后，玩家 COMMITTED 凭据同步保存；World 再保存 `MATERIAL_COMMITTED`。
3. 按冻结 operation UUID 借用原预约、提交原来源费用，用 `Prepared.owns/snapshot` 精确放行自己短 writer 的完整世界快照，保存 `SOURCES_COMMITTED`。保存失败保留唯一 `pendingSave` 和 after 值；先 complete 短 writer，预约直到准确 pending flush 成功才 release，不能回滚已经尝试持久化的来源结果。
4. `SOURCES_COMMITTED` 已可靠保存后才清理玩家材料凭据。恢复此阶段及其后不重新预约、不再要求当前来源等级恰好等于旧 after，正常新研究或遗忘不能导致再次扣费。
5. 从 frozen native 模板通过共享 `ItemDeliveryAdapter` 冻结完整计划，先保存 `DELIVERING` 操作，再逐片段 prepare、保存开始状态、全量或零效果交付、真实 effect commit 和 World CONFIRMED。片段只能按 `requireTransitionTo` 改状态，不能改 parent、完整 payload、数量或目标。
6. 材料凭据可靠清理且全部片段已 durable CONFIRMED 后，转为 terminal nonce receipt，丢弃重型库存镜像。保留完整 CONFIRMED 片段，供卸载后再加载的掉落和登录后重复清标签；missing/released 从不补发。

`PREPARED`/`MATERIAL_COMMITTED` 重载时从持久操作重建原 before 预约；离线原成员继续在世界来源表和 trackedPlayers 中保留。等级下降导致的旧队列失效仍交由 World 原有 pending 结算及 prune 机制。PREPARED 凭据加 after 库存仅表示未知，不能据内容相等推定该操作已扣；缺失、损坏、冲突或不确定材料/交付证据保留恢复错误或 pending。

## 分级盘物品、编码与导入

`ResearchDiskCarrier` 是使用方物品的真实盘职责接口：使用方正常通过 Forge 注册自己的 `Item`，自行决定物品/等级数量、物理 tier 来源和存储布局；库不内置盘物品、等级枚举、数量表、模型、研究树或正式配方。接口提供物理 `researchDiskTier`、便于交互前探测的 `hasResearchDiskData`，及可覆盖的 `readResearchDisk`；默认解析可复用严格 `ResearchDiskNbtCodec`，自定义布局可自行实现。服务器总会比较解析内容的 tier 与该实际物品的物理 tier，完成等级仍独立保存。

`ResearchDiskEncodingEvent.register(downloads, research, material, outputTemplate)` 从使用方盘模板的 Carrier 读取 tier，并把精确材料 AEItemKey 绑定到现编码事件；也可直接注册自定义 `ResearchItemDownloadEvent`。它直接进入上述普通下载按钮、回调、扣料/扣级和完整 native 输出保存/交付流程。编码仅检查 `researchTierForLevel(目标完成等级) ≤ diskTier`；编码数据库沿用普通转换已有研究门槛及权限，不额外检查空白盘阶级是否不高于编码数据库。产物保留模板其它 NBT/ForgeCaps。外部可以继续注册自己的材料及输出模板，或以 `ResearchDiskCarrier` 暴露自定义盘物品；特殊研究可用 `ResearchDiskData`/`ResearchDiskNbtCodec.writeCopy` 直接写入，不能强迫它经普通云下载。

已编码盘对真实 `MachineControllerHost` 数据中心普通右键，既有 `ServerResearch` 的 `RightClickBlock` hook 在 LOWEST 优先级先尊重已取消及 useItem DENY，再调用公开 `ResearchDiskInteractions.use`；合法 Carrier 交互取消为 SUCCESS，使 Forge 在方块打开 GUI 前返回。客户端保留原生使用 packet，只有真实服务器玩家排入 `ServerResearch.requestDiskImport` 的已有 MAIN owner 队列，冻结原 tile、准确主手/副手槽和物品 AEItemKey。无关物品/空白盘/目标 PASS 沿用原行为，外部自有交互调度也可直接调用此 helper。新操作核对当前 FTB 队伍、目标 owner 成员、动作权限、实际活动物理数据库、盘阶级、容量及研究互斥；只有导入目标检查 `diskTier ≤ databaseTier`。后端必须具有 `ResearchDiskStorage` 且当前真实持久能力可用，普通 `DataCenterStorage` 不据此消费盘。Java 平台入口还能明确给出原队伍、数据库 UUID、槽位和 nonce，不新增第二份 packet 或 GUI。

`ResearchDiskOperation` 在 World 唯一 writer 中保存完整原 intent/目标 owner 与位置、数据库 commit before/after、准确单件盘和全库存 before/after。复用真实 `MinecraftResearchDownloadInventory` 的 player PREPARED/COMMITTED 凭据和交互、死亡/重生、光标、装备、漏斗保护，不制造研究扣级、奖励或手动会话：

1. World 保存未 armed 的 `PREPARED`，再可靠保存玩家 `PREPARED` 材料凭据。
2. World 可靠保存 `databaseArmed`，随后才调用数据库原子 `commitDisk`，同时发布研究内容和该完整 operation receipt。首次调用明确返回拒绝保证本次零效果，可以保存 `ABORTING` 后清玩家凭据；异常和重载后的 armed 缺失凭据保持未知。
3. `persistDisk` 写入并从实际恢复来源回读当前完整数据库内容和准确 receipt，成功后 World 保存 `DATABASE_COMMITTED`。默认平台经 `MachineControllerHost`/原 machine 精确检查 Anvil chunk、tile、ownership 及完整组件 payload；外部真实宿主可供给自己的 `MachineDataCenterPersistence`，不要求继承内置 TE。
4. 原玩家准确单槽消费一件盘，可靠保存玩家 `COMMITTED`/after，再由 World 保存 `COMPLETED`。
5. World 的完成状态已 durable 后清理玩家和数据库凭据并分别保存，最后保存 `RELEASED` nonce 终态。缺失清理重试保存当前状态，不再次写研究或扣盘；正常后续库存变化不要求永远等于旧 after。

数据库 receipt 与正常升级共同保存，后续普通下载/同步不擦除证明，也不靠旧 after 内容相等推定成功。恢复通过 `ResearchDataCenters.captureOriginal` 锁定原 UUID、owner、维度/位置及实际存储绑定；数据库已发生效果后，换队或重选数据库不丢弃原物理义务。原目标卸载或未知材料/数据库证据保持 pending/recovery error，不补发、不重扣。`requestDiskImportCancel`/World `abortDiskImport` 仅能以未 armed 的 World 证明及准确 PREPARED before 安全取消；ABORTING 世界保存失败前不清玩家凭据。恢复明确换入另一队伍的未 armed 请求也可安全取消，已 armed 请求不能按权限变化丢弃。

外部接入示例（`tier`、注册名、材料与条目均由使用方传入，示例不进入库产物）：

```java
public class AddonDisk extends AEBaseItem implements ResearchDiskCarrier {
    private final int tier;

    public AddonDisk(ResourceLocation itemId, int tier) {
        setRegistryName(itemId);
        this.tier = tier;
    }

    @Override
    public int researchDiskTier(ItemStack stack) {
        return tier;
    }
}

// 使用方在自己的 RegistryEvent.Register<Item> 中正常注册 disk。
// 在库现有 ResearchItemDownloadRegistrationEvent 中绑定自己的材料与盘模板：
ResearchDiskEncodingEvent.register(event.registry(), ResearchRegistry.INSTANCE, material, new ItemStack(disk));
// 默认 namespace 的已编码输出会由库 RightClickBlock hook 处理；无须新增 GUI 或 packet。
// 特殊研究也可直接由使用方写入：
ItemStack encoded = ResearchDiskNbtCodec.writeCopy(new ItemStack(disk),
    new ResearchDiskData(ResearchRegistry.INSTANCE, tier, entries));
```

已静态核对本机 Cleanroom/Forge 实际调用顺序：客户端 `RightClickBlock` 取消仍发送原生 packet；服务端 event cancel 直接返回，位于 `onItemUseFirst` 与 block activation 之前。独立只读审计闭环了 armed 缺失 receipt 禁止再次调用、block GUI 前导入、LOWEST 尊重已有取消/useItem DENY 三项，报告 `build/staging/research-disk-audit/report.md`。本阶段新增 35 项真实行为测试（World 23、数据库侧 12），共同完整 `gradlew.bat build --console=plain` 为 1214 tests / 156 suites，零失败/错误/跳过；29 Java IDEA MCP `errorsOnly:false` 零错误/警告/弱警告，无 timeout，构建前后 SHA 一致。证据 `build/staging/research-disk-workflow/full-build-final-result.json`、`full-build-final-test-results/`、`pre-build-java-sha256.json`、`post-build-java-sha256.json` 与 `build/reports/idea/research-disk-inspections-final.json`。当前 HEAD 的 main/dev/sources 三个 Jar 都有 Carrier/Interactions、无已移除内置盘类/模型/专有 item 文案残留，见 `jar-verification-final.json`。没有启动游戏或服务端，实机点击与平台生命周期未计为验收；本阶段已精确本地提交 `8da5da7`，未推送。

## 验证

下载世界新增 18 项行为测试覆盖原离线成员费用、零费用空来源、黑名单拒绝、armed/save 失败、材料提交后授权变化、来源 pending 保存失败、已提交来源变化、未知材料/物品证据、完整计划先保存、真实观察恢复、nonce 冲突、terminal 清理和明确 abort。材料 41 槽测试实际发现并修正移除最后槽后空 NBT list 原生存盘类型变化造成的 after 不匹配。

本轮全 `gradlew.bat test --console=plain`：1164 tests / 150 suites，零失败、错误或跳过。World 核心 8 Java、材料修复 2 Java、旧世界 schema 契约测试 1 Java 均取得 IDEA MCP `errorsOnly:false` 完整零错误/警告/弱警告。证据：`build/reports/idea/download-world-final.json`、`download-world-verification-final.json`；平台及来源检查另见 `download-platform-final.json`。共享 ItemDeliveryTemplates 进一步只递归规范原生存盘会变型的空列表，不反复 gzip 或整栈往返；新增一项真实 native `.dat` 写入/回读后恢复已观察片段且不重复交付的测试，相关下载/奖励 suite 已在合并定向运行中全部通过，证据 `build/reports/tests/ae-physical-extension/combined-run-output.txt`。该合并运行中唯一失败属于独立物理层新 fixture，修复后的该 fixture 定向重跑已通过；不将合并 141 项称为全通过。新增共享修复 2 Java IDEA MCP 全级零诊断，证据 `build/reports/idea/download-native-template-final.json`。没有启动 Minecraft 客户端或服务端，实机交互未列为通过。
