# 实施进度

需求依据：[开发文档](development-spec.md)。仅记录实际完成的工作与验证证据，不将设计目标标为已实现。

项目定位为纯库：机器、配方、研究内容和玩法数值由使用方定义；内置仓室为通用组件，独立示例不进入主产物。

## 当前状态

- 手动研究执行阶段已完成：ManualResearch绑定准确队列项，ManualMaterials按requirementId/inputIndex保留独立进度、每个未完成项各投1个；公开ManualResearchInventory与真实Minecraft背包实现保存完整before/after及PREPARED/COMMITTED/SETTLEMENT_CONFIRMED。九状态会话/NBT、暂停接管、冻结成员/等级/时间的完成传播和准确队头删除已接到执行链。正常缺料不固定PREPARED库存；世界checkpoint异常先重存准确状态，同进程未消费证明允许补准备，重载缺证据仍不重扣。旧source的41槽范围与同步执行保留，移除PM planned桥接。服务器存档owner、tick/登录退出/成员协调及操作入口仍待接，见[手动研究](manual-research.md)。

- 本阶段验证：`gradlew.bat spotlessApply build --console=plain`通过691项测试、101 suites，零失败/错误/跳过；新增44项材料计划、会话/NBT、真实队列/完成状态机及回执恢复逻辑测试。IDEA MCP对本阶段14个src Java和Tags共15文件逐个errorsOnly:false，零错误/警告/弱警告、无超时；build_project成功。证据`build/reports/idea/manual-research-final.json`。未初始化Bootstrap/FML或运行游戏；真实Minecraft背包和磁盘IO尚无实机验收。原23份未暂存修改完整保持。

- 配方事件专项复核：复用独立子代理检查PM当前源码，未发现既有审计之外的独立配方事件框架。补充两个边界：进程Pre/Normal/Post内置回调为空，仅迁移扩展钩子；旧Failure清空工位且继续POST，没有独立取消/失败事件，PE cancel为新增清理。对应Java实际调用已核对，CT仍最后。此次仅更新文档，不重跑构建，不将历史647项测试当作本轮新增验证；原23份源码修改保留。

- 研究启动/队列阶段已实现：ResearchPermissionPolicy保留PN默认同队/管理角色规则；TeamResearchStart在当前证明下校验权限、设备等级、结构资格与真实actor/resources条件后入队，不扣物料或numeric，重复/满队/忙返回业务结果。回调后重验authority及queue epoch，异常原样传播。TeamResearchQueue保留容量5、稳定entryId、多站numeric独立授予、同entry/time去重、grant/prune可逆提交及严格NBT。prune不用匿名startable缓存；队列及mutation强制准确owner线程，回滚递增epoch防旧plan复活。材料/逻辑lease/真实站点/手动session及服务器存档服务尚未接入，不能据此称完整研究流程完成。

- 本阶段验证：`gradlew.bat spotlessApply build --console=plain`通过647项测试、97 suites，零失败/错误/跳过；新增14项queue和7项启动行为测试。本机IDEA MCP对本阶段涉及的30个src Java及Tags共31文件逐个errorsOnly:false，零错误/警告/弱警告、无超时；build_project成功。证据`build/reports/idea/research-queue-start-final.json`。原23份工作区修改逐文件与阶段基线完全相同。

- PN工作单元审计见[pn-research-execution-audit.md](pn-research-execution-audit.md)：旧计费仅绑定每tick候选operation，planned process为空，普通路径无逐配方研究/计算消费者。新实现需要明确计费阶段/并行语义，已向用户核对；不能复制旧反复投START材料的缺口。

- 功能优先。独立复审确认PM配方有需求阶段、选择提交回调、动态匹配条件/脚本、检查点/属性效果、进程组件钩子；这些Java调用已迁，CT尚缺。此前将“没有顶层events字段”泛化为“没有运行时配方回调/DSL”的说法已修正，具体源码证据见[PM配方事件复审](pm-recipe-events-audit.md)。

- 研究功能本阶段已实现：公开定义/阶段/公式/冻结注册；双来源资格与真实startCondition；TeamResearchCompletion按个人/直属队伍策略创建等级及UNCLAIMED资格、准确事务补偿；ResearchRewardClaimer按固定选择执行事件与物品片段并恢复；玩家/队伍NBT。外部callback/checkpoint异常保留准确状态后原样传播，显式RETRY_LATER才作为业务暂停。尚未接完整研究队列/手动与研究站推进/逐配方研究计算消费者/服务器存档/领取packet和UI/下载/物品交互冻结，见[研究功能](research-features.md)。

- CommonProxy/ClientProxy用途已修正：主类四个现有生命周期方法仅委托；CommonProxy负责配置、能力、GUI、定义注册及服务器启动/停止。ClientProxy.preInit先调用super，再注册模型事件；模型辅助类不再自行订阅。已对照Cleanroom源码确认ModelRegistryEvent在preInit分发后触发，客户端注册时序有效。

- 本阶段验证：`gradlew.bat spotlessApply build --console=plain`通过626项测试、95 suites，零失败/错误/跳过（新增研究行为测试33项）。本机IDEA MCP对所有本阶段涉及的59个src Java及Tags共60文件逐文件errorsOnly:false，零错误/警告/弱警告、无超时；MCP build_project成功。报告`build/reports/idea/research-and-proxies-final.json`。未启动Minecraft/服务端，实机交互仍未验收。

- 调度器planned所有权已实现：公开PlannedExecution按advance/awaitExit/finished/cancel职责划分，内部factory不暴露任意host提交。TaskScheduler在普通join后先持准确cycle再执行，旧pending在任何新业务前恢复，正常false不故障、不丢ordinary队列；初始取消后等实际退出并有界清理，跨tick只清理。取消未确认或await无退出证明均保留，wholecycle完成加退出证明才开放boundary。tryClose在原lane上先清理，closingRequested保留关闭请求，tick清完也须再确认线程池终止才排卸载。prepare新增beforeDispatch，复用原runner；真实机器factory、组件cycle/登记代、PN消费者和纯prepare扩展仍待接，mode仍拒绝。见[调度所有权](scheduler-planned-ownership.md)。

- 可暂停机器边界已实现：ComponentMap.captureDispatch在无回调时冻结同一图，begin全pre、advance逐normal、finish全post且首错停止，失败/重入/跨线程不重放；原普通tick与process post语义不变。MachineLifecycle.beginScheduled的准确句柄跨暂停持RUNNING，前进/清理分开，全部已观察工作槽quiescent才释放；替换组件不隐藏原义务，正常pending返回false，故障不复活。新增MachineWorkSlots.quiescent事实查询。尚未接真实MachineInstance处理器拦截、跨机器scope、TaskScheduler或服务器，PLANNED_PARALLEL仍拒绝；先清旧pending以防fence/join死锁、清理后关闭lane与PN授权缺口已记录，详见[机器tick边界](machine-tick-boundaries.md)。

- 真实跨宿主阶段协调已接入：TickGroup先验证/捕获全部host，begin后共享callbackProcess防跨host重入，子batch完成仍持lease至全组关闭。PlannedRecipeCoordinator保留真实current Stage与partial，正常拒绝先单阶段原lane补偿再获取后槽；成功预留合批source apply、owner授权前后重验/业务提交、原lane终态确认后release/ack。不可逆COMPLETE不被通知故障回滚；UNKNOWN/仍running保持pending，awaitExit独立证明实际退出；forward期限不续，cleanup续期只恢复确认。runner新增beforeDispatch准确句柄交付，直接派发/时钟故障完整stop/freeze且wrapper不漏退出计数。阶段内部可显式调用；真实TaskScheduler/服务器、纯prepare扩展、预算消费与退休接线仍待完成，见[真实配方协调](planned-recipe-coordinator.md)。

- 工位批次入口已接入：MachineWorkSlots.TickBatch捕获真实slot/process并在pre前持宿主lease，begin顺序运行实际回调、nextRound每进程一个当前Stage、currentStages保留partial。tryFinish等当前轮完全结算，post逐个尝试且不重放，健康终态detach；未知局部事务保留pending，故障进程留槽。宿主与process边界共同拒绝批次期间save/admission/普通tick/转移/取消/提前post；默认RecipeExecutor显式批次，自定义同步executor执行前拒绝批次替换。PN receipt提交后错误要求RESERVED的旧probe缺陷已记入审计。正式coordinator/预算与退休join仍待接，详见[工位批次](work-slot-batches.md)。

- 真实阶段会话已接入：RecipeStageExecutor.Acquisition保留成功/拒绝/异常库存、过滤SUCCESS重复业务、末尾生命周期transition，同步观察准确source/business终态；旧execute/probe共用该入口。RecipeProcess.TickSession/Stage在pre前登记、获取前持lease，校验准确session/host/thread/state/version，零事务同样防重入；prepared取消可discard，0速度每轮仅一次TICK。整round绑定准确ResourceBatch与原token，finished后才ack；普通异常立即post但pending保留，planned确认前不post，恢复不清故障标记。工位批次已接，coordinator/预算消费仍待接，详见[阶段会话](recipe-tick-sessions.md)。

- 文档恢复入口：先读 [INDEX.md](INDEX.md)，再按任务定位章节；根 AGENTS.md 和 README 已链接该入口。索引维护当前检查点与下一步，本文件维护详细状态和证据。
- 业务确认/revision额度已补齐：公开ReversibleTransaction由RequirementTransaction直接扩展；ResourceBatch逐实例commit/terminal/query，成功项不重放、准确REVERSIBLE才重试，UNKNOWN保留pending。ScopedRequirement保存原RequirementPath和冻结Tick，准确终态才关闭一次，拒绝commit重放与terminal/query重入，原故障标记不被确认恢复擦除。checkpoint/selector通过TransactionGroup确认全部子与自有效果，无自身query的失败仍UNKNOWN。StorageContext为apply/终态和独立invalidate保留额度，普通mutation不侵占，pending归还未用apply；token仅增加byte余量。详见[事务恢复](transaction-recovery.md)，阶段handle/session已接，正式coordinator仍待接。
- 跨lane资源批次基础已完成：StorageFence全域try-claim、source单域/owner全域短锁scope和ordinary访问隔离；ResourceBatch沿准确context稳定source分组，全部apply实际退出后owner提交，授权前后重验，整批决定后才complete，失败先业务再逆序source补偿。ResourceReservation.resolution为可选准确证明，内置资源/FE提供真实状态；UNKNOWN不盲重试，成功token不重放，终态全部确认才release。通知在全域锁下冻结并由owner发布，修正reader抢先drain和非法scope污染pending的问题。业务query与revision清理额度已补齐；真实阶段已接会话，正式coordinator仍未接入，invalidate作用域要求见[资源批次](deferred-resource-batches.md)。
- planned 执行屏障基础已完成：PlannedCancellationToken 保留首 reason、准确独立注册与锁外全部通知；PlannedDeadline 支持 nanoTime 负值/绕回。内部 PlannedTaskExecutor 借用 worker/source lanes，保留 PM 稳定 source 映射和有序结果；接受、实际入口、结果发布都重查共享 deadline，停止后 queued 永久惰性、running 结果不确定且迟到返回不能覆盖。Batch 提供独立实际退出证明，中断等待不提前返还所有权。正式 coordinator/source 结算/真实配方尚未接线，PLANNED_PARALLEL 仍拒绝；旧 planned 配方适配缺少真实进程，后续须复用既有阶段并解决跨 operation 延迟 complete，详见[执行屏障](planned-execution.md)与[PM/PN 审计](planned-migration-audit.md)。
- 每 tick 计算账本已接入：NetworkRuntime.begin/end 包住真实 server END 调度，capture epoch 不触发补充；队伍首份成功容量、余额、lease和操作键保留至tick结束。同机器每logical维度一份lease，中心免标准THREAD，保持实际请求顺序。numeric研究供给与普通余额独立；完整向量预检后addTo，过期receipt拒提交但可原代幂等补偿。WorkPermit绑定准确Publication/identity revision/Registration，owner操作重验本批全部Capture；组件入口校验绑定，worker仅读取发布状态。账本仅保存去重键，避免持有旧receipt的发布引用。普通配方/研究/planned自动消费仍待接，不等于共同gate已消费额度；重验性能边界见[预算说明](network-budgets.md)。
- 计算资源注册已接入：NetworkResourceRegistry 保留标准 thread/compute/qubit，NetworkResourceRegistrationEvent 在 post-init 的配方/机器 runtime 解析前注册并冻结。完整 metadata 对照权威表，相等 record 不要求对象身份一致；NetworkRuntime/NetworkPlanner 明确接受冻结表，设备、flat/ppm 及参与者的资源声明均检查，元数据漂移或未注册键使捕获失败并撤销旧许可。单物品供给和研究阶段限额仍随对应执行链迁移，不误套设备/全队总量；旧 lease、双份 numeric 预算、同 tick 重捕获及过期补偿审计见[计算资源](network-resources.md)。
- 网络运行时已接入：MachineNetworkDevice 显式角色组件在未发布装配安装永久 gate，recreated 恢复，ownership 按依赖先load。NetworkRuntime owner捕获、同批规划和证明/registry revision重验后volatile发布；失败/过期/显式revoke不回退旧批次。复制来去的unique ABA、同位置旧卸载、加载失败均不能恢复或撤销错误代。TaskScheduler prepare位于每次MAIN action前与CON捕获前，服务器finally撤销；ordinary并行/join不变。canProcessRecipes保留formation，低层scanner/工位/进程叠加recipeWorkAllowed，失权不退休普通组件且恢复不重START。每tick预算核心与owner凭据已接，研究/普通配方消费和正式planned仍须接，详见[网络运行时](network-runtime.md)。
- 队伍/网络规划核心已接入：OwnerTeamResolver 与直接 FtbOwnerTeamResolver，具体不可变结果保留离线 owner、有效 PLAYER/SERVER 和成员语义。NetworkPlanner.capture 每 owner 一次查询，检查重复设备/参与者、参与者 epoch、权威注册 metadata 与成员互相一致；plan 可在 worker 只读执行。保留中心/数据中心优先级和满库选择、等级状态、通用 DEVICE，中心+合格电脑+同队参与者合计后一次 exact flat/ppm，数量使用 fastutil addTo；所有公开 map/entries 冻结。规划核心已由真实角色组件、服务器整批发布和共同网络门限接入；组件供给/研究阶段限额、预算消费/研究及完整参与者仍待接。详见[网络规划](network-planning.md)。
- PN 持久身份已接入：MachineOwnership 为显式组件，复用 MachineAssembly 内置 codec 和生命周期；FRESH 由真实放置者创建 machine/owner UUID，RESTORED 保留旧身份，FakePlayer 不授 owner。MachineIdentityRegistry 绑定服务器会话与准确 MachineInstance，World 引用 + pos 定位，不含类型；同位置替换撤旧句柄，旧卸载不删新代，重复 UUID 全部失去唯一性且剩一个可恢复。所有变更/枚举限定 owner 线程，关闭会话永久撤销；身份不保存 team/epoch/lease/ready，也不授权工作。当前队伍、中心与共同网络门限已接；预算、研究与正式提交许可仍待接，详见[身份说明](machine-identity.md)及[PN 审计](pn-identity-migration-audit.md)。
- 已完成：工程标识、Java 25 模板、需求基线；固定 AE2S `v1.1.0:dev` 与 HEI `4.35.1`；移除模板示例 Mixin；开发包和生产重混淆构建。
- 独立资源后端已完成：ResourceHatch 返回 ResourceStorage，槽位扩展由 SlottedResourceStorage 表达；SlottedResourceBuffer 独立持有 AEKey/long 余额、可用量、输入数量预留及输出容量预留。MEStorage、GenericInternalInventory 与 GUI 均为共享同一 context 的适配层，不再依赖 GenericStackInv 持有核心状态。外部仓室仍通过公开接口扩展，不依赖内置 TileEntity。FE 独立 long 存储已用同一无 AEKey 字段的预留生命周期参与并发批结算。
- P1 已接入：资源/能源各输入、输出、混合三方向十阶，共 60 个方块及物品注册；方块实体保存和能力暴露、挖掘保留内容、Java 容器和直接继承 AEBaseGui 的资源/能源界面。十阶默认值来自 PM ItemHatchConfig/ItemIOHatchConfig、FluidHatchConfig 与 EnergyHatchConfig，均可配置。资源输入/输出独立滚动，最多 5 行并随分辨率压缩，隐藏槽禁用且索引保持；能源 GUI 为 176×214，使用原 16×66 纵向条，支持速率/自动标志、Enter 提交和完整 long tooltip。实际 GUI/游戏交互未运行验证。
- 能源职责已接入：EnergyOutput 及公开发现能力、EnergyHatch 双职责、ForgeEnergyPort 三方向，每次操作限额同时约束机器与 Forge，并随设置动态更新；模式/等级/限额/自动标志写入 NBT。能源重载以当前配置容量为准并裁剪保存量，资源仓仍保留保存容量。PM 限额并非累计 tick 预算，自动标志也尚未发现邻居 tick 传输调用链；本项目保留设置/保存，不宣称邻居自动传输已实现。
- 美术已复制：PM item_hatch 与 energy_hatch 十阶三方向原模型 60 份、方块 PNG 144 张、CTM/Bloom 元数据 30 份（其中 6 份还带动画），另有 gui_energy_hatch 的 22 张 PNG，保留 credit 与 bloom 元素。原仓室走标准 Forge 模型与这些元数据，外部 CTM/BLOOM 层环境的实际效果未验证；机器自定义 Gecko/Bloom 管线仍待迁移。
- 本地化已接入：GuiText 实现 AE2S LocalizationEnum，客户端使用 getLocal，容器标题使用 text 组件；禁止 GUI 拼键和裸翻译键调用。最终本机 IDEA MCP 逐文件检查零错误、警告、弱警告，MCP 构建及 Gradle 完整构建均通过，证据见下。
- P2 已迁移组件/需求基础：软依赖拓扑、组件多系统、Pre/Normal/Post 调度与 tick 事件适配；配方按需求职责分组、外部类型绑定及 start/tick/end 整阶段协调结算。RequirementTransaction 上交全部资源/FE 预留，业务 commit 仍可补偿，全部成功才 complete；获取/应用/业务异常完整逆序清理，完成/通知故障不再回滚终态令牌。
- 固定需求已接入：MachineHatches 保存独占执行边界安装的公开仓室绑定；RecipeRequirements 的 ResourceRequirement/EnergyRequirement 经公开配方池执行 start/tick/end，支持多 AEKey 类型、各阶段固定 IO、来源别名去重、单次 FE 限额及显式 voidExcessOutputs。数量定义为单次执行量，执行时使用进程有效并行数并按 PM 的 long 饱和规则缩放。资源需求已接概率/随机产物及模糊动态输入，选择器与需求 overlay 已接入，FE 保留原独立数量语义。原因由 RecipeText 本地化枚举提供。
- 属性与单进程循环已接入：MachineAttributes 将根层/进程 overlay 合为一个实际类，以属性 ID 寻址；AttributeModifier 保留外部运算接口，NumericAttributeModifier 提供 PM 三种运算，支持本地遮盖、实时继承和局部状态快照。RecipeProcess.tick 保留 START 一次成功、完成前后检查、成功 TICK 才推进和 END 阻塞只重试 END；double 绝对进度及 long 成功刻数随资源同阶段可补偿提交。Tick 上下文在获取前冻结，组件 pre/tick/body/post 使用同一 dispatch。此循环已接入多工位宿主、扫描 admission 与取消/失败清理，真实 planned 调度仍待迁移。
- 属性需求已接入：START 添加自己的 modifier，使后续获取看到效果，失败只撤销本需求成功添加的 modifier；END 在全部业务 commit 成功后的 complete 局部清理，避免输出提前失去倍率，也避免整层快照抹去其他需求的业务修改。有限 base/amount/fallback 与每次运算结果严格校验；父属性在多个进程并行执行期间必须保持稳定，读取不写缓存。PM 的 ENERGY_EFFICIENCY 目前只声明，没有可迁移的内置能耗公式，未自造玩法计算。
- 概率与加权输出已接入：ResourceChance 的百分比与可选属性乘数决定资源执行次数，保留 PM 的整倍数加二项抽样、正 long 饱和量及固定数量保守检查。进程固定 ProbabilityTuning，成功刻索引用于 TICK 盐；默认仍为 PM 的 Poisson/固定 Gaussian 表近似，可选择小 n 精确 Bernoulli。临时预检占用释放后才保留真实用量，允许多个零概率检查共享；输出按真实选中 AEKey 与固定输出一起预留，拒绝不会留下部分产物。加权整数抽签按 entry 无放回，全部候选必选时批量缩放；普通随机池仍按执行次数循环，其异步预算尚待 planned 集成。
- 检查点已接入：CheckpointRequirement 可包装任意需求，在 scaled/unscaled 冻结时间线上跨阈值触发完整子 START/TICK/END；time=0 在父 START 执行一次，子 TICK 使用零宽上下文。子事务汇总所有令牌上交外层，拒绝立即停止后续子阶段，业务和资源共同补偿，complete 不能先于其他父需求 commit；临时上下文涵盖子 TICK 获取和全部业务回调，结束或抛错后恢复。进程状态及子选择/锁已接入严格 NBT；工位整组恢复及宿主生命周期边界已接入；实际 TileEntity/结构匹配与调度集成仍待迁移。
- 模糊动态输入与逻辑解析锁已接入：公开 ResourceMatcher/NamedResourceMatcher 直接绑定稳定行为，FuzzyInputGroup 按声明顺序选择，DynamicInputGroup 将所有匹配 key 规范排序后截断；类型、资源 ID 与完整 NBT 参与排序，扩展可提供稳定全序。解析先扣固定及前组保守需求，成功获取后才安装 ResourceInputLocks，失败只撤本次新增选择；零概率也预检并保持身份。完整 RequirementPath 捕获所有返回事务回调，复用子定义不会共享包装器状态。逻辑锁已通过进程 codec 保存恢复；动态全扫描/排序/NBT 成本仍待 planned 预算。
- 选择器已接入：按顺序选第一个 START 成功候选，拒绝候选立即补偿业务和令牌；provisional 选择可供检查点后续子阶段读取，外层失败会撤销，包括 disabled 状态。公开 SelectiveModifier/Context/Descriptor 保留外部扩展，追踪自身进程属性修改，部分异常局部补偿；END 失败保留已生效属性，全部业务成功才清理。终态完成链聚合外部 complete 故障并继续其他回调、令牌及通知，不回滚或重放终态。
- 需求 overlay 已接入：RecipeOverlayApplier/Type 绑定不可变的外部 payload 转换，ResourceRequirementPatch 覆盖四种 IO 和余量策略，EnergyRequirementPatch 覆盖刻输入/输出和余量策略；null 继承、空列表清空、0 为有效值，其余字段保留。RecipeOverlays 按完整路径及 writer 层 ID 保存，写时计算缓存，替换保留原顺序；局部撤层后重算剩余层，清空要求没有未完成句柄。RecipeRequirement 的有效视图保留 canonical definition，获取前统一解析一次，嵌套同样生效，已获取资源事务不重算。显式 pipeline 按声明/重复顺序应用，失败撤自身修改；选择器受追踪 overlay 在 START 失败和 END complete 清理，END 阻塞保留。层及所有权已接入严格进程 codec，外部 applier 必须纯且支持剩余层重组。
- 旧固定资源流程已改为内部 ResourceTransferPlan/Run 迁移材料，不再定义公开 MachineRecipe 或从公开池选择配方。本轮已改用独立 ResourceStorage，保留 4 项守恒/检查点/别名测试；预留、补偿和并发另有专门行为用例，不能用此固定流程代替完整需求处理器。未接入机器且无调用方的固定资源 ResourceTransferNbt 已移除；正式进程/组件持久化采用 RecipeProcessNbt；整组宿主发布使用 MachineWorkSlotsNbt；已接宿主卸载/新实例恢复边界，真实世界恢复仍待集成。
- 严格进程持久化已接入并验证：RecipeProcessNbt 固定 canonical recipe/revision/graph，保存 seed/tuning、double progress、long successfulTicks、started 与完整结果、本地属性、有序 overlay 层、解析锁、选择器自有效果，以及组件实际装配/依赖和未安装 dependent 的 pending 约束。exact NBT 类型、未知/重复/坏引用全部拒绝；fresh process 先创建所有组件再按依赖恢复，最后跨组件及宿主校验成功才返回。selector 只绑定现有效果，不重跑 START/modifier。open transaction、pending Change 和抛错 tick 拒绝保存。外部 overlay 历史必须提供 validator；嵌套正阈值 checkpoint 仅推断可证明关系，不声称恢复未保存的历史触发区间。
- 多工位宿主及整组恢复已接入并验证：MachineWorkSlots 保留有序定义、隐式 main 与 group 白名单，通过受检查 admission 拒绝 foreign owner、重复/跨 host process 和不匹配组。MAX_CONCURRENT_PROCESSES 只限制新加入；降低上限不驱逐已运行或保存的进程。单一同步 RecipeExecutor 每槽每刻执行一次；Blocked 保留，正常终态释放并保留结构化结果，已保存终态不重跑 executor；抛错 host 禁止重跑与保存。detach 只移交独占进程，不执行 END、不退历史消耗，不冒充取消。MachineWorkSlotsNbt 先完整校验 layout/revision/结果与各进程再替换整组和 attachment，失败保留旧工位；ProcessResultNbt 共用严格结果格式。isCore/restartPolicy 是保留元数据，旧 RESCAN 倒计时本就未接线。
- 扫描 admission 已接入并验证：RecipeCatalog 冻结配方池声明顺序、canonical 身份和工位 group 范围，外部索引使用同一完整 scope 并区分 null/empty/交集。RecipeScanner 排除运行 recipe ID，选择首个空兼容槽，每次最多发布一个 NEW 进程；候选/probe 限额可续查，默认下降穷举，完整 probe 明确单调时才二分，long 防边界溢出且 best 发布前重验。factory、seed/tuning 与 owner/lifecycle 受校验，扫描故障后拒绝继续。
- 扫描事务与局部准备已接入：RecipeStageExecutor.probeStart 共享正式 acquire，成功/拒绝/异常都撤回全部业务和资源/FE token，不提交或推进生命周期。RecipeScanPreparation/RecipeScanProbes 按顺序准备、逆序撤回等价首刻局部状态，实际组件 callback 只执行一次；外部不可逆/世界副作用不能自动投影。setResolvedBase 屏蔽已计入的父 modifier、保留本地 modifier，属性快照及 Version2 NBT 严格记录 InheritModifiers。
- 固定 IO 预筛已接入并验证：FixedIoScanConstraint 对 flat 内置资源/能源、无动态包装/随机量/回调的候选读取有效 overlay，复用 probeStage 的整阶段预留和完整回滚。TICK/END 分别检查当前量与容量，不 commit、不推进；零时长跳 TICK，void 使用真实系统语义。跨阶段输入可能释放同 context 输出空间时跳过受影响阶段，资源跨 AEKeyType 共槽也识别，默认混合仓两侧独立时正常检查。默认 scanner 三参构造安装该约束，显式构造由调用方配置；各独立阶段通过不等于联合首 tick 保证。
- 内置资源索引已接入并验证：ResourceRecipeIndex 使用 AEKey 倒排，Footprint 明确声明完整 factory/preparation/overlay/调参范围内的必需 START 输入下界。forFixedInputs 从受支持的真实内置输入提取，仍需显式 stableInputs 承诺；未知/动态/未声明/纯能源配方保守保留。按 context/key 取 alias 最大量，跨 context 及重复声明键的数量聚合饱和为必要下界；匹配全部声明键，保持 canonical scope 和目录顺序。不 reserve、不重建资源类型系统。
- 扫描内部工作预算仍待迁移：目录、索引、factory/constraint、动态排序/NBT、随机逐次抽样及嵌套需求成本尚未接完整工作/deadline 预算。独立当前阶段预筛不能证明后续资源可行；动态完整生命周期的等价投影和真实 planned 仍未完成。
- 显式取消与失败清理已接入并验证：RecipeProcess.cancel 进入 CANCELLED，业务 Failure 进入 FAILED，保留 committed started/progress/ticks 与结构化 Failure 原因，不执行 END、不退历史 IO。按根需求执行逆序调用 cancel，再对预先捕获的组件按依赖逆序调用 ProcessAbortListener；回调故障继续其余参与者并聚合，poison 禁止执行与保存。host.cancel 协调结果与挂接，cancelAll 逆槽尝试全部工作；故障宿主能清其他稳定进程但不能复活，失败进程留槽诊断。普通区块卸载/保存不自动取消；现已接结构失效暂停与控制器破坏取消的宿主边界。
- 精确内置归属已补齐：RequirementAttributeState 跟踪完整路径/属性及实际安装的 Renamed modifier 对象；START rollback、END 与 abort 只删除准确自有对象，foreign 同 ID 碰撞不 claim，替换违约保留 foreign 并抛错。END removeAtEnd=true 清本路径历史安装目标，false 保留；abort 清所有 owned。修复私有包装导致默认 codec 无法保存活动内置属性的问题；localModifier 只读本层，避免归属检查复制整份属性图。Selector 只向选中未完成 child 传播取消；组件清理逆 selection、overlay 后 modifier 各族逆序，逻辑锁清空，其他写入方效果保留。
- 取消持久化与执行边界已验证：进程 NBT Version2 保存归属/清理回执，恢复绑定已有对象并检查已记录效果缺席，不重放 START/modifier/abort；FAILED/CANCELLED 不留活动内置归属，非 abort 状态不能带回执。回执不证明任意外部历史副作用，外部 validator 仍必需。任何抛错 tick 或清理故障后，同一进程拒绝再次 tick，即使资源已补偿或已经终态；正常 Blocked 仍可重试。每层 overlay 写时核验直接子项的 canonical identity/顺序不变，防止 selector 重排后取消错误 child。
- 机器类型、附宿主装配/恢复、控制器运行时及实际Block/Item/TileEntity/Forge调用已接入；完整24向、结构并发刷新、放置者和独立显示/默认模型已有实现，见[控制器集成](controller-integration.md)。自动扫描及全局空组语义、默认/自定义 Java GUI 和方向工具已接入；正式 planned、严格结构 JSON 与注册、完整预览/渲染、JEI、研究与计算、CraftTweaker 仍待迁移。
- 暂缓：Applied-Flux 兼容；后续仍使用带 v 前缀的 `v1.0.0:dev` 坐标，当前不拉取该依赖。
- 必须继续迁移：研究/队伍、并行调度、结构/投影/渲染及其他未被明确取消的 PM/PN 功能效果。实现策略仍需按源码审查；正式机器与玩法数值不属于待开发内容。

- 机器生命周期边界已接入并验证：MachineLifecycleHost/ControllerLifecycleHost 提供宿主身份与服务端线程约束，MachineLifecycle 一次挂接到实例。组件依赖顺序后接全局注册顺序，统一捕获参与者；保留 FRESH/RESTORED。未成型仅停扫描/工位，普通卸载保留进程可由新实例恢复，破坏先闭门再 broken/cancelAll/unloaded，不 END/退历史 IO。加载失败自动对同一捕获参与者发 LOAD_FAILED 清理，异常全部聚合且机器不再执行/保存。
- 生命周期并发与故障边界：共享 VarHandle gate 拒执行/生命周期重叠和重入，生命周期先要求服务端线程与无活动 acquisition；faulted但quiescent进程仍允许外部卸载清理。损坏组件图的异常清理保留最后有效组件快照并补充无排序装配/当前全局监听，原错误仍抛出。注册表 synchronized 去重和精确订阅取消修复旧 check-add 竞态。工位 codec 检查机器保存边界但不持有跨序列化锁；普通真实join与结构发布已接，控制器保存/自退出和PN权限仍待实际调用。详见 [机器生命周期](machine-lifecycle.md)。

- 结构定义与匹配已接入：复用AE2 BlockOrientation的24向，缓存整数StructureTransform；MachineStructure保留外部形状职责，模板/切片按PM顺序匹配，独立结果树保留每次occurrence计数与覆盖位置。固定controller排除、相对validator旋转、零片children锚点在规划/匹配/收集中统一，未加载实际试探不能当普通切片不匹配。
- 结构捕获层已接入：StructureQuery显式读/拷贝/等价契约，ReadPlan冻结并去重潜在area与query-position；WorldStructureSource只在主线程读取且不强加载。Snapshot区分未加载/缺失/捕获失败，未用潜在分支不自动否决，使用失败事实则抛错，Error直接中断；NBT捕获与交付均深拷贝。evaluate独立记录实际读取证明，包含失败slice probe/验证器；事实不等价则重捕获全计划并按当前结果匹配，时间改变而条件仍真也可正常发布，旧访问错误可由新短路分支跳过。
- 结构发布及公开仓室重建已接入：StructureRefresh服务端prepare、脱离宿主的worker Request.evaluate、服务端publish/fail；latest request和稳定配置代对象拒旧成功/失配/失败与ABA。MachineLifecycle.refreshStructure在同一TRANSITION gate内复核及一起安装匹配树/仓室，再通知真正formed变化；同朝向重新发现也替换绑定。真正当前失配才清绑定，失稳候选返回false保留等待重试；最后事实复核的必要读取失败仍抛错，不能隐成无限重试。当前失败故障机器，已失权异常只报告，不故障替代配置；入口拒重入，卸载实例不能重新发布。
- 实际世界适配：ControllerLifecycleHost固定World，活controller检查World/位置/invalid/已加载/同TE身份，清理仍可在TE移除后执行。HatchCapabilities专用事实和发现共用公开接口，resource/FE输入/FE输出独立收集，复核provider及能力身份和有效性，不要求内置TE继承；Request与协调器暴露捕获诊断。尚待实际控制器及PN owner变化调用、调度join/失稳重试、工作预算、严格JSON与预览/渲染，真实World/TE/Forge未运行验证。详见[结构说明](structures.md)及[源码审计](structure-migration-audit.md)。

- 普通真实调度已接入：TaskScheduler复用PM并查集/固定线程池/single lanes和有序key hash分配lane，组内按登记顺序且首错停止；无key任务真实并行。私有FutureTask不cancel，所有accepted动作在异常/提交拒绝/中断时仍先join，中断末尾恢复并抛错；tick故障永久拒新执行/提交，注销和close可清理，关闭失败保留资源而不重启。MAIN登记代和执行对象identity分别防旧候选复活/同tick二跑，MAIN后采集当前CON元数据，不阻止正常放置/破坏其他控制器。
- 机器和服务器普通接线已接入：MachineInstance实现ScheduledTask，MachineScheduling选择模式并保持默认CONCURRENT，公开资源/能源storageIdentity形成真实共享affinity；未成型仍运行普通组件，卸载/破坏/故障不调度。FMLServerAboutToStart创建确切server会话，ServerTick.END推进并join，FMLServerStopped关闭；普通workers/lanes沿用PM配置默认。没有注册正式机器。PLANNED当前明确拒绝，没有降级；两join、PN权限预算、补偿、性能统计/后端与实际控制器仍须后续完成。详见[调度说明](scheduling.md)与[控制器审查](machine-controller-migration-audit.md)。

## 验证记录

- 2026-11-07（scheduler planned 所有权）：`gradlew.bat spotlessApply build --console=plain` 成功，593 tests / 91 suites，failure/error/skipped 全为 0。新增 6 项真实资源/完整cycle调度测试及 2 项 prepare 句柄行为测试，覆盖未完成source实际退出、UNKNOWN关闭重试、取消失败禁止继续advance、owner中断后真实退款和关闭请求不会被tick恢复遗忘。IDEA 本机 MCP 对 336 个 src Java 加 Tags 共 337 文件逐个 `get_file_problems(errorsOnly:false)`，最终文件集合无遗漏/重复、零错误/警告/弱警告且无超时；MCP `build_project` 成功，problems 为空。报告 `build/reports/idea/scheduler-planned-ownership-final.json`。原 23 份修改的内容及diff与阶段基线一致，保持未暂存；本阶段本地提交，不推送。未操作桌面IDE或启动游戏/服务端。


- 2026-11-07（机器 tick 边界）：`gradlew.bat spotlessApply build --console=plain` 成功，585 tests / 90 suites，failure/error/skipped 全为 0。新增 4 项组件游标与 7 项持续生命周期行为测试，包含已观察 replacement 被再次移除后不能提前释放 gate 的回归。IDEA 本机 MCP 对 334 个 src Java 加 Tags 共 335 文件逐个 `get_file_problems(errorsOnly:false)`；最终文件集合无遗漏/重复、零错误/警告/弱警告且无超时，最终修改两文件重新检查。MCP `build_project` 成功且 problems 为空。报告 `build/reports/idea/machine-tick-boundaries-final.json`。未操作桌面 IDE，未启动游戏/服务端/Bootstrap/FML。原 23 份文件逐字内容和 diff 与阶段基线一致，继续未暂存；本阶段本地提交，不推送。


- 2026-11-07（真实多宿主阶段协调）：`gradlew.bat spotlessApply build --console=plain`通过574项测试（88 suites，无失败/错误/跳过）。新增TickGroup 9项、runner异常边界4项、真实协调器11项行为测试，覆盖整组capture原子性/跨host作用域/统一释放、派发前句柄与直接/clock异常实际退出、真实FE START/TICK/END与0speed、拒绝先退hold、整批逆补偿、partial/pre故障、UNKNOWN恢复及过期cleanup期限、inflight取消、通知异常不重放。IDEA MCP对332个src Java加Tags共333文件逐个errorsOnly:false，零错误/警告/弱警告，无超时；MCP build_project成功。报告`build/reports/idea/planned-recipe-coordinator-final.json`。原23份修改逐diff核对一致并保持未暂存，未启动游戏/服务端。
- 当前验证边界：协调器可显式执行真实多宿主阶段与source lane结算；TaskScheduler/服务器调用、独立纯prepare扩展/内部工作预算、PN实际budget receipt及取消/退休接线仍未完成，PLANNED_PARALLEL尚未开放。

- 2026-11-07（工位阶段批次）：`gradlew.bat spotlessApply build --console=plain` 通过550项测试（85 suites，零失败/错误/跳过）。新增10项真实工位批次行为测试：共享FE的START/TICK/END轮次、Blocked/Failure不停止其他槽、prepared取消、容量下降、捕获身份/线程边界、后续pre故障、post聚合、UNKNOWN source/business确认、部分round创建、自定义executor保留，以及raw acquisition/pre/post/需求回调跨进程重入拒绝。修复post拒绝入口后误标已执行的问题。IDEA MCP对328个src Java与Tags共329文件逐个errorsOnly:false检查，零错误/警告/弱警告，无超时；MCP build_project成功。证据`build/reports/idea/work-slot-batches-final.json`。全部检查仅用本机MCP，未运行游戏/服务端；原23份未暂存修改分离保留。
- 本阶段边界：仅完成真实工位批次入口与宿主作用域；多宿主整体捕获/回调作用域、正式planned coordinator、网络预算消费及退休实际join仍须接入。

- 2026-11-07（真实阶段会话）：`gradlew.bat spotlessApply build --console=plain` 通过 540 项测试（84 suites，零失败/错误/跳过）。新增组件会话、acquisition和真实进程会话行为测试，覆盖同round共享源补偿、原冻结Tick/START效果、0速度/0时长、零事务重入、旧句柄与跨线程拒绝、prepared丢弃、同步/延迟post、source/business未知确认和终态通知故障。IDEA MCP 对 327 个 src Java + Tags 共 328 文件逐个 `errorsOnly:false`，零错误/警告/弱警告且无超时，MCP build_project成功。报告 `build/reports/idea/recipe-sessions-final.json`；未运行游戏/服务端。原23份未暂存修改分离保留；重构Executor中原清理代码的展开格式随职责迁移到accumulate/rethrow，提交版本与保留工作副本仅在这些原格式部分有空白差异。
- 本阶段不宣称正式planned已完成：工位批次入口、coordinator、网络许可/预算消费、取消/退休join仍是下一阶段工作。

- 2026-11-07（事务确认与版本额度阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，513项测试（81 suites）无失败/错误/跳过。新增18项行为测试：6项真实资源/FE版本额度，6项业务与Scoped恢复，6项真实checkpoint/selector聚合恢复；覆盖普通修改不能侵占apply/终态/失效额度、未知/after-mutation/query失败、准确确认不重放、冻结Tick/路径、terminal/query重入、父子确认和其他writer效果保留。没有反射注入、游戏/Bootstrap/FML初始化或常量/getter/hash专项测试。
- 2026-11-07（事务恢复 IDEA 证据）：最终全程通过本机MCP逐文件 `get_file_problems(errorsOnly:false)` 检查324个src Java和Tags共325文件，零错误/警告/弱警告且无超时；MCP `build_project` 成功、`problems:[]`。报告 `build/reports/idea/transaction-recovery-final.json`。原23份修改保留，RequirementTransaction仅本轮API契约改动暂存，原有格式修改继续未暂存。
- 2026-11-07（恢复边界审计）：修复Scoped失败后无条件关闭、同方向terminal重入，以及复合wrapper丢失准确子query的问题。selector自有cleanup缺少公开准确query时仍UNKNOWN，不能凭child完成掩盖自身失败；确认后原persistenceFailure不清除。正式acquired-stage handle、stage version、tick session、工位批次及planned coordinator/预算接线仍待完成。

- 2026-11-07（跨lane资源批次阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，495 项测试（78 suites）零失败/错误/跳过。新增18项行为测试：10项fence和8项批次结算，涵盖真实并行source、资源/FE的读写和保存隔离、取消后实际退出、部分apply及业务失败的准确补偿、授权撤销、UNKNOWN与已确认终态、拒绝持锁/嵌套调用后恢复、全部通知异常和确定性reader抢跑。未增加游戏/Bootstrap/FML初始化或getter/hash专项测试，测试计数器也未使用Atomic包装。
- 2026-11-07（跨lane资源批次 IDEA 证据）：最终通过已启用的本机MCP逐个 `get_file_problems(errorsOnly:false)` 检查319个src Java和Tags共320文件，零错误、警告、弱警告且无超时；MCP `build_project` 成功、`problems:[]`。报告 `build/reports/idea/deferred-resource-batches-final.json`。原23份改动保留，HatchInventoryPort只暂存本阶段lockAll调用，原Javadoc格式修改继续未暂存。
- 2026-11-07（跨lane结算审计边界）：ResourceBatch先决定不可逆完成再调用complete；terminal抛错后沿准确token的resolution确认，UNKNOWN不重试。业务Runnable尚无query，失败保留pending而不假关闭；真实RecipeProcess的阶段handle/tick session/工位批次驱动和ScopedRequirement确认式关闭仍待接。原revision算术未保证终态清理额度的问题已记录，须在正式planned接线前解决；owner失效须进入准确fence scope，不能在scope外等待自己。

- 2026-11-07（planned 执行屏障阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，477 项测试（76 suites）零失败/错误/跳过。新增 10 项行为测试覆盖取消注册/注销竞态、锁外通知与异常汇总、真实 prepare 并行及有序结果、超时迟到/时钟绕回、排队惰性、同 source 跨批查询补偿串行、提交拒绝、中断后实际退出，以及实际入口和结果发布重查 deadline。没有 getter/常量/hash 专项测试或 Minecraft/Bootstrap/FML 初始化。
- 2026-11-07（planned 执行屏障 IDEA 证据）：最终复查直接通过已启用的本机 MCP，逐文件 `get_file_problems(errorsOnly:false)` 覆盖 315 个 src Java 与 Tags 共 316 文件，零错误、警告、弱警告且无超时；MCP `build_project` 返回 `isSuccess:true`、`problems:[]`。报告 `build/reports/idea/planned-execution-final.json`。原 23 份格式/注解 diff 逐字保持不变。
- 2026-11-07（planned 原实现审计）：确认旧 PM 工位的 process 始终为空、PN prepare/commit 仅 operation 序号和 numeric receipt，不能当成真实配方迁移。旧 runner 结果终态不证明动作退出；coordinator 直接 throw 清理、discovery rollback pending、source 短提取与确认重试问题已记录。sourceRevision 在完整 ledger 链确有检查，不误报。正式 coordinator、source 结算、真实 RecipeProcess 与预算消费仍未完成，下一步须解决既有事务锁归属和跨 operation 延迟 complete，不能 source 投料后再次调用 tick 双重消费。

- 2026-11-07（网络预算阶段）：复用PN每机器每tick lease、完整numeric向量预留、两份numeric与原book补偿的语义，分离server tick与capture epoch。修复旧句柄仅靠tick标签仍可提交的问题；独立复查修正只验请求机器proof而漏掉依赖中心的缺口。新增8项行为测试，并在复制存档集成测试验证实际组件凭据失效/恢复；没有getter/常量/hash专项测试。普通配方、研究、完整参与者和正式planned消费链仍未完成。
- 2026-11-07（网络预算验证）：最终 `gradlew.bat spotlessApply build --console=plain` 成功；74 suites、467项测试零失败/错误/跳过。本机IDEA MCP逐文件检查310个src Java及Tags共311文件，全部 `errorsOnly:false`，零错误/警告/弱警告且无超时；MCP build_project成功且problems为空。报告 `build/reports/idea/network-budgets-final.json`。原23份修改保留未暂存，未操作桌面或初始化/启动Minecraft。

- 2026-11-07（计算资源注册阶段）：迁入 PN 的权威注册/封闭语义，修正旧注册复制对象却要求 research 声明同一实例、computer calculator 仅校验 logicalConcurrency 等边界。新增 1 项运行时组合回归覆盖跨 capture 供给 metadata 漂移、未注册类型、仅修饰器/参与者出现的键、失败撤权后恢复，以及合法设备总量超过 per-item 上限。原规划和机器组件测试显式使用独立冻结表；未添加 getter/常量/hash 专用测试。
- 2026-11-07（计算资源注册验证）：`gradlew.bat spotlessApply build --console=plain` 成功；72 suites、459 项测试全部通过，无失败/错误/跳过。本机 IDEA MCP 对 307 个 src Java 和 Tags 共 308 文件逐个 `get_file_problems(errorsOnly:false)`，最终零错误/警告/弱警告且无超时；MCP build_project 成功且 problems 为空。证据 `build/reports/idea/network-resources-final.json`。未操作桌面或启动 Minecraft/Bootstrap/FML 初始化。原 23 份格式/注解修改继续保留未暂存。

- 2026-11-07（网络运行时）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，458 项逻辑测试全部通过，无失败、错误或跳过。新增 12 项覆盖身份修订防ABA、MAIN/CON prepare顺序及故障、整批失效/证明过期/跨线程约束，以及真实assembly/FE/scanner/工位/进程的暂停恢复、保存复制和部分load失败新代隔离。低层原formation契约保持，原retirement回归用例继续通过；未初始化游戏或使用反射。
- 2026-11-07（网络运行时）：本机 IDEA MCP 对 304 个 src Java 和 Tags 共 305 文件逐个 `errorsOnly:false`，最终零错误、警告、弱警告及超时；MCP `build_project` 成功且 `problems:[]`。报告 [network-runtime-final.json](../build/reports/idea/network-runtime-final.json)。新runtime句柄使用static类与显式所属runtime；测试清理不再引入常值参数。未操作桌面或启动客户端/服务端。
- 2026-11-07（网络运行时审计）：PN旧START发布在PM END调度前，未覆盖所有普通配方；新共同条件包括直接scanner/工位/进程阶段，既有事务完整结束后再暂停。MAIN回调之间重新捕获以反映外部变化，网络计划当前在owner同步完成；尚无性能压测或正式planned证据。FTB依赖版本已在Mod声明与开发依赖一致配置。预算必须按游戏tick单独结算，不能按捕获epoch重充。

- 2026-11-07（队伍/网络规划）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，446 项逻辑测试全部通过，无失败、错误或跳过。新增 13 项覆盖成员换队快照、选择/满库/等级/角色、汇总与修饰顺序、每 owner 一次查询/真实 worker、重复/迟到输入、矛盾成员、故障传播、exact 边界和 map entry 不可变。删除两项仅测参数/getter 的初稿，不增加游戏初始化或反射。
- 2026-11-07（队伍/网络规划）：本机 IDEA MCP 对 299 个 src Java 和 Tags 共 300 文件逐个 `errorsOnly:false`，最终零错误、警告、弱警告及超时；MCP `build_project` 成功且 `problems:[]`。报告 [network-planning-final.json](../build/reports/idea/network-planning-final.json)。全程不操作桌面、不启动游戏；FTB 的真实 Universe 生命周期尚未运行验证。
- 2026-11-07（队伍/网络规划审计）：FTB 使用旧 Curse 文件坐标；将 repositories 提前到 MDG 首次解析前，并对 Curse group 限定两 Curse 仓库，解决插件仓库抢先查询及已使用仓库不能改过滤器的问题。保留 PN 数据中心已存研究优先和被动电脑贡献；修复 accepted owner 分支延迟去重、重复贡献二次构建及只读 entry 穿透。DATA_CENTER 等级 0 的旧声明/运行实现矛盾统一按声明允许，仍需要活动中心；不新增无中心工作语义。

- 2026-11-07（PN 持久身份）：`gradlew.bat spotlessApply build --console=plain` 成功，433 项逻辑测试全部通过，无失败、错误或跳过。新增 14 项覆盖多副本冲突及恢复、同位置不同类型替换、迟到卸载不删新代、引用身份/线程/会话边界、严格 NBT，以及真实 assembly/lifecycle 搬运与部分加载失败清理。没有游戏初始化、反射、哈希或 getter 专用测试。
- 2026-11-07（PN 持久身份）：本机 IDEA MCP 对 283 个 src Java 和生成 Tags 共 284 文件逐个 `errorsOnly:false`，清理诊断并复查后最终零错误、警告、弱警告及超时；MCP `build_project` 返回 `isSuccess:true`、`problems:[]`。报告 [machine-identity-final.json](../build/reports/idea/machine-identity-final.json)。registry 借用句柄更新使用局部 resource 标注并说明不能关闭的原因，测试夹具明确关闭。只使用 MCP，未操作桌面或启动游戏。
- 2026-11-07（PN 源码复核）：保留 UUID/owner 持久化、所有重复 UUID 拒绝及加载顺序；修正按位置无条件卸载、位置键包含类型和 runtime 可变身份问题。旧 pending/planned/station 清理、planner 延迟去重、快照异常绕过撤权与 world 清理聚合仍须随下阶段接线修正。FTBLib 持久玩家支持离线 owner、PLAYER/SERVER 和无队长服务器队伍；尚未加入 FTB 依赖或把身份唯一性冒充许可，详见[PN 审计](pn-identity-migration-audit.md)。

- 2026-11-07（自动配方/机器界面/工具）：RecipeScanning 显式组件声明复用旧工位与事件驱动处理；工位先创建、scanner 恢复时重建，runBefore 保证同 tick 扫描后处理，恢复不重跑 START。RecipeRegistry 完整构造池和共享 catalog 后单次 volatile 发布。空 groups 在 catalog/index/扫描/直接 admission/restore 一致表示全局，包含无组配方，混合显式/wildcard 工位按各自规则接收。详见[配方](recipes.md)。
- 2026-11-07（GUI/方向工具）：默认 MachineGui/MachineContainer 直接继承 AE2 基类，公开两侧 Java factory 注册；自定义容器必须绑定实际 TE，客户端匹配类型。owner FIFO join 后重验窗口和 TE，只同步可见最多七行不可变快照，保留进度、阻塞/失败原因和真实背包；修复 AE2 Scrollbar 直接调用缺命中检查吞掉背包左键的问题。PM 临时库存/固定 Idle/空扩展页不作为真实机器功能。方向工具保留原两模式、24 向及 DOWN top=SOUTH、采掘/武器/附魔/耐久、十项配置、模型和成就，手持时控制器让出右键。详见[机器 GUI](machine-gui.md)、[方向工具](orientation-tool.md)。
- 2026-11-07（AEKey 数量表）：存储三表、快照、配方合并/输入解析与索引改用 AEKey2LongMap，增减 addTo；快照总量复制后仅遍历实际预留量扣减，避免逐总量 key 查预留表。保留 Linked 顺序、溢出前置预检、零条目删除、跨来源饱和和 alias 最大值语义；快照 UnmodifiableMap 拒绝 addTo。复用已有资源/事务测试，仅补已有不可变快照用例的新增写入口验证；未增加 hash 性能断言。详见[存储](storage-redesign.md)。
- 2026-11-07（本阶段验证）：最终 gradlew.bat spotlessApply build --console=plain 成功，419 项测试零失败/错误/跳过。较上一阶段新增 18 项逻辑用例：自动控制器处理/恢复 7 项、全局组/索引 4 项、GUI 快照 3 项、方向数学 4 项。IDEA MCP get_file_problems(errorsOnly:false) 覆盖 276 个 src Java 和生成 Tags 共 277 文件，修改后复查 59 份，最终零错误/警告/弱警告且无超时；MCP build_project 返回 isSuccess:true、problems:[]。报告 build/reports/idea/machine-gui-final.json。全部检查使用现有本机 MCP，无桌面操作，也未启动 Minecraft、Bootstrap/FML。原 23 份工作区修改保留不提交；三个重叠文件只提交本阶段功能修改。实际 Forge/GUI/模型交互仍无游戏内证据。


- 2026-11-07（控制器世界接线）：新增类型/运行时注册事件，全局MachineTypeRegistry整批校验并同时发布结构/assembly，零声明也注册通用TE。Block factory/空构造/read只存类型、朝向和NBT，server update过Forge取消窗口后load；已装配BlockSnapshot恢复先检查保存边界、退旧代再由新controller恢复，不直接覆盖组件。setup失败也清理已登记runtime。完整方向在owner FIFO操作，修正外部A→原方向漏队列及相对变换提前求值；结构按PM 20/5节奏真实CONCURRENT评估、join后发布、失稳暂停下tick重试。客户端仅类型/朝向/formed/显示代与序号，旧包拒绝；放置者限本TE实际玩家，不授PN权限。默认模型沿用PM 48状态和inventory，复制7PNG与6份JSON/metadata并保留credit。见[世界接线](controller-integration.md)、[资产来源](controller-assets.md)。
- 2026-11-07（控制器世界接线验证）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，401项测试零失败/错误/跳过，新增15项（5 runtime发布、7结构调度、3数据/24向/显示）。本机MCP逐文件 `get_file_problems(errorsOnly:false)` 覆盖259个src Java和Tags共260文件，最终零错误/警告/弱警告且无超时；MCP build_project成功、problems为空。证据 `build/reports/idea/controller-integration-final.json`。主类功能接线单独暂存，原有格式/注解增删保持；其他22份原diff逐字未变，全部原改动保留未暂存。未运行Minecraft、Bootstrap/FML或桌面；普通TileEntity构造会触发AttachCapabilitiesEvent，因此没有用new TE冒充纯逻辑测试。
- 2026-11-07（真实调用顺序复核）：对当前Cleanroom sources核验ItemBlock先onLoad后NBT及pending反向顺序、Forge取消/BlockSnapshot二次read、Template read→mirror→rotate、Chunk保存早于TE卸载。补查ForgeChunkManager确认正常dormant会清TE map并从NBT重建，撤回“正常休眠复用Java TE”的早先判断。异常保存失败/自定义loader同TE重加且旧卸载晚到仍需精确Chunk卸载代证明，不添加宽泛自动复活。真实世界时序、模型/CTM/GUI仍无实机证据，余下范围保持。

- 2026-11-07（控制器运行时阶段）：MachineController持有一次装配和独立对象身份登记，load后注册，注册失败卸载/注销并聚合，旧代退出不注销新代。停止先经合法边界admission再retire，同一MAIN回调的卸载/破坏按最强原因合并，回调返回或所有accepted worker物理退出后清理。独立fastutil FIFO保留fault后的清理；close只有证明executor终止才排空并关闭边界接收，否则保留资源/队列供重试。未装配/失败未返回机器时保留复制的原NBT，已装配始终检查codec；退休volatile不保存、不复活，阻止新阶段/工位/probe/admission，当前事务/回滚完整结束。见[控制器运行时](machine-controller.md)。实际TE、24向/配置、结构刷新调度、放置身份与客户端仍须继续。
- 2026-11-07（控制器运行时验证）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，386项逻辑测试零失败/错误/跳过，较装配阶段新增27项（11调度边界、6退休、10控制器）。覆盖真实两个worker退出、异常/中断后清理、FIFO/关闭终止证明、阶段提交与探测预留回滚、MAIN自卸载升级破坏取消工位、恢复时序/原NBT隔离、注册失败聚合、同位置新旧代及故障不复活。本机MCP逐文件 `get_file_problems(errorsOnly:false)` 检查245个src Java及Tags共246文件，最终零错误/警告/弱警告且无超时；MCP build_project成功，problems为空。证据 `build/reports/idea/controller-runtime-final.json`。原23份diff逐字保持且未暂存；未启动游戏、Bootstrap/FML初始化或操作桌面。构建仍报告既有Gradle未来版本弃用提示，不是Java检查诊断。

- 2026-11-07（附宿主装配阶段）：新增MachineAssembly/MachineComponentCodec，复用属性与工位/进程NBT。附host后factory，保存图预检无factory，完整创建后按依赖恢复，根属性先恢复并按ID补缺默认，完整校验后load；不自动登记scheduler。组件codec可返回immutable替代，replaceValue保留实际图。实际顺序、动态边、pending和被删除声明按NBT V1恢复；MachineHatches能力不恢复。ASSEMBLING/PERSISTING门限保护完整回调并在故障后永久停止，保存故障仍允许已加载监听清理。详见[机器持久化](machine-persistence.md)。实际TE与Forge接线、PN身份/权限、planned仍待后续。
- 2026-11-07（附宿主装配验证）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，359项测试零失败/错误/跳过。新增15项装配顺序、动态图/不可变替换、默认属性与modifier-only、NBT隔离、未知/坏图预拒、回调非法修改/失败不复活、load清理及实际工位续跑和跨线程gate用例。MCP逐文件 `get_file_problems(errorsOnly:false)` 覆盖241个src Java及Tags共242文件，零错误/警告/弱警告、无超时；MCP build_project成功。证据 `build/reports/idea/machine-assembly-final.json`。原23份diff逐字未变，未运行游戏或Bootstrap/FML初始化，未操作桌面。

- 2026-11-07（机器声明阶段）：新增不可变MachineType与MachineTypeRegistry，保留PM声明字段、默认模式和LocalizationEnum显示契约。整批注册/关闭和后置结构绑定均完整校验后发布，规范对象不重建，拒绝重入和同ID仿造。ComponentMap声明验证复用实际系统拓扑；typed实例附宿主/load前不调度，显式工位factory读取类型布局/组，已有模式组件优先。只做库入口，未注册正式机器。默认属性自动补缺、机器级NBT/TE及PN配置仍须后续；详见[机器声明](machine-types.md)。
- 2026-11-07（机器声明验证）：`gradlew.bat spotlessApply build --console=plain` 成功，344项测试零失败/错误/跳过，新增12项批次/规范身份/重入/元数据图/真实调度/工位准入行为用例。本机IDEA MCP逐文件 `get_file_problems(errorsOnly:false)` 检查238个src Java及Tags共239文件，最终零错误、警告、弱警告且无超时；MCP build_project成功、problems为空。报告 `build/reports/idea/machine-types-final.json`。全程MCP，无桌面/游戏/Bootstrap/FML初始化。原23份修改保留，22份diff逐字相同；MachineInstance重叠功能按原布局单独暂存，剩余差异只涉及原格式。

- 2026-11-07（普通调度阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，332项测试零失败/错误/跳过。新增17项真实并发/机器集成行为用例，覆盖重叠worker与join、共享key传递串行及稳定lane、MAIN登记/卸载/破坏/新增、同对象二跑拒绝、owner元数据、下一tick提交、独立失败聚合、被拒派发仍drain、中断恢复、faulted及close不复活；不写hash/getter/常量专用测试。未运行Minecraft或Bootstrap/FML初始化。
- 2026-11-07（普通调度IDEA证据）：本机MCP逐文件 `get_file_problems(errorsOnly:false)` 覆盖234个src Java及Tags共235文件，最终零错误、警告、弱警告且无文件超时；MCP build_project成功、problems为空。报告 `build/reports/idea/ordinary-scheduler-final.json`。全程MCP无桌面操作；原23份改动保留，其中两文件功能改动单独暂存，原有格式/导入顺序增删行完全相同，其余21份diff逐字相同。FML真实会话与游戏交互仅有接线/编译证据。

- 2026-11-07（结构发布阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，315项测试零失败/错误/跳过；新增16项实际读取证明/宿主测试，涵盖未访问分支、失败slice增长、时间重算、可变事实显式比较、旧分支异常短路、真实worker、同方向换仓、三公开职责、陈旧结果/配置ABA/卸载、发现失稳重试、故障隔离、重入和执行重叠。最后复核读取异常也验证为当前故障，不能无限重试。没有游戏/Bootstrap/FML初始化或常量/hash/getter测试。
- 2026-11-07（结构发布IDEA证据）：本机MCP逐个 `get_file_problems(errorsOnly:false)` 覆盖226个src Java及Tags共227文件，最终零错误、警告、弱警告且无文件超时；MCP build_project为isSuccess:true、problems为空。报告 `build/reports/idea/structure-publication-final.json`。全程MCP，不操作桌面；原23份格式/注解diff逐字比对未变。实际控制器调用和真实调度仍未完成，当前发布契约不代表这些能力已完成。

- 2026-11-07（结构匹配IDEA证据）：本机MCP逐文件 `get_file_problems(errorsOnly:false)` 检查218个src Java及Tags共219文件，零错误、警告、弱警告且无文件超时；MCP build_project成功且problems为空。分批最终报告 `build/reports/idea/structure-matching-final.json`。

- 2026-11-07（结构匹配阶段）：`gradlew.bat spotlessApply build --console=plain` 成功，299项测试无失败/错误/跳过。新增22项：24向/576朝向组合与坐标边界、捕获隔离及潜在分支、NBT/AND/OR/未加载谓词、嵌套模板/贪心切片、固定控制器、旋转validator、零片、多occurrence。没有启动Bootstrap/FML或游戏；真实World/TE/能力捕获、IBlockState行为仍缺实机证据。

- 2026-11-07（机器生命周期IDEA证据）：本机MCP逐文件 `get_file_problems(errorsOnly:false)` 覆盖198个src Java文件及生成Tags共199文件，零错误、警告、弱警告且无文件超时；MCP build_project `isSuccess:true`、problems为空。分批报告 `build/reports/idea/machine-lifecycle-final.json`。

- 2026-11-07（机器生命周期阶段）：`gradlew.bat spotlessApply build --console=plain` 成功，277 项逻辑测试无失败/错误/跳过。新增17项聚合测试覆盖监听身份/并发注册取消、事件顺序与捕获、失败全清理、卸载保存新实例恢复不重 START、结构暂停而其他组件续跑、破坏不 END/退历史 IO、活动预留/跨线程/重叠/重入及损坏组件图清理。
- 2026-11-07（生命周期源码复核）：修复 PM 普通处理器缺 formed 门、load 失败漏清先前注册、卸载/破坏回调前未关 active，以及全局注册 none→add 竞态；保留 PN FRESH/RESTORED 和运行注册清理用途。此阶段不代表 TileEntity 世界实机、结构匹配、PN owner/epoch或planned两次join已迁完。

- 2026-11-07（取消/失败清理阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，260 项逻辑测试全部通过，无失败、错误或跳过。新增 20 项聚合行为用例覆盖 NEW/RUNNING/Blocked 取消、历史 IO 不退款、detach 转移、Failure 自动清理、依赖顺序/多回调故障/批量部分失败、归属精确替换、selector/逻辑锁/回执、活动属性默认 codec 及终态恢复无重放。另更新 14 个旧异常后继续 tick 的预期，保留首次补偿/时钟/数量/归属证明并验证故障后拒绝推进；正常 Blocked 重试未改。
- 2026-11-07（取消/失败清理阶段）：本机 IDEA MCP 对 187 个 src Java 文件及生成 Tags 共 188 文件逐个 `errorsOnly:false`，最终零错误、警告、弱警告且无文件超时；MCP `build_project` 成功、`problems:[]`，报告 [process-abort-final.json](../build/reports/idea/process-abort-final.json)。首次整批 Node 调用超时重置后，重新连接既有本地 MCP 并分批落盘完整复查；没有操作桌面、改 IDE 设置或启动游戏。
- 2026-11-07（取消边界源码复核）：普通 PM stop/clear/业务失败只移除工位，不退款或额外 END；普通区块卸载保存进程，结构失效不自动清槽，新显式取消不擅自接成卸载策略。planned 取消补偿本次未完成 source 操作，仍须保留 COMPENSATION_PENDING、精确 after-image 与运行权限失效后继续补偿；本轮同步取消不替代这些后续多线程机制。

- 2026-11-07（固定预筛/资源索引阶段）：`gradlew.bat spotlessApply build --console=plain` 成功，240 项逻辑测试通过，无失败、错误或跳过。新增 10 项聚合行为用例覆盖多需求/别名容量、FE 单次限额、默认扫描选并行与真实提交、零时长/void、跨类型共享槽位释放、动态回调保守跳过/有效 overlay、TICK 上下文与异常回滚、倒排 scope/通用候选、多类型 alias/hold 及总需求超过 long 的合法边界。
- 2026-11-07（固定预筛/资源索引阶段）：本机 IDEA MCP 逐个对 181 个 src Java 文件和生成 Tags 共 182 文件执行 `errorsOnly:false`，零错误、警告、弱警告且无超时；MCP `build_project` 成功、`problems:[]`，报告 [fixed-io-index-final.json](../build/reports/idea/fixed-io-index-final.json)。全程 MCP，无桌面操作或游戏初始化。
- 2026-11-07（固定预筛/资源索引源码复核）：保留 PM 固定阶段当前量/容量判断和 key 倒排目的，改用现有批预留避免重复使用同一余额；输入可释放后续共享输出空间时不误拒。固定输入声明只提供必要下界，索引没有证明真实 START 成功或完整未来可行性；保持未声明候选并要求声明覆盖全部外部改写。内部工作预算、取消和真实多线程仍待迁移。

- 2026-11-07（扫描 admission 阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，230 项逻辑测试全部通过，无失败、错误或跳过。新增 28 项行为用例覆盖组/索引交集、真实调度顺序/去重、非单调模糊输入、int 最大边界与旧 best 重验、预算续查及固定 seed/tuning、父倍率与局部 modifier/NBT、START 资源/FE/选择回滚、首刻可逆投影和多处清理异常。无常量/哈希/getter 专用测试或游戏初始化。
- 2026-11-07（扫描 admission 阶段）：本机 IDEA MCP 对 177 个 src Java 文件及生成 Tags 共 178 文件逐个 `errorsOnly:false`，最终零错误、警告、弱警告且无超时；MCP `build_project` 成功、`problems:[]`。报告 [recipe-scanning-final.json](../build/reports/idea/recipe-scanning-final.json)。全程通过本地 MCP 检查，未操作桌面或启动游戏。
- 2026-11-07（扫描审计边界）：修复旧 group/index 范围、无类型候选交集契约、有效父倍率重复应用、非单调完整 START 二分和 int 溢出风险。START acquisition/rollback 不执行 selector commit modifier；普通组件 pre/tick 不直接模拟。固定 END/TICK 预筛、内置资源索引和内部工作预算尚待迁移，阶段提交不表示扫描或整体重开发全部完成。

- 2026-11-07（工位宿主阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，202 项逻辑测试全部通过，无失败、错误或跳过。新增 13 项聚合行为用例覆盖真实 onSchedule/执行器、Blocked/终态、降低并发上限、owner/group/跨 host 排他、资源与 FE 移交不重复扣费、不执行 END、重入与故障拒绝、整组坏数据不发布、末尾解码失败及恢复别名隔离。没有常量/哈希/getter 专用测试或游戏初始化。
- 2026-11-07（工位宿主阶段）：本机 IDEA MCP 对 164 个 src Java 文件及生成 Tags 共 165 文件逐个 `errorsOnly:false` 检查，最终零错误、警告、弱警告且无超时；MCP `build_project` 成功、`problems:[]`，报告 [work-slots-final.json](../build/reports/idea/work-slots-final.json)。修正 import、Math.clamp 与测试事务/非空契约等诊断，全程使用 MCP，未操作桌面或启动游戏。
- 2026-11-07（源码复核）：PM 实际并发上限仅用于 admission，不能误作恢复驱逐规则；旧公开 mutable collection 可绕过 owner/group 与唯一归属，新入口统一核验。旧 executor 列表未找到注册实现，单 driver 保留扩展职责且消除额外重复推进的结构风险。旧 planned 假工位只提供元数据，后续复用稳定定义及真实 planned 状态。扫描仍需修复索引 group 范围不一致、未知需求漏检查、共享余量重复模拟、有效并行值二次倍率和非单调谓词二分；取消不等于 END，PN 旧 epoch/lease 不可恢复为权限。


- 2026-11-07（严格进程持久化阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，189 项逻辑测试全部通过，无失败、错误或跳过。新增 36 项覆盖 exact NBT 与别名隔离、外部 modifier/overlay、真实资源/FE 阻塞续跑、概率延续、选择器不重跑及 END 局部清理、动态组件依赖/未来安装顺序、坏数据整体拒绝、嵌套检查点与保存边界。测试初稿调用已删除的顺序查询方法，已改用真实组件 tick 回调验证；未增加游戏初始化或常量/哈希/getter 专用测试。
- 2026-11-07（严格进程持久化阶段）：本机 IDEA MCP 对 153 个 src Java 文件及生成 Tags 共 154 文件逐个 `errorsOnly:false` 检查，最终零错误、警告、弱警告且无超时；MCP `build_project` 返回 `isSuccess:true`、`problems:[]`。报告 [process-persistence-final.json](../build/reports/idea/process-persistence-final.json)。仅使用本机 MCP，没有操作桌面或启动 Minecraft。
- 2026-11-07（源码复核）：保留 PM 的局部属性、进程种子和组件状态目的，修复缺字段默认值、未知组件跳过、选择器引用未验证、mutable NBT 共享及自定义 modifier 丢行为的问题。显式 revision 由宿主保证函数兼容；不恢复 PN epoch/lease/ready authority，factory/workslot 宿主装配、扫描/取消与真实多线程仍待迁移。


- 2026-11-07（需求 overlay 阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，153 项逻辑测试全部通过，无失败、错误或跳过。新增 8 项覆盖真实多类型 IO、FE、清空固定 IO 后保留高级需求、多个写入方层顺序/局部补偿、纯函数故障、有效视图和嵌套路径；另有 13 项 pipeline/选择器集成测试覆盖顺序/重复、真实资源参数冻结、外层回滚、END 阻塞/异常和其他写入方效果保留。修复了测试初稿的括号与重复实例 ID，最终无跳过；未引入游戏初始化或 getter/常量/哈希专用测试。
- 2026-11-07（需求 overlay 阶段）：本机 IDEA MCP 对 133 个 src Java 文件及生成 Tags 共 134 文件逐个 `errorsOnly:false`，全部零错误、警告、弱警告且无超时。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；报告 [requirement-overlays-final.json](../build/reports/idea/requirement-overlays-final.json)。修正测试匿名返回及泛型写法提示，检查全程使用本机 MCP，没有操作桌面或启动 Minecraft。
- 2026-11-07（源码复核）：复用 PM 内置 IO/FE 字段覆盖语义和显式 modifier 顺序，补齐旧嵌套需求绕过 overlay、自定义数据缺乏公开写入口、热替换 applier 及失败留下部分修改的问题。旧 NBT 序列化/恢复共用可变 compound、先清空再跳过坏条目等问题已记录，当前 typed 内存层不冒充完整 NBT 恢复；下一阶段严格保存恢复必须处理这些问题。实际扫描/取消和 planned 多线程继续保留。
- 2026-11-07（模糊动态/解析锁/选择器阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，132 项逻辑测试全部通过，无失败、错误或跳过。新增 5 项动态候选匹配/排序/完整 NBT/截断行为、7 项资源解析与阶段补偿、14 项选择器拒绝/禁用/嵌套/属性所有权/重试，以及 3 项完成故障链测试。覆盖真实资源余量、选择锁局部撤销、终态故障继续清理和不重放；无哈希、常量或 getter 专用测试，无游戏初始化补丁。
- 2026-11-07（模糊动态/解析锁/选择器阶段）：本机 IDEA MCP 对 121 个 src Java 文件及生成 Tags 共 122 文件逐个 `errorsOnly:false` 检查，全部零错误、警告、弱警告且无超时。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；报告 [input-selection-final.json](../build/reports/idea/input-selection-final.json)。ITEM_ONLY 作为外部配方入口作精确 unused 标注；其 item+metadata 行为仅静态核对，没有启动游戏或添加 Bootstrap/FML 初始化测试。
- 2026-11-07（源码复核）：保留 PM 模糊顺序、动态 matcher、选择器全程固定候选和 modifier 效果；修正截断先于排序、重复使用固定/前组余量、Blocked 候选 commit、失败残留选择及 END 失败提前清属性。嵌套身份改用 parent/type/id 和长度分帧，避免 1.12 ResourceLocation 字符造成歧义。需求 overlay、完整恢复/扫描/取消和实际 planned 多线程仍未完成。
- 2026-11-07（概率与检查点阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，103 项逻辑测试全部通过，无失败、错误或跳过。新增 11 项抽样/分布策略/边界/无放回行为、13 项检查点时间线/嵌套/拒绝及异常补偿、6 项资源概率集成行为；后者覆盖零概率共享预检、>100% 属性倍率、阻塞刻重试、实际随机 key 容量、全选/丢弃和输出预检故障后释放输入占用。未增加哈希、常量或 getter 专用测试。
- 2026-11-07（概率与检查点阶段）：本机 IDEA MCP 对 102 个 src Java 文件及生成 Tags 共 103 文件逐个 `errorsOnly:false`；修复测试匿名返回提示，对外部 binomial 默认策略入口作精确 unused 标注并说明用途，最终所有错误、警告、弱警告为零且无超时。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；报告 [probability-checkpoints-final.json](../build/reports/idea/probability-checkpoints-final.json)。全程使用 MCP，没有操作桌面或启动游戏。
- 2026-11-07（源码复核）：复用 PM ChanceMath/BinomialSampler/WeightedSampling 和 CheckpointRequirementSystem 的计算目的及触发流程，修正非有限概率静默归零、极大 chance 上界溢出、double 权重抽签精度、真实随机 key 空间检查、旧流体刻索引停滞、time=0 不触发及子生命周期拒绝后继续获取。普通随机池的大次数遍历、解析锁/选择状态和恢复依然需要后续迁移，不将这些剩余项标作完成。
- 2026-11-07（属性与单进程循环阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，73 项逻辑测试全部通过，无失败、错误或跳过。在原 52 项基础上新增 6 项属性计算/overlay/快照/非法恢复行为、7 项包围业务的组件回调顺序与异常清理、8 项进程集成行为；涵盖三阶段阻塞与重试、属性局部补偿、冻结时间线、三阶段完成后通知故障不重放、有效并行与 long 饱和边界。未新增哈希、常量或 getter 专用测试。
- 2026-11-07（属性与单进程循环阶段）：本机 IDEA MCP 对 89 个 src Java 文件及生成 Tags 共 90 文件逐个 `errorsOnly:false`；修复泛型写法提示，对尚待扫描器使用的公开并发属性仅作精确 unused 标注并说明用途，复查后全部零错误、警告、弱警告且无超时。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；报告 [process-attributes-final.json](../build/reports/idea/process-attributes-final.json)。全程使用 MCP，没有操作桌面或启动游戏。
- 2026-11-07（源码复核）：复用 PM 属性三运算、overlay 和 FactoryRecipeProcessor 的阶段循环，修正易丢失失效信号的属性缓存及固定父实例引用；实际计数改用 double/long，状态纳入阶段提交。已核对并记录旧扫描器有效并行值写 base 后可能再次倍乘、二分上界溢出、流体随机刻索引停滞和检查点预估速度不一致；完整扫描、随机需求、检查点与持久化仍未迁移，不宣称这些路径已修复。
- 2026-11-07（需求与 FE 协调阶段）：最终 `gradlew.bat spotlessApply build --console=plain` 成功，52 项逻辑测试通过、无失败或跳过。在原 37 项基础上增加 7 项跨需求阶段补偿/终态故障用例、4 项能源预留与并发用例、4 项公开配方池到固定内置需求的集成用例；能源方向/限额/重载等原用例继续通过。未增加哈希、常量或 getter 测试。
- 2026-11-07（需求与 FE 协调阶段）：本机 IDEA MCP 对 79 个 src Java 文件与生成 Tags 共 80 个文件逐个使用 `errorsOnly:false`；修复能源边界冗余条件后复查全部改动文件，最终无错误、警告、弱警告及超时。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；报告 [recipe-energy-final.json](../build/reports/idea/recipe-energy-final.json)。只使用 MCP，没有操作桌面或启动游戏。
- 2026-11-07（源码复核）：核对 PM 固定物品/流体/FE 及高级需求，确认选择器回滚后遗留索引可能跳过 START 扣料、END 失败可能误删 modifier、重复流体/FE 模拟、随机输出容量界和动态候选截断顺序等风险。审计和后续依赖已补到 [migration-audit.md](migration-audit.md) 与 [recipes.md](recipes.md)。当前固定需求修正了逐次模拟与跨类型完成边界；高级选择/概率缺陷仍在未来迁移项中，没有声称已修完。
- 2026-11-07（独立资源存储阶段）：`gradlew.bat spotlessApply build --console=plain` 成功，37 项逻辑用例全部通过、无跳过；包含原 25 项和新增 12 项。新增用例覆盖共享数量/容量占用、跨 key 空槽竞争、成功完成后再次预留、应用失败反向补偿、外部修改参与者列表与通知异常、失效令牌、快照、long 边界、8 线程预留竞争及 32 轮反序双存储并发结算，以及双侧恢复的溢出/预留拒绝。没有哈希、常量或 getter 专用测试。
- 2026-11-07（独立资源存储阶段）：最终本机 IDEA MCP 检查 67 个 src Java 文件与生成 Tags，共 68 个文件；逐文件 `errorsOnly:false`，全部 `errors:[]`，无超时。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`。报告 [storage-final.json](../build/reports/idea/storage-final.json)。只对 VarHandle 间接写字段、外部同步入口使用精确 unused 抑制；并发测试的 ExecutorService 手动 shutdownNow 并有界 awaitTermination，局部 resource 抑制防止 TWR close 在真实死锁时无限等待。没有关闭任何 IDE 检查。
- 2026-11-07（独立资源存储阶段）：混合仓恢复用例验证的是 NBT 解码后的 `restoreContents` 双侧原子边界，未引导 AE2/FML 注册环境，未验证 GenericStack NBT 编解码、GUI 或游戏内流体交互。生产代码已将工作线程变更通知改为 VarHandle 脏标记，服务端 tick 与失效/卸载回调冲刷 markDirty；真实调度、owner/epoch/lane、FE 事务及世界生命周期仍待集成。
- 2026-11-07（源码复核）：复用 PM ItemResourceStorage 的按 key 汇总、稳定槽位及 BitSet 分配思路，修正可变数量快照与 long 算术边界；普通资源转移不创建临时分配列表/数组。审查确认底层预留账本成功 finalize 缺口、部分提取及输出补偿风险、来源身份分裂和 PN 提交后仍要求 RESERVED 的生命周期风险，详见 [storage-redesign.md](storage-redesign.md)。未运行旧项目复现，不宣称完整 planned 调度已迁移。
- 2026-11-07：改用本机 IDEA MCP 完成检查。已启用服务的 `mcpServer.xml` 端口为 64343，通过 SSE `http://127.0.0.1:64343/sse` 连接并在 `tools/list` 发现 `get_file_problems` 与 `build_project`。对 57 个 src Java 文件及 1 个生成 Tags 文件逐个使用 `errorsOnly:false`，全部 `errors:[]`，无错误、警告、弱警告及超时；最终报告为 [final.json](../build/reports/idea/final.json)。后续检查沿用 MCP，不操作桌面或修改 IDE 设置。
- 2026-11-07：MCP `build_project` 返回 `isSuccess:true`、`problems:[]`；最终 `gradlew.bat build` 成功，含生产重混淆与 25 项逻辑用例，其中 7 项能源覆盖三方向、动态机器/Forge 限额、重载裁剪及旧引用失效等行为。未启动 Minecraft 客户端或服务端，GUI/游戏验收未列为通过。
- 2026-11-07：静态核对 128 份模型 JSON 的模型/纹理引用，无缺失；18 个 GuiText 枚举项的中英语言键完整，格式占位一致。源码未发现裸 `TextComponentTranslation` / `I18n` 调用；未建立资源哈希、常量或 getter 测试。
- 2026-11-07：早先错误选择桌面 IDEA 检查，被用户 Escape 中止且报告未导出；用户纠正后改用上述 MCP，修复诊断并取得最终零问题结果。早先中断不再作为当前状态。
- 2026-11-06：`gradlew.bat --version` 确认 Gradle 9.8.1 使用 Java 25；系统 PATH 中的 Java 8 未被 Gradle 启动器采用。
- 2026-11-06：早期构建从 4 项逻辑用例扩展至 P1 的 7 项，再至固定资源原型的 11 项，验证双缓存方向、鼠标转移、同源去重、部分收取/付款与检查点守恒；这些证据不代表独立存储、完整需求迁移或 NBT/游戏验收通过。
- 2026-11-06：组件与需求基础迁移通过 `gradlew.bat spotlessApply build --console=plain`，含生产重混淆及 20 项逻辑测试。新增 9 项覆盖软依赖/替换/重装、外部系统事件顺序和失败终止，以及外部需求的整阶段回滚/重试、状态隔离、三个阶段及异常清理。没有宣称真实调度器、NBT 或游戏运行已经验证。
- 早期依赖排查曾启动临时服务端并正常关闭，发现普通 implementation 导致 AE2 插件类加载冲突，改用 MDG 的 modImplementation 后完成基础加载。该记录发生在仓室核心接入之前，不是当前业务功能的游戏验证。
- 进程 NBT 已取得独立逻辑测试证据；真实注册环境中的 AE2 key、能力注册、GUI 与游戏交互尚未取得运行证据；不自行启动客户端或服务端。
- 直接测试 Forge FluidStack 需要 Bootstrap/FML 注册流程；普通 JUnit 引导还遇到 Gson 运行库冲突。该游戏依赖用例未纳入当前通过集，没有用反射、替身注册表或大批附属依赖绕过它。混合仓流体方向目前依据 AE2 适配器源码和本项目分离视图核对，实际流体交互仍待运行验证。

## 提交约定

阶段完成后本地提交，不推送。测试限于静态推断无法充分确认的行为；测试证据、构建结果与游戏验证分别记录。

集合实现优先使用 fastutil；本轮容量表采用原生 long 映射，能力适配缓存采用引用身份映射。没有自行实现反射扫描或通过捕获异常维持常规资源流程。

当前实施先完成源码、构建及必要逻辑测试，不继续自行启动客户端或服务端。此前的两个临时服务端已经正常停止。
