# 数据中心下载与自动同步

## 默认控制器（2026-11-13）

`DataCenterController` 是可继承的默认数据库控制器，直接继承 `MachineInstance`，实现 `DataCenter` 网络角色接口。它组合已有 `DataCenterStorage` 组件，等级、容量、研究内容及导入回执保存在该组件中，不在控制器另存一份。没有普通配方工位或独立业务 tick；下载和自动同步由共享研究服务推进，GUI 已进入设计核对，正式界面等待审核。

Java先定义存储组件，再声明机器与装配：

```java
var storage = MachineDataCenterStorage.component(
    new ResourceLocation("example", "database/storage"), 1, 16, ResearchRegistry.INSTANCE);
var type = MachineType.builder(id, title, structure)
    .component(MachineOwnership.TYPE).component(storage).component(DataCenter.DEVICE).build();
var assembly = MachineAssembly.builder(type, "data-center-v1")
    .instanceFactory(value -> new DataCenterController(value, storage))
    .component(storage, MachineDataCenterStorage.CODEC).recreated(DataCenter.DEVICE).build();
```

`new DataCenterController(type, storage)` 接受任意实现 `DataCenterStorage` 的已声明组件类型。默认存储工厂的等级为非负整数，容量为正整数，容量按不同研究条目数计数；研究完成等级不是数据库阶级。存储内容和完整磁盘回执沿现有codec保存恢复，默认物理持久适配仍使用原机器区块的真实保存与回读。

`DataCenter.dataCenterStorage()` 返回该加载代的实际存储组件；`DataCenter.DEVICE` 从其当前内容、等级和容量捕获网络快照，并保留存储版本证明。自定义控制器可直接实现此接口与独立存储，仍须声明所有权、存储组件、持久codec及重建的角色。写入遵守既有服务器授权和存储契约，接口访问不单独授予下载或导入权限。

每队活动数据库按等级、已有研究条目数及持久机器 UUID 选择；多个库不合并内容。活动数据库占用 1 份标准并发，优先于普通设备，不能取得额度时不授予活动许可。只升级库中已有的合格多等级研究，不自动补入新研究。云端写库保持下文规则，物品导入按最新配方与累加规则执行。

ZS使用 `MachineType.dataCenter(id, translationKey, structureId, level, capacity).register(revision)`；工厂安装相同Java控制器、持久存储及网络角色，无需为专用数据库安装普通配方组件。Java示例 `DataCenterExamples` 注册一级/16条的 `java_data_center`，结构为控制器上方铁块；原ZS `net_database` 使用同样等级和容量，原结构保留，修订为 `data-center-v1`。

云端数据库下载只把当前云研究等级写入活动物理数据库，不消费研究等级、材料或奖励资格，继续由云端调用服务方法。自定义物品化下载与物品导入使用单输入、单输出配方，详见[下载与导入](research-downloads.md)。分级盘由使用方注册实际物品并实现 `ResearchDiskCarrier`，编码与导入不需要配方注册。默认编码支付来源 1 级并记录所选研究的 1 级；导入每份向对应已存等级累加 1，返回同一物品的空盘，不扣研究来源等级或改变奖励资格。

## 旧实现与保留行为

- PN `PrototypeNetworkServerPacketExecutionImpl.executeDatabaseDownload` 在请求执行时重新读取活动物理数据库，检查实际玩家、当前队伍和下载权限；同一发布周期的多次下载累计写入，不能用旧网络快照整库替换。
- PN `ActiveDataCenterLoopingResearchImpl.synchronizeAndCommit` 只升级数据库中已经存在的普通多等级研究，不增加缺失研究、不降低等级。特殊研究和 `ITEM_ONLY` 不自动升级；黑名单不会删除已存条目。升级仍受数据库 tier 和互斥规则约束。
- PN `DataCenterStorageComponentImpl.replace` 先替换内容再标记 dirty。回调抛错不能证明写入未发生；新实现保留实际内容、撤销旧发布并传播错误，禁止回填旧整库快照。

## 公开职责

`DataCenterStorage` 是外部存储接口，`ResearchDataCenter` 是默认规则实现，`MachineDataCenterStorage` 负责机器组件、生命周期和 NBT。带 `BooleanSupplier current` 的写入方法在实际发布前重验只读授权；不带证明的重载供已持有独占授权的直接宿主调用。

`ResearchDataCenters.capture(teamId, expectedDataCenterId)` 从既有机器身份注册表中寻找准确物理目标。必须是本队当前选中的 `ACTIVE_DATA_CENTER`，并核对真实机器、登记代、世界位置、成型状态、角色和存储组件实例。存储按公开接口发现，重复绑定明确失败。不能只凭内容相等，把新存储组件接到旧发布上。成功写入会令原 capture 失效。

磁盘导入在写前仍使用上述授权。`Capture.physicalCurrent()` 单独保留原登记代、机器、世界／位置和存储组件绑定，不读取旧内容许可；导入自己的内容发布不会令它失效。`Target` 保存原数据库 UUID、owner UUID 和机器类型／维度／坐标。`captureOriginal(Target)` 只恢复准确物理义务，不借当前队伍、网络角色或新选中库的权限；复制身份、搬迁、卸载或原捕获后的组件替换都会使相应物理证明失效。

`ResearchDiskStorage` 是可选的真实持久导入职责。外部 `DataCenterStorage` 不实现此接口，或 `diskPersistenceAvailable()` 明确不可用时，新请求在世界 PREPARED 之前拒绝。实现必须原子记录完整 `ResearchDiskCommit` 与内容，并在真实保存来源回读一致后才从 `persistDisk` 返回；dirty、UPDATED 或内容相等均不构成持久证明。外部宿主可直接实现接口，机器组件也可通过 `MachineDataCenterPersistence` 提供自己的真实写入与回读，不要求继承默认方块实体。

`ResearchWorld.captureCloud` 根据当前成员、个人及队伍直属来源和黑名单计算云等级。玩家版本仅执行指定动作的权限策略；系统版本没有玩家权限回调。查询不创建来源、不运行研究开始条件、不保存、不推进时间。后续证明同时覆盖原本存在和原本缺失的来源、当前成员证明及世界健康状态。

`DataCenterResearchAccess` 在独占 owner 边界组合上述职责：

- `download` 重新捕获下载权限、云等级及指定物理数据库，执行一次存储操作；成功写入后刷新网络发布。
- `synchronize` 使用物理 owner 的当前队伍成员自动升级已有条目，同样在写后刷新。
- `view` 独立查询状态查看和下载权限；拒绝查看时不暴露数据库或云等级。
- 世界存档尚待确认时不执行下载或自动升级；只读界面仍可显示保存状态。

刷新沿用 `NetworkRuntime` 本 tick 的预算，不能补回已经消耗的算力或重新分配整机并发。每次请求都重新 capture；第二次下载不会丢失第一次写入。写入、dirty 或刷新异常会撤销旧 publication 并原样抛出，已写数据不盲目回滚。

## 服务器接线

`ServerResearch.requestDataCenterDownload` 复用既有 MAIN 提交队列，绑定原玩家实例、原窗口证明、队伍 ID 和数据库 UUID。执行时重新捕获 FTB 成员与角色。客户端不能提交云等级、数据库内容或权限，数据库重选也不能把旧点击静默作用到新目标。

`ServerTaskSchedulers` 在研究 prerequisite 完成后、普通工作开始前的首次独占准备边界调用 `ServerResearch.refreshDataCenters`。每 tick 只遍历同步一次；后续 MAIN、并发和 planned 准备只刷新网络发布。原研究或来源工作尚未退出时不会越过边界写数据库。

自动同步每个队伍时重新读取当前选中目标，经真实物理 owner 捕获全部当前成员；前一数据库更新后重新发布，后一队伍使用新许可。故障撤销发布，不撤回前面已成功写入的数据库。

## 磁盘共同持久提交

`ResearchDiskImport.prepare` 冻结 operation、actor、team、输入阶级、本次增加等级、原内容和完整 after。存盘每份增加 1 级，普通物品配方按声明增加量处理。累加后的研究等级不能超过最高等级；数据库阶级、容量和双向互斥全部通过后才能发布，没有逐条部分成功。

1. 唯一研究世界先保存 unarmed PREPARED 和准确原槽的 before／after 库存及返回产物；原输入库存 PREPARED 可靠保存后，再保存 databaseArmed。没有尝试写库的操作仍可明确取消。
2. `commitDisk` 最后重验原 before 和当前授权，用一次不可变 `ResearchDataCenter.State` 发布累加后的内容及完整 operation receipt。拒绝返回保证本次零效果；发布后的 dirty 异常只抛错，保留 after 和 receipt，不回填旧内容。重试读取原回执，不再次累加等级。
3. `persistDisk` 保存此时完整 State，再回读真实数据库来源，匹配准确回执及所有当前内容后，World 才保存 DATABASE_COMMITTED。普通下载／自动升级始终保留 unresolved receipt，后续保存验证当前完整 State，不要求内容仍等于旧导入 after，也不覆盖后续写入或补回 tick 预算。
4. DATABASE_COMMITTED 可靠保存后，原输入凭据只允许准确槽消费一次。COMMITTED 可靠保存后冻结输出计划并进入 DELIVERING；返回空盘或自定义产物得到确认后，World 保存 COMPLETED，再可靠清理输入与数据库回执并保存 RELEASED nonce 终态。物理库输入输出在该过程中持续保留原操作占用。清理时回执已缺失仍保存并回读当前 State，后续内容升级不妨碍清理重试。

首次调用明确拒绝、无 receipt 且材料未扣时可以完成 ABORTING。抛错或重启后 databaseArmed 且缺失回执属于未知，不能据内容相等推定成功、再次扣盘或取消原义务。冲突回执、玩家证据损坏、原物理库不可用或保存尚未确认均保留待恢复状态。

`MachineDataCenterStorage.CODEC` 保存研究、DiskReceipts 及附属 Transfers 操作库存，不写入或校验 Schema 字段。回执只限制本次实际导入涉及的研究，允许移出其他条目。Transfers 的空输入、空输出使用原生空 NBT 语义，可选材料与交付回执按是否存在解码。默认持久适配沿原机器区块保存并回读完整组件数据，核对准确物理目标及原回执，不使用全世界保存或第二份数据库文件。

## 界面与配方操作

数据库页使用 [最新 HTML](previews/data-center.html) 核对，正式 GUI 等待用户审核。左侧列出已存研究并支持二次确认移出；右侧通过按钮切换研究下载和物品导入，同一材料槽、箭头和输出槽供两种模式使用，底部有完整背包。下载来源和下载等级不占界面行，研究费用在本次付款不使队伍掉级时显示“无”。返回采用 AE `Icon.BACK`。完整设计见 [research-gui.md](research-gui.md#数据库界面)。

默认物理数据库的操作库存与材料、交付回执保存到 `MachineDataCenterStorage` 的同一组件。输入槽 0、输出槽 1，切换模式或关闭窗口不清空。服务端生成候选输出，执行时重新捕获权限、原输入和物理目标。预览与直接执行均通过交付适配检查实际输出及数量能否容纳，检查必须早于材料扣取和研究写入。实际付款及交付继续使用唯一研究世界的恢复流程。

物品导入配方由输入取得研究、增加等级并生成输出；容量按不同研究条目计数，向已存研究追加等级不额外占一格。累加后必须仍满足最高等级、数据库阶级及互斥条件，不能以取最大值代替每份累加。输出槽有物品时不允许开始下一笔。普通物品没有匹配导入或下载配方时查询结果为空，不报告无对应输出的错误。

## 验证与剩余范围

行为验证覆盖当前成员与权限、只读捕获、失效证明、外部存储绑定、连续下载、同 tick 预算、升级过滤、失败后的内容与发布状态，以及 GUI 分页和动作编解码。完整构建 1093 tests / 143 suites 全通过，本阶段 21 个 Java（含生成 Tags）IDEA MCP 全级零诊断，build_project 成功。证据为 build/reports/idea/datacenter-download-final.json，详见 [实施进度](implementation-status.md)。

当前磁盘阶段新增数据库侧 12 项行为测试，集中覆盖原子内容／回执发布、dirty 后失败、明确拒绝零效果、普通后续写入保留回执、清理失败重试、原物理恢复与复制／搬迁拒绝，以及真实 Anvil 文件中宿主／owner／位置／存储绑定／完整回执被替换或缺失。与 World 的 23 项行为共同验证后，完整 build 为 1214 tests / 156 suites，零失败、错误或跳过，assemble/Spotless/test/check 全通过；29 个 Java 本机 IDEA MCP `errorsOnly:false` 全级零诊断、无超时，源码构建前后 SHA 一致。证据 `build/staging/research-disk-workflow/full-build-final-result.json`、`full-build-final-test-results/` 和 `build/reports/idea/research-disk-inspections-final.json`。独立审计确认数据库 State／receipt、原物理捕获和真实 Anvil 回读无剩余阻断；当前三种 Jar 也已核对无内置盘类、模型或专有 item 文案残留。

普通物品转换的既有验证见[物品化下载](research-downloads.md)。个人遗忘、管理员操作，以及永久破坏研究站后的材料会话收尾仍需后续处理。没有启动 Minecraft 或服务端，实际游戏交互和平台生命周期仍待实机验收。
