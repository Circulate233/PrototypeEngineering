# Prototype Engineering 开发文档

版本：0.8 · 日期：2026-11-07 · 状态：纯库定位；已确认 PN 资源按配方并行缩放、并发按整机占用

本文记录本次对话已确认的需求、旧项目调查结果和实现边界。除用户明确取消或替换的部分外，PM/PN 的功能效果与多线程能力全部保留；重开发修正架构失误，不借简化架构裁减功能。旧源码及调用链用于确认实际行为和目的，旧文档中的愿景不能冒充已实现功能。

项目已开始按确认后的需求实施。本文定义目标与设计约束，不能用作功能已完成的证明；阶段进度和实际验证结果另记于 [implementation-status](implementation-status.md)。

## 1. 项目定位与工程基线

Prototype Engineering 是 PrototypeMachinery 与 PrototypeNetwork 合并重开发的库模组，向模组开发者和整合包作者提供机器、配方、研究、计算及相关通用组件。具体机器、配方内容、研究树、等级与平衡数值由库的使用者定义。本工程不规划正式机器系列或自带科技路线。

两个旧项目的代码均可使用，但每项复用必须先确认库能力需求、实现质量和当前适用性。开发以功能和数据职责为单位重新设计，不以旧类、旧包或旧接口为迁移单位。已确认的内置通用仓室属于可配置的库组件，其默认参数不构成玩法设计。独立使用示例和测试夹具只用于验证 API，不自动注册为主产物中的内容。

| 项目 | 已确认内容 |
|---|---|
| 工程目录 | `F:/new/PrototypeEngineering` |
| 模组名称 | `Prototype Engineering` |
| modid | `prototype_eng` |
| 根包 | `com.circulation.prototype_eng` |
| 对外 API 包 | `com.circulation.prototype_eng.api`，内部按契约职责划分子包 |
| 主类 | `PrototypeEngineering` |
| 环境 | Minecraft 1.12.2、Cleanroom、Java 25；沿用当前 MDG 工程 |
| 代码来源 | `F:/clone/PrototypeMachinery`、`F:/new/PrototypeNetwork` |
| 必需依赖 | `F:/clone/Applied-Energistics-2` 对应的 AE2 Supergiant 分支 |
| 可选依赖 | [Applied-Flux](https://github.com/FormlessDragon/Applied-Flux)，其 FE Key 可作为机器能源输入 |

合并后的机器和研究逻辑直接存在于本工程。PM、PN 不再作为本工程的运行时依赖，也不再通过本地 PM dev Jar 连接两个子系统。

PN 原先作为 PM 附属模组产生的访问限制、注册桥接、重复上下文和跨模组协调，应在合并后重新评估。新工程可直接调整机器本体与研究系统的共同模型，避免为了沿用旧实现继续保留已经失去用途的适配层。

## 2. 已确认的设计决定

| 编号 | 决定 | 对实现的约束 |
|---|---|---|
| R01 | 使用纯 Java 25 | 复用 Kotlin 算法时改写为 Java；不引入 Kotlin、Kotlin 协程或 Forgelin 依赖。 |
| R02 | 按职责设计接口与类名 | 公开扩展职责必须保留接口，外部实现不依赖内置 TileEntity 或内部具体类；值对象、配置、状态与结果可直接使用具体类。实现类按实际用途命名，不使用统一的 `XxxImpl` 命名规则，也不要求接口与实现一一配对。 |
| R03 | 保留独立 API 包 | 对外契约集中在 `com.circulation.prototype_eng.api`，参考 AE2 按职责划分子包；内部实现位于实际功能包，源码目录与 package 对齐，不再建立机械对应的 `impl` 包树。 |
| R04 | AE2 作为 API 基础 | AE2 API 均可按需求使用；物品、流体、库存、GUI 等直接基于该分支能力设计。库提供哪些 ME 联网/合成接口另行核对。 |
| R05 | 使用 AE2 资源类型系统 | 物品、流体及其他支持的资源采用 `AEKey`、`AEKeyType`、`GenericStack` 等；不重建 PMKey 体系。 |
| R06 | 通用仓室保留三种方向 | 保留输入仓、输出仓、输入输出混合仓；每侧缓存均支持 AE2 多资源类型，不再按物品/流体分别制造同类仓室；混合仓的输入、输出缓存独立。 |
| R07 | FE 业务与默认存储独立 | 保留独立能源仓、FE 存储和能力契约；本项目不自行注册 FE 的 AE2 资源类型。已安装 Applied-Flux 时，按 R17 将它提供的 FE Key 接入同一 FE 消费契约。 |
| R08 | GUI 直接基于 AEBaseGui 重实现 | 机器和仓室的具体 GUI 直接继承 `AEBaseGui`，使用标准 Java 与 AE2 容器/控件；不另造本项目的通用 GUI 基类，也不复制 AEBaseGui 或重建 ModularUI 抽象层。 |
| R09 | 不再开发 GUI 编辑器 | 不迁入 Web GUI 编辑器、GUI DSL、编辑器 IR 或为其服务的 Runtime JSON/表达式系统。 |
| R10 | CraftTweaker 最后实现，共用 Java API | 随核心功能建立独立包中的通用 Java API，供 Java 扩展与最终 CT 接入共同使用；脚本桥、ZenClass 和参数转换仍在最后阶段实现，不另造脚本专属领域模型。 |
| R11 | 允许完全不兼容旧项目 | 旧存档、结构 JSON、脚本和公开 API 均无兼容要求；无需建设旧包别名、旧数据解码器或迁移层。 |
| R12 | 优先复用合适实现，再审查修正 | 先核对并复用 PM/PN 适合当前需求的实现，再审查质量、修正架构缺陷；旧代码已有实现、曾经编译通过或长期存在，都不能证明质量合格。逐功能判断复用、重构或重写，重点消除 PN 附属性质造成的多余限制；不能裁减已确认保留的效果。 |
| R13 | 按 Java 25 重新评估实现 | 结合当前 JVM、标准库和线程模型审查旧实现，不限于修改编译版本或将 Kotlin 逐行翻译为 Java；仅为 Java 8 历史环境存在的设计不自动继承。 |
| R14 | 原子字段优先采用 VarHandle | 确需跨线程原子更新时，优先使用宿主对象字段配合共享 VarHandle，减少逐实例 Atomic 包装对象；先明确线程归属与内存语义，再通过针对性验证判断收益。 |
| R15 | 混合仓沿用 PM 独立缓存设计 | 保留输入区、输出区及各自的独立缓存。独立性按输入/输出划分，每侧可容纳 AE2 多种资源类型；不要求再按物品、流体划分缓存。 |
| R16 | 仓室是正式接口扩展点 | 在独立 API 包内定义仓室契约，允许外部实现特殊仓室。机器通过契约识别与访问仓室，不要求继承内置具体类；具体类按实际用途命名。 |
| R17 | Applied-Flux 为可选兼容，当前暂缓 | 输入仓及混合仓输入侧中的 Applied-Flux FE Key，在机器能源输入职责上等同于独立能源输入仓；未安装该附属时，独立 FE 功能正常运行。当前先跳过这项兼容，保留后续接入设计。 |
| R18 | JEI 页面按配方池关联 | 每个配方池对应一个生效的 JEI 配方页面实现实例，页面使用该池的配方数据；页面身份与注册以配方池为单位。 |
| R19 | JEI 提供默认与自定义两种实现 | 页面作为公开接口扩展点；未指定自定义实例时，使用该配方池的默认生成实例，生成思路参考 MMCE。开发者可提供同一接口的自定义实例，自行实现布局与效果，无需继承默认类。 |
| R20 | 纯库定位 | 实现可配置、可扩展的机器/配方/研究/计算能力与通用组件；具体内容和数值由使用方提供，样例独立于主产物。 |
| R21 | 保留事件、组件和配方需求扩展能力 | 迁移 PM 的机器事件/组件调度、配方需求阶段和进程组件扩展；不能把它们缩减为固定 input/output/FE 流程。AE2 资源类型替换不等于删除需求系统；旧实现中的吞异常等问题按新约束修正。 |
| R22 | 保留功能效果和多线程性能能力 | 除明确取消/替换的部分外，不允许功能性缺失；多工位、并行、资源亲和性、planned 预留/提交/补偿等属于迁移要求。必须先确认旧实现及目的，不能盲目重造或把尚未迁移的功能标为可选。 |
| R23 | 独立存储契约，复用 AE2 类型 | 不使用 MEStorage 作为核心存储接口。按配方事务、预留与多线程需求独立设计存储；AEKey/AEKeyType 等类型继续复用，MEStorage 和 Forge 库存仅在对外适配层使用。 |
| R24 | 保留十阶仓室及 PM 美术 | 资源与能源各保留输入、输出、混合三方向和十阶。资源槽数/物品容量沿用 PM ItemHatchConfig，流体每槽容量沿用 FluidHatchConfig，能源容量/操作限额沿用 EnergyHatchConfig；资源美术使用 PM item_hatch 原模型纹理，能源使用 energy_hatch，保留署名与 Bloom 元素。默认值可配置，不新增正式玩法内容。 |
| R25 | 翻译使用 AE2S 枚举契约 | 本项目本地化枚举实现 LocalizationEnum；客户端字符串使用 getLocal，服务器可用标题/文本使用 text 组件。GUI 不拼接翻译键，也不直接以裸翻译键调用翻译函数。 |
| R26 | 逐类真实 IDEA MCP 检查 | 使用本机 MCP 的 get_file_problems（errorsOnly:false），每个类消除错误、警告及弱警告并记录范围/结果。不得操作桌面 IDE；工具未挂载时连接已经启用的本地 MCP、通过 tools/list 查找工具，不修改 IDE 设置。编译、格式检查和人工审查不能代替该证据。 |
| R27 | 数量映射复用 AE2 专用集合 | AEKey 数量使用 AEKey2LongMap；累加/扣减优先 addTo，避免 get 后 put 的重复寻址。非 AEKey 累加也优先 fastutil addTo；保留真实余额的溢出预检、预留/退款约束、顺序和零值清理。 |
| R28 | PN 资源占用与配方并行 | 算力等 PN 资源占用随配方并行量缩放；并发资源除外，同一台机器无论多少并行、多少工作线程，均只占用 1。资源占用与 Java 后台线程是不同职责；多个工位不能重复申请整机并发额度。必须将此规则接到实际配方消费者，不能只以账本存在作为完成证据。 |
| R29 | 研究站材料的阶段效果 | 每次有效推进研究，都完整执行一次材料需求的 START、TICK、END，保留 PN 每次有效推进支付一份材料的频率。不能把整项研究只扣一次 START，也不能继续丢弃物品属性、选择修改器、阶段输入/输出等效果。 |
| R30 | 补齐分级研究存盘 | 研究编码成盘属于旧 PN 也未完成、本项目必须补齐的功能。磁盘具有独立阶级，编码时要求 researchTierForLevel(目标完成等级) ≤ 磁盘阶级，导入时要求磁盘阶级 ≤ 数据库阶级；研究完成等级另存，不与门槛阶级混用。保留普通材料经研究转换成物品的事件扩展，存盘共用物品下载的扣料/扣级/产物保存与交付流程。 |

R11 允许重新设计格式，也允许保留经审查确认适用的算法和数据表达。R12–R14 是实现选择原则，不表示本轮已完成旧代码的全面质量审计或性能测试。

## 3. 旧项目调查

### 3.1 调查口径

本次检查了两个旧项目的说明、主要设计文档、构建依赖及代表性源码，包括机器类型/实例、调度、资源键、仓室、研究身份、资源预算、生命周期与下载事务。AE2 检查了 README、API 文档和资源/库存/GUI 相关源码。

调查依据是本地工作区，包含未提交内容，特别是 PN 的大量新增和修改文件。下列 HEAD 用于定位调查时点，不能代替工作区快照：

| 仓库 | 调查时 HEAD |
|---|---|
| PM | `034541c593ee735312904e635c7ea17db1e376a1` |
| PN | `6a82c8c16ec44adc7f7f3793575fe0d43befd9d1` |
| AE2 | `a47dfe029305eead8750fbaa7c79a538e1a481ba` |

此次未运行旧项目测试或服务器，没有把旧文档记载的结果当作本轮运行结果。

旧代码包含历史 Java 8 设计背景。当前 PM、PN 工作区的构建脚本已经声明 Java 25，这只说明当前编译目标；数据布局、并发协议、API 设计和运行成本仍需逐项检查。文档或注释也可能落后于源码，功能清单不能充当质量认可清单。

### 3.2 已发现的结构问题

| 观察 | 本地证据 | 新项目处理方向 |
|---|---|---|
| PM 主要以 Kotlin 实现 | 主工程 `src/main` 有 609 个 Java/Kotlin 文件，其中 601 个 Kotlin、8 个 Java；不含独立 Modern Backend。 | 先审查功能、数据和算法，再决定 Java 实现；不逐类、逐行翻译。 |
| PN 强制接口与实现配对 | 638 个主源码文件中，336 个位于 `api`、301 个位于 `impl`；270 个文件包含 public interface 声明，262 个实现文件名以 `Impl` 结尾。旧规格第 3 节明确要求该模式。 | 撤销全工程配对规则；逐个判断真实扩展点。文件数量只说明结构规模，不代表所有接口都有问题。 |
| 简单身份对象也允许外部实现 | `ResearchId` 接口的工厂转调 `ResearchIdImpl`；后者还需校验外部实现的 namespace/path/serialized 是否一致。 | 若确有独立研究 ID 的必要，用不可变具体类或 record；无需提供可随意实现的身份接口。 |
| 包的语义和文件定位分散 | PM 的 `api/machine/MachineInstance.kt` 对应 `impl/MachineInstanceImpl.kt`；Kotlin 文件位置省略了完整根包目录。PN 大量类型按 api/impl 机械配对。 | 保留独立 API 包，集中对外契约；内部实现按实际功能归属和用途命名，使调用者能区分公开边界与具体运行逻辑。 |
| 合并前产生大量桥接层 | PN 依赖精确 PM dev Jar，为四类机器注册 factory/context provider，并分别处理 PM 与 PN 的运行时 epoch。 | 在同一工程内设计机器、研究和执行上下文；不照搬用于连接两个模组的协议层。 |
| Java 项目仍依赖 Kotlin ABI | PN 的 `DevelopmentItemInputHatchPredicate` 使用 `kotlin.jvm.functions.Function1`；构建引入 Kotlin stdlib 与 Forgelin。 | 新公共方法和回调使用 Java 类型，不保留 Kotlin ABI。 |
| 自有资源键与 AE2 能力重叠 | PMKey 带有可变 `count`；AE2 已提供无数量的 AEKey，以及独立数量的 GenericStack。 | 统一采用 AE2 类型和数量表达，避免来回转换两套等价模型。 |
| GUI/脚本/编辑器相互牵连 | PM 存在 ModularUI 定义、绑定、Runtime JSON 解析器和 Web 编辑器导出链。 | 依据 R08–R10 退出这套链路，先实现具体 Java GUI。 |
| 原子包装对象的用途需要重审 | PM 的统计代码使用 AtomicLong，生命周期注册与 planned 协调中也存在 AtomicBoolean 状态字段。 | 区分单线程数据、共享计数与任务状态；确有原子更新需求的字段优先评估 VarHandle，不进行全局文本替换。 |
| PM 混合仓已有独立输入/输出缓存 | `ItemIOHatchBlockEntity`、`FluidIOHatchBlockEntity` 分别保存 inputStorage/outputStorage；组合处理器把插入路由到输入侧、抽取路由到输出侧。 | 保留该缓存划分与交互设计，每侧改用 AE2 多类型资源模型；旧实现仍按复用前审查规则处理。 |

### 3.3 功能清单与迁移状态

下表中的能力均须保留，明确取消或替换的项目除外。“实现方式待审查”不等于“是否实现待决定”。任何“已有实现”都不表示质量合格，采用何种实现须经过第 3.5 节审查。

| 旧能力 | 调查结果 | 新项目状态 |
|---|---|---|
| 多方块结构、朝向、匹配与 JSON 加载 | PM 有结构定义、24 向朝向、区块已加载检查、两阶段解析。 | 保留朝向、模板/切片和加载能力；新格式允许不兼容。 |
| 机器类型、实例、配方、工位 | PM 有 ECS 组件/系统、配方阶段与多个工位。 | 保留组件扩展、系统调度及多工位；重划运行时职责。 |
| 配方需求和资源提交 | PM 有 start/tick/end、随机/模糊/选择/检查点、属性与并行需求及提交/回滚逻辑。 | 全部保留对应效果与扩展能力；资源身份改用 AE2，修正事务协议缺陷。 |
| 物品/流体仓室与 PMKey | PM 按资源种类、方向、10 个等级组合；混合仓有独立输入和输出缓存。 | 已确认使用 AE2 多类型资源、保留三方向及双缓存混合仓，并开放仓室接口；十阶槽数/物品容量沿用 ItemHatchConfig，流体每槽容量沿用 FluidHatchConfig。 |
| 能源仓 | PM 有独立大数值能量存储、三方向与十阶 FE 适配。 | 保留三方向、十阶与可配置容量/操作限额；公开能源输入、输出职责。Applied-Flux FE Key 是另一种能源输入来源，接入暂缓。 |
| 机器 GUI | PM 使用 ModularUI、绑定和脚本覆盖。 | 已确认以具体 Java GUI 直接继承 AEBaseGui 重实现，不新增第二套通用 GUI 基类。 |
| GUI 编辑器 | PM 有独立 Web 子项目与 Runtime JSON 接入。 | 已确认不迁入。 |
| CraftTweaker | PM 提供机器、配方、UI 等脚本入口。 | 已确认最后阶段重做；无旧脚本兼容要求。 |
| 结构投影、GUI 结构预览、复杂机器渲染 | PM 有世界投影、切片、BOM、透明/Bloom 管线及缓存。 | 保留；GUI 编辑器取消不影响这些能力。 |
| JEI/HEI 配方展示 | PM 有配方索引、需求渲染和布局扩展；MMCE 有按配方需求自动生成布局的实现。 | 按配方池绑定默认生成或自定义页面接口实例；旧有交互效果须保留，额外新功能另行核对。 |
| 调度与 Modern Backend | PM 有主线程/并发/planned 模式、Java/Kotlin 协程后端及独立 SPI 加速模组。 | 保留并发效果与正确性保证，Kotlin 逻辑改写 Java；独立 Jar/SPI 的组织形式需审查，不能以取消依赖为由删除能力。 |
| 研究、队伍、队列、奖励 | PN 有分级研究、前置/互斥、个人与队伍来源、手动研究和奖励状态。 | 保留为可配置库能力；具体研究内容由使用方定义。 |
| 网络中心、计算机、研究站、数据中心 | PN 已有四类机器声明和资源/研究接线；物理结构仍是开发结构。 | 保留角色、工作许可和资源预算；去除附属模组的重复接线，不注册正式机器。 |
| 数据中心下载、特殊磁盘 | PN 区分数据库下载、物品编码下载、特殊磁盘导入。 | 保留全部路径及可配置消费规则，补齐恢复协议。 |
| 存档、权限与客户端同步 | PN 已有 WorldSavedData、NBT、FTBLib 与 packet 接入。 | 按新规则重做；服务器权威、越权拒绝与重复请求处理需纳入验收。 |

### 3.4 旧实现不能直接视为完成的部分

PN 的当前审计记录了干净世界的专服基础启动，但明确指出真实配方、formed 机器、owner/team 生命周期及下载崩溃恢复尚缺完整实机证据。

审计还记录特殊磁盘导入的未关闭问题：`SpecialDiskInteractionImpl` 先执行 `storage.replace(transaction)`，再 `heldStack.shrink(1)`。已写入数据中心、未完成磁盘消费的失败窗口缺少持久恢复协议。新项目保留磁盘导入，须重新定义可恢复提交，不能仅换类名后复用该路径。

### 3.5 复用前审查与合并后的重设计

每个进入实现阶段的功能先回答以下问题，结论可以直接写入该功能的设计或变更说明，不另建繁重的审批流程：

| 审查方面 | 必须弄清的内容 |
|---|---|
| 实际需求 | 新项目是否需要该功能；需要保留哪些可观察行为，哪些旧规则已经取消。 |
| 原实现质量 | 当前源码是否真正实现目标；已知缺陷、异常路径、保存恢复及测试证据是否覆盖实际行为。 |
| 附属模组限制 | 哪些代码只为绕过 PM 访问限制、衔接注册时序、搬运状态或适配 Kotlin ABI；合并后是否还有实际作用。 |
| 数据与生命周期 | 谁拥有机器、配方、研究、资源和持久化状态；是否存在重复存储、重复索引、重复事件及不必要的上下文转换。 |
| Java 25 适用性 | 是否可以用现有标准库、明确的值类型、简单的线程归属和更紧凑的数据布局代替旧实现；是否存在无必要的包装、装箱或同步。 |
| 验证与成本 | 哪些行为需要测试，哪些性能判断需要测量；维护复杂度是否与保留的功能相称。 |

审查结论按实际情况采用：

- **复用算法或局部实现**：行为和质量符合新需求，必要时只调整类型、归属和接口。
- **重构后复用**：核心逻辑有价值，但需要合并重复状态、改数据模型、重划生命周期或改变并发协议。
- **重新实现**：旧约束、缺陷或复杂度妨碍新设计，按已经确认的行为重新编写。
- **不迁入**：需求已取消，或原实现仅服务于不再存在的跨模组限制。

对 PN 与机器系统的合并，优先检查机器身份、研究门槛、材料与 FE 提交、计算资源结算、事件和保存入口。采用共同模型与明确的状态所有者，减少跨模组桥接。epoch、预留、补偿等机制按真实的一致性边界重新确定，不能仅因合并就删除仍有用途的校验，也不能为了复用旧代码继续维护没有必要的双重运行时。

Forge 的实际加载时序、AE2/Forge 的线程约束、区块卸载和跨存储保存窗口仍是客观约束。应将这些约束与旧附属架构带来的限制分别判断。

旧测试可作为场景与回归用例来源。迁入时按新需求检查断言；不让旧包名、旧接口数量或已经取消的行为成为新实现的验收标准。

## 4. AE2 接入与基础模型

### 4.1 依赖来源

AE2 仓库 README 的 Maven 段明确提供以下方式：

```groovy
repositories {
    maven { url 'https://jitpack.io' }
}

dependencies {
    implementation "com.github.FormlessDragon:Applied-Energistics-2-Supergiant:v1.1.0:dev"
}
```

这是仓库原文。当前工程已在 `gradle/scripts/dependencies.gradle` 接入同一 `v1.1.0:dev` 构件；在 MDG 下使用 `modImplementation`，使模组进入游戏类加载器及模组发现路径。普通 `implementation` 在实际启动中出现了 AE2 核心插件类加载器冲突，因此不能直接照抄配置名。构建与生产重混淆已经通过，业务运行验证状态见实施进度。

PN 旧工程固定的是 `v1.0.9`；AE2 本地 `gradle.properties` 又保留 `mod_version = 1.0.0`。已读取选用发布包的 `mcmod.info`，实际 modid 为 `ae2`、版本为 `1.1.0`，新工程据此设置最低版本约束；Maven 坐标仍保留 `v1.1.0` 前缀。

该分支 modid 为 `ae2`，资源 API 位于 `ae2.api.*`。新代码不使用旧 AE2 的 `appeng.api.*`、`IAEItemStack`、`IAEFluidStack` 或按物品/流体分开的旧 storage channel 设计。

Applied-Flux 是已确认但当前暂缓实施的可选兼容，modid 为 `appflux`，自身依赖 `ae2`。后续按 `com.github.FormlessDragon:Applied-Flux:v1.0.0:dev` 接入并核验构件解析；当前构建不拉取它，也不包含尚未实现的兼容代码。生产包不内嵌该附属，也不将其标为必需依赖。FE Key 的设计约束保留在第 4.4.2 节。

### 4.2 直接复用的能力

| 用途 | 已核实的 AE2 类型/位置 | 新工程使用方向 |
|---|---|---|
| 资源身份 | `AEKey`、`AEItemKey`、`AEFluidKey`、`AEKeyType` | 配方、仓室及资源显示共用同一种身份。 |
| 资源与数量 | `GenericStack(AEKey what, long amount)`、`KeyCounter` | 数量与 key 分离；存储和汇总使用 long。 |
| 多类型槽位适配 | `GenericInternalInventory`、`GenericStackInv` | 仅评估作为 AE2/GUI 适配视图；核心缓存由本库独立设计，真实状态和预留只有一个所有者。 |
| AE2 存储适配 | `MEStorage` | 仅作为对外适配，不进入核心仓室存储契约；适配操作仍受独立存储的预留、方向和失效规则约束。 |
| Forge 能力适配 | `GenericInternalInventoryAdapters`、`AECapabilities.GENERIC_INTERNAL_INV` | 让自有仓室暴露正确的通用库存和物品/流体能力。 |
| 客户端资源显示 | `AEKeyRendering` 及 AE2 现有槽位/控件 | 多类型资源显示沿用 AE2 支持。 |
| 容器与界面 | `AEBaseContainer`、`AEBaseGui` | 具体 Java GUI 直接继承 AEBaseGui，配合 AEBaseContainer；共用功能通过控件组合复用，不增加通用 GUI 中间基类。 |
| ME 节点与服务 | `IManagedGridNode`、`IStorageService`、crafting services | 根据后续联网需求接入，不把“使用 AE2 API”自动解释为“所有机器必须连接 ME 网络”。 |

`GenericStackInv`、`AEBaseContainer` 和 `AEBaseGui` 分别位于 AE2 的 helper/container/client 包，而非全部处于 `ae2.api`。可以按已确认的 AE2 基础依赖方向评估复用；落地时核验它们的构造、GUI 打开、同步和版本约束，不假设只继承一个类就完成了集成。

AE2 文档明确说明：其能力适配注册不会自动改造其他模组的 TileEntity。本项目必须实际暴露自己的能力入口，并验证管道、存储总线和玩家交互。

### 4.3 通用仓室

#### 4.3.1 仓室形态与独立缓存

混合仓沿用 PM 旧设计的输入区、输出区与独立缓存。统一的是 AE2 资源类型和操作模型；输入、输出两侧的内容仍各自保存。每侧缓存均可同时容纳支持的物品、流体等 AE2 资源类型，不再为每种资源另设一套方向缓存。

以下方向表以“外部自动化访问仓室”为视角；机器内部配方使用单独的受控访问入口：

| 仓室 | 缓存组成 | 外部插入 | 外部抽取 | 机器内部职责 |
|---|---|---|---|---|
| 输入仓 | 输入缓存 | 写入输入缓存 | 拒绝 | 从输入缓存消费配方材料。 |
| 输出仓 | 输出缓存 | 拒绝 | 读取输出缓存 | 将配方产物写入输出缓存。 |
| 输入输出混合仓 | 独立输入缓存 + 独立输出缓存 | 仅写入输入缓存 | 仅读取输出缓存 | 从输入侧取料，向输出侧交付产物。 |

内置混合仓遵守以下规则：

- 输入与输出缓存分别保存内容，容量、槽位与过滤状态分别计算。同一种 AEKey 在两侧出现时也有各自的数量，不能因 key 相同就合并账本。
- 外部自动化无法经组合入口抽取输入缓存，也无法向输出缓存插入。组合处理器只是路由与访问视图，不把两侧变成可任意双向存取的同一缓存。
- 配方查找、扣料与产出明确使用对应缓存角色；输出不会因同处一个仓室就自动回流为输入。
- GUI 保留 PM 输入区与输出区分开展示的设计，每区使用 AE2 的多类型资源展示；具体实现仍采用 AE2 GUI/容器和标准 Java。
- 保存、恢复、同步及缓存失效保留输入/输出身份；不能仅保存合并后的资源总量，再猜测恢复到哪一侧。
- 共用代码通过缓存组件、方向策略和访问适配复用；不将“减少类数量”解释为“只允许一个内置具体仓室类”。

玩家在 GUI 中整理库存的权限和操作范围单独定义，不直接把管道限制套用到所有玩家操作。

每侧缓存允许 AE2 已注册且该仓室支持的多个 key 类型。物品和流体是基础验收对象；其他类型受支持范围、容量和能力适配约束，不能宣称任意外部管道自动支持所有类型。

安装 Applied-Flux 时，输入仓和混合仓输入侧支持其 FE Key，并向机器提供能源输入职责。该职责使用输入缓存中的实际余额，仍遵守两侧隔离；输出侧的 FE Key 不回流供能。

仓室容量、槽位、速率等由使用方配置；内置实现保留 PM 十阶默认参数，资源混合仓每侧槽数沿用 ItemIOHatchConfig。多类型资源共用物品仓槽位布局，流体每槽容量沿用 FluidHatchConfig，不再另设流体仓等级槽位布局。参数来源与当前数值见 [hatches.md](hatches.md)。额外过滤、侧面扩展等行为契约仍需按实际用途核对。

#### 4.3.2 仓室公开接口与外部实现

仓室是已确认需要接口化的扩展点。资源仓室契约放在 `com.circulation.prototype_eng.api.storage`，独立能源仓的能源契约放在 `api.energy`。接口按仓室承担的职责设计；具体名称和方法签名随实现设计确定。

仓室契约至少明确：

| 职责 | 约定内容 |
|---|---|
| 发现与接入 | 外部宿主通过公开的能力暴露或提供者注册入口交付仓室契约；结构匹配与机器运行使用同一套发现规则。具体发现入口在实现时确定。 |
| 缓存角色 | 明确提供输入、输出或两者，并能区分对应的操作目标；不要求只有输入能力的仓室伪造输出能力。 |
| 资源访问 | 使用 AE2 key 与 long 数量，说明支持类型、容量、过滤、模拟/实际执行和返回数量语义；核心操作、快照、预留与事务使用本库独立契约，AE2 库存契约只用于适配。 |
| 生命周期与一致性 | 明确可调用线程、可用性、内容变化通知、卸载失效和操作失败语义；实际执行结果必须与已发生的资源变化一致。 |
| 状态访问 | 对外提供约定的操作或视图，不要求暴露内置缓存数组、某个具体容器实现或其私有 NBT 布局。 |

外部实现可以提供特殊过滤、容量策略或不同的存储后端。它不必继承内置仓室 TileEntity，也不必使用 `GenericStackInv`。对外声明的缓存角色、操作及一致性语义必须真实有效；宣称提供标准混合仓行为时，须保持输入/输出隔离。

机器发现、结构识别、配方资源收集和执行应面向这些接口。不得通过判断内置方块类名、强转内置仓室实现或硬编码注册名排除满足契约的外部仓室。公开 API 同时说明宿主接入方式，不能只提供一个无人查询的 Java 接口。

默认仓室实现按实际用途命名，例如 `BufferedResourceHatch`；名称仅作示意，不采用 `ResourceHatchImpl` 式统一命名。缓存数据、配置和值对象仍可使用具体类，不随仓室扩展接口一并机械拆分。

### 4.4 FE 与 Applied-Flux 可选兼容

#### 4.4.1 独立 FE 基础

机器的 FE 需求、消费语义和能源接口保持独立。默认能源仓使用独立 FE 存储，本项目不自行定义 FE key 或注册相应 `AEKeyType`。Applied-Flux 已提供的 FE Key 是另一种受支持的存储来源，可存入通用缓存并使用多类型资源显示，经适配参与同一能源输入契约。

优先复用适合的旧能源实现，再审查大数值、模拟、容量与速率逻辑并修正缺陷。默认实现使用具体 Java 能量存储类，由公开 EnergyInput/EnergyOutput 职责接入机器，并通过 Forge `IEnergyStorage` 暴露三方向能力；外部能源仓可以提供自己的实现。内部 long 余额与 Forge int 参数/返回值须明确转换边界，禁止溢出或截断丢失。PM EnergyStorageImpl 的 maxReceive/maxExtract 限制每次操作，EnergyHatchConfig 的旧 FE/t 注释不能作为累计 tick 预算的证据；如需累计预算必须另行明确契约和实现。PM 自动输入/输出标志存在，但当前源码调查尚未发现邻居 tick 传输调用链，不能由标志推断功能已存在。

FE 消耗仍参与机器工作的一致性判断。一次工作需要物料与 FE 时，应先形成完整决策，避免物料已扣除却因能量不足产生错误推进。

#### 4.4.2 Applied-Flux FE Key 作为能源输入

本轮检查了 Applied-Flux `main` 分支提交 `8f9a6204b357275f47d7e17d8937999e9802f48e` 的 README、依赖配置、key 注册及 FE 存储适配源码，未执行其构建或游戏验证。已核实：

| 项目 | 源码中的定义 |
|---|---|
| FE key | `com.glodblock.github.appflux.common.me.key.FluxKey.of(EnergyType.FE)` |
| key 类型与身份 | `FluxKeyType.TYPE` 的类型 ID 为 `appflux:flux`；FE key 的 ID 为 `appflux:fe`。 |
| 数量单位 | `getAmountPerUnit()` 为 1；FE 存储适配直接使用 FE 数量，1 个 key 数量单位对应 1 FE。 |
| 注册时机 | `AppFlux.init` 注册 `FluxKeyType.TYPE`；兼容接入须考虑附属加载及类型注册时序。 |

已确认的行为与实现约束：

- **来源等效**：输入仓以及混合仓的输入缓存可通过能源输入契约被机器发现，并满足结构定义中的能源输入职责及配方 FE 消耗。仅用该来源也能供能，不要求再放置一只独立能源仓。
- **输入侧限定**：机器取能走受控的输入缓存访问入口，按实际取出的 FE Key 数量结算。混合仓输出缓存、普通输出仓不因存有相同 key 就参与能源输入。
- **单一余额**：FE Key 保存在原多类型缓存内；适配器提供能源访问视图，不额外复制一份独立 FE 余额。相同来源被资源接口和能源接口同时发现时，不重复计量、预留或扣取；与独立能源仓并用时，各真实来源按实际贡献结算。
- **保持独立 FE 需求**：配方声明 FE 消耗即可使用两类来源，无需另写一份 Applied-Flux 专属配方。类型识别使用附属的正式 key 身份及相等性；`FluxKey.of` 会创建对象，不能按引用相等判断，也不能只按显示文字或 `getModId()` 判断。
- **保留操作约束**：遵守来源缓存的容量、过滤、可用量与模拟/执行语义；部分取能只计入实际取出值，跨资源提交按第 4.6 节处理。long 数量不擅自缩放，涉及 Forge int 能力时处理边界。两类仓室的容量、等级和速率政策仍待确定，“等效”不表示容量或吞吐无限。
- **可选加载**：兼容代码放在 `integration.appflux`，检测到附属并满足注册时序后才装配。核心能源 API 不引用 Applied-Flux 具体类；附属缺失时，不加载引用这些类型的兼容类，客户端和专服均可使用独立 FE 系统。
- **外部仓室共用契约**：满足公开仓室契约的外部实现也可接入上述能源视图，不限定为内置 TileEntity 或 `GenericStackInv`。具体适配类按职责命名，不机械增加一整套转换接口。

这项确认覆盖机器能源输入职责。能源输出、通用仓室是否额外向外暴露 Forge FE 能力，以及其他附属能量类型的换算，尚未由此决定；不要求机器必须连接 ME 网络才能消费已存入输入缓存的 FE Key。

### 4.5 GUI：直接基于 AEBaseGui

机器和仓室界面以具体 Java 类直接继承 `ae2.client.gui.AEBaseGui` 重实现，配合 `AEBaseContainer` 组织服务端容器、槽位和交互。AEBaseGui 已承担的界面生命周期、控件管理与交互能力直接复用；不另建 `PrototypeBaseGui`、`BaseMachineGui` 等覆盖全项目的中间基类，也不复制一份 AEBaseGui 源码作为新框架。

确有重复的槽位布局、按钮组和资源显示可通过普通辅助类或组合控件复用；不要求这些类都接口化。具体界面仍由标准 Java 编写。客户端绘制放在客户端包，服务端容器验证和真实状态修改不依赖客户端类。

不建设新的控件定义语言、通用绑定表达式、JSON 界面运行时或网页编辑器。未来 CT 接入也不恢复旧 GUI DSL。

首个 GUI 验证应覆盖：正确打开自有仓室、混合仓输入/输出分区、每区的物品与流体多类型显示、槽位点击和 Shift-click 的正确目标缓存、同步大数量、关闭/重开、拒绝越权操作，以及专服不加载客户端类。

JEI 配方页面使用第 4.7 节的页面接口与 JEI 绘制生命周期，不是另一个机器 GUI 基类，也不要求页面继承 AEBaseGui。

### 4.6 资源操作的一致性

AE2 的 `SIMULATE/MODULATE` 表示模拟和实际执行，不等于跨多个仓室、FE 和研究状态的原子事务。`beginBatch/endBatch` 用于变更通知批处理，也不提供事务回滚保证。

因此需要为新机器定义清晰的检查、实际扣取、输出和失败处理顺序。优先在本项目拥有的库存内建立简单、可验证的提交边界；涉及外部 ME 库存或跨持久化位置时，再针对实际操作设计预留、缓冲或恢复机制。具体方案应由真实使用场景决定，不先复制旧 planned 协议的全部层次。

资源操作必须保留来源仓室及缓存角色。外部仓室按公开声明的能力参与操作，执行器不得依赖内置缓存实现的私有回滚捷径，也不得把外部实现的模拟成功当作实际扣取保证。

Applied-Flux FE Key 的资源视图与能源视图共享同一余额。一次工作若从多个入口引用同一份 FE，须统一核算可用量与已提交量，避免把适配视图误当作另一份库存。

### 4.7 JEI：每个配方池一个页面实现

#### 4.7.1 关联单位与实例选择

每个配方池是 JEI 页面关联与配方注册的单位。这里的“页面实现”指该池对应的配方分类/展示实现，在 JEI 中承载该池的多条配方；不表示一个池只能显示一条配方，也不要求每条配方各建一个页面实现对象。

| 配方池的选择 | 生效的页面实例 | 行为 |
|---|---|---|
| 未指定自定义实现 | 为该配方池创建的默认生成实例 | 读取该池配方，自动生成输入/输出布局及说明。 |
| 指定自定义实现 | 开发者提供的页面接口实例 | 使用同一配方池的数据，由开发者自行实现布局、绘制及展示交互。 |

两种实例实现同一个公开接口，默认实例不是自定义实例必须继承的父类。每个池最终只绑定一个生效实例；自定义实例替代该池的默认绑定，不额外注册一份重复分类。注册阶段应明确绑定时序并拒绝冲突的重复绑定，不按不确定的加载顺序静默覆盖。

分类标识以稳定的配方池 ID 为依据。机器的展示入口引用其关联配方池；同一池被多个入口引用时仍使用同一个分类与绑定实例。不会照搬 MMCE 按机器创建分类的归属关系，也不引入另一个与实际配方池脱节的 JEI 配方目录。

#### 4.7.2 默认生成行为及 MMCE 参考

已检查 `F:/clone/ModularMachinery-Community-Edition` 本地工作区的 `CategoryDynamicRecipe`、`DynamicRecipeWrapper`、`RecipeLayoutPart`、`JEIComponentEnergy` 与 `ModIntegrationJEI`。调查时 HEAD 为 `e1d80aeaeaff55ef6482f9a9734fa1d5d8d16082`；本轮未运行该工程。其默认生成思路可作为本项目默认实例的参考：

- 扫描同组可展示配方，按输入/输出及展示内容统计槽位需求，以组内所需的最大数量形成共用布局。
- 根据展示组件的大小、间距、顺序和每行数量排布输入区与输出区，在两区之间放置加工箭头。
- 由当前配方填充资源、数量、耗时、能量与说明；提供悬停提示及相应的 JEI ingredient 数据。

新默认实例以配方池替换上述“同组”的机器归属。保留自动生成思路，按 AE2 资源模型、独立 FE 需求及 Java 25 重新组织实现；不直接迁入 MMCE 的资源类、需求类和 GUI 继承体系。MMCE 中按 Java 类分组、以 Long 特判能量的处理不作为新 API 约束。

默认行为应在开发者只提供配方池和配方数据时即可使用，无需手工指定每个槽位坐标。至少覆盖输入/输出资源分区、数量、耗时和已有 FE 参数；布局顺序应稳定，数量不同的配方共用分类时不发生槽位错配或数据残留。页面超出显示区域时需有明确布局处理，不能静默裁掉配方必要信息。

资源身份与真实数量来自 AEKey/GenericStack 等已确认模型，独立 FE 需求作为能量信息展示。Applied-Flux 兼容改变的是供能来源，不要求给同一 FE 消耗重复生成一条附属专用需求。AE2 已提供 `ae2.api.integrations.hei.IngredientConverter` 与 `IngredientConverters`，优先核验并复用可用的资源转换入口；绘制资源图标与支持 JEI 配方/用途查询分别验证，不能只因 AE2 能显示一种 key 就宣称 JEI 已完整支持它。

#### 4.7.3 页面接口与自定义实现

建议公开契约位于 `com.circulation.prototype_eng.api.client.jei`，名称例如 `RecipePoolJeiPage`；默认具体类例如 `AutoLayoutRecipePage`，位于 `integration.jei`。名称仅作示意，最终按实际职责命名，不使用 `RecipePoolJeiPageImpl` 这种统一后缀。

页面契约覆盖对应配方池的数据访问、分类展示信息、布局、绘制、资源槽位与提示交互。与 JEI 原生 `IRecipeCategory`、配方包装及 ingredient API 的衔接在实现时确定；能直接使用的原生契约无需逐个再包装一层接口。

自定义实现可以完全自行安排槽位、背景、说明、动画和交互效果；它不是只有贴图、颜色或坐标可改的默认模板。开发者通过公开的客户端注册入口，用配方池标识关联自定义接口实例；不需要继承默认实现、修改内置注册表或接入 GUI 编辑器。默认与自定义路径均读取同一配方定义，并保持输入/输出、数量和条件含义一致，不修改机器执行规则。

关联在逻辑上属于配方池，在运行时由客户端持有“配方池 ID → 页面接口实例”的绑定。公共配方池定义保留服务端可用的数据，不直接持有 GUI 对象、JEI 类型或客户端类字面量；页面实例在客户端及 JEI 注册阶段创建。JEI 专用公开 API 可以使用相应客户端依赖类型，但服务端配方加载和普通 API 初始化不得触发这些类型加载。

#### 4.7.4 展示范围

默认生成、自定义接口实例及配方池关联均为已确认需求，随配方池功能实现，不等待 CraftTweaker。自定义页面用 Java 编写，CT 最后接入时仍使用同一配方池和配方注册 API，不扩展为页面 DSL。

文中“JEI”指配方浏览集成；当前环境的 HEI 与 `mezz.jei.api` 兼容性、具体依赖版本需在接入时核验。配方转移/一键填充、额外书签操作、结构预览页及其他特殊联动尚未因此确认。

## 5. 代码组织与 API 边界

### 5.1 包结构

独立 `api` 包及职责命名方式已经确认。以下子包是建议的职责边界，随实际功能逐步建立，不要求提前创建空包或占位类：

```text
com.circulation.prototype_eng
├── PrototypeEngineering        模组入口
├── api                         对外 Java 契约，供扩展与最终 CT 接入使用
│   ├── machine                 公开机器定义、访问契约和生命周期扩展
│   ├── structure               公开结构定义及匹配扩展契约
│   ├── recipe                  配方池、配方定义、条件、注册和查询契约
│   ├── storage                 资源仓室接口、输入/输出角色与接入契约，使用 AE2 类型
│   ├── energy                  能源仓及能源输入/输出职责、独立 FE 契约
│   ├── research                保留的研究定义、注册和访问契约
│   ├── computing               保留的计算资源与工作许可契约
│   └── client.jei              配方池页面接口及客户端绑定入口
├── machine                     机器实例、方块实体与生命周期实现
├── structure                   结构解析、方向和匹配实现
├── recipe                      配方池目录、具体条件、运行进度与提交
├── storage                     内置多类型仓室、独立缓存、方向路由和能力适配
├── energy                      独立 FE 存储、能源仓和能量需求
├── research                    研究进度、队列与策略执行
├── team                        队伍身份、归属与权限
├── computing                   保留的计算资源、设备预算和工作许可
├── container                   服务端容器及同步交互
├── client.gui                  直接继承 AEBaseGui 的具体 Java GUI
├── client.render               按确定范围实现的渲染和投影
├── sync                        本模组网络消息与同步
└── integration                 确有需要的外部模组适配
    ├── appflux                 可选加载，将输入缓存中的 FE Key 接入能源输入契约
    ├── jei                     配方池默认页面、JEI 注册和配方展示适配
    └── crafttweaker            最后阶段添加，转换脚本参数并调用 api
```

公开定义和值类型放在所属 `api.*` 包，内部功能包直接使用它们，不再复制一份同名内部定义。内部状态、调度细节、NBT 实现和具体 TileEntity 留在功能包。

启动生命周期通过侧代理组织：主类只将 Forge 生命周期委托给 `CommonProxy`；双端通用的配置、能力、注册及服务器启动/停止逻辑在 `CommonProxy`。`ClientProxy` 继承对应方法，先调用 `super`，再增加客户端初始化。客户端模型事件由 `ClientProxy.preInit` 注册，辅助模型类不再自行注册另一份订阅。

“计算网络”与“网络消息”通过 `computing` 和 `sync` 区分，避免同一个 network 包同时承载两种含义。AE2 是基础依赖，可在 API 签名与内部实现中直接使用；不要求所有 AE2 调用都经过一个等价的包装接口层。

### 5.2 契约职责与实际类名

`api` 包既可以包含接口，也可以包含公开具体类、不可变定义、枚举和必要的构建入口。是否使用接口取决于它承担的调用或扩展职责；单一实现可以有必要的公开契约，普通数据也不必因进入 API 包而改成接口。

接口名称说明调用者可以执行什么、观察什么或扩展什么；具体类名称说明实际业务用途、数据来源或执行方式。同一个类可实现多个职责接口，一个接口也可由不同的实际类实现，不要求一一对应。

以下为命名示意，不是预先决定要新增的类型：

| 契约或公开类型 | 具体类/使用方式 | 职责划分 |
|---|---|---|
| `api.machine.MachineView` | `machine.MachineInstance` 提供该视图 | 对外只暴露约定的机器观察能力，内部实例负责完整生命周期。 |
| `api.recipe.RecipeCondition` | `recipe.condition.ResearchCondition` 等具体条件 | 契约负责条件判断，类名说明判断的业务条件。 |
| `api.recipe.RecipeRegistry` | `recipe.MachineRecipeCatalog` | 对外提供注册/查询契约，具体目录负责验证、索引与注册期冻结。 |
| `api.client.jei.RecipePoolJeiPage` | `integration.jei.AutoLayoutRecipePage` 或开发者自定义实例 | 每个配方池绑定一个页面接口实例，默认生成和自定义实现共用注册路径。 |
| `api.machine.MachineDefinition` | 直接作为公开不可变具体类或 record 使用 | 数据定义自身已经有明确职责，不再拆成接口与实现。 |
| `api.storage.ResourceHatch` | `storage.BufferedResourceHatch` 或外部自定义仓室 | 仓室接口负责输入/输出角色及机器接入，具体类决定缓存实现与特殊行为。 |
| `api.energy.EnergyInput` / `EnergyOutput` / `EnergyHatch` | 独立能源仓；Applied-Flux 输入缓存通过适配提供能源输入职责 | 保留可单独实现的输入/输出接口，EnergyHatch 可承担两者；具体存储来源可不同，核心契约不依赖 Applied-Flux 类型。 |
| 本库独立存储契约 | 内置缓存与外部后端共同保证资源操作、快照、预留和事务 | 资源使用 AEKey/AEKeyType；GenericInternalInventory/MEStorage 位于对外适配层，不定义核心行为。 |

类型与边界规则：

- API 按 machine、recipe、research 等契约职责分包，避免依实现类位置或每个方法拆包。
- 仓室属于已经确认的接口扩展边界；内部机器逻辑也通过同一契约接入，外部实现具有与内置实现相同的调用路径。
- 配方池 JEI 页面也是已确认的接口扩展边界；默认实现与开发者自定义实例具有相同的绑定地位，自定义实现不依赖默认具体类。
- API 文档说明可调用阶段、线程要求、可变性、权限及失败语义；不向调用者暴露内部缓存或持久化状态。
- 公共方法的签名使用本项目 API 类型、Java 类型及明确依赖的 AE2/Forge 类型，不要求调用者引用内部实现类。
- `api.client.jei` 是明确的客户端边界，可使用 JEI 原生契约；`api.recipe` 等服务端可用包不依赖该客户端包。
- Applied-Flux 等可选附属的具体类型只在对应兼容包使用，不进入核心 API 签名或无条件初始化路径。
- 内部实现依赖并实现 API 契约；API 声明不反向依赖具体运行类。服务装配在模组初始化处完成，公开入口只交付约定的契约。
- API 需要公开构造数据时，可直接提供具体类构造器或必要的 builder；不为每个类型添加 Factory、Registry、Context 全套结构。
- 对外需要的注册入口置于对应 `api.*` 包，具体存储、索引和冻结逻辑留在功能包；内部专用注册表无需提升为 API。
- 不把所有 public 类都当作稳定扩展 API，也不建立聚合所有内部服务的巨大静态 API 门面。
- 研究条件、材料与机器执行使用同一套公开定义和新运行模型，去掉专为 PM/PN 跨模组连接而存在的重复 DTO 和桥接注册。

组件拓扑、事件与生命周期、多工位和全部配方扩展效果属于必须保留的能力。可以合并只有一个实现的容器和数据类，但外部实现组件、系统及配方需求的入口必须保留。

### 5.3 Java API 与 CraftTweaker

API 在核心功能开发时同步建立和验证，CT 的最后阶段负责脚本类型转换、调用适配和脚本错误报告。这样 CT 可直接使用已经稳定的机器、配方与研究契约，不需要再进入内部包寻找注册点。

建议的调用关系：

```text
Java 扩展 ─────────────────────┐
                             ├── api 中的定义与契约 ── 已绑定的具体业务实现
CraftTweaker 参数转换（最后）──┘
```

实现约束：

- `integration.crafttweaker` 通过本项目 API 与其基础依赖交互，不直接操作内部目录、运行状态或持久化对象。
- Java 与 CT 共用定义校验、注册阶段和错误语义，脚本层不另写一套配方/研究业务逻辑。
- API 包不依赖 CraftTweaker 或 ZenScript 类型，也不因未来 CT 接入而提前建立脚本桥。
- 核心功能验收包含通过公开 Java API 注册和查询实际定义的使用路径；CT 接入时复用这些路径。
- CT 注册的配方进入同一配方池，由该池已有的默认或自定义页面实例展示，不再建立一套脚本专属 JEI 分类。
- API 面向本项目已确定的功能逐步完善；其存在不扩大尚未确认的 addon 功能范围。

### 5.4 线程和生命周期

保留旧实现的多线程能力；先审查线程归属与共享资源，再选择同步工具：

- 世界、TileEntity、玩家库存、队伍状态及持久化变更有明确的服务端执行线程。单线程持有的状态使用普通字段和合适的集合，不为其额外添加原子包装对象。
- 后台计算只处理受控快照，结果提交前校验目标仍存在且输入有效。
- 不把游戏内“计算线程/工作许可”当作 JVM 线程数量。
- 区块卸载、机器破坏、结构失效、断网或队伍变化后，停止不再合法的工作，清理临时占用。
- 资源不足、权限拒绝等正常情况返回可展示的原因；非法注册、数据损坏或内部状态错误提供带上下文的诊断。

保留双 barrier 所保障的 prepare/resolve 完成边界、source affinity 的共享源串行化、取消/超时/卸载后的迟到任务隔离，以及资源预留与补偿。多后端和独立 Modern Backend 可以重组实现，但不得用单线程执行器替代这些能力。普通容器并不因一个 volatile 字段就具备线程安全；结构修改与调度快照发布必须有真实的所有权/屏障协议。

### 5.5 VarHandle 与 Java 25 实现原则

确实需要字段级原子操作时，优先采用宿主对象中的 primitive/引用字段，以及在类初始化时查找并由 `private static final VarHandle` 共享的访问句柄。这样可避免为每个宿主实例的每个原子字段分别分配 Atomic 包装对象；不为每个实例创建句柄，也不再包一层自制原子对象。

| 场景 | 优先考虑的实现 |
|---|---|
| 单线程持有的数值与状态 | 普通字段；无需原子操作。 |
| 跨线程传递只读结果 | 不可变快照与明确的安全发布方式。 |
| 只需可见性、无复合更新 | 满足协议的 volatile 字段或 VarHandle 访问模式，选择更直接的表达。 |
| 同一字段需要 CAS 或原子读改写 | 优先使用 VarHandle 操作对象内字段。 |
| 多字段或多个资源必须共同变化 | 单一线程所有权、合适的锁或整体状态提交协议；逐字段 CAS 不能替代业务原子性。 |
| 高争用的统计或框架专用场景 | 对比适用的 JDK 工具；使用 Atomic 类、LongAdder 等需有明确语义或测量依据，不机械套用一种方案。 |

内存语义与封装要求：

- 为共享字段说明读写线程、状态转换和发布关系，禁止只为减少对象就取消必要的同步。
- VarHandle 的读写语义由所选访问模式决定。普通 get/set、acquire/release、volatile 访问和原子读改写不能随意替换，也不能仅凭字段声明推断访问语义。
- 从 Atomic 类改写时先保持原有必要的可见性和原子操作保证；采用较弱访问模式时，应说明正确性依据并验证相应并发场景。
- CAS 重试、任务取消、回调只执行一次等场景需检查竞争和失败路径，避免在可重试操作中重复扣料、发放奖励或调用外部副作用。
- VarHandle 属于内部实现细节；公开 API 暴露业务操作、只读值或快照，不把访问句柄或可变原子容器交给调用者。

内存收益主要取决于被消除的包装对象数量、宿主实例数量与实际 JVM 布局。少量全局计数器与大量逐任务状态的收益可能不同；VarHandle 不自动保证更低延迟或更高吞吐。针对实际热点比较分配量、堆占用、GC、争用及延迟，必要时使用对象布局分析、微基准与并发压力测试，再结合游戏负载验收。

其他 Java 25 改造也按相同原则判断：采用适合实际数据和执行模型的语言/标准库能力。虚拟线程不自动提升 CPU 密集型计算吞吐；Vector API、对象池、无锁结构和独立加速后端均需具体场景支持，不以“用了新特性”作为质量结论。

## 6. 研究与计算系统：保留能力与实现策略

PN 已实现的库能力、数据契约和策略效果均须保留，不要求用户先设计研究树或设备配方。下表只核对合并后的策略配置与实现方式，不重新决定是否保留功能：

| 主题 | 旧规则摘要 | 需要确认 |
|---|---|---|
| 队伍来源 | FTBLib/FTB Utilities；机器持有 owner UUID，按当前队伍归属。 | 是否继续依赖 FTB 队伍；跨维度、离线和换队行为。 |
| 研究所有权 | 一部分来自成员个人等级最大值，另一部分直属队伍；黑名单/前置决定有效等级。 | 保留双来源、个人携带、互斥与黑名单语义，重审换队和失效计算。 |
| 研究推进 | 等级阶段、可循环研究、tier 0 手动研究、多研究站材料提交和多资源累计。 | 保留全部推进方式及公式能力，具体配置由使用方提供。 |
| 计算资源 | 计算机组件贡献资源，中心提供工作许可，机器工位消费数值预算。 | 资源种类和单位的定义接口、预算共享范围、并行与公平性策略。 |
| 数据中心 | 每队唯一活动中心/数据中心；设备研究门槛查询活动数据中心。 | 是否仍要求唯一活动实例；研究完成后是否必须显式写入数据库。 |
| 下载 | 数据库下载不消费等级；物品编码下载按规则消费个人或队伍等级。 | 保留等级消费和物品化研究，明确可恢复提交。 |
| 特殊磁盘 | 独立导入特殊研究并消费磁盘。 | 保留，修正持久提交与磁盘消费之间的失败窗口。 |
| 奖励 | 逐玩家、逐研究等级领取资格和恢复状态。 | 保留领取资格、奖励和恢复；种类及交互由使用方定义。 |
| ME 网络 | 当前 PN 源码中检索到的是 AE2 依赖声明/检查，未发现 ae2/appeng API import。 | 库提供哪些联网/自动合成接入点，队伍边界与 ME 安全权限如何组合。 |

研究与 FE、物料库存不是同一种东西。未确定前，不把研究等级、计算预算或工作许可一并注册为 AE2 key 类型。

## 7. 建议的开发顺序与验收

阶段内容随第 6 节和第 8 节的库能力决定更新，实际进度另记。下文机器/研究样例均为独立验收用例，不是本项目的正式内容规划。

每个阶段开始前，对所涉及的旧功能执行第 3.5 节审查，简要记录“保留行为、原有问题、新设计、复用方式、验证依据”。实现与验收围绕新需求组织，不以迁入旧文件的数量衡量进度。

| 阶段 | 交付内容 | 通过标准 |
|---|---|---|
| P0 需求与 AE2 基线 | 确认保留功能并审查对应旧实现；确定合并后的职责、独立 API 包与 Java 25 基线；接入固定 AE2 构件。 | 复用决策有行为和质量依据；依赖可重复解析，dev/生产 Jar 名称与版本正确；无 PM、PN、Kotlin、Forgelin 依赖。当前以构建检查推进，不继续自行启动游戏进程。 |
| P1 仓室与 FE | 公开仓室接口、三种通用仓室、双缓存混合仓、独立能源仓与 Java GUI；Applied-Flux 暂缓。 | 每侧支持多类型资源；输入/输出隔离及外部路由正确；NBT/同步保留两侧身份；外部实现可经公开入口被发现；独立 FE 正常运行。重启和游戏交互证据待后续运行验证补齐。 |
| P2 机器库与配方池 | 结构/机器定义与执行 API、配方池及资源/FE 执行核心、JEI 默认及自定义页面路径。 | 独立使用示例仅经公开 API 定义多方块、配方池和配方，验证形成、扣料、耗能、产出、暂停、破坏和卸载；外部仓室可实际参与；不重复扣取、产出或丢失；JEI 两条路径通过下述验收。主产物不注册样例机器和配方。 |
| P3 研究与计算库 | 按确认的能力边界实现研究、队伍、资源预算、设备与数据中心等定义接口及通用执行逻辑。 | 独立示例经公开 API 定义研究条件并影响机器执行；预算与权限由服务器裁决；换队/重启遵守库的策略契约。具体研究树和数值由示例/使用方提供。 |
| P4 完成功能与运行验证 | 补齐投影、渲染、JEI 附加交互和复杂配方等保留能力，验证多线程调度与内存优化；新 ME 联网/合成能力另行设计。 | 按迁移矩阵逐项取得行为证据；下载/磁盘/奖励覆盖失败恢复；并发同时有正确性与性能依据。 |
| P5 CraftTweaker（最后） | 基于独立 API 包中已稳定的 Java 契约增加脚本参数转换、注册与配置。 | 不依赖内部实现类；Java 与脚本入口产生一致的定义和校验结果；无旧脚本兼容要求，不新增 GUI 编辑器/DSL。 |

测试用于证明真实行为：重点包括多类型仓室方向、FE 数值边界、资源提交、结构卸载、权限和保存恢复。格式检查、编译通过或服务器显示 Done，都不能替代这些行为验收。

混合仓应验证同一 AEKey 在输入、输出两侧具有独立数量，单侧满载不占用另一侧容量；组合入口不会抽取输入或写入输出；保存与 GUI 同步不会混淆缓存。外部仓室应覆盖公开发现、特殊过滤、模拟/执行结果、机器取料/产出及卸载失效，不能仅以“编译时实现了接口”作为完成依据。

恢复 Applied-Flux 兼容开发时，验收覆盖安装/未安装、输入仓和混合仓输入侧供能、只有输出侧存有 FE 时无法供能、FE 与其他资源共存、与独立能源仓并用、共享来源去重、能量不足及部分提取、long/int 边界、保存同步，以及保持附属安装时的重启恢复。仅能识别 FE Key 或显示余额不算完成，须在没有独立能源仓时实际形成并运行机器；当前不以这项暂缓兼容阻塞基础功能开发。

GUI 验收检查具体界面直接复用 AEBaseGui，公共代码通过控件组合而非新增通用基类实现。JEI 验收至少使用两个配方池：一个只提供配方数据即可默认生成；另一个通过公开入口绑定不继承默认类的自定义实例，并呈现实际不同的布局和绘制。检查每池只有一个生效分类、配方归属正确、多条配方切换不串数据、多类型输入/输出及 FE 信息准确、可支持 ingredient 的配方/用途查询有效、大数量未截断、自定义绑定不影响其他池，以及专服不加载 JEI 页面类。

性能目标待确认机器数量、并行工位数、结构规模、队伍人数、ME 库存规模及基准硬件后填写。暂不承诺没有测试依据的 TPS、吞吐或延迟数字。

## 8. 下一轮需要决定的事项

当前已确认事项不重复提问。其余按影响顺序讨论：

| 编号 | 待决定事项 | 当前建议/状态 |
|---|---|---|
| D01 | PN 研究、下载、特殊磁盘、奖励如何合并？ | 保留已有功能效果；核对定义、查询、执行与恢复契约，具体内容由使用方提供。 |
| D02 | 是否继续使用 FTB 队伍；计算网络和 ME 网络如何关联？ | AE2 作为 API 基础已确认；物理连接及权限规则尚未确认。 |
| D03 | 多工位、并行配方与后台计算的实现审查 | 均须迁移；先确认旧实现目的与同步保证，确有字段原子更新需要时优先采用 VarHandle。 |
| D04 | Modern Backend 独立模组形态是否取消？ | 建议经正确性和性能审查后，将确有价值的算法并入 Java 25 主工程；独立 SPI/双 Jar 形态待确认。 |
| D05 | 结构的 24 向、模板/切片、投影和复杂渲染如何迁移？ | 保留效果；重审缓存、线程、释放和资源所有权，GUI 编辑器除外。 |
| D06 | 仓室/能源仓还需哪些过滤和侧面扩展？ | 三方向、十阶默认值、双缓存混合仓、每侧多类型、独立 FE 及 Applied-Flux FE Key 输入等效已确认；参数可配置，额外行为契约待核对。 |
| D07 | 公开扩展职责如何划分？ | 保留组件、系统、需求、进程和调度扩展，以及仓室和 JEI 页面接口；允许 API 不兼容，不能删除对应能力。 |
| D08 | 库能力如何验收？ | 使用独立示例与必要行为测试覆盖公开 API、多类型物料、FE、研究条件和外部实现，不以设计正式机器或玩法数值为前提。 |
| D09 | JEI 配方转移、书签操作和结构预览联动如何迁移？ | 按池绑定默认或自定义实例；逐项核对旧有交互并保留，额外新功能另行决定，接入时核验 JEI/HEI API 版本。 |

## 9. 调查资料与源码入口

以下为本次实际检查的本地资料及 Applied-Flux 固定提交的源码入口；后续仓库版本变化可能使调查结论变化。

| 资料 | 位置 |
|---|---|
| PM 功能与结构概览 | [README](F:/clone/PrototypeMachinery/README.md)、[PROJECT_OVERVIEW](F:/clone/PrototypeMachinery/PROJECT_OVERVIEW.md) |
| PM 机器与配方 | [MachineLogic](F:/clone/PrototypeMachinery/docs/MachineLogic.md)、[MachineType](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/MachineType.kt)、[MachineInstanceImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt) |
| PM 仓室与资源 | [Hatches](F:/clone/PrototypeMachinery/docs/Hatches.md)、[PMKey](F:/clone/PrototypeMachinery/src/main/kotlin/api/key/PMKey.kt)、[Storage](F:/clone/PrototypeMachinery/docs/Storage.md) |
| PM 混合仓独立缓存与路由 | [ItemIOHatchBlockEntity](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/item/ItemIOHatchBlockEntity.kt)、[ItemIOHatchCombinedHandler](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/item/ItemIOHatchCombinedHandler.kt)、[FluidIOHatchCombinedHandler](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/fluid/FluidIOHatchCombinedHandler.kt) |
| PM 十阶默认值与能源操作限额 | [ItemHatchConfig](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/item/ItemHatchConfig.kt)、[FluidHatchConfig](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/fluid/FluidHatchConfig.kt)、[EnergyHatchConfig](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/energy/EnergyHatchConfig.kt)、[EnergyStorageImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/storage/EnergyStorageImpl.kt) |
| PM GUI 与后端 | [MachineUiEditorRuntime](F:/clone/PrototypeMachinery/docs/MachineUiEditorRuntime.md)、[ModernBackend](F:/clone/PrototypeMachinery/docs/ModernBackend.md) |
| PN 旧规格与现状 | [development-spec](F:/new/PrototypeNetwork/docs/development-spec.md)、[implementation-audit](F:/new/PrototypeNetwork/docs/implementation-audit.md) |
| PN 接口拆分样例 | [ResearchId](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/api/research/ResearchId.java)、[ResearchIdImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/research/ResearchIdImpl.java) |
| PN 磁盘提交 | [SpecialDiskInteractionImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/disk/SpecialDiskInteractionImpl.java) |
| AE2 Maven 与分支说明 | [README](F:/clone/Applied-Energistics-2/README.md) |
| AE2 类型与节点文档 | [API](F:/clone/Applied-Energistics-2/API.md) |
| AE2 通用库存 | [GenericInternalInventory](F:/clone/Applied-Energistics-2/src/main/java/ae2/api/behaviors/GenericInternalInventory.java)、[GenericStackInv](F:/clone/Applied-Energistics-2/src/main/java/ae2/helpers/externalstorage/GenericStackInv.java)、[MEStorage](F:/clone/Applied-Energistics-2/src/main/java/ae2/api/storage/MEStorage.java) |
| AE2 GUI 基础 | [AEBaseContainer](F:/clone/Applied-Energistics-2/src/main/java/ae2/container/AEBaseContainer.java)、[AEBaseGui](F:/clone/Applied-Energistics-2/src/main/java/ae2/client/gui/AEBaseGui.java) |
| AE2 HEI 资源转换 | [IngredientConverter](F:/clone/Applied-Energistics-2/src/main/java/ae2/api/integrations/hei/IngredientConverter.java)、[IngredientConverters](F:/clone/Applied-Energistics-2/src/main/java/ae2/api/integrations/hei/IngredientConverters.java) |
| MMCE JEI 自动生成 | [CategoryDynamicRecipe](F:/clone/ModularMachinery-Community-Edition/src/main/java/hellfirepvp/modularmachinery/common/integration/recipe/CategoryDynamicRecipe.java)、[DynamicRecipeWrapper](F:/clone/ModularMachinery-Community-Edition/src/main/java/hellfirepvp/modularmachinery/common/integration/recipe/DynamicRecipeWrapper.java)、[RecipeLayoutPart](F:/clone/ModularMachinery-Community-Edition/src/main/java/hellfirepvp/modularmachinery/common/integration/recipe/RecipeLayoutPart.java) |
| MMCE JEI 注册与能量显示 | [ModIntegrationJEI](F:/clone/ModularMachinery-Community-Edition/src/main/java/hellfirepvp/modularmachinery/common/integration/ModIntegrationJEI.java)、[JEIComponentEnergy](F:/clone/ModularMachinery-Community-Edition/src/main/java/hellfirepvp/modularmachinery/common/crafting/requirement/jei/JEIComponentEnergy.java) |
| Applied-Flux 说明与注册 | [README](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/README.md)、[gradle.properties](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/gradle.properties)、[AppFlux](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/src/main/java/com/glodblock/github/appflux/AppFlux.java) |
| Applied-Flux FE Key 与数量 | [FluxKey](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/src/main/java/com/glodblock/github/appflux/common/me/key/FluxKey.java)、[FluxKeyType](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/src/main/java/com/glodblock/github/appflux/common/me/key/type/FluxKeyType.java)、[EnergyType](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/src/main/java/com/glodblock/github/appflux/common/me/key/type/EnergyType.java)、[FEExternalStorageStrategy](https://github.com/FormlessDragon/Applied-Flux/blob/8f9a6204b357275f47d7e17d8937999e9802f48e/src/main/java/com/glodblock/github/appflux/common/me/strategy/FEExternalStorageStrategy.java) |

## 10. 实施、测试与提交规则

- 集合实现优先使用 fastutil，包括适用的原生类型集合；仅在确实需要并发等特殊语义时另选实现。
- 禁止非必要反射；使用正式 API 和显式注册，框架要求的标准注解接入不再自行复制一套反射机制。
- 禁止异常控制流：资源不足、容量不足和不支持的操作按正常返回值处理；配置错误、非法契约调用或内部不变量破坏立即失败，不捕获后伪装成功。
- 完成一个可说明库能力与验证结果的阶段后进行本地 Git 提交，不推送远端；示例内容与默认参数不扩展为正式玩法需求。
- 保留必要的设计、API 使用方式、阶段状态及实际验证记录。已实现、已编译、已运行和待验证须分别陈述。
- 每个类通过本机 IDEA MCP 进行实际检查（get_file_problems，errorsOnly:false），修正错误、警告及弱警告并记录范围/结果。工具未挂载时连接已启用的本地 MCP，不操作桌面或修改 IDE 设置；编译、格式检查或人工审查不替代此证据。
- 维护简短的 `docs/INDEX.md`，记录当前检查点、下一步与按需阅读入口。恢复上下文先读索引，按任务定位章节，不将完整文档批量填入上下文；实现状态与验证证据更新后同步维护索引。
- 自动化测试仅用于静态推断难以确认的行为逻辑，例如多步骤资源操作、方向隔离、失败恢复、真实并发和客户端/服务器集成；不为常量、文件哈希、类名、包路径或机械的 getter/setter 建立测试。
- 编译、格式检查和人工代码审查用于发现静态错误；不把这些检查包装成大量重复测试。测试用例描述可观察行为，不逐行复刻实现。
