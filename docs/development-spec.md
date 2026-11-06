# Prototype Engineering 开发文档

版本：0.2 · 日期：2026-11-06 · 状态：需求核对稿，已补充独立 API 包与职责命名规则

本文记录本次对话已确认的需求、旧项目调查结果和建议的开发边界。标为“已确认”的条目可作为后续实现依据；标为“建议”或“待确认”的条目尚未决定。旧项目文档仅作为调查材料，不自动成为新项目要求。

本轮只建立开发文档。当前新项目仍处于模板初始化阶段，以下业务能力尚未在新工程实现。

## 1. 项目定位与工程基线

Prototype Engineering 是 PrototypeMachinery 与 PrototypeNetwork 合并后的重新开发版本。两个旧项目的代码均可复用；复用时按新需求组织实现，不要求保留原有分层、接口或跨项目适配协议。

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

合并后的机器和研究逻辑直接存在于本工程。PM、PN 不再作为本工程的运行时依赖，也不再通过本地 PM dev Jar 连接两个子系统。

## 2. 已确认的设计决定

| 编号 | 决定 | 对实现的约束 |
|---|---|---|
| R01 | 使用纯 Java 25 | 复用 Kotlin 算法时改写为 Java；不引入 Kotlin、Kotlin 协程或 Forgelin 依赖。 |
| R02 | 按职责设计接口与类名 | 接口表达明确的行为职责、调用契约或扩展点；值对象、配置、状态与结果可直接使用具体类。实现类按实际用途命名，不使用统一的 `XxxImpl` 命名规则，也不要求接口与实现一一配对。 |
| R03 | 保留独立 API 包 | 对外契约集中在 `com.circulation.prototype_eng.api`，参考 AE2 按职责划分子包；内部实现位于实际功能包，源码目录与 package 对齐，不再建立机械对应的 `impl` 包树。 |
| R04 | AE2 作为 API 基础 | AE2 API 均可按需求使用；物品、流体、库存、GUI 等直接基于该分支能力设计。具体 ME 联网玩法仍需另行确认。 |
| R05 | 使用 AE2 资源类型系统 | 物品、流体及其他支持的资源采用 `AEKey`、`AEKeyType`、`GenericStack` 等；不重建 PMKey 体系。 |
| R06 | 通用仓室保留三种方向 | 保留输入仓、输出仓、输入输出混合仓；每种均支持 AE2 多资源类型，不再按物品/流体分别制造同类仓室。 |
| R07 | FE 独立 | 保留独立能源仓；FE 使用独立存储及能力接口，不注册为 AE2 资源类型。 |
| R08 | GUI 使用 AE2 体系与标准 Java | 机器和仓室界面按 AE2 GUI/容器体系编写；替换旧 PM GUI 体系，不继续建设 ModularUI 抽象层。 |
| R09 | 不再开发 GUI 编辑器 | 不迁入 Web GUI 编辑器、GUI DSL、编辑器 IR 或为其服务的 Runtime JSON/表达式系统。 |
| R10 | CraftTweaker 最后实现，共用 Java API | 随核心功能建立独立包中的通用 Java API，供 Java 扩展与最终 CT 接入共同使用；脚本桥、ZenClass 和参数转换仍在最后阶段实现，不另造脚本专属领域模型。 |
| R11 | 允许完全不兼容旧项目 | 旧存档、结构 JSON、脚本和公开 API 均无兼容要求；无需建设旧包别名、旧数据解码器或迁移层。 |

R11 允许重新设计格式，也允许复用旧格式中有价值的部分；不意味着需要为了变化而改写成熟算法。

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

### 3.2 已发现的结构问题

| 观察 | 本地证据 | 新项目处理方向 |
|---|---|---|
| PM 主要以 Kotlin 实现 | 主工程 `src/main` 有 609 个 Java/Kotlin 文件，其中 601 个 Kotlin、8 个 Java；不含独立 Modern Backend。 | 按需要迁移功能并改写为 Java。 |
| PN 强制接口与实现配对 | 638 个主源码文件中，336 个位于 `api`、301 个位于 `impl`；270 个文件包含 public interface 声明，262 个实现文件名以 `Impl` 结尾。旧规格第 3 节明确要求该模式。 | 撤销全工程配对规则；逐个判断真实扩展点。文件数量只说明结构规模，不代表所有接口都有问题。 |
| 简单身份对象也允许外部实现 | `ResearchId` 接口的工厂转调 `ResearchIdImpl`；后者还需校验外部实现的 namespace/path/serialized 是否一致。 | 若确有独立研究 ID 的必要，用不可变具体类或 record；无需提供可随意实现的身份接口。 |
| 包的语义和文件定位分散 | PM 的 `api/machine/MachineInstance.kt` 对应 `impl/MachineInstanceImpl.kt`；Kotlin 文件位置省略了完整根包目录。PN 大量类型按 api/impl 机械配对。 | 保留独立 API 包，集中对外契约；内部实现按实际功能归属和用途命名，使调用者能区分公开边界与具体运行逻辑。 |
| 合并前产生大量桥接层 | PN 依赖精确 PM dev Jar，为四类机器注册 factory/context provider，并分别处理 PM 与 PN 的运行时 epoch。 | 在同一工程内设计机器、研究和执行上下文；不照搬用于连接两个模组的协议层。 |
| Java 项目仍依赖 Kotlin ABI | PN 的 `DevelopmentItemInputHatchPredicate` 使用 `kotlin.jvm.functions.Function1`；构建引入 Kotlin stdlib 与 Forgelin。 | 新公共方法和回调使用 Java 类型，不保留 Kotlin ABI。 |
| 自有资源键与 AE2 能力重叠 | PMKey 带有可变 `count`；AE2 已提供无数量的 AEKey，以及独立数量的 GenericStack。 | 统一采用 AE2 类型和数量表达，避免来回转换两套等价模型。 |
| GUI/脚本/编辑器相互牵连 | PM 存在 ModularUI 定义、绑定、Runtime JSON 解析器和 Web 编辑器导出链。 | 依据 R08–R10 退出这套链路，先实现具体 Java GUI。 |

### 3.3 功能清单与迁移状态

“候选保留”表示已找到对应能力，但细节尚未由本次需求确认。

| 旧能力 | 调查结果 | 新项目状态 |
|---|---|---|
| 多方块结构、朝向、匹配与 JSON 加载 | PM 有结构定义、24 向朝向、区块已加载检查、两阶段解析。 | 候选保留；旋转范围、模板/切片能力和新格式待确认。 |
| 机器类型、实例、配方、工位 | PM 有 ECS 组件/系统、配方阶段与多个工位。 | 候选保留业务能力；具体运行时结构重新设计。 |
| 配方需求和资源提交 | PM 有 start/tick/end 需求及提交/回滚逻辑。 | 应保留真实资源一致性；是否保留全部扩展需求种类待确认。 |
| 物品/流体仓室与 PMKey | PM 按资源种类、方向、10 个等级组合。 | 已确认替换为三种方向的 AE2 通用仓室；等级及容量待确认。 |
| 能源仓 | PM 有独立大数值能量存储与 FE 适配。 | 已确认保留独立能源仓；方向、容量、速率待确认。 |
| 机器 GUI | PM 使用 ModularUI、绑定和脚本覆盖。 | 已确认转为 AE2 体系的 Java GUI。 |
| GUI 编辑器 | PM 有独立 Web 子项目与 Runtime JSON 接入。 | 已确认不迁入。 |
| CraftTweaker | PM 提供机器、配方、UI 等脚本入口。 | 已确认最后阶段重做；无旧脚本兼容要求。 |
| 结构投影、GUI 结构预览、复杂机器渲染 | PM 有世界投影、切片、BOM、透明/Bloom 管线及缓存。 | 待确认首版范围；GUI 编辑器取消不等于游戏内结构预览取消。 |
| JEI/HEI 配方展示 | PM 有配方索引、需求渲染和布局扩展。 | 待确认展示范围，不能由 CT 延后推断一并延后。 |
| 调度与 Modern Backend | PM 有主线程/并发/planned 模式、Java/Kotlin 协程后端及独立 SPI 加速模组。 | Kotlin 及其协程已排除；并发目标、独立后端形态与优化算法取舍待确认。 |
| 研究、队伍、队列、奖励 | PN 有分级研究、前置/互斥、个人与队伍来源、手动研究和奖励状态。 | 候选保留；具体玩法不能直接继承旧规格。 |
| 网络中心、计算机、研究站、数据中心 | PN 已有四类机器声明和资源/研究接线；物理结构仍是开发结构。 | 候选保留；最终机器、等级和连接方式待确认。 |
| 数据中心下载、特殊磁盘 | PN 区分数据库下载、物品编码下载、特殊磁盘导入。 | 待确认是否保留各路径及等级消费规则。 |
| 存档、权限与客户端同步 | PN 已有 WorldSavedData、NBT、FTBLib 与 packet 接入。 | 按新规则重做；服务器权威、越权拒绝与重复请求处理需纳入验收。 |

### 3.4 旧实现不能直接视为完成的部分

PN 的当前审计记录了干净世界的专服基础启动，但明确指出真实配方、formed 机器、owner/team 生命周期及下载崩溃恢复尚缺完整实机证据。

审计还记录特殊磁盘导入的未关闭问题：`SpecialDiskInteractionImpl` 先执行 `storage.replace(transaction)`，再 `heldStack.shrink(1)`。已写入数据中心、未完成磁盘消费的失败窗口缺少持久恢复协议。若新项目保留磁盘导入，应重新定义可恢复提交，不能仅换类名后复用该路径。

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

这是仓库原文，本轮尚未修改新工程依赖，也未实际拉取/验证该发布构件。实施时将仓库与依赖放入 `gradle/scripts/dependencies.gradle`，核对 MDG 开发运行和生产重混淆结果。

PN 旧工程固定的是 `v1.0.9`；AE2 本地 `gradle.properties` 又保留 `mod_version = 1.0.0`。新工程应核验所选发布构件中的实际 modid/version，再设置 FML 依赖约束，不能把三处版本文本混用。

该分支 modid 为 `ae2`，资源 API 位于 `ae2.api.*`。新代码不使用旧 AE2 的 `appeng.api.*`、`IAEItemStack`、`IAEFluidStack` 或按物品/流体分开的旧 storage channel 设计。

### 4.2 直接复用的能力

| 用途 | 已核实的 AE2 类型/位置 | 新工程使用方向 |
|---|---|---|
| 资源身份 | `AEKey`、`AEItemKey`、`AEFluidKey`、`AEKeyType` | 配方、仓室及资源显示共用同一种身份。 |
| 资源与数量 | `GenericStack(AEKey what, long amount)`、`KeyCounter` | 数量与 key 分离；存储和汇总使用 long。 |
| 多类型槽位库存 | `GenericInternalInventory`、`GenericStackInv` | 复用容量、过滤、NBT、变更通知等现有能力，补充本项目方向与机器访问规则。 |
| 通用存储入口 | `MEStorage` | 提供按 key 插入、抽取与查询的边界。 |
| Forge 能力适配 | `GenericInternalInventoryAdapters`、`AECapabilities.GENERIC_INTERNAL_INV` | 让自有仓室暴露正确的通用库存和物品/流体能力。 |
| 客户端资源显示 | `AEKeyRendering` 及 AE2 现有槽位/控件 | 多类型资源显示沿用 AE2 支持。 |
| 容器与界面 | `AEBaseContainer`、`AEBaseGui` | 编写本项目具体 Java 容器和 GUI，复用已有交互能力。 |
| ME 节点与服务 | `IManagedGridNode`、`IStorageService`、crafting services | 根据后续联网需求接入，不把“使用 AE2 API”自动解释为“所有机器必须连接 ME 网络”。 |

`GenericStackInv`、`AEBaseContainer` 和 `AEBaseGui` 分别位于 AE2 的 helper/container/client 包，而非全部处于 `ae2.api`。可以按已确认的 AE2 基础依赖方向评估复用；落地时核验它们的构造、GUI 打开、同步和版本约束，不假设只继承一个类就完成了集成。

AE2 文档明确说明：其能力适配注册不会自动改造其他模组的 TileEntity。本项目必须实际暴露自己的能力入口，并验证管道、存储总线和玩家交互。

### 4.3 通用仓室

以下方向表以“外部自动化访问仓室”为视角；机器内部配方使用单独的受控访问入口：

| 仓室 | 外部插入 | 外部抽取 | 机器内部职责 |
|---|---|---|---|
| 输入仓 | 允许 | 拒绝 | 为配方提供输入。 |
| 输出仓 | 拒绝 | 允许 | 接收配方输出。 |
| 输入输出混合仓 | 允许 | 允许 | 根据配方使用输入与输出能力。 |

上述外部访问规则是沿用旧方向含义的首版建议。玩家在 GUI 中整理库存的权限应单独定义，不能直接把管道限制套用到所有玩家操作。

三个方向共用多类型库存实现，差异由明确的方向配置表达。建议采用一个具体仓室运行时类配合方向枚举，避免再复制三套存储代码。方块注册仍保留三种仓室身份。

同一库存模型允许 AE2 已注册且本仓室支持的多个 key 类型。物品和流体是基础验收对象；其他类型受类型支持、容量和能力适配约束，不能宣称任意外部管道自动支持所有类型。

仓室等级数、槽位数、容量单位、各类型容量、过滤和侧面配置尚未决定，不自动继承旧 10 阶数值。

### 4.4 FE

FE 属于独立能源仓及独立能量需求。它不实现资源 key，不注册 `AEKeyType`，不进入 GenericStack 库存，也不通过通用资源槽位表示。

建议复用旧能源存储中有价值的大数值及模拟逻辑，改写为具体 Java 能量存储类，通过 Forge `IEnergyStorage` 暴露能力。若内部使用 long，应明确 Forge int 参数/返回值边界及每 tick 传输上限，禁止溢出或截断丢失。

FE 消耗仍参与机器工作的一致性判断。一次工作需要物料与 FE 时，应先形成完整决策，避免物料已扣除却因能量不足产生错误推进。

### 4.5 GUI

GUI 使用具体 Java 类组织容器、槽位、按钮、资源展示和交互逻辑；以 AE2 基础类与现有控件为基础，按需求复用。客户端绘制放在客户端包，服务端容器验证和真实状态修改不依赖客户端类。

不建设新的控件定义语言、通用绑定表达式、JSON 界面运行时或网页编辑器。未来 CT 接入也不恢复旧 GUI DSL。

首个 GUI 验证应覆盖：正确打开自有仓室、物品与流体混合显示、槽位点击和 Shift-click、同步大数量、关闭/重开、拒绝越权操作，以及专服不加载客户端类。

### 4.6 资源操作的一致性

AE2 的 `SIMULATE/MODULATE` 表示模拟和实际执行，不等于跨多个仓室、FE 和研究状态的原子事务。`beginBatch/endBatch` 用于变更通知批处理，也不提供事务回滚保证。

因此需要为新机器定义清晰的检查、实际扣取、输出和失败处理顺序。优先在本项目拥有的库存内建立简单、可验证的提交边界；涉及外部 ME 库存或跨持久化位置时，再针对实际操作设计预留、缓冲或恢复机制。具体方案应由真实使用场景决定，不先复制旧 planned 协议的全部层次。

## 5. 代码组织与 API 边界

### 5.1 包结构

独立 `api` 包及职责命名方式已经确认。以下子包是建议的职责边界，随实际功能逐步建立，不要求提前创建空包或占位类：

```text
com.circulation.prototype_eng
├── PrototypeEngineering        模组入口
├── api                         对外 Java 契约，供扩展与最终 CT 接入使用
│   ├── machine                 公开机器定义、访问契约和生命周期扩展
│   ├── structure               公开结构定义及匹配扩展契约
│   ├── recipe                  配方定义、条件、注册和查询契约
│   ├── storage                 本项目确有需要的仓室契约，直接使用 AE2 类型
│   ├── energy                  本项目确有需要的独立 FE 契约
│   ├── research                保留的研究定义、注册和访问契约
│   └── computing               保留的计算资源与工作许可契约
├── machine                     机器实例、方块实体与生命周期实现
├── structure                   结构解析、方向和匹配实现
├── recipe                      配方目录、具体条件、运行进度与提交
├── storage                     AE2 多类型仓室、方向规则和能力暴露
├── energy                      独立 FE 存储、能源仓和能量需求
├── research                    研究进度、队列与保留玩法的实现
├── team                        队伍身份、归属与权限
├── computing                   保留的计算资源、设备预算和工作许可
├── container                   服务端容器及同步交互
├── client.gui                  Java GUI
├── client.render               按确定范围实现的渲染和投影
├── sync                        本模组网络消息与同步
└── integration                 确有需要的外部模组适配
    └── crafttweaker            最后阶段添加，转换脚本参数并调用 api
```

公开定义和值类型放在所属 `api.*` 包，内部功能包直接使用它们，不再复制一份同名内部定义。内部状态、调度细节、NBT 实现和具体 TileEntity 留在功能包。

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
| `api.machine.MachineDefinition` | 直接作为公开不可变具体类或 record 使用 | 数据定义自身已经有明确职责，不再拆成接口与实现。 |
| AE2 的 `GenericInternalInventory` / `MEStorage` | 复用 `GenericStackInv`，必要时提供明确用途的仓室类 | 使用已有库存契约，不新建仅改名转发的等价接口。 |

类型与边界规则：

- API 按 machine、recipe、research 等契约职责分包，避免依实现类位置或每个方法拆包。
- API 文档说明可调用阶段、线程要求、可变性、权限及失败语义；不向调用者暴露内部缓存或持久化状态。
- 公共方法的签名使用本项目 API 类型、Java 类型及明确依赖的 AE2/Forge 类型，不要求调用者引用内部实现类。
- 内部实现依赖并实现 API 契约；API 声明不反向依赖具体运行类。服务装配在模组初始化处完成，公开入口只交付约定的契约。
- API 需要公开构造数据时，可直接提供具体类构造器或必要的 builder；不为每个类型添加 Factory、Registry、Context 全套结构。
- 对外需要的注册入口置于对应 `api.*` 包，具体存储、索引和冻结逻辑留在功能包；内部专用注册表无需提升为 API。
- 不把所有 public 类都当作稳定扩展 API，也不建立聚合所有内部服务的巨大静态 API 门面。
- 研究条件、材料与机器执行使用同一套公开定义和新运行模型，去掉专为 PM/PN 跨模组连接而存在的重复 DTO 和桥接注册。

是否保留完整 ECS、组件拓扑系统、多工位和全部配方扩展能力，仍需由功能需求决定；建议先以具体对象组合表达已确定能力。

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
- API 面向本项目已确定的功能逐步完善；其存在不扩大尚未确认的 addon 功能范围。

### 5.4 线程和生命周期

尚未决定沿用哪些异步执行能力。建议保持以下基本规则：

- 世界、TileEntity、玩家库存、队伍状态及持久化变更有明确的服务端执行线程。
- 后台计算只处理受控快照，结果提交前校验目标仍存在且输入有效。
- 不把游戏内“计算线程/工作许可”当作 JVM 线程数量。
- 区块卸载、机器破坏、结构失效、断网或队伍变化后，停止不再合法的工作，清理临时占用。
- 资源不足、权限拒绝等正常情况返回可展示的原因；非法注册、数据损坏或内部状态错误提供带上下文的诊断。

是否保留双 barrier、source affinity、多种可切换后端和独立 Modern Backend，目前均未确认。

## 6. 研究与计算系统：待核对的业务边界

用户尚未逐项确认 PN 玩法。第一版仅列出影响结构最大的决定，避免把旧规格中的复杂语义带入新实现：

| 主题 | 旧规则摘要 | 需要确认 |
|---|---|---|
| 队伍来源 | FTBLib/FTB Utilities；机器持有 owner UUID，按当前队伍归属。 | 是否继续依赖 FTB 队伍；跨维度、离线和换队行为。 |
| 研究所有权 | 一部分来自成员个人等级最大值，另一部分直属队伍；黑名单/前置决定有效等级。 | 是否保留双来源、个人携带、互斥与黑名单全部语义。 |
| 研究推进 | 等级阶段、可循环研究、tier 0 手动研究、多研究站材料提交和多资源累计。 | 首版需要哪些研究方式；是否保留循环/分阶段公式。 |
| 计算资源 | 计算机组件贡献资源，中心提供工作许可，机器工位消费数值预算。 | 资源种类、单位、预算共享范围、并行与公平性。 |
| 数据中心 | 每队唯一活动中心/数据中心；设备研究门槛查询活动数据中心。 | 是否仍要求唯一活动实例；研究完成后是否必须显式写入数据库。 |
| 下载 | 数据库下载不消费等级；物品编码下载按规则消费个人或队伍等级。 | 是否保留等级消费及物品化研究；这会决定存档恢复复杂度。 |
| 特殊磁盘 | 独立导入特殊研究并消费磁盘。 | 是否保留；若保留，重新定义持久提交与恢复。 |
| 奖励 | 逐玩家、逐研究等级领取资格和恢复状态。 | 是否保留奖励、奖励种类与领取方式。 |
| ME 网络 | 当前 PN 源码中检索到的是 AE2 依赖声明/检查，未发现 ae2/appeng API import。 | 新项目哪些机器需实际入网，是否接入自动合成，队伍边界与 ME 安全权限如何共同生效。 |

研究与 FE、物料库存不是同一种东西。未确定前，不把研究等级、计算预算或工作许可一并注册为 AE2 key 类型。

## 7. 建议的开发顺序与验收

阶段内容随第 6 节和第 8 节的决定更新；本表不表示已经开始实现。

| 阶段 | 交付内容 | 通过标准 |
|---|---|---|
| P0 需求与 AE2 基线 | 确认保留功能；接入固定 AE2 构件；确定独立 API 包、基础类型与依赖。 | 依赖可重复解析，dev/生产 Jar 名称与版本正确，客户端/专服基本加载；无 PM、PN、Kotlin、Forgelin 依赖。 |
| P1 仓室与 FE | 三种通用仓室、独立能源仓、Java GUI。 | 同一通用仓室容纳物品/流体；外部方向约束生效；存取、容量、NBT、重启和 GUI 数量同步正确；FE 独立。 |
| P2 最小机器流程 | 一台真实多方块、一个可执行配方、仓室及 FE 连接，同步建立所需 Java API。 | 经公开 API 注册机器/配方并在游戏中验证建造、形成、扣料、耗能、产出、暂停、破坏和卸载，无重复产物或丢失。 |
| P3 研究与计算 | 依据确认后的规则接入研究、队伍、计算机、数据中心及相应 API。 | 经公开 API 定义的至少一个研究完成并改变真实机器可用性；预算与权限由服务器裁决；换队/重启按确定规则执行。 |
| P4 完成功能与运行验证 | 补齐已选定的投影、渲染、HEI、ME 联网/合成、复杂配方等。 | 对实际保留的功能取得单元、集成或实机证据；涉及下载/磁盘/奖励时覆盖失败恢复。 |
| P5 CraftTweaker（最后） | 基于独立 API 包中已稳定的 Java 契约增加脚本参数转换、注册与配置。 | 不依赖内部实现类；Java 与脚本入口产生一致的定义和校验结果；无旧脚本兼容要求，不新增 GUI 编辑器/DSL。 |

测试用于证明真实行为：重点包括多类型仓室方向、FE 数值边界、资源提交、结构卸载、权限和保存恢复。格式检查、编译通过或服务器显示 Done，都不能替代这些行为验收。

性能目标待确认机器数量、并行工位数、结构规模、队伍人数、ME 库存规模及基准硬件后填写。暂不承诺没有测试依据的 TPS、吞吐或延迟数字。

## 8. 下一轮需要决定的事项

当前已确认事项不重复提问。其余按影响顺序讨论：

| 编号 | 待决定事项 | 当前建议/状态 |
|---|---|---|
| D01 | PN 研究、下载、特殊磁盘、奖励规则保留到什么程度？ | 先定最终玩家流程，再裁剪状态与恢复协议。尚未决定删除任何一条业务路径。 |
| D02 | 是否继续使用 FTB 队伍；计算网络和 ME 网络如何关联？ | AE2 作为 API 基础已确认；物理连接及权限规则尚未确认。 |
| D03 | 多工位、并行配方与后台计算哪些属于首版要求？ | 保留必要性能能力，避免为了框架形态而复制旧调度器。 |
| D04 | Modern Backend 独立模组形态是否取消？ | 建议有用算法直接并入 Java 25 主工程；独立 SPI/双 Jar 形态待确认。 |
| D05 | 结构的 24 向、模板/切片、投影、复杂渲染和 HEI 范围？ | GUI 编辑器已取消；游戏内结构预览和渲染仍独立讨论。 |
| D06 | 仓室/能源仓的等级、容量、速率、过滤和侧面规则？ | 通用仓室三方向、FE 独立已确认；不自动继承旧 10 阶参数。 |
| D07 | 面向单个整合包还是面向公开 addon 扩展？ | 独立 API 包与 CT 最后接入已确认；此项只决定额外扩展点、内容配置方式和 API 兼容承诺的范围。 |
| D08 | 第一台验收机器与第一条研究流程是什么？ | 建议选择同时覆盖多类型物料、FE、研究门槛的实际玩法样例。 |

## 9. 调查资料与源码入口

以下均为本次实际检查的本地资料，后续修改旧仓库可能使调查结论变化。

| 资料 | 位置 |
|---|---|
| PM 功能与结构概览 | [README](F:/clone/PrototypeMachinery/README.md)、[PROJECT_OVERVIEW](F:/clone/PrototypeMachinery/PROJECT_OVERVIEW.md) |
| PM 机器与配方 | [MachineLogic](F:/clone/PrototypeMachinery/docs/MachineLogic.md)、[MachineType](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/MachineType.kt)、[MachineInstanceImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt) |
| PM 仓室与资源 | [Hatches](F:/clone/PrototypeMachinery/docs/Hatches.md)、[PMKey](F:/clone/PrototypeMachinery/src/main/kotlin/api/key/PMKey.kt)、[Storage](F:/clone/PrototypeMachinery/docs/Storage.md) |
| PM GUI 与后端 | [MachineUiEditorRuntime](F:/clone/PrototypeMachinery/docs/MachineUiEditorRuntime.md)、[ModernBackend](F:/clone/PrototypeMachinery/docs/ModernBackend.md) |
| PN 旧规格与现状 | [development-spec](F:/new/PrototypeNetwork/docs/development-spec.md)、[implementation-audit](F:/new/PrototypeNetwork/docs/implementation-audit.md) |
| PN 接口拆分样例 | [ResearchId](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/api/research/ResearchId.java)、[ResearchIdImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/research/ResearchIdImpl.java) |
| PN 磁盘提交 | [SpecialDiskInteractionImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/disk/SpecialDiskInteractionImpl.java) |
| AE2 Maven 与分支说明 | [README](F:/clone/Applied-Energistics-2/README.md) |
| AE2 类型与节点文档 | [API](F:/clone/Applied-Energistics-2/API.md) |
| AE2 通用库存 | [GenericInternalInventory](F:/clone/Applied-Energistics-2/src/main/java/ae2/api/behaviors/GenericInternalInventory.java)、[GenericStackInv](F:/clone/Applied-Energistics-2/src/main/java/ae2/helpers/externalstorage/GenericStackInv.java)、[MEStorage](F:/clone/Applied-Energistics-2/src/main/java/ae2/api/storage/MEStorage.java) |
| AE2 GUI 基础 | [AEBaseContainer](F:/clone/Applied-Energistics-2/src/main/java/ae2/container/AEBaseContainer.java)、[AEBaseGui](F:/clone/Applied-Energistics-2/src/main/java/ae2/client/gui/AEBaseGui.java) |
