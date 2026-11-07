# 结构定义、快照与匹配

已迁入 PM 模板/切片的 Java 定义、24 向旋转、声明读取、脱离世界的事实快照和独立匹配结果，并接入宿主发布复核、公开仓室发现、实际控制器和调度器。2026-11-08 已补齐 A 启动期严格两阶段 JSON 与共享目录/机器装配原子发布，以及 B 运行中重载、准确宿主配置代撤销/刷新和客户端权威目录/缓存同步。源码依据和未迁完内容见[结构审计](structure-migration-audit.md)；库不内置正式机器或配方。

## 朝向与定义

直接复用 AE2S `BlockOrientation` 的 24 种朝向。`StructureTransform` 只缓存 24 个整数有符号置换，初始化时从 AE2 得到基向量，热路径不做矩阵/浮点旋转；支持基准变换、任意 from→to、坐标/方向与逆变换。坐标运算检查溢出，不允许绕回到另一世界位置。

PM 默认 NORTH/UP 绕 UP 到 EAST 的约定已验证。AE2S `rotateClockwiseAround(UP)` 实际把 NORTH 变为 WEST，因此没有拿这个方法直接替代 PM 旋转；目标朝向通过 AE2 的前/上方向表示。

`MachineStructure` 保留外部形状的真实扩展职责：转换、声明潜在读取、产生完整匹配结果。`TemplateStructure`、`SliceStructure` 是实际实现类，没有接口/Impl 镜像；定义保持对象身份，不引入递归结构相等。`StructurePattern` 冻结相对位置和谓词，模板/切片冻结偏移、验证器与子结构；`StructureDefinition` 另存名字及 display，启动 JSON/Java 共用完整 `StructureCatalog`。客户端权威目录/缓存入口已接，预览渲染仍待接入。

`StructurePlan` 将定义从它的基础朝向转换到请求朝向，保留一个固定控制器坐标并收集读取。每次 `match` 返回独立 `StructureMatch` 树或 null，前一次成功不会污染下一次失败。结果保存每个 occurrence 的重复数、实际本层方块位置及子树；位置收集按公开结果进行，不按具体模板/切片类判断，也不按 ID 扁平覆盖计数。

## A：启动期两阶段 JSON 与共享发布

`CommonProxy.preInit` 从 `config/prototype_eng/structures/` 递归、按相对路径稳定排序读取 JSON，只严格解析并冻结 DTO，不解析 Block、不访问方块注册表。不存在的目录或可访问的空目录是合法纯库；存在但不可访问/非目录的根、枚举或文件读取失败直接报告。`postInit` 注册内置谓词，发布 `StructurePredicateRegistrationEvent` 后 freeze，再收集 `MachineRuntimeRegistrationEvent` 的 Java 结构/装配，解析全部 JSON/Java 子结构依赖并检查全部机器引用与装配。通过后只替换 `MachineTypeRegistry` 私有不可变 `ResolvedRuntime` 一次；任一步失败不发布半套目录或机器绑定。目录保留所有定义，包括未被机器直接引用的定义，持有的目录对象是稳定代身份。

每个文件为一个结构对象，新格式不兼容旧 JSON。常用字段如下：

| 位置 | 字段与规则 |
|---|---|
| 根 | 必填 `id`、`type`、`pattern`；type 为 `template` 或 `slice`。可选 `translationKey`、`offset`、`hideWorldBlocks`、`validators`、`children`。ID 必须含 namespace。 |
| 切片 | 必填 `minCount/maxCount`，非负且 min ≤ max；`sliceOffset` 默认 `(0,1,0)`。模板拒绝这些切片字段。 |
| 坐标 | `pos/offset/sliceOffset` 使用 `{ "x": 0, "y": 0, "z": 0 }`；pattern 格必填 pos，禁止重复坐标。 |
| pattern 格 | `blockId/meta/nbt` 与 `alternatives` 中各候选组成独立 OR 分支，各分支保留自己的 NBT；结果再与 `predicates` 按 AND 合并。meta 默认 0，必须在 0..15 且真实方块可表示。 |
| 谓词/验证器 | `{ "id": "prototype_eng:day_only", "params": {} }`；params 可省略为空对象。外部工厂通过注册事件声明允许参数并读取冻结参数视图。 |
| 子结构 | `children` 为结构 ID 数组；JSON 可引用 Java 或 JSON 定义，共用 canonical 子对象，拒绝未知引用、重复 ID、JSON/Java ID 冲突和环。 |
| display | 格内独立 `display` 可含 `key`、`blocks`、`blockIdRegex`、`tileNbt`，至少提供 blocks 或 regex；不参与 matcher。根的 hideWorldBlocks 同样是展示数据。 |

严格解析保留 Gson 2.2.4，不增加第二套解析器或依赖；拒绝重复成员、未知字段、null、缺必填项、类型错误、非法 JSON 字面量/转义及尾随内容，错误携带源文件和准确字段路径。整数按精确数值与 signed int 范围判断，`1`、`1.0`、`1e0` 等价，`1E-3` 可作为外部数值参数但不能作为整数。内置工厂包括 any/not_air/has_tile_entity、维度、昼夜、晴天、block_id_regex 和 tile_nbt；相对 NBT 验证器保留参数、额外读取及旋转。正则在 PostInit 展开冻结集合，匹配不再访问注册表；display 正则最多保留 32 个代表块。名字使用 `translationKey` 与实现 `LocalizationEnum` 的 `StructureTitle`，沿 AE2S 客户端 getLocal/服务器 text 契约。

A 新增 21 项行为测试，受影响正常 Gradle 测试 112 tests / 14 suites，完整 `gradlew.bat build --console=plain` 1285 tests / 161 suites，均零失败/错误/跳过。18 Java 的 IDEA MCP `get_file_problems(errorsOnly:false)` 零错误/警告/弱警告，完整 `read_file` 项目文件文本与磁盘一致，MCP build_project 成功；18 Java 构建前后与 MCP 检查 SHA 一致。精确写前/最终清单、快照和原始证据见 `build/staging/structure-json/`，不将文件读回称为未保存 Document 证明。

## B：运行中重载与权威客户端目录

实际 permission-2 `/pe_structures reload` 在原 TaskScheduler 的 joined owner boundary 中重新读取同一目录，沿 A 严格 DTO/完整 JSON+冻结 Java resolver 构造候选。所有机器引用、精确 loaded host、展示图与有界传输数据均通过预检后，才 callback-free 撤销整批旧 StructureConfiguration，再逐宿主暂停原绑定、原子发布 registry、安装新的 canonical 配置并立即重匹配。canonical 装配对象保持；同朝向仍更换配置代并重建仓室/结构组件。旧 worker 成功与失败均无权改变新配置；原 scheduler pending 的实际 source 退出/未完成确认义务会继续阻止 reload 边界，不能强制解约或恢复旧授权。

GLOBAL 实际 Loaded 只记录 machine/host/lifecycle 身份，离开 load gate 后才自动登记独立宿主；显式更新器不重复构造或被 service 代 tick。ReloadableStructureHost 负责只赋值的 installStructureConfiguration 与永久精确 structureLifecycleCurrent；外部宿主无须继承 TE，自定义 lifecycle registry 可交付实际 Loaded。live server session 参与重载/自动管理必须使用该会话原 scheduler；公开构造器显式拒绝 foreign scheduler，拒绝在任何登记/配置改变前完成。无 Session 的独立逻辑 owner 保持原能力。

解析/候选预检失败保留旧 registry/config/client sequence。整批配置撤销先于生命周期 pause 回调，所有 pause 均尝试；pause 失败保留旧目录和序号、旧配置为 null、旧绑定暂停/故障，不安装新代或恢复旧 worker 权限。成功提交目录后，即使个别安装/刷新回调失败，客户端仍收到已提交代，原异常继续报告。

StructureCatalogView 只捕获原 Template/Slice 相对几何、child shape 身份 DAG、translationKey 与独立 display；不传 matcher/factory。外部形状通过 StructurePreviewProvider 提供几何，否则显式 OPAQUE。实际 Forge S2C 通道以 session UUID/成功 sequence 同步，严格 UTF-8 codec 限总 8 MiB、集合及字符串字节 65,536，分为 16 KiB 消息；登录发送当前目录。客户端在实际 client thread/准确 NetHandler manager 上收件，永不重读本地 config。完整合法新代到齐才一次替换 view 并清代缓存；部分、旧代、错连接、冲突或畸形数据保留旧完成目录。断连清 view/cache，旧断连不清替代连接；重复同 manager 通知保持目录。StructurePreviewCache 按权威 view 身份与设置缓存，跨代 builder 无权发布旧结果，订阅 handles 各自保留对象身份。

B 新增 23 项行为（3 suites），最终受影响正常 Gradle 187 tests / 24 suites、一次完整 build 1308 tests / 164 suites 均零失败/错误/跳过。24 Java 本机 IDEA MCP errorsOnly:false 全级零、完整项目文件读回/磁盘一致，MCP build_project 成功；26 源码资源构建前后与 24 Java MCP SHA 稳定。独立 max 审计的 foreign scheduler P2 以最小入口拒绝/无残留纯行为及实际构造转发复核闭环；准确正式写前、只读候选/修正、统一最终清单/快照与真实证据见 `build/staging/structure-reload/`。未启动游戏或 native Session/Bootstrap/FML 测试初始化；同步展示数据不表示预览/投影渲染、切片/BOM/display override 消费、模型隐藏或复杂渲染完成。

## 模板、切片与修正

| 情形 | 当前行为 |
|---|---|
| 模板 | `anchor + offset` 后按 pattern、validators、children 顺序匹配；全部成功才发布树。 |
| 切片 | 贪心匹配连续切片，首次普通不匹配停止；实际试探的切片未加载则整体失败，即使已满足 min。到 max 后不多读一片。 |
| 切片验证器/子结构 | 验证器只在基础 origin 执行一次，子结构只在最后匹配切片执行一次；子结构失败不回退到更短切片。 |
| 零片 | 保留旧 matcher/preview 的规则：children anchor 为 `base - sliceOffset`。规划、匹配和结果收集统一此规则；max=0 不检查 pattern，仍执行验证器及子结构。 |
| 控制器排除 | 所有层只排除最初固定的实际 controller；子结构自己的 anchor 不再误当控制器。匹配与结果位置收集一致排除。 |
| 旋转 | orientation、offset、pattern、验证器相对读取及 children、sliceOffset 一同转换；修复旧验证器未旋转的问题。 |
| 包围盒 | 保留稀疏 pattern 整个 min/max 区域的加载检查，不能仅检查有方块的位置。空 pattern 的区域是本层 origin。 |

`minCount/maxCount` 严格校验非负与顺序，计数循环避免 int 末尾溢出。当前规划枚举潜在最大范围，尚未接入调度工作量/时间预算；这不是允许把巨大结构准备工作直接塞进每 tick 的承诺。

## 读取与线程

`StructureQuery<T>` 是显式事实描述符。reader 只能在服务端捕获，copier 必须在捕获和交付时隔离可变值；不可变值可 identity copy。第四个构造参数为显式等价比较器，不能默认外部值的 equals 足够。比较器收到非 null 的隔离副本，true 必须保证该描述符的所有消费者无法区分两值，false 可以保守触发重新匹配；null/缺失由框架单独比较。计划通过 descriptor 对象身份保持类型，并拒绝同 ID 的不同 descriptor。查询不能把 World、TileEntity、活动 handler 或执行许可当作事实返回。

`StructureReadPlan` 冻结全部潜在 area/query-position 读取，按声明顺序去重。模板与切片提前声明潜在分支，验证器也必须声明 pattern 之外的读取。潜在范围不是额外的成型条件：某条未使用远端分支未加载，不会提前否决较短的有效结构。

`WorldStructureSource` 复用现有 `MachineLifecycleHost` 校验服务端线程，以 `isAreaLoaded(..., false)` 检查区域；不强加载。`StructureSnapshot.capture` 同步完成读取后不再保留 source。工作线程只消费快照，不能临时回到 world 补未知读取。NBT 在捕获及每次交付时深拷贝。

快照区分三种情况：已捕获的缺失值（如无 TE 的 null）、未加载区域、捕获异常。普通未加载的所需谓词为不匹配；直接读取未声明/不可用位置是调用错误。潜在分支上的 RuntimeException 保留为诊断事实，若实际访问该事实则抛出，绝不转换为空气/null/false；未访问的分支不因此改变结果。JVM Error 立即中断捕获。该错误处理不表示调用者可忽略 `failures()` 诊断或继续使用已失败的真实运行宿主。

快照只解决读取线程和对象隔离，不授予后续发布权限。以下发布入口复核世界事实、配置代与实际仓室；B 重载经原调度 joined boundary 与准确 active lifecycle/配置代撤销，原 source 退出/确认义务保持。外部 reader 和能力发现必须遵守观察性读取契约，不得修改世界、配置或递归调用刷新入口。

## 谓词与内置事实

`StructurePredicate` 同时承担单格需求或相对位置验证器的读取声明与纯匹配。方向无关谓词可复用默认 transform；方向相关的外部实现必须同时转换声明与测试。`StructurePredicates` 提供查询条件、AND/OR、相对 offset、非空气、声明 TE、精确 state、预先展开的方块集合以及浅层 NBT。

- AND/OR 保留声明顺序与短路；alternatives 可以分别组合 state/NBT，不再把含 NBT 的候选退化成首项。
- 精确 state 仍按 canonical IBlockState 身份比较；方向/Axis 变换复用同一个实现，state+NBT 与普通 state 一致。仍保留旧代码对 property 不接受目标值时维持该 property 的行为，完整块状态旋转策略以后续实机核对为准。
- NBT 保留 PM root 浅层规则：String 比实际字符串值，其他 tag 比 SNBT；缺字段/无 TE 不匹配。`stateWithNbt` 的空约束不额外要求 TE，独立 NBT 谓词要求存在 TE。
- JSON 正则在 PostInit 定义解析阶段展开，匹配阶段使用冻结方块集合；display 单独保存有界代表块，不改变完整 matcher 集合。

`StructureQueries` 提供 block state、PM 双条件非空气、block-state 声明 TE、TE NBT、dimension、day-cycle WORLD_TIME、实际 `world.isDaytime()` 的 DAYTIME、rain/thunder、biome ID，以及显式 Forge capability 的存在性查询。DAYTIME 与 WORLD_TIME 独立，昼夜工厂使用前者。通用 capability 查询只检查 hasCapability；资源/能源输入/能源输出的专用查询使用 HatchCapabilities.find，与实际仓室发现共用 getCapability/isValid 规则。全部只捕获 Boolean，不把 handler 交给 worker。静态初始化只建立描述符，注册表/世界访问留在实际 reader 内。

## 结果复核与仓室发布

`StructurePlan.evaluate` 在共享不可变事实之上创建本次独立读取追踪，返回 `StructureEvaluation`。证明包含真正访问的 area/query-position、贪心切片的首个失败探测、验证器额外位置，以及短路前确实访问过的分支；不只检查成功的 allBlocks，也不把全部潜在最大范围当作必要条件。外部结构必须只根据输入快照进行确定性匹配；无读取声明的外部可变状态不属于证明。

`StructureEvaluation.refresh` 在服务端先捕获实际使用的事实。证明仍成立时直接使用 worker 结果；有变化时重新捕获完整计划并重新匹配，以新结果为准。WORLD_TIME 仍精确比较，不能使用“都满足某个时间条件”冒充通用等价；时间改变但条件仍真可以重算后正常发布。旧访问位置现在故障，也先允许新分支短路跳过它；只有新匹配仍访问的故障才抛出。重算回退发生在主线程，未来调度预算必须覆盖该成本，不能声称所有匹配成本都已移到 worker。

`StructureRefresh` 属于一台机器的生命周期，流程如下：

1. 服务端 `prepare` 获取稳定的 `StructureConfiguration` 身份，建立计划并捕获快照；每次新请求使旧请求永久失去发布权。`Request.evaluate` 只保存配置、计划和快照，可在 worker 运行，不持有 host、World、TileEntity 或活动仓室。
2. 调度器 join 后，服务端 `publish` 在现有 `MachineLifecycle` TRANSITION gate 内核对当前请求、配置身份和加载状态。配置对象每次方向/定义/owner 权限变化都重新创建，包括 A→B→A；不能把旧保存身份或旧请求恢复成执行许可。
3. 复核实际事实，按最终树的 allBlocks 排序发现仓室；三个公开职责分别收集、同一契约对象去重，不依赖内置 TileEntity 类。复核事实后再次查当前 provider/能力身份和 isValid，缺失能力也参与比对。
4. 在同一 gate 内再次确认请求适用，再一起替换匹配树和 MachineHatches，最后才触发实际 formed 真值变化。每次成功都重建绑定；同朝向、仍成型但长度或 provider 变化，也更新绑定而不重复成型事件。
5. fresh match 确认失配才接受 `StructureAssembly.UNFORMED`，清绑定并暂停配方，保留工位。发现期间候选失稳则返回 false，不发送误导的失效事件，当前请求可重试；调度器必须在该发布边界完成重试，或显式 invalidate 后暂停，不能释放执行权继续使用未复核候选。旧结果返回 false，不改新请求和当前绑定。

刷新入口拒绝服务端回调重入，避免外层捕获覆盖内层新请求/撤销。当前读取或发现异常会故障机器且原异常抛出；若异常发生时该配置已撤销，原异常仍报告，但不能故障替代配置。worker 异常由任务所有者报告并通过 `fail(request, cause)` 返回服务端，仅当前请求能故障生命周期；过期异常不能变成普通失配。`Request.failures()` 与协调器 `diagnostics()` 提供初次及重算快照中未访问分支的诊断，调用者须报告这些信息。

`ControllerLifecycleHost` 同时实现公开的 `MachineStructureHost`，固定绑定 World，在结构入口检查确切 controller 的 World、位置、invalid、区域已加载及当前位置 TE 身份。清理的线程校验与活控制器检查分开，控制器已移除也能执行 broken/unload。configure/revoke 的调用方必须先 join 并 `StructureRefresh.invalidate` 旧绑定。默认发现使用 unsided 能力；专用外部宿主可明确定义侧面，不扫描六面后任意挑代理。

仓室本身仍是活动存储契约。替换/卸载 provider 必须使旧 StorageContext 失效；结构证明不能代替余额锁、预留或配方提交时的有效性检查。主线程串行和观察性回调是上述复核的前提，不能支持任意第三方在最后一次检查中继续修改世界。B 的实际目录/宿主重载和原 scheduler 停止/待确认边界已接，其他授权仍由各自真实生命周期和资源提交证明控制。

## 验证与后续

测试涵盖 24 向及任意朝向间变换、整数边界、快照隔离与潜在分支、重复读取去重、实际模板/切片树、旋转验证器、未加载探测、零片及同 ID 多 occurrence。验证不创建世界或启动 Bootstrap/FML/游戏；真实 WorldStructureSource、方块状态与 Forge capability 行为仍缺实机证据。当前完整测试和逐类 MCP 结果记录在[实施进度](implementation-status.md)。

新增测试覆盖真实 worker 消费快照、读取证明/时间刷新、切片增长、同方向换仓、过期成功/失败/失配、配置 ABA、卸载、发现失稳重试、故障隔离、跨线程/执行重叠及重入。逻辑 fixture 实现公开宿主接口，没有伪造 Forge 注册环境。

实际控制器/机器类型生命周期与调度入口已有集成，A 启动期 JSON/共享发布与 B 运行中重载/准确宿主撤销刷新/客户端权威目录缓存同步已实现验证。下一步接预览/投影渲染、切片/BOM/display override 消费、模型隐藏/复杂渲染及结构准备工作预算；当前目录同步与宿主发布契约不代表这些能力已完成，也没有缩减其迁移范围。游戏内集成未验收；本阶段实现与验证已完成。
