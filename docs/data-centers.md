# 数据中心下载与自动同步

普通数据库下载只把当前云研究等级写入活动物理数据库，不消费研究等级、玩家材料或奖励资格。物品化下载及特殊磁盘是独立操作，不能把本章的存储更新当作它们已完成。

## 旧实现与保留行为

- PN `PrototypeNetworkServerPacketExecutionImpl.executeDatabaseDownload` 在请求执行时重新读取活动物理数据库，检查实际玩家、当前队伍和下载权限；同一发布周期的多次下载累计写入，不能用旧网络快照整库替换。
- PN `ActiveDataCenterLoopingResearchImpl.synchronizeAndCommit` 只升级数据库中已经存在的普通多等级研究，不增加缺失研究、不降低等级。特殊研究和 `ITEM_ONLY` 不自动升级；黑名单不会删除已存条目。升级仍受数据库 tier 和互斥规则约束。
- PN `DataCenterStorageComponentImpl.replace` 先替换内容再标记 dirty。回调抛错不能证明写入未发生；新实现保留实际内容、撤销旧发布并传播错误，禁止回填旧整库快照。

## 公开职责

`DataCenterStorage` 是外部存储契约，`ResearchDataCenter` 是默认规则实现，`MachineDataCenterStorage` 负责机器组件、生命周期和 NBT。带 `BooleanSupplier current` 的写入方法在实际发布前重验只读授权；早先不带证明的重载仍供已持有独占授权的直接宿主调用。

`ResearchDataCenters.capture(teamId, expectedDataCenterId)` 从既有机器身份注册表中寻找准确物理目标。必须是本队当前选中的 `ACTIVE_DATA_CENTER`，并核对真实机器、登记代、世界位置、成型状态、角色和存储组件实例。存储按公开接口发现，重复绑定明确失败。不能只凭内容相等，把新存储组件接到旧发布上。成功写入会令原 capture 失效。

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

## 界面

数据库页复用 `ResearchGui` / `ResearchContainer` 和 AE2 同步。显示云可下载研究及所有已存研究，包含云等级、已存等级、数据库 tier 和容量；特殊或仅物品交付研究仅在已经存入时展示，不能普通下载。

列表分页与动作使用独立有界快照。操作绑定最后绘制的条目和数据库身份，并由服务器自己的快照再次匹配；队伍变化、数据库重选、行重排和旧窗口不会改变操作目标。所有标签和业务结果使用本地化枚举。

## 验证与剩余范围

行为验证覆盖当前成员与权限、只读捕获、失效证明、外部存储绑定、连续下载、同 tick 预算、升级过滤、失败后的内容与发布状态，以及 GUI 分页和动作编解码。完整构建 1093 tests / 143 suites 全通过，本阶段 21 个 Java（含生成 Tags）IDEA MCP 全级零诊断，build_project 成功。证据为 build/reports/idea/datacenter-download-final.json，详见 [实施进度](implementation-status.md)。

尚未完成：研究物品下载的 `createOutput`、扣料/扣级与冻结产物交付；特殊磁盘真实消费与数据库共同恢复；个人遗忘、管理员操作，以及永久破坏研究站后的材料会话收尾。没有启动 Minecraft 或服务端，实际游戏交互和平台生命周期仍待实机验收。
