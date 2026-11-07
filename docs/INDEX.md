# 开发索引

恢复上下文先读本文件和 `git status --short`，再按入口定位源码/章节。需求见开发文档，完整状态和历史证据见实施进度；不整批加载文档。

## 当前检查点

- 配方事件已按用户要求由独立子代理重查：PM并非只有机器tick；选择提交回调、动态匹配脚本、检查点/属性效果、进程组件都有实际入口，PE对应Java调用已有，CT桥仍最后。此前宽泛的“没有配方runtime回调/DSL”已纠正，见[配方事件复审](pm-recipe-events-audit.md)。功能优先，不继续脱离业务细化调度。
- 新增研究定义/冻结注册、双来源有效等级与真实startCondition、完成传播、奖励领取/片段恢复及NBT；33项研究行为测试。尚未接研究队列/手动与研究站推进/逐配方研究计算消费者/完整存档和领取入口，不能称研究已完整迁移，见[研究功能](research-features.md)。
- 生命周期按侧代理整理：主类仅委托，CommonProxy承载双端通用初始化及server生命周期，ClientProxy.preInit先super再注册唯一客户端模型处理；辅助模型类不重复订阅。
- 本阶段接入TaskScheduler的planned执行所有权：PlannedExecution四方法/包内factory，在普通join后先保留准确cycle再advance；旧pending先于任何新业务，false保留提交队列，取消与实际退出分别确认。tryClose先使用原lane清理，正常pending保留现场；closingRequested阻止tick恢复后提前排卸载。runPrepare补beforeDispatch句柄。实际机器factory、复合组件/生命周期接线、pureprepare与PN预算仍待完成，正式mode仍拒绝，见scheduler-planned-ownership.md。
- 已具备机器暂停边界：ComponentMap.captureDispatch固定同一dispatch，normal逐项推进并保持机器fail-fast；MachineLifecycle.beginScheduled持RUNNING至全部已观察工作槽确认quiescent，retire/fault后只清理。基础API已测试，尚未接MachineInstance拦截、跨机器scope与TaskScheduler；下一步先处理pending优先清理、源lane关闭顺序已由调度器持有；真实组件接线仍待完成，详见machine-tick-boundaries.md、scheduler-planned-ownership.md。
- 上一阶段已接入真实多宿主阶段协调器：TickGroup先整体捕获/持lease、共享callback作用域并统一释放；PlannedRecipeCoordinator按真实current-stage获取/拒绝补偿/成功批次source apply→owner提交→原lane确认→release/ack推进，保留pending与实际退出。runner派发前交付Batch、直接异常完整stop/freeze。服务器调度、纯prepare扩展及PN预算仍未接入，PLANNED_PARALLEL继续拒绝。阶段本地提交、不推送，原23份修改保留未暂存。
- 最近验证（2026-11-07）：`gradlew.bat spotlessApply build --console=plain` 通过626项测试（95 suites，无失败/错误/跳过）；IDEA MCP对本阶段涉及的59个src Java与Tags共60文件逐个`errorsOnly:false`，零错误/警告/弱警告且无超时；MCP build_project成功。证据`build/reports/idea/research-and-proxies-final.json`。此前未改动Java沿用上一阶段337文件检查，未运行游戏或服务端。
- 预算：begin/end 每 server END 事件一次，capture epoch 不充额度；同 tick 首次成功队伍供给固定，revoke/失败/队伍消失返回不清余额。lease 按实际请求，每机器每 logical 维度一次，中心免标准 THREAD。研究初始numeric与普通余额独立。receipt过期不能commit，rollback仅退原book；去重键含machine/source/sequence，不保留receipt反向引用。owner许可重验全部Capture，worker只读current；重验成本须在正式planned边界整合。
- planned：借用 worker/serial source lanes；接受、实际开始及发布结果均检查共同 deadline。NEW/QUEUED 停止后不执行，RUNNING 为不确定结果；保留已发布结果，迟到返回不覆盖。Batch 退出证明独立于结果屏障，中断等待也必须等实际退出。旧 PM planned 工位 process 始终为空，PN commit 仅计算 receipt；当前协调器已复用真实 RecipeProcess 当前阶段；纯prepare/服务器接线待续，不能按整张配方重复投料。审计与边界见 planned-execution.md、planned-migration-audit.md。
- 资源批次：全部context一次try-claim，source单域/owner全域scope自动短锁，跨屏障保留逻辑独占；ordinary多域先退锁再等。source实际退出后owner业务commit前后重验授权，成功先决定COMPLETE再完成；rollback先业务后原lane tokens。业务与token均按准确实例query，UNKNOWN不重试，成功项不重放。全部确认后owner释放fence并发布捕获通知；owner invalidate需scope。普通修改不侵占token apply/终态及独立失效额度，见deferred-resource-batches.md、transaction-recovery.md。
- 需求恢复：ScopedRequirement查询/清理均保留原路径与冻结Tick；成功返回或准确terminal才减少acquired计数，禁止commit重放及terminal/query重入。确认后仍保留原persistenceFailure，不自动恢复健康。checkpoint/selector逐子与自身效果确认，自有cleanup无query仍UNKNOWN且不覆盖其他writer。真实阶段session已接，见recipe-tick-sessions.md。
- 计算声明：NetworkResourceRegistrationEvent 早于配方/机器runtime解析，冻结表含标准 thread/compute/qubit；同名完整metadata须一致，相等record无需对象身份相同。捕获检查设备/flat/ppm/参与者全部键，失败撤旧许可。maxPerTickValue限制组件单物品供给和研究segment grant，不限制设备/全队。旧预算与lease审计见network-resources.md。
- 网络运行时：MachineNetworkDevice 显式声明且 recreated，ownership 先load；capture owner-only，精确identity/loadOrder/formed及宿主证明校验后整批发布。每次MAIN action前与CON捕获前refresh，tick退出revoke；失败无旧许可回退。registry revision拒副本来去后的旧批次；旧卸载不撤新代。只暂停配方，普通组件仍运行，恢复不重START。
- 网络规划：OwnerTeamResolver 是扩展接口，FTB 使用真实 server/Universe 持久玩家，离线允许。capture 每 owner 一次查询、拒重复/过期/成员矛盾与同 ID 不同资源元数据；worker plan 只读。中心按等级/加载顺序，数据中心再比已有研究；满库可选。中心+合格电脑+同队参与者合计后一次 exact flat/ppm，不把 maxPerTickValue 当全队上限。
- 身份：FRESH 只接受真实放置玩家，RESTORED 保留 machine/owner；不自动安装到普通机器。位置按 World 引用 + pos，同位置新代撤旧登记，旧 close 不删新代；同 UUID 多个活副本全不唯一，剩一个恢复。登记绑定准确 MachineInstance，不等于 team/epoch/recipe-work 许可。
- 自动配方：显式声明 MachineWorkSlots 后再声明 RecipeScanning，工位走既有 codec、scanner recreated；系统 runBefore 保证同 tick 先扫描后处理。注册表整批发布共享 catalog。空 groups 在 catalog/index/scanner/admission/restore 一致表示全局，包含无组配方；显式组仍过滤。候选/probe 有限额，内部工作预算仍待接。
- GUI：具体类直接继承 AEBaseGui/AEBaseContainer，公开两侧 Java factory。join 后重查窗口/TE 身份，仅同步最多七行不可变快照，保留进度和真实背包；PM 临时 4×4 库存、固定 Idle 和空扩展页是占位。方向工具保留两模式、PM 24 向数学、武器/采掘/耐久/附魔、配置/模型/成就。
- 存储：独立 ResourceStorage/FE 契约和事务不变；AEKey 数量用 AEKey2LongMap 和 addTo，保留必要的溢出预检/零清理/顺序/别名去重，快照通过专用不可变包装器保护。
- 控制器：类型/结构/assembly 整批发布；TE 构造/read 仅留副本，下一 update 过 Forge 放置取消窗口后装配；结构 PM 20/5 节奏 capture→worker→join 后发布。完整朝向与配置变更走 owner FIFO，旧代请求失效。显示不含 runtime/ForgeCaps，不授 PN 权限。
- 运行时与持久化：附 host 后 factory，完整恢复/validate 后 load；retire 先停止新工作，accepted worker 全部实际 join 后清理，卸载保进度、破坏取消。普通调度的 MAIN/CONCURRENT/亲和 lanes 已接；故障不复活。正常 Cleanroom dormant 从 NBT 重建 TE。
- 已有配方能力：组件多系统/Pre-Normal-Post、需求事务、概率/检查点/模糊动态输入/选择器/overlay、完整进程/工位 NBT、取消/失败清理。结构已有 AE 24 向、模板/贪心切片、事实快照与失稳重试；这些不代表剩余整合全部完成。

## 下一步

1. 先按recipe-feature-parity.md补完整实际功能：PN逐配方研究/计算条件消费者，研究队列/手动与研究站阶段推进、材料提交、存档所有者及完成/领取接线，随后下载事件。复用本轮已经完成的研究定义/资格/完成/奖励步骤，不能把可调用步骤视为服务器全链已通；Java先完整，CraftTweaker最后。
2. 将研究与普通配方消费者接到真实计算供给/阶段限额、队伍当前状态及预算，保留numeric独立、lease共享与准确原代补偿。角色与账本已经存在，不能只检查共同gate或worker current()就跳过具体配方条件和提交校验。
3. 结合具体功能连接真实MachineController/Registration/MachineInstance与机器cursor、完整生命周期和planned cycle。已有调度持有pending/取消/退出/tryClose，优先复用；纯prepare/frozen input/result、内部工作预算与PN向量receipt仍待完成，正式mode仍拒绝，不先扩展无业务消费者的底座。
4. 完成严格两阶段JSON/注册、结构预览/投影/切片/BOM/display override、复杂渲染、每池默认/自定义JEI及其他未取消功能，最后CraftTweaker。Applied-Flux继续暂缓，保留v1.0.0:dev。

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
| 配方阶段/预设事件的真实迁移状态与功能优先顺序 | [recipe-feature-parity.md](recipe-feature-parity.md)、[pm-recipe-events-audit.md](pm-recipe-events-audit.md) |
| 研究定义/资格/完成传播/奖励执行与服务器缺口 | [research-features.md](research-features.md)，api/research、research |
| 配方/组件/事务/扫描 | [recipes.md](recipes.md)，api/recipe、api/machine/workslot |
| 机器 GUI/自定义入口 | [machine-gui.md](machine-gui.md)，api/gui、api/client/gui、container/MachineContainer |
| 方向工具/原交互数学 | [orientation-tool.md](orientation-tool.md)，item、OrientationToolConfig |
| 机器声明/整批绑定 | [machine-types.md](machine-types.md)，MachineType/Registry |
| 装配/NBT/稳定边界 | [machine-persistence.md](machine-persistence.md)，MachineAssembly |
| PN 持久身份/准确登记/后续授权 | [machine-identity.md](machine-identity.md)、[pn-identity-migration-audit.md](pn-identity-migration-audit.md)，api/machine/identity |
| 队伍/FTB/中心规划/计算贡献 | [network-planning.md](network-planning.md)，api/team、api/network、integration/ftb |
| 计算资源注册/单物品限额/旧预算与lease审计 | [network-resources.md](network-resources.md)，api/network/resource |
| 实际tick预算/lease/当前代工作凭据/原代补偿 | [network-budgets.md](network-budgets.md)，NetworkTeamBudget/NetworkRuntime.WorkPermit |
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
