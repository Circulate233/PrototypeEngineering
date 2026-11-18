# 研究下载、物品导入与恢复

2026-11-15 数据库的下载和物品导入已按最新要求配方化，正式 GUI 等待预览审核。右侧复用输入、箭头、输出并切换模式，附完整玩家背包；云端写库保持独立方法调用。设计见[数据库界面](research-gui.md#数据库界面)。

云端下载只把当前云研究写入活动物理数据库，不扣材料、来源研究等级或奖励资格。物品导出按配方消费一件材料和来源等级，再交付产物；物品导入按配方消费一件材料，向数据库增加研究并交付返回物品。数据库内部内容不作为导出的等级付款来源，物品导入也不扣队伍来源等级。

## 旧 PN 语义与本次接线

`DATABASE_AND_ITEM` 的固定配方需求等级不高于活动数据库的已存等级，且满足数据库阶级门槛；冻结全体当前成员，每人的原始个人等级必须足以支付配方 `levelCost`，各扣同一费用。不能额外要求每名成员达到配方需求等级，或要求当前云等级达到库内已存等级。`ITEM_ONLY` 不要求数据库存有该等级，由队伍当前成员的最高原始个人等级满足目标等级，每位成员均支付配方等级费用。旧直属记录和已冻结的旧扣级计划仍按原来源结算。费用由配方定义，允许 0，研究定义不再包含下载费用。存盘编码固定输出所选研究的 1 级，不接受用户选择等级。

界面研究费用必须使用来源付款前后队伍可见等级的实际差。没有降低时显示“无”，降低时显示“队伍降低 N 级”。队伍直属来源仍覆盖个人最高等级时，支付个人等级可能不会改变队伍可见等级；这种情况不免除配方已经定义的来源付款。JEI 显示配方静态的研究要求和来源消耗，不能代替某个队伍的运行时费用预览。

普通转换拒绝 `specialOnly`，在扣料前核对黑名单和目标阶段互斥规则。当前活动物理数据库的 owner 必须属于捕获成员；FTB 成员、角色权限、实际 actor/window、原数据库实例及 publication 证明由服务器重新捕获。全体冻结成员的真实空来源在费用为 0 时也会先创建，不能用缺失来源代替预约。

## 公开入口与真实调用

- `ResearchItemDownloadRegistry` 按完整材料 AEItemKey 和研究索引下载配方；每份定义包含稳定 ID、固定研究要求、等级费用和输出模板，回调可计算实际输出。`CommonProxy` 在研究 freeze 后发布 `ResearchItemDownloadRegistrationEvent`，同时开放 `.registry()` 和 `.imports()`，随后冻结两份注册表，不内置游戏材料或数值。
- `ResearchItemDownloadContext` 提供真实 actor/server/world、准确活动数据库 UUID、队伍、当前 actor world tick、目标等级、冻结单件材料及 operation UUID。回调只运行于主线程真实 actor 能力上下文。
- `ServerResearch.requestItemDownloadPreview` 运行纯回调并生成准确原输入、候选输出及来源付款计划。`requestItemDownload` 使用该候选，通过 MAIN 队列重新捕获玩家、队伍、窗口、物理目标和原输入授权；客户端不提交下载等级或自行构造输出。
- `ResearchWorld.requestItemDownload` 使用候选输出保存完整 native Count=1 模板与独立 long 数量，不在点击时再次调用事件。相同 actor+nonce 和相同 intent 重试同一操作；不同 intent 返回冲突，不能另建操作或重复付款。
- `ResearchWorld.recoverItemDownloads`、`pendingDownloadOperations` 和 `downloadReceipts` 供服务器边界、登录及后续物品/实体加载恢复。`abortItemDownload` 仅在完整 before 库存证明材料未扣时允许明确终止。

`ResearchItemImportRegistry` 提供静态和纯回调的自定义单输入、单输出导入。定义指定研究及本次增加等级，回调返回 `ResearchItemImportEvaluation`。存盘由 `ResearchDiskCarrier` 直接处理，物理阶级和内容来自实际输入，返回同一物品的空盘，不按物品、metadata 或阶级注册配方。没有匹配或回调没有输出时是正常的空查询结果，不写无对应输出错误日志或 GUI 错误文案。

默认数据库的 `MachineDataCenterStorage.transfers()` 保存输入槽 0、输出槽 1 和材料、交付回执；`.forPlayer(uuid)` 提供同一物理库的付款和交付适配。物理目标与库存类别写入唯一世界操作，重选活动库或操作者换队不能转移原义务。输入在付款及输出确认期间保持原操作占用，不能提前清除回执让其他玩家开始下一笔。

授权 `current` 在材料提交前重新核对 FTB、权限、物理数据库及 actor/window。World 自己核对原来源 before、预约和黑名单/互斥规则；不能把读取阶段的 cloud snapshot 证明用于 World 已初始化来源或持有短 writer 的阶段。材料提交后使用独立 `actorCurrent` 控制交付能力，换队或原数据库变化不抹去已承担的原来源义务。

## 唯一存档与提交顺序

下载材料的记录只限制同一玩家同时开始另一笔研究扣料，不限制玩家的普通操作。`COMMITTED` 记录证明该笔材料已扣；恢复时保存当前库存及原记录，禁止再次扣料，也不要求玩家库存长期保持旧 after 镜像。扣料尚未提交时仍检查本次 before 和准确槽位；库存变化导致该次研究返回失败或恢复状态，不冻结玩家，也不改写死亡或重生行为。

`ResearchWorldSnapshot` 同时保存玩家、队伍、队列、目标进度、手动会话、未完成下载、终态回执，以及物品导入的返回产物与交付阶段。`ResearchWorldNbt` 和各子编解码直接处理业务字段，不写入或检查固定格式版本号；已有 NBT 中的 Version/Schema 字段不参与读取。下载与导入共用这一份世界存档。

1. `PREPARED` 保存原 intent、成员/个人或直属来源 before/after、材料完整 native tag/ForgeCaps 与库存 before/after、输出模板和数量。按 researchId 预约，其他研究仍可推进。
2. 保存 `materialArmed` 后才写玩家 PREPARED 凭据；消费前必须完整 before 相等，只扣准确槽一件。真实库存写入观察到完整 after 后，玩家 COMMITTED 凭据同步保存；World 再保存 `MATERIAL_COMMITTED`。
3. 按冻结 operation UUID 借用原预约、提交原来源费用，用 `Prepared.owns/snapshot` 精确放行自己短 writer 的完整世界快照，保存 `SOURCES_COMMITTED`。保存失败保留唯一 `pendingSave` 和 after 值；先 complete 短 writer，预约直到准确 pending flush 成功才 release，不能回滚已经尝试持久化的来源结果。
4. `SOURCES_COMMITTED` 已可靠保存后，原玩家背包通路可以清理材料凭据；物理数据库保留材料回执，直到输出确认且 World terminal 已可靠保存后再清理，保证输入输出槽持续归原操作使用。恢复此阶段及其后不重新预约，不要求当前来源等级仍等于旧 after，正常新研究或遗忘不能导致再次扣费。
5. 从 frozen native 模板通过共享 `ItemDeliveryAdapter` 冻结完整计划，先保存 `DELIVERING` 操作，再逐片段 prepare、保存开始状态、全量或零效果交付、真实 effect commit 和 World CONFIRMED。片段只能按 `requireTransitionTo` 改状态，不能改 parent、完整 payload、数量或目标。
6. 材料凭据可靠清理且全部片段已 durable CONFIRMED 后，转为 terminal nonce receipt，丢弃重型库存镜像。保留完整 CONFIRMED 片段，供卸载后再加载的掉落和登录后重复清标签；missing/released 从不补发。

`PREPARED`/`MATERIAL_COMMITTED` 重载时从持久操作重建原 before 预约；离线原成员继续在世界来源表和 trackedPlayers 中保留。等级下降导致的旧队列失效仍交由 World 原有 pending 结算及 prune 机制。PREPARED 凭据加 after 库存仅表示未知，不能据内容相等推定该操作已扣；缺失、损坏、冲突或不确定材料/交付证据保留恢复错误或 pending。

## 分级盘物品、编码与导入

`ResearchDiskCarrier` 是使用方物品的盘接口。使用方通过 Forge 注册自己的 `Item`，决定物理 tier 来源和存储布局。接口提供 `researchDiskTier`、`hasResearchDiskData`、`readResearchDisk`、`writeResearchDisk` 和 `emptyResearchDisk`，默认使用 `ResearchDiskNbtCodec`；自定义布局必须同时覆盖读取、写入和清空方法。写入与清空返回独立输出，输入按只读方式借用，其他物品 NBT 和 ForgeCaps 保留。服务器核对内容 tier 与实际物品的物理 tier，完成等级仍独立保存。

实现 `ResearchDiskCarrier` 的物品直接支持默认存盘下载与导入，不需要 Java 或 ZS 配方注册，也不生成 JEI 页面。空盘下载每次支付来源 1 级并编码所选研究的 1 级。编码只要求 `researchTierForLevel(1) ≤ diskTier`，数据库仍执行普通下载的研究门槛及权限，不额外要求空盘阶级不高于编码数据库。产物和导入后的空盘保留该实际物品的其他 NBT/ForgeCaps。特殊研究可直接用 `ResearchDiskData`/`ResearchDiskNbtCodec.writeCopy` 编码，不要求普通云下载。自定义下载与自定义导入的注册列表只包含使用方定义，JEI 从这些定义生成输入、输出与研究说明，不执行回调。

盘导入每份向其中的研究增加 1 级，例如数据库已有 2 级，导入一份后为 3 级。自定义导入配方可定义其他增加量。研究最高等级、数据库阶级、容量和双向互斥按累加后的完整批次检查；整批通过后只消费一件输入，并交付单种返回物品，默认存盘返回空盘。不以取最大值合并多份盘，不逐条部分成功。

已编码盘对真实 `MachineControllerHost` 数据中心普通右键，既有 `ServerResearch` 的 `RightClickBlock` hook 在 LOWEST 优先级先尊重已取消及 useItem DENY，再调用公开 `ResearchDiskInteractions.use`；合法 Carrier 交互取消为 SUCCESS，使 Forge 在方块打开 GUI 前返回。客户端保留原生使用 packet，只有真实服务器玩家排入 `ServerResearch.requestDiskImport` 的已有 MAIN owner 队列，冻结原 tile、准确主手/副手槽和物品 AEItemKey。无关物品/空白盘/目标 PASS 沿用原行为，外部自有交互调度也可直接调用此 helper。新操作核对当前 FTB 队伍、目标 owner 成员、动作权限、实际活动物理数据库、盘阶级、容量及研究互斥；只有导入目标检查 `diskTier ≤ databaseTier`。后端必须具有 `ResearchDiskStorage` 且当前真实持久能力可用，普通 `DataCenterStorage` 不据此消费盘。Java 平台入口还能明确给出原队伍、数据库 UUID、槽位和 nonce，不新增第二份 packet 或 GUI。

`ResearchDiskOperation` 在唯一 World 存档中保存原目标 owner、位置、数据库 before/after、准确单件输入、库存 before/after，以及空盘或自定义输出的完整原生数据和交付片段。玩家输入复用 `MinecraftResearchDownloadInventory`，物理槽使用 `DataCenterTransferInventory.Adapter`，两种库存不能互换。原材料与数据库保存确认后才开始交付；输出确认与 World 终态保存完成后才清理回执，禁止在原物理输出尚未确认时开放下一笔交易。数据库发布、输入扣取及输出交付的完整次序见[数据中心导入](data-centers.md)。

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

// Register disk in the addon's RegistryEvent.Register<Item>.
// ResearchDiskCarrier enables default download and import without recipe registration.
// Custom items may also encode special research directly:
ItemStack encoded = ResearchDiskNbtCodec.writeCopy(new ItemStack(disk),
    new ResearchDiskData(ResearchRegistry.INSTANCE, tier, entries));
```

已静态核对本机 Cleanroom/Forge 实际调用顺序：客户端 `RightClickBlock` 取消仍发送原生 packet；服务端 event cancel 直接返回，位于 `onItemUseFirst` 与 block activation 之前。独立只读审计闭环了 armed 缺失 receipt 禁止再次调用、block GUI 前导入、LOWEST 尊重已有取消/useItem DENY 三项，报告 `build/staging/research-disk-audit/report.md`。本阶段新增 35 项真实行为测试（World 23、数据库侧 12），共同完整 `gradlew.bat build --console=plain` 为 1214 tests / 156 suites，零失败/错误/跳过；29 Java IDEA MCP `errorsOnly:false` 零错误/警告/弱警告，无 timeout，构建前后 SHA 一致。证据 `build/staging/research-disk-workflow/full-build-final-result.json`、`full-build-final-test-results/`、`pre-build-java-sha256.json`、`post-build-java-sha256.json` 与 `build/reports/idea/research-disk-inspections-final.json`。当前 HEAD 的 main/dev/sources 三个 Jar 都有 Carrier/Interactions、无已移除内置盘类/模型/专有 item 文案残留，见 `jar-verification-final.json`。没有启动游戏或服务端，实机点击与平台生命周期未计为验收；本阶段已精确本地提交 `8da5da7`，未推送。

## 验证

下载世界新增 18 项行为测试覆盖原离线成员费用、零费用空来源、黑名单拒绝、armed/save 失败、材料提交后授权变化、来源 pending 保存失败、已提交来源变化、未知材料/物品证据、完整计划先保存、真实观察恢复、nonce 冲突、terminal 清理和明确 abort。材料 41 槽测试实际发现并修正移除最后槽后空 NBT list 原生存盘类型变化造成的 after 不匹配。

本轮全 `gradlew.bat test --console=plain`：1164 tests / 150 suites，零失败、错误或跳过。World 核心 8 Java、材料修复 2 Java、旧世界 schema 契约测试 1 Java 均取得 IDEA MCP `errorsOnly:false` 完整零错误/警告/弱警告。证据：`build/reports/idea/download-world-final.json`、`download-world-verification-final.json`；平台及来源检查另见 `download-platform-final.json`。共享 ItemDeliveryTemplates 进一步只递归规范原生存盘会变型的空列表，不反复 gzip 或整栈往返；新增一项真实 native `.dat` 写入/回读后恢复已观察片段且不重复交付的测试，相关下载/奖励 suite 已在合并定向运行中全部通过，证据 `build/reports/tests/ae-physical-extension/combined-run-output.txt`。该合并运行中唯一失败属于独立物理层新 fixture，修复后的该 fixture 定向重跑已通过；不将合并 141 项称为全通过。新增共享修复 2 Java IDEA MCP 全级零诊断，证据 `build/reports/idea/download-native-template-final.json`。没有启动 Minecraft 客户端或服务端，实机交互未列为通过。
