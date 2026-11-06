# 实施进度

需求依据：[开发文档](development-spec.md)。仅记录实际完成的工作与验证证据，不将设计目标标为已实现。

项目定位为纯库：机器、配方、研究内容和玩法数值由使用方定义；内置仓室为通用组件，独立示例不进入主产物。

## 当前状态

- 文档恢复入口：先读 [INDEX.md](INDEX.md)，再按任务定位章节；根 AGENTS.md 和 README 已链接该入口。索引维护当前检查点与下一步，本文件维护详细状态和证据。
- 已完成：工程标识、Java 25 模板、需求基线；固定 AE2S `v1.1.0:dev` 与 HEI `4.35.1`；移除模板示例 Mixin；开发包和生产重混淆构建。
- 独立资源后端已完成：ResourceHatch 返回 ResourceStorage，槽位扩展由 SlottedResourceStorage 表达；SlottedResourceBuffer 独立持有 AEKey/long 余额、可用量、输入数量预留及输出容量预留。MEStorage、GenericInternalInventory 与 GUI 均为共享同一 context 的适配层，不再依赖 GenericStackInv 持有核心状态。外部仓室仍通过公开接口扩展，不依赖内置 TileEntity。FE 独立 long 存储已用同一无 AEKey 字段的预留生命周期参与并发批结算。
- P1 已接入：资源/能源各输入、输出、混合三方向十阶，共 60 个方块及物品注册；方块实体保存和能力暴露、挖掘保留内容、Java 容器和直接继承 AEBaseGui 的资源/能源界面。十阶默认值来自 PM ItemHatchConfig/ItemIOHatchConfig、FluidHatchConfig 与 EnergyHatchConfig，均可配置。资源输入/输出独立滚动，最多 5 行并随分辨率压缩，隐藏槽禁用且索引保持；能源 GUI 为 176×214，使用原 16×66 纵向条，支持速率/自动标志、Enter 提交和完整 long tooltip。实际 GUI/游戏交互未运行验证。
- 能源职责已接入：EnergyOutput 及公开发现能力、EnergyHatch 双职责、ForgeEnergyPort 三方向，每次操作限额同时约束机器与 Forge，并随设置动态更新；模式/等级/限额/自动标志写入 NBT。能源重载以当前配置容量为准并裁剪保存量，资源仓仍保留保存容量。PM 限额并非累计 tick 预算，自动标志也尚未发现邻居 tick 传输调用链；本项目保留设置/保存，不宣称邻居自动传输已实现。
- 美术已复制：PM item_hatch 与 energy_hatch 十阶三方向原模型 60 份、方块 PNG 144 张、CTM/Bloom 元数据 30 份（其中 6 份还带动画），另有 gui_energy_hatch 的 22 张 PNG，保留 credit 与 bloom 元素。原仓室走标准 Forge 模型与这些元数据，外部 CTM/BLOOM 层环境的实际效果未验证；机器自定义 Gecko/Bloom 管线仍待迁移。
- 本地化已接入：GuiText 实现 AE2S LocalizationEnum，客户端使用 getLocal，容器标题使用 text 组件；禁止 GUI 拼键和裸翻译键调用。最终本机 IDEA MCP 逐文件检查零错误、警告、弱警告，MCP 构建及 Gradle 完整构建均通过，证据见下。
- P2 已迁移组件/需求基础：软依赖拓扑、组件多系统、Pre/Normal/Post 调度与 tick 事件适配；配方按需求职责分组、外部类型绑定及 start/tick/end 整阶段协调结算。RequirementTransaction 上交全部资源/FE 预留，业务 commit 仍可补偿，全部成功才 complete；获取/应用/业务异常完整逆序清理，完成/通知故障不再回滚终态令牌。
- 固定需求已接入：MachineHatches 保存独占执行边界安装的公开仓室绑定；RecipeRequirements 的 ResourceRequirement/EnergyRequirement 经公开配方池执行 start/tick/end，支持多 AEKey 类型、各阶段固定 IO、来源别名去重、单次 FE 限额及显式 voidExcessOutputs。数量定义为单次执行量，执行时使用进程有效并行数并按 PM 的 long 饱和规则缩放。资源需求已接概率/随机产物及模糊动态输入，选择器与需求 overlay 已接入，FE 保留原独立数量语义。原因由 RecipeText 本地化枚举提供。
- 属性与单进程循环已接入：MachineAttributes 将根层/进程 overlay 合为一个实际类，以属性 ID 寻址；AttributeModifier 保留外部运算接口，NumericAttributeModifier 提供 PM 三种运算，支持本地遮盖、实时继承和局部状态快照。RecipeProcess.tick 保留 START 一次成功、完成前后检查、成功 TICK 才推进和 END 阻塞只重试 END；double 绝对进度及 long 成功刻数随资源同阶段可补偿提交。Tick 上下文在获取前冻结，组件 pre/tick/body/post 使用同一 dispatch。此循环已接入多工位宿主与扫描 admission，取消及真实 planned 调度仍待迁移。
- 属性需求已接入：START 添加自己的 modifier，使后续获取看到效果，失败只撤销本需求成功添加的 modifier；END 在全部业务 commit 成功后的 complete 局部清理，避免输出提前失去倍率，也避免整层快照抹去其他需求的业务修改。有限 base/amount/fallback 与每次运算结果严格校验；父属性在多个进程并行执行期间必须保持稳定，读取不写缓存。PM 的 ENERGY_EFFICIENCY 目前只声明，没有可迁移的内置能耗公式，未自造玩法计算。
- 概率与加权输出已接入：ResourceChance 的百分比与可选属性乘数决定资源执行次数，保留 PM 的整倍数加二项抽样、正 long 饱和量及固定数量保守检查。进程固定 ProbabilityTuning，成功刻索引用于 TICK 盐；默认仍为 PM 的 Poisson/固定 Gaussian 表近似，可选择小 n 精确 Bernoulli。临时预检占用释放后才保留真实用量，允许多个零概率检查共享；输出按真实选中 AEKey 与固定输出一起预留，拒绝不会留下部分产物。加权整数抽签按 entry 无放回，全部候选必选时批量缩放；普通随机池仍按执行次数循环，其异步预算尚待 planned 集成。
- 检查点已接入：CheckpointRequirement 可包装任意需求，在 scaled/unscaled 冻结时间线上跨阈值触发完整子 START/TICK/END；time=0 在父 START 执行一次，子 TICK 使用零宽上下文。子事务汇总所有令牌上交外层，拒绝立即停止后续子阶段，业务和资源共同补偿，complete 不能先于其他父需求 commit；临时上下文涵盖子 TICK 获取和全部业务回调，结束或抛错后恢复。进程状态及子选择/锁已接入严格 NBT；工位整组恢复已接入，机器世界生命周期仍待迁移。
- 模糊动态输入与逻辑解析锁已接入：公开 ResourceMatcher/NamedResourceMatcher 直接绑定稳定行为，FuzzyInputGroup 按声明顺序选择，DynamicInputGroup 将所有匹配 key 规范排序后截断；类型、资源 ID 与完整 NBT 参与排序，扩展可提供稳定全序。解析先扣固定及前组保守需求，成功获取后才安装 ResourceInputLocks，失败只撤本次新增选择；零概率也预检并保持身份。完整 RequirementPath 捕获所有返回事务回调，复用子定义不会共享包装器状态。逻辑锁已通过进程 codec 保存恢复；动态全扫描/排序/NBT 成本仍待 planned 预算。
- 选择器已接入：按顺序选第一个 START 成功候选，拒绝候选立即补偿业务和令牌；provisional 选择可供检查点后续子阶段读取，外层失败会撤销，包括 disabled 状态。公开 SelectiveModifier/Context/Descriptor 保留外部扩展，追踪自身进程属性修改，部分异常局部补偿；END 失败保留已生效属性，全部业务成功才清理。终态完成链聚合外部 complete 故障并继续其他回调、令牌及通知，不回滚或重放终态。
- 需求 overlay 已接入：RecipeOverlayApplier/Type 绑定不可变的外部 payload 转换，ResourceRequirementPatch 覆盖四种 IO 和余量策略，EnergyRequirementPatch 覆盖刻输入/输出和余量策略；null 继承、空列表清空、0 为有效值，其余字段保留。RecipeOverlays 按完整路径及 writer 层 ID 保存，写时计算缓存，替换保留原顺序；局部撤层后重算剩余层，清空要求没有未完成句柄。RecipeRequirement 的有效视图保留 canonical definition，获取前统一解析一次，嵌套同样生效，已获取资源事务不重算。显式 pipeline 按声明/重复顺序应用，失败撤自身修改；选择器受追踪 overlay 在 START 失败和 END complete 清理，END 阻塞保留。层及所有权已接入严格进程 codec，外部 applier 必须纯且支持剩余层重组。
- 旧固定资源流程已改为内部 ResourceTransferPlan/Run 迁移材料，不再定义公开 MachineRecipe 或从公开池选择配方。本轮已改用独立 ResourceStorage，保留 4 项守恒/检查点/别名测试；预留、补偿和并发另有专门行为用例，不能用此固定流程代替完整需求处理器。未接入机器且无调用方的固定资源 ResourceTransferNbt 已移除；正式进程/组件持久化采用 RecipeProcessNbt；整组宿主发布使用 MachineWorkSlotsNbt，机器世界恢复仍待迁移。
- 严格进程持久化已接入并验证：RecipeProcessNbt 固定 canonical recipe/revision/graph，保存 seed/tuning、double progress、long successfulTicks、started 与完整结果、本地属性、有序 overlay 层、解析锁、选择器自有效果，以及组件实际装配/依赖和未安装 dependent 的 pending 约束。exact NBT 类型、未知/重复/坏引用全部拒绝；fresh process 先创建所有组件再按依赖恢复，最后跨组件及宿主校验成功才返回。selector 只绑定现有效果，不重跑 START/modifier。open transaction、pending Change 和抛错 tick 拒绝保存。外部 overlay 历史必须提供 validator；嵌套正阈值 checkpoint 仅推断可证明关系，不声称恢复未保存的历史触发区间。
- 多工位宿主及整组恢复已接入并验证：MachineWorkSlots 保留有序定义、隐式 main 与 group 白名单，通过受检查 admission 拒绝 foreign owner、重复/跨 host process 和不匹配组。MAX_CONCURRENT_PROCESSES 只限制新加入；降低上限不驱逐已运行或保存的进程。单一同步 RecipeExecutor 每槽每刻执行一次；Blocked 保留，正常终态释放并保留结构化结果，已保存终态不重跑 executor；抛错 host 禁止重跑与保存。detach 只移交独占进程，不执行 END、不退历史消耗，不冒充取消。MachineWorkSlotsNbt 先完整校验 layout/revision/结果与各进程再替换整组和 attachment，失败保留旧工位；ProcessResultNbt 共用严格结果格式。isCore/restartPolicy 是保留元数据，旧 RESCAN 倒计时本就未接线。
- 扫描 admission 已接入并验证：RecipeCatalog 冻结配方池声明顺序、canonical 身份和工位 group 范围，外部索引使用同一完整 scope 并区分 null/empty/交集。RecipeScanner 排除运行 recipe ID，选择首个空兼容槽，每次最多发布一个 NEW 进程；候选/probe 限额可续查，默认下降穷举，完整 probe 明确单调时才二分，long 防边界溢出且 best 发布前重验。factory、seed/tuning 与 owner/lifecycle 受校验，扫描故障后拒绝继续。
- 扫描事务与局部准备已接入：RecipeStageExecutor.probeStart 共享正式 acquire，成功/拒绝/异常都撤回全部业务和资源/FE token，不提交或推进生命周期。RecipeScanPreparation/RecipeScanProbes 按顺序准备、逆序撤回等价首刻局部状态，实际组件 callback 只执行一次；外部不可逆/世界副作用不能自动投影。setResolvedBase 屏蔽已计入的父 modifier、保留本地 modifier，属性快照及 Version2 NBT 严格记录 InheritModifiers。
- 扫描尚缺固定 END/TICK IO 预筛与内置资源索引；目录、索引、factory/constraint、动态排序/NBT、随机逐次抽样及嵌套需求内部成本仍无完整工作/deadline 预算。START 成功不能证明后续资源可行，局部 preparation 不能自动适配任意组件回调。
- 尚未实现：机器/结构定义和运行接入、JEI 页面、研究与计算库能力、CraftTweaker。
- 暂缓：Applied-Flux 兼容；后续仍使用带 v 前缀的 `v1.0.0:dev` 坐标，当前不拉取该依赖。
- 必须继续迁移：研究/队伍、并行调度、结构/投影/渲染及其他未被明确取消的 PM/PN 功能效果。实现策略仍需按源码审查；正式机器与玩法数值不属于待开发内容。

## 验证记录

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
