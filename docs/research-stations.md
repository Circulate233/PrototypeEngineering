# 研究站材料与推进

2026-11-13。研究站已接入 `ResearchWorld` / `ServerResearch` 自动推进、原 pending 所有权、世界会话保存恢复与永久破坏清理。正 tier 入队、领取、普通下载及接口式用户盘共同导入的后台能力保留；研究 GUI 及其专用接线已移除，仍延缓实现。盘物品、tier、布局由外部注册，库不注册正式研究内容或内置盘。

## 研究站控制器与速率

`api.research.station.ResearchStation` 提供 `researchLevel()`、`researchRates()` 和 `energyPerTick()`。默认 `ResearchStationController(type, level, rates, energyPerTick)` 直接继承 `MachineInstance`，不安装配方工位，不登记机器业务 tick；实际推进由共享研究服务负责。自定义运行时可实现该接口并声明 `MachineOwnership.TYPE`、`ResearchStation.DEVICE`，装配将 DEVICE 标为 recreated。专用研究站始终保留网络角色，不受普通控制器默认配置影响。

`rates` 是按只读契约借用的数值资源速率表，缺项为 0。每站等级为正、耗能与速率非负，具体速率由开发者定义，没有资源类型固有上限。不同站可提供不同的速率与 FE 定义。研究公式只提供 `constant(type, demand)` / `affine(type, base, slope)`，不再存储单站限额；旧 API 调用须移除最后的 limit 参数，把速率移到站点定义。

新段候选排除无力支付本 tick FE 的站。END 获取每站的能源预留，与阶段材料、队列进度及研究完成共同提交；任何未成功提交的费用沿同一 ResourceBatch 补偿。只有成功推进收费，同站同服务器 tick 不重复扣费，待机不扣；材料需求自带的 FE 仍独立按 START/TICK/END 规则执行。保存恢复保留已支付材料阶段，后续仍需取得当前站点许可。

Java 示例为 `run/visual-examples/src/main/java/pe/visual/examples/ResearchStationExamples.java`：等级 1、COMPUTE 速率 10、8 FE/推进 tick，左输入、右输出、后能源仓。ZS 的 `MachineType.researchStation(id, title, structure, level, rates, energyPerTick)` 使用同一默认实现，run 的 net_station 提供 net_compute 速率 10、8 FE。使用与普通联网开关见 [API](../api.md#研究与计算网络)。未增加 HTML 或 GUI。

## 对齐旧实现与 R29

复核 PN `ResearchStationRuntimeImpl` / `ResearchStationTickImpl` 和 PM 的真实需求系统。保留同队、成型、加载、等级、活动物理数据中心门限，维度轮换与维度内 UUID 顺序，各站共享整机 THREAD 租约。多个站合摊一份材料，每种 numeric 的推进量改为 `min(研究供给, 各参与站该资源速率之和, 剩余需求)`；研究读取独立供给，不扣普通配方余额。

R29 确认每次有效推进完整执行材料 START、TICK、END。旧 PN 投影丢掉属性/输出并混合部分阶段，不沿用该缺口。新材料保留完整 `RecipeRequirement`，不新增投影 DTO；每段只在 END 成功后授予研究进度。

## 实际入口

- `ResearchStations.capture(teamId, tier)` 枚举现有唯一机器登记，使用 `MachineNetworkDevice.workPermit()` 和该 publication 的 `deviceSnapshot()`。选同维度、同一 world 实例的合格站，逐站申请 lease，并冻结真实主站及一份合摊 `MachineHatches`。没有第二份站点注册表。
- `ResearchStations.renew(capture)` 在新 tick 重新获取许可和 lease，保持原主站、原站集合、原仓室绑定及同一个合摊对象。站被替换、卸载或仓室绑定变化时正常返回 null，不偷偷把半途材料换到另一批站上。来源证明只读身份/绑定及许可，在资源 fence 内不重新读库存或获取额外锁。
- `ResearchStationWork` 持有一个真实队列和玩家/队伍研究来源。`advance` 从队头规划一段、建立或续办原材料进程，`resolve` 保留并恢复原批，`awaitExit` 只证明来源任务实际退出，`cancel` 在结算后取消未完成进程。每个持久时间值最多成功推进一段，不重复推进同刻的新队头。
- `PlannedRecipeCoordinator.forProcess` 接受独立材料进程，共用原协调器的 source lanes、截止时间、取消、退出屏障与 UNKNOWN 恢复。额外事务只在成功获取阶段后捕获一次；返回 null 为正常拒绝。END 的 queue grant 和 completion 与该阶段物料/FE、进程状态进入同一个 `ResourceBatch`。

调用方必须在独占 owner 边界执行，并持有消费者直到旧来源任务退出且所有事务终态确认。保存、prune、替换站点、重新发布网络或运行其他可能访问相同仓室的工作前，必须处理原 pending。异常仍原样传播；不能因调用抛错就丢弃消费者。

## 阶段顺序与材料数量

`RecipeProcess.materialCycle` 使用真实机器 owner 与冻结仓室，只包含所给材料图，不附加普通 `MachineRecipeRequirements`，避免重复普通配方算力需求。资源和 FE 系统通过 `process.hatches()` 取这份来源；普通配方继续读取当前机器绑定。

START 完成 source apply → business commit → 全部终态确认后，才获取 TICK；TICK 完成后才获取 END。因此 START 选择修改器与产物可供后续阶段使用。后续阶段缺料/输出满或授权失效时，保留先前成功阶段，只重试未成功阶段，不重付 START。取消也不退款历史成功阶段，沿用正常配方规则。

材料周期以一次成功 TICK 作为 END 门限，速度 0、0.5、2 都不会多执行或跳过 TICK；TICK 的 scaled/unscaled 上下文仍保留实际速度时间线，供属性和检查点使用。材料本地并行基数为 1，阻断主站普通配方并行对一份材料的放大；材料自身随后添加的 selector/属性修改器仍可生效。各站贡献速率相加，不直接复制材料。

结束业务被拒时，只补偿该次 END 的物料/FE、grant 和 completion，历史 START/TICK 不回滚。结束已经决定 COMPLETE 后，即使外部 complete 结果未知，也保留原 grant/完成结果与原 token，仅查询或执行有明确可重试证明的终态确认，不重放完整材料或重新发放研究。

## 世界会话与原站恢复

`RecipeProcessNbt.encode` 对材料周期增加显式 `MaterialCycle` 标志；`decodeMaterialCycle` 复用完整属性、selector、overlay、输入锁和组件 codec，并调用 `restoredMaterialCycle`。普通/材料恢复入口互相拒绝错误模式，速度为 0 的“已成功 TICK、进度仍为 0”合法。恢复仍由新宿主提供仓室与授权，不恢复旧 token/epoch/lease。扩展组件继续需要显式 codec 与定义 revision，不静默丢弃未知状态。

`ResearchRegistry.freeze` 为每个定义的正 tier 阶段绑定唯一材料配方与完整 `RecipeProcessNbt`，同阶段等级共用，跨定义隔离。`ResearchLevelStage.Builder.materialPersistence(revision, configure)` 提供外部组件/选择器/overlay 所需 codec 配置。注册前置校验失败可修正；外部配置开始后禁止重入或重放。

`ResearchWorldNbt` Schema 5 在同一研究文件内保存队头、原站 UUID/owner/维度/位置、可选原控制器方块 ID、最后授予时间与完整材料进程。严格拒绝缺字段、重复站、错误队头和旧格式；NBT 防御复制。恢复先保留原会话，原站真实加载、身份与位置准确且重新获准后才解码，不重放历史 START/TICK。方块 ID 只证明物理来源，不是另一层运行身份；相同站点恢复保留原凭据。普通不可用继续等待，不换另一批站续付半途材料。

换队或黑名单使研究失效时，`restoreForCleanup` 只证明原真实站点和仓室，允许在失去旧队网络工作许可后解码并取消；该证明不能用于推进，不获取 THREAD/numeric。取消异常保留已重建进程，禁止重新解码重放回调。

## 永久破坏与原进程所有权

`ResearchWorld.stationLifecycle(stations)` 接真实 `ControllerBroken`；`ServerResearch` 会话在 GLOBAL 注册，成功关闭才解除。事件在所有原 source 退出、事务终态及 fence 结清后的 scheduler boundary 执行，先于原 ownership 的 Unloaded 注销；破坏事实从准确原 runtime/registry/world/位置取得，不要求 formed、工作许可或 UUID 当前唯一。普通卸载和结构失效不调用材料取消。

`suspend` 编码后保留原 `RecipeProcess` 作为仅取消现场：它不提供 sources、租约或新阶段授权，成功 resume 才释放旧引用。即使主站已卸载，另一原贡献站破坏也沿原 owner/仓室执行 cancel，保留已成功 START/TICK 的 IO，停止未完成材料周期，队列的已获 numeric 与研究完成不退。UNKNOWN 的已经决定结果仍只确认原 token，不重放材料或发放。原过程或已重建 cleanup 取消失败使世界 faulted，拒绝旧 tag 再保存/恢复。

跨重启只剩 raw checkpoint、没有已拥有的重建进程或 source token 时，真实永久破坏直接终结局部恢复状态，不构造不存在的机器，也不宣称执行了 abort 回调。`MachineAssembly.savedIdentity` 只读准确 ownership codec；未装配、queued load 被停止和已停放 raw 控制器通过 `UnassembledControllerBroken` 通知。TE 的 replacementData 使用当前保存源，vanilla 已替换方块时使用保存的规范 type，不重读新方块来认领旧身份。

销毁前旧 checkpoint 写失败保留精确破坏事实和原进程，先重写旧快照，再取消；销毁后写失败保留准确终止快照，只重写不重跑 cancel。vanilla chunk 与研究文件仍不跨文件原子：重启后只对 cold raw 核验已经加载的原位置，不强制加载。默认宿主先核实际 ControllerHost 的 live/raw 身份；准确原身份仍在时保留，未知外部宿主/未知 raw/未加载区块不能判毁。无实体且原物理方块已不存在，或实际宿主证明原身份已被替换，才终结旧局部状态。虚拟宿主默认不提供方块凭据，外部可通过 ResearchStations 的明确 absence 职责提供等价证明。

当前仓室 token 没有可持久结果回执，因此不宣称 vanilla chunk 库存与研究文件跨文件原子。嵌套检查点已通过原批次分段执行修复阶段可见性，候选后续拒绝已支持局部退款再改选，见[专项审计](checkpoint-execution-audit.md)。检查点仍以一次外层触发为共同补偿边界，不能与研究站材料周期已经提交的历史阶段混同。

## 自动调度与故障边界

`ServerTaskSchedulers` 将研究安装为普通工作之前的 `PlannedExecution`。调度器先持有 execution，再捕获真实来源、持有贡献机器生命周期句柄并执行。旧 tick、source fence、UNKNOWN 或未退出任务未结清时，不放行新的网络捕获及普通工作；仍使用原 lanes，没有另起执行框架。

`ResearchWorld.StationTick` 保留当前队伍游标和消费者。存档失败先重写准确 pendingSave，正常缺料只保留未完成材料阶段；未知事务保留旧 execution 并允许确认后恢复。已结清的异常释放执行所有权，世界保留原错误与材料现场并禁止新工作或保存，不伪装成永久等待。正常业务 Failure 走真实取消清理。

## 验证

永久破坏阶段新增 10 项 World 行为，覆盖真实 broken 原进程、主站卸载后贡献站破坏、cold raw 不重建、queued/parked raw 通知、旧 checkpoint/终止 checkpoint 写失败、取消故障、UUID 冲突及重启后准确 absence 补结。原 UNKNOWN 用例改走永久 destroy，仍保留已决定 completion 和原 token。完整 build 1224 tests / 156 suites 全通过；18 Java IDEA MCP errorsOnly:false 全级零诊断，23 文件构建前后 SHA 无漂移，独立只读审计无剩余确定缺陷。证据 `build/reports/idea/research-station-destruction-final.json`、`build/staging/research-station-destruction/full-build-result.json` 与同目录完整测试/原始快照。真实 Chunk 先换 block 再调用旧 breakBlock、DimensionManager 非初始化读取及 AE2S 清理时序按本机源码静态核对；未启动游戏或服务端。

研究站服务器/存档阶段新增 42 项行为测试；完整 Gradle 构建通过 879 项测试、123 suites，零失败/错误/跳过。本阶段 18 个 src Java 与 Tags 共 19 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-stations-server-final.json。原 23 份用户修改保留，未启动游戏或服务端。

显式消费者阶段新增 34 项行为测试：真实站点资格/维度轮换/租约、完整阶段产物与 selector 顺序、速度/并行、同一 source lanes 的拒绝/取消/UNKNOWN、实际 queue grant/completion 与新宿主完整进程恢复。完整 Gradle 构建通过 837 项测试、118 suites，零失败/错误/跳过；14 个 src Java 与 Tags 共 15 文件 IDEA MCP `errorsOnly:false` 零错误/警告/弱警告，MCP `build_project` 成功。证据 `build/reports/idea/research-stations-core-final.json`。未启动游戏。这是显式消费者阶段的历史验证；自动服务器/存档阶段的最新验证见实施进度。
