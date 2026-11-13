# 数据中心下载与自动同步

普通数据库下载只把当前云研究等级写入活动物理数据库，不消费研究等级、玩家材料或奖励资格。普通物品化下载已接到真实注册回调、扣料/扣级、世界存档和交付恢复，详见[物品化下载](research-downloads.md)。分级盘由外部用户注册实际物品及阶级，库提供公开物品接口、编码事件及真实数据库／玩家库存共同恢复；磁盘导入消费准确槽位的一件盘，不扣研究来源等级或改变奖励资格。

## 旧实现与保留行为

- PN `PrototypeNetworkServerPacketExecutionImpl.executeDatabaseDownload` 在请求执行时重新读取活动物理数据库，检查实际玩家、当前队伍和下载权限；同一发布周期的多次下载累计写入，不能用旧网络快照整库替换。
- PN `ActiveDataCenterLoopingResearchImpl.synchronizeAndCommit` 只升级数据库中已经存在的普通多等级研究，不增加缺失研究、不降低等级。特殊研究和 `ITEM_ONLY` 不自动升级；黑名单不会删除已存条目。升级仍受数据库 tier 和互斥规则约束。
- PN `DataCenterStorageComponentImpl.replace` 先替换内容再标记 dirty。回调抛错不能证明写入未发生；新实现保留实际内容、撤销旧发布并传播错误，禁止回填旧整库快照。

## 公开职责

`DataCenterStorage` 是外部存储契约，`ResearchDataCenter` 是默认规则实现，`MachineDataCenterStorage` 负责机器组件、生命周期和 NBT。带 `BooleanSupplier current` 的写入方法在实际发布前重验只读授权；早先不带证明的重载仍供已持有独占授权的直接宿主调用。

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

`ResearchDiskImport.prepare` 复用默认数据库的完整批次规则，冻结 operation、actor、team、磁盘阶级及独立完成等级、原内容和完整 after。编码要求研究完成等级对应的门槛阶级不高于磁盘阶级，导入再要求磁盘阶级不高于数据库阶级。容量、数据库门槛和双向互斥全部通过后才能发布，没有逐条部分成功。

1. 唯一研究世界先保存 unarmed PREPARED 和准确原槽的完整 before／after 库存；玩家材料 PREPARED 可靠保存后，再保存 databaseArmed。没有尝试写库的操作仍可明确取消。
2. `commitDisk` 最后重验原 before 和当前授权，用一次不可变 `ResearchDataCenter.State` 发布内容及完整 operation receipt。即使内容已经足够，也必须发布回执并使旧内容凭据失效。拒绝返回严格保证本次零效果；发布后的 dirty 异常只抛错，保留整个 after／receipt，不回填旧内容。
3. `persistDisk` 保存此时完整 State，再回读真实数据库来源，匹配准确回执及所有当前内容后，World 才保存 DATABASE_COMMITTED。普通下载／自动升级始终保留 unresolved receipt，后续保存验证当前完整 State，不要求内容仍等于旧导入 after，也不覆盖后续写入或补回 tick 预算。
4. DATABASE_COMMITTED 可靠保存后，原玩家材料凭据只允许准确槽消费一次。真实玩家 COMMITTED 可靠保存后，World 保存 COMPLETED；之后才可靠清理玩家和数据库回执，并保存 RELEASED nonce 终态。清理时回执已缺失仍保存、回读当前 State，后续内容升级不妨碍清理重试。

首次调用明确拒绝、无 receipt 且材料未扣时可以完成 ABORTING。抛错或重启后 databaseArmed 且缺失回执属于未知，不能据内容相等推定成功、再次扣盘或取消原义务。冲突回执、玩家证据损坏、原物理库不可用或保存尚未确认均保留待恢复状态。

`MachineDataCenterStorage.CODEC` 以明确 Schema 2 同时保存研究内容与完整 DiskReceipts，读取也验证二者一致，不兼容旧 schema。默认 Minecraft 适配通过公开 `MachineControllerHost` 核对原机器及真实 AE 方块实体，使用原 Anvil loader 写准确已加载区块，等待文件 writer 结清且 pending 为零，然后用新的 `RegionFile` 回读实际文件。核对正确世界来源、区块坐标、唯一原 tile 坐标／native id、机器类型、owner／数据库 UUID 及唯一存储组件完整 payload；写前和回读后都重验原物理绑定。未使用全世界保存或第二份数据库存档。

## 界面

数据库页复用 `GuiResearch` / `ContainerResearch` 和 AE2 同步。显示云可下载研究及所有已存研究，包含云等级、已存等级、数据库 tier 和容量；特殊或仅物品交付研究仅在已经存入时展示，不能普通下载。

列表分页与动作使用独立有界快照。操作绑定最后绘制的条目和数据库身份，并由服务器自己的快照再次匹配；队伍变化、数据库重选、行重排和旧窗口不会改变操作目标。所有标签和业务结果使用本地化枚举。

## 验证与剩余范围

行为验证覆盖当前成员与权限、只读捕获、失效证明、外部存储绑定、连续下载、同 tick 预算、升级过滤、失败后的内容与发布状态，以及 GUI 分页和动作编解码。完整构建 1093 tests / 143 suites 全通过，本阶段 21 个 Java（含生成 Tags）IDEA MCP 全级零诊断，build_project 成功。证据为 build/reports/idea/datacenter-download-final.json，详见 [实施进度](implementation-status.md)。

当前磁盘阶段新增数据库侧 12 项行为测试，集中覆盖原子内容／回执发布、dirty 后失败、明确拒绝零效果、普通后续写入保留回执、清理失败重试、原物理恢复与复制／搬迁拒绝，以及真实 Anvil 文件中宿主／owner／位置／存储绑定／完整回执被替换或缺失。与 World 的 23 项行为共同验证后，完整 build 为 1214 tests / 156 suites，零失败、错误或跳过，assemble/Spotless/test/check 全通过；29 个 Java 本机 IDEA MCP `errorsOnly:false` 全级零诊断、无超时，源码构建前后 SHA 一致。证据 `build/staging/research-disk-workflow/full-build-final-result.json`、`full-build-final-test-results/` 和 `build/reports/idea/research-disk-inspections-final.json`。独立审计确认数据库 State／receipt、原物理捕获和真实 Anvil 回读无剩余阻断；当前三种 Jar 也已核对无内置盘类、模型或专有 item 文案残留。

普通物品转换的既有验证见[物品化下载](research-downloads.md)。个人遗忘、管理员操作，以及永久破坏研究站后的材料会话收尾仍需后续处理。没有启动 Minecraft 或服务端，实际游戏交互和平台生命周期仍待实机验收。
