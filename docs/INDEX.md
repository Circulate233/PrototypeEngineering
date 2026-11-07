# 开发索引

恢复上下文先读本文件和 `git status --short`，再按入口定位源码/章节。需求见开发文档，完整状态和历史证据见实施进度；不整批加载文档。

## 当前检查点

- PN 普通配方研究/计算已接通：有序扩展注册、机器默认与逐配方条件合并、真实选中数据中心、阶段占用/提交/补偿。算力等 numeric 随并行量缩放；THREAD 按整机 UUID 只占 1，不随工位或 Java 线程数重复。START/扫描只检查，工作 TICK 占用，零时长 START 占用一次。见[配方消费者](network-recipes.md)。
- 服务器 CONCURRENT 网络机器已自动转入 owner 协调，原组件游标在工位处理处暂停，材料/FE 来源 lanes 仍并行，结算后恢复后续 normal/post；pending、卸载及旧 tick 保留准确清理义务。独立三参 scheduler 不自动安装此工厂，完整 pure prepare 与 PLANNED_PARALLEL 仍未接。
- 真实计算组件物品供给已接通：AEItemKey 精确注册、真实 committed 槽、long 数量、单物品上限和整机供给合并；提取预留仍供给，待插入输出不供给。捕获后的授权只读版本证明，可在材料/FE 锁及 source fence 内使用，见[计算资源](network-resources.md)。
- 研究站自动服务器执行与世界会话已接：普通工作之前由 scheduler 持有原 execution，真实站点/共享 lease、合摊材料、numeric 独立限额及 END grant/completion 共用原 lanes。每段 START→一次成功 TICK→END，缺料只重试未成功阶段；Version 2 世界存档保存原站和完整材料，见[研究站执行](research-stations.md)。
- 最近验证（2026-11-07）：玩家研究入队阶段新增 7 项行为测试；完整 Gradle 构建通过 886 项测试、124 suites，零失败/错误/跳过。3 个本阶段 src Java 与 Tags 共 4 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-admission-final.json。原 23 份修改保留，未启动游戏或服务端。 玩家/FTB 平台适配按静态代码及 MCP 核对，未做游戏实机验收。
- 物理数据中心的存储接口、机器组件、真实内容证明及 NBT 已有；普通下载、磁盘写入、循环研究升级的存储规则已迁入，自动云同步、下载授权/事件与物品消费恢复仍缺。正 tier 玩家 Java 入队已接；研究界面/packet、领取与存档事务绑定仍缺；永久破坏后的材料会话仍待接生命周期，见[研究功能](research-features.md)。
- 服务器手动研究已接通世界单一存档、FML 生命周期、FTB 成员核对、tick 边界和 Java 开始/接管；失效队列先结算回执再 prune，旧 pending 检查点先重试，关闭失败保留会话。见[服务器研究](server-research.md)。
- 配方事件已有真实 Java 调用：需求 START/TICK/END、选择提交回调、检查点/属性效果、动态匹配条件、进程钩子及 PN 配方扩展。机器事件不计入；CT 最后，见[事件复审](pm-recipe-events-audit.md)。
- 控制器、结构捕获/匹配、三方向十阶通用资源/独立 FE 仓、普通调度、扫描、默认/自定义 Java GUI 与方向工具已接入。GUI 直接 AEBaseGui，资源数量使用 AEKey2LongMap；实际边界按专题及实施进度核对。

## 下一步

1. 按[配方功能核对](recipe-feature-parity.md)继续研究界面/packet、领取入口与下载事件，处理永久破坏后的材料会话和嵌套 checkpoint 子阶段顺序。复用已有定义、资格、权限、队列、手动执行、完成与奖励，不把可调用步骤称为完整服务器链；Java 先完整，CT 最后。
2. 保留已接的研究站成员核对、原 pending 所有权、numeric 独立、整机 lease 共享及准确原代补偿；数据中心自动云同步/下载操作必须带当前授权和真实物品事务。
3. 在具体消费者中完成纯 prepare、冻结输入/结果及内部工作预算；复用已接好的机器游标、生命周期与 source lanes，不脱离业务反复扩底座。整体重开发仍在进行。
4. 完成严格两阶段 JSON/注册、结构预览/投影/切片/BOM/display override、复杂渲染、每池默认/自定义 JEI 及其他未取消功能，最后 CraftTweaker。Applied-Flux 继续暂缓，保留 v1.0.0:dev。

## 必须遵守

- 纯库：`prototype_eng` / `com.circulation.prototype_eng`。不添加正式机器、配方和玩法数值。先确认 PM/PN 实现及目的，再复用和修正；保留未明确取消的效果及多线程性能能力。
- API 独立包，接口按真实外部扩展职责划分，不制造 XxxImpl 镜像。Java 25，Kotlin 逻辑改写；集合优先 fastutil，AEKey 数量使用 AEKey2LongMap，累加/扣减优先 addTo；必要原子字段优先共享 VarHandle。无非必要反射或异常控制流，仅为静态审查不足的逻辑行为写测试。
- AEKey/AEKeyType 负责身份，核心不用 MEStorage。资源/FE 仓室保留十阶/三方向/资产/公开接口，混合资源仓两侧独立且每侧多类型；FE 独立。
- GUI 直接 AEBaseGui，无编辑器/第二套基类。本地化枚举实现 LocalizationEnum：客户端 getLocal、服务器 text，禁止 GUI 拼键/裸翻译调用。JEI 每池一个默认或自定义接口实例。
- IDEA 检查必须用本机 MCP：未挂载时连接已启用的 `http://127.0.0.1:64343/sse`，先 tools/list，再逐类 get_file_problems(errorsOnly:false)，消除错误/警告/弱警告。不得操作桌面或改 IDE 设置；构建不替代检查。
- 不启动 Minecraft 客户端/服务端，不加入 Bootstrap/FML 测试初始化。阶段本地提交，不推送，保留已有工作区改动。

## 按需阅读

| 内容 | 入口 |
|---|---|
| 已确认需求/功能范围 | [development-spec.md](development-spec.md)，R 编号 |
| 实际完成度/验证证据 | [implementation-status.md](implementation-status.md) |
| 旧实现及已知问题 | [migration-audit.md](migration-audit.md)、[machine-controller-migration-audit.md](machine-controller-migration-audit.md) |
| 检查点完整子阶段的旧回滚语义与未修可见性 | [checkpoint-execution-audit.md](checkpoint-execution-audit.md)，CheckpointRequirementSystem、ResourceBatch |
| 配方阶段/预设事件的真实迁移状态与功能优先顺序 | [recipe-feature-parity.md](recipe-feature-parity.md)、[pm-recipe-events-audit.md](pm-recipe-events-audit.md) |
| 研究定义/资格/入队/队列/完成传播/奖励与服务器缺口 | [research-features.md](research-features.md)、[pn-research-execution-audit.md](pn-research-execution-audit.md)，api/research、research |
| 研究站显式消费者/真实来源/材料周期/END共同提交 | [research-stations.md](research-stations.md)，api/research/station、RecipeProcess.materialCycle |
| 服务器研究 owner/单一存档/FTB/玩家事件/手动入口 | [server-research.md](server-research.md)，ResearchWorld、ServerResearch |
| 手动研究逐项投料/玩家回执/九状态恢复/暂停接管 | [manual-research.md](manual-research.md)，api/research/manual、research/manual |
| 配方/组件/事务/扫描 | [recipes.md](recipes.md)，api/recipe、api/machine/workslot |
| 机器 GUI/自定义入口 | [machine-gui.md](machine-gui.md)，api/gui、api/client/gui、container/MachineContainer |
| 方向工具/原交互数学 | [orientation-tool.md](orientation-tool.md)，item、OrientationToolConfig |
| 机器声明/整批绑定 | [machine-types.md](machine-types.md)，MachineType/Registry |
| 装配/NBT/稳定边界 | [machine-persistence.md](machine-persistence.md)，MachineAssembly |
| PN 持久身份/准确登记/后续授权 | [machine-identity.md](machine-identity.md)、[pn-identity-migration-audit.md](pn-identity-migration-audit.md)，api/machine/identity |
| 队伍/FTB/中心规划/计算贡献 | [network-planning.md](network-planning.md)，api/team、api/network、integration/ftb |
| 计算资源注册/单物品限额/旧预算与lease审计 | [network-resources.md](network-resources.md)，api/network/resource |
| 实际tick预算/lease/当前代工作凭据/原代补偿 | [network-budgets.md](network-budgets.md)，NetworkTeamBudget/NetworkRuntime.WorkPermit |
| PN 配方扩展/并行占用/真实数据中心/服务器自动协调 | [network-recipes.md](network-recipes.md)，api/network/recipe、NetworkRecipeWorkSystem、NetworkRecipeExecution |
| 网络角色/服务器发布/共同配方门限 | [network-runtime.md](network-runtime.md)，MachineNetworkDevice/NetworkRuntime/ServerNetworkRuntime |
| 生命周期/卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md)，api/machine/lifecycle |
| 可暂停机器组件/持续执行gate/调度接线风险 | [machine-tick-boundaries.md](machine-tick-boundaries.md)，ComponentMap.TickDispatch、MachineLifecycle.ScheduledExecution |
| 控制器运行时/退休 | [machine-controller.md](machine-controller.md)，MachineController |
| 真实 TE/朝向/结构接线 | [controller-integration.md](controller-integration.md)，MachineControllerBlockEntity/ControllerStructureUpdates |
| 普通调度/服务器边界 | [scheduling.md](scheduling.md)，api/scheduler、scheduler |
| scheduler持有的planned cycle/取消/实际退出/可重试关闭 | [scheduler-planned-ownership.md](scheduler-planned-ownership.md)，PlannedExecution、TaskScheduler.tryClose |
| planned 屏障/取消/实际退出与后续接线 | [planned-execution.md](planned-execution.md)、[planned-migration-audit.md](planned-migration-audit.md)，api/scheduler/planned、scheduler/planned |
| 真实跨宿主阶段协调/source屏障/pending/实际退出 | [planned-recipe-coordinator.md](planned-recipe-coordinator.md)，PlannedRecipeCoordinator、MachineWorkSlots.TickGroup |
| 工位批次/宿主lease/多进程round/结果发布 | [work-slot-batches.md](work-slot-batches.md)，MachineWorkSlots.TickBatch |
| 真实阶段/一次tick会话/延迟post/准确恢复 | [recipe-tick-sessions.md](recipe-tick-sessions.md)，RecipeProcess.TickSession/Stage、RecipeStageExecutor.Acquisition |
| 跨lane资源独占/批次结算/准确token恢复 | [deferred-resource-batches.md](deferred-resource-batches.md)，StorageFence、ResourceBatch、ResourceReservation |
| 业务query/需求作用域/复合事务/revision额度 | [transaction-recovery.md](transaction-recovery.md)，ReversibleTransaction、ScopedRequirement、TransactionGroup、StorageContext |
| 结构匹配/快照/发布 | [structures.md](structures.md)、[structure-migration-audit.md](structure-migration-audit.md) |
| 存储/预留/FE | [storage-redesign.md](storage-redesign.md)，api/storage、api/energy |
| 仓室/美术来源 | [hatches.md](hatches.md)、[assets.md](assets.md)、[controller-assets.md](controller-assets.md) |

## 本地源码

新项目 `F:/new/PrototypeEngineering`；PM `F:/clone/PrototypeMachinery`；PN `F:/new/PrototypeNetwork`；AE2S `F:/clone/Applied-Energistics-2`；MMCE `F:/clone/ModularMachinery-Community-Edition`。PM 存储在 `src/main/kotlin/{api,impl}/storage`，planned 在 `impl/scheduler/planned`。

索引不替代源码审查；阶段提交时同步实施进度，只记录实际证据。
