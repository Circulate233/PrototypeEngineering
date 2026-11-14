# PN 配方条件与资源占用

本模块复用 PN 的逐配方扩展职责，在新项目的真实需求阶段中检查研究和占用计算资源。机器公共需求和配方附加需求统一进入 `RecipeProcess`，不另造一套配方处理器。它仍是库能力，不注册正式机器、配方或玩法数值。

## 计量规则

- 算力、量子位及其他 numeric 资源按当前配方的有效并行量缩放。机器默认数值与配方追加数值先相加，再乘并行量；不同进程分别占用并累加。
- 标准并发 THREAD 按机器 UUID 申请，同一台机器本 tick 无论多少工位、并行或 Java 工作线程均只占 1。活动网络中心延续 PN 的标准 THREAD 豁免；自定义 logical 维度由对应机器 lease 接口管理。
- 例如两个进程每份需求各为 10，并行量分别为 3 和 5：占用数值资源 80，整机并发 1。
- 正时长配方在实际 TICK 阶段占用一次，START 只检查资格和当前容量，END 只重验许可和研究。零时长配方没有 TICK，因此在 START 占用一次，END 不重复占用。
- 扫描 START 和独立 TICK/END 预检只检查，不占 lease、数值余额或操作序号。数值不足及并行需求超出 long 可表示范围均返回正常 Blocked，不把机器置为故障。

普通配方和研究共用数值余额与整机并发。研究在普通设备结束后使用剩余预算，各项网络资源可部分累计；研究站实际工作各占 1 并发，见[研究站执行](research-stations.md)。

## 注册与外部 API

公开入口在 `api.network.recipe`。`NetworkRecipeExtensions` 注册 `NetworkRecipeExtension` 回调；`RecipeRegistrationEvent.networkExtensions()` 提供全局注册入口。CommonProxy 在配方冻结之后、机器运行时声明解析之前发布全部扩展。

发布按配方 ID 排序，对每个配方按优先级、注册顺序调用回调。回调通过 `NetworkWorkRequirements.Builder.research(id, minimumLevel)` 和 `resource(type, amount)` 添加需求。研究最低等级合并取最大值，numeric 同类型精确相加，logical 资源不能混入 numeric 向量。元数据、研究 ID/等级均对冻结注册表检查。发布失败不提供部分索引、不重放回调，交出的 builder 随即封闭；查询严格对应冻结目录中的原配方对象。

机器安装 `MachineNetworkDevice` 之后，再声明 `NetworkRecipeWork.component(id, deviceType, defaults)`，两者在 `MachineAssembly` 中 recreated。静态声明 ComponentType 时不要求全局表已冻结，实际装配时验证 defaults 和扩展发布状态。独立宿主可显式传入自己的扩展表、研究表与资源表。

`MachineRecipeRequirements` 是机器为进程提供额外需求的外部接口。`RecipeProcess` 构造时捕获机器 provider 和原配方需求，冻结完整根列表及路径；执行、扫描预检、取消和 NBT 恢复都使用这同一份图。保存不包含运行许可、lease 或历史账本；恢复重新装配 provider，不重放 START、不重扣历史工作。声明行为改变仍须调整装配/配方 revision。

## 真实数据中心

配方研究条件读取当前网络计划选中的物理数据中心内容，不用队伍云端研究或未选中数据中心代替。`DataCenterStorage` 是外部存储职责接口；内置 `ResearchDataCenter` 保存不可变内容快照及单调运行 revision，`MachineDataCenterStorage` 负责真实机器身份、dirty 回调、生命周期和严格 NBT。

运行时 DATA_CENTER 捕获必须提供完整 `DataCenterResearchSnapshot`。`MachineNetworkDevice.capturedComponent` 接受外部精确版本证明；`MachineDataCenterStorage.networkCapture` 将它与真实存储绑定。仅有研究数量的旧构造仍可用于拓扑规划，不能用于实际数据中心授权。普通 snapshot source 会重新捕获并比较内容；外部证明需遵守只读、准确失效的契约。

存储保留 PN 的数据库规则：普通下载拒绝 special-only 与 item-only，循环研究使用云端当前等级；磁盘写入允许特殊研究，整批检查等级、不同研究 ID 容量及双向互斥，再一次发布。同步只升级已存循环研究，不新增、不降级，并校验新增等级与云端/库存互斥。旧 PN 同步没有检查此互斥；直接迁入会产生能保存却不能恢复的状态，因此新实现整批拒绝冲突升级。

这些接口不自行授权玩家，也不消费磁盘物品。当前队伍云端同步的自动调用、下载权限/事件、磁盘物品消费与恢复尚未接入，属于后续真实交互链。

## 提交与恢复

`NetworkRecipeWorkSystem` 在真实 START/TICK/END 获取当前机器 WorkPermit，以 `RecipeParallelism.effective` 计算本次向量。Registration 分配实际尝试的序号，多个工位不会复用操作键。

PN receipt 作为无库存 token 的业务事务加入原阶段；物料和 FE 保持原资源预留。阶段先执行全部 `prepareCommit`（包括 receipt.commit），再执行普通业务 commit，随后 `validateCommit` 重新检查全部捕获证明，最后才能 complete。许可失效返回普通拒绝，数值、FE、物料及本阶段可逆效果一起撤销；已经完成的历史 tick 不退款、不重跑。外部异常保留原异常和原现场，补偿只作用于签发 receipt 的旧账本。

检查点、选择器和其他事务组合均传播 prepare/validate 及原始需求路径、probe 和 TICK 上下文。恢复 pending 不重放 prepare 或 commit；UNKNOWN 证明保持 pending。

## 服务器自动执行

ServerTaskSchedulers 为服务器会话安装 MachineRecipeExecutionFactory，只为普通 CONCURRENT 注入 NetworkRecipeWork admission 和原网络 refresh/tick 证明。带 NetworkRecipeWork 的默认 CONCURRENT 控制器从普通 worker 批次移交给 owner 协调器，不修改声明模式；显式 MAIN_THREAD 继续原同步路径。正式 PLANNED_PARALLEL 机器通过公开 PlannedMachineTask 进入同一真实执行体，无 PN 的真实机器也可使用；其他普通 CONCURRENT 机器保持 worker 路径。

协调器通过 MachineLifecycle.beginScheduled 和 ComponentMap 的原游标运行机器回调，在 WorkSlotProcessingSystem 的准确位置暂停，交给真实 PlannedRecipeCoordinator。PN 许可、业务事务和扫描在 owner 执行，材料/FE 的来源任务继续使用原并行 lanes；全部来源实际退出、阶段结算完成后才恢复后续 normal/post。别名工位不会重复推进，多个独立工位宿主按原回调顺序继续。

pending 保留准确 lifecycle 句柄和原始期限，失效 tick 只确认/补偿旧事务，不能继续下一阶段或后续 normal 回调。卸载与取消在来源实际退出、进程 post 及事务确认完成后才释放生命周期门限；UNKNOWN 保持 pending，不盲目重发完成回调。刷新网络只发生在来源工作之外。

普通 CONCURRENT 网络 admission 属于内置 ServerTaskSchedulers。公共三参 TaskScheduler 默认安装泛化机器入口，Controller/Instance 或外部 PlannedMachineTask 包装交付已有 lifecycle 的真实机器即可选择正式模式，不需内部 factory 或服务器静态服务。当前性同时证明原登记、active、捕获 mode、原机器/lifecycle 与底层 mode；owner refresh 返回后再次重验才进入后继组件。旧 pending 不取得新 tick 授权。块一 pure prepare/实际需求工作预算、2A 正式入口与 2B 扫描内部预算均已接并验证。扫描在原 machine/lifecycle 组件游标暂停，MachineRecipeExecution 复用同一 ScanAttempt、pure prepare 与 source lanes；candidate/probe 限额和内部 RecipeWorkBudget 独立。完整同轮捕获后才派发，FE/opaque 保持原副作用顺序，continuation owner prepare 返回后重验原 current/deadline。UNKNOWN、实际未退出、部分 token 和独立 additional cleanup 保留原义务，旧 pending 不续权或重复额度；外部合作回调和完整首次 tick 投影边界见[执行边界](planned-execution.md)。

owner 先增量捕获资源数量及 revision，纯 worker 根据冻结数据计算倍率、随机输出和动态候选；owner 再捕获当前数量，验证原事实和来源版本，完成选择、预检与资源/FE 预留。动态 kernel 保留全部已排序匹配，owner 先过滤当前非零可用项，再应用 maxCandidates 并选择满足数量的项。来源变化不能让旧候选误消费；成功 START/历史 tick 的材料与效果不因计算暂停重复执行。

`MachineRecipeExecution` 沿原 deadline 和 current attempt 继续普通 work yield，UNKNOWN 保留原确认义务。跨 tick 缓存只保存事实，许可、来源池、租约和预留在当前执行边界获取；终态清理缓存，存档不保存缓存。研究站现为同步总量材料结算，目标进度使用世界 V6 保存，见[研究站](research-stations.md)。普通配方的外部 snapshot、matcher/comparator 和回调规范见[执行预算](planned-execution.md)。

验证使用真实 MachineAssembly、MachineController、调度器、FE/资源事务、网络账本及研究存储，未启动游戏。最终测试计数与 IDEA MCP 证据见[实施进度](implementation-status.md)。
