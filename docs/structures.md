# 结构定义、快照与匹配

## 2026-11-10：原生物品与准确预览选择

预览代表物品不再使用 `ItemStack(block, 1, stateMeta)`。完整properties解析及朝向旋转后的state，沿公共StructurePickWorld调用Block.getPickBlock；拥有独立state/TE上下文及作者显式tile NBT，不临时替换真实世界方块，也不扫描meta猜物品。预览图标、构建器树名称/图标和服务器实际用料共用该规则，空结果保持未就绪，不以错误物品代替。

预览面板以本地只读展示Slot调用实际GuiContainer.drawSlot，AE宿主的原生覆写继续生效；HEI普通GuiScreen使用正常RenderItem及其overlay。物品tooltip直接走宿主renderToolTip和Forge物品提示事件，不再拼接需求ID、内部key等单独提示；按钮文本提示仍保留。候选及材料列表分别滚动并裁剪，列表上滚轮不缩放场景，当前代表项不随候选列表滚走。

修正相机逆变换的pitch纵轴符号，使射线与绘制矩阵一致。按原准备预算建立16格section粗筛，再对射线相关格执行原生collisionRayTrace，取最近非负实际命中；不按登记顺序取格，不在每帧对所有方块调用原生几何钩子。悬停与点击共用同一命中逻辑；原生selected bounding box以较粗亮线绘制，悬停与已选均关闭深度测试/写入，可穿透看清。点击另一格直接替换选择，重复点击同格保留，空画布才清除；原owner/token/代次及cell身份校验保留。

这批依据源码与本机MCP检查、必要构建完成；按用户最新指示撤去可静态判断的新增测试。原生GL、HEI提示及模组getPickBlock实际调用仍待用户复验；现有Mixin新增方法需要重启客户端，不能仅资源重载。

## 2026-11-10：结构声明与预览同源

`StructurePredicates.blocks/state/stateWithNbt` 保存具体匹配事实，组合谓词从同一事实树派生只读 `StructureDisplay`；自定义 query 可使用带 display 的 `test` 重载。未知自定义谓词也可实现 `display()`，但库不会执行 condition/matches 或反射推断。`StructurePattern.displays()` 惰性缓存，Template/Slice 的 `MachineStructure.displays()` 直接沿用；shape-only `new StructureDefinition(shape)` 即可取得表示，逐格显式 displays 覆盖仍优先。几何规划和预算变换不提前求展示。

AND 保留完整 state、block membership 的实际相容性及同臂浅 NBT，冲突分支消除；OR 不从其他分支借具体材料。未知条件、非零 at、空 NBT 所隐含的方块实体存在约束及搜索预算耗尽都保留未决语义。搜索 pop 与 children/options 展开共同计入8192额度，最多32代表；超额保持已确认 hard material 的 partial seed，而非伪造空 NBT witness。`complete=false` 的显示可以提供明确材料外观，观察仍为 UNKNOWN，构建器不把它放置为完整要求。

BlockOption 独立保存完整 properties 与浅 NBT，按名称/合法值还原 meta 不能完整表达的状态后再旋转。无效属性返回未知；选项键分别排序、长度编码 properties/NBT。Catalog 的准确 Node 自带 intrinsic displays，未独立注册、同 ID 的不同子结构仍按对象身份传输；Entry 显式覆盖优先。codec v2传输这些数据，保留v1读回及原三参构造入口。纯行为和真实codec回归、最终构建及实机边界见[实施进度](implementation-status.md)。

## 2026-11-10：纯结构计划与朝向复用

`MachineStructure.planReusable()` 与 `StructurePredicate.planReusable()` 默认 false。扩展显式声明 true 时，几何、变换和读声明必须终身稳定，变换结果也遵守相同合同；多个宿主可以并发使用同一实例，各次 begin/match 的游标、builder 和 scratch 必须独立。实际 query capture、condition、freshness 与 matcher 每轮重新执行。默认 Template/Slice 的 `create` 工厂仅在 pattern、validators 和 children 全部合格时复用；普通公开构造器与未知扩展保留原 virtual 路径。资格在原复制及增量变换循环中累计，读取资格不重新扫描结构树。JSON 外部工厂返回未知谓词时，同样沿这些组合规则回退。

`StructureCatalog` 以 canonical shape 对象身份与目标24向保存完成的纯变换；目录对象本身隔离 reload 代。短同步段只查询或安装完成值，扩展回调和增量构造在锁外执行。并发 miss 各自推进独立游标，允许重复计算，后续采用一份完成结果。`StructurePlan` 的 configuration 重载消费该 memo；原 definition/controller/orientation 三参入口保留每次执行原 hook 的语义。

每个 `StructureRefresh` 只保留一个完整计划，准确 key 为 configuration 对象身份与不可变控制器位置。同步与并行准备共用同一选择/记忆入口；规划全部完成且 preparation owner、生命周期、配置和位置仍准确时才入槽，invalidate 显式清除。无 catalog 的独立宿主也可复用自己的完成计划。位置、配置 ABA、同 ID 新定义或新目录都不能采用旧绝对计划。命中仅省去纯规划，每轮仍创建新 Request、snapshot、匹配结果并执行完整最终捕获/匹配和原权限复核；世界失配不会成为负缓存。

首次 miss 仍沿原 transform→collectReads→freeze 增量构造，命中消耗实际常量预算；原 attempt deadline、首真实 tick 的 COOPERATIVE/STRICT 行为及 capture/关闭/发布成本保持。新增行为用例覆盖默认贪心 slice 的变化事实、完成形状的并发独立匹配与读取证明、并发 miss、失败变换、one-unit 前缀、未知 virtual 路径、配置/位置/目录隔离、两种准备入口、无 catalog 的未完成计划与过期预算。

最终 full-resumed 已实际完成 **1814 tests / 237 suites，零失败、零错误、零跳过**，无陈旧 XML、无格式变化。已按最早 before 对照方法名，逐项核对保存 XML：StructurePlanReuseTest 的 8 个新增用例、StructureRefreshTest 的 3 个新增用例全部运行通过（后者整类 23 项也全通过）。原阶段 before/frozen 与清单保留在 `build/staging/structure-plan-cache/`；早期格式化后的当前 11 个 Java 已重新逐文件 read_file→get_file_problems(errorsOnly:false)，全级零且完整读回一致，新冻结清单与验证位于 `build/staging/chat-goal-recovery-2026-11-10/cache-final/`。旧 frozen 到当前仅格式、导入和成员声明顺序变化，未修改逻辑；当前证据不复用旧字节的 IDEA 结果。

## 2026-11-10：周期结构检查的跨控制器批次

实际控制器和自动登记宿主现通过 `ControllerStructureUpdates.enqueueTick` 将周期检查加入原 TaskScheduler 的相邻结构队列项；首真实 tick 仍在 owner 完整检查。原公开 `tick` 保留即时边界调用。控制器的准确 attachment epoch 渲染状态观察附在同一个结构队列项完成后执行，避免每台 TE 的独立观察边界提前冲刷队列、使实际路径重新串行。

每批最多原 `workerThreads` 个宿主，遇到 reload、retire 等原 FIFO 普通边界即截断。同一准确宿主的积压项不进入同一个批次，原队列项和观察顺序全部保留；不得跨边界去重或同时续办同一捕获游标。`StructureSnapshot.Capture` 将原算法拆为 owner 的 area/加载复核/最多256个 read-port 绑定、原 worker 的实际读/复制、全批实际退出后的 owner 强制关闭。各宿主完成捕获后进入同一批次的 detached matcher；阶段续办共用该宿主本软片的 work/deadline，不重置5ms软片或5秒attempt。短deadline不会取消其他宿主；worker超时、拒绝、异常和撤权均先按原 Batch 证明实际退出，再清理已借用窗口。旧 `beginCapture(..., JoinedWorker)` 同步入口复用同一 capture 算法。

完整 freshness recapture/rematch、实际 discovery、binding/provider复核和 lifecycle 原子发布仍逐 owner执行。普通/source/pending 未退出或未完成确认时不进入结构边界；准备前缀、缓存事实和阶段完成均不新增安装授权。未知 shape/predicate 的原 virtual 调用保持；该批次之后的纯计划/朝向缓存进展见上节。

新增源码测试覆盖双控制器原生端口读取与匹配同时进入、准确 owner 观察在批次完成后执行、撤权仍关闭窗口、FIFO边界、独立deadline、pending确认、失败及超时后的真实退出。独立复核后共新增10项行为测试，包括全批owner完成之后才开启下一轮窗口、运行中短job独立deadline。最终完整1744 tests/224 suites全零；9个所属Java本机全级零、完整读回一致，MCP build成功。首次修改前内容与复核证据位于 `build/staging/structure-parallel-reuse/`，统一构建日志/XML见 `build/staging/resumed-goal-plan/unified/third-results/`。

## 2026-11-09：原 worker 读取、预算与完整安装

默认 Java 定义使用 `TemplateStructure.create` / `SliceStructure.create`，JSON resolver 选择同类增量工厂；原 public 构造器及旧 shape/predicate 的 transform、collectReads、match virtual 合作调用保持。transform 后仍保留默认 binding，child 和 predicate 使用其真实 begin hook，不反射识别覆盖、不封闭继承点。转换、最大潜在声明、AND/OR/offset/NBT/property、事实捕获、匹配树冻结、实际 occurrence DFS、discovery、位置 merge sort、仓室/port 索引冻结都逐项记 units，在下一真实 host tick 续办。

原 TaskScheduler 在 ordinary/source 实际退出后持有 exclusive owner boundary。具体默认 controller 通过真实可覆盖 host factory 显式选择 native reader：owner 只从现有 loadedChunks 绑定有限 Chunk 引用，不清 unloadQueued、不强加载；原 worker 真正调用这些 Chunk 的 getBlockState 并检查 BLOCK_STATE/NON_AIR/HAS_TILE_ENTITY。worker 不查 provider/map、World 或 TileEntity。通用 source/host 仍在 owner 捕获实际 this.read，NBT、capability、biome/custom World 回调默认留在 owner；外部可通过公开 read port 明确承担 safe worker 责任。每片都实际 join 后关闭全部 read ports，deadline/异常不跳过强制关闭，原 primary 与后继 cleanup suppressed 保持；live 引用不跨 tick。

准备完成包括真实 discovery，但任何准备前缀和仓室/port 候选都不授权安装。默认 cooperative 的最终尾部在同一个 owner 窗口重新执行完整实际 host capture、原 worker rematch/实际退出、used facts 与真实 whole-area virtual、provider/absence/capability/isValid、准确配置/attachment 证明，再通过原 lifecycle gate 单次安装完整树、仓室、ports 与成型通知。成功和真实失配都先完成证明；改变事实的旧失配不能清 accepted formation。图节点的 declared/pending soft edges 先无权逐项准备，整组 owner/type ID/revision 预检在任何写之前完成，同 ID counterfeit 或 stale group 不得部分安装。原普通 map、dependency order cache、cleanup、reload/retire/join 路径保持。

`StructureCheckPolicy.DEFAULT` 为每软片4096 units/5 ms、同一次 attempt固定5秒 deadline。默认 finalMode COOPERATIVE 允许首真实 tick 和完整 final tail 超过软片，记录实际 load/bind/capture、比较、发现、复制、安装、listener、强制 close 的阶段成本与真实等待耗时，不更新原 deadline。STRICT 保留合法 accepted 状态，不装未验证树，额度/时间不足明确 NEEDS_MORE_BUDGET；三单位 live read 原子最小量由 `requiredAtomicUnits()` 报告，没有事实进展的过短借用窗口也明确不足。未知公开 reader/copier/comparator/provider/listener 是实际执行的合作边界，不能强制中断或用异常 yield。

首检查机会属于准确 loaded/configured host、world 和 lifecycle attachment，不以 world time==0 判定；第一个实际 tick 在 owner 完整处理，先于普通工作与成型广播，load-era UNAVAILABLE 不消耗该机会。UNAVAILABLE、YIELD、NEEDS_MORE_BUDGET 保留合法 accepted 树/formed/hatches/slots，通过独立 structure readiness 暂停配方，不用会取消 process 的 recipeWorkAllowed=false。只有真实已加载失配或真正 owner/config/world 撤权走原撤销；当前 pre-Request planning/capture 异常精确故障本 preparation owner，旧失败不污染 replacement。

验证包括实际 JSON 默认旋转/贪心 slice、旧 Template/Slice/predicate virtual、嵌套 offset/NBT 的 one-unit cursor，原 scheduler finite block read/实际退出/port关闭，首非零 tick、strict不足、accepted process 暂停/恢复、改变失配事实、eager failure exact owner、stale/same-ID prepared group与原子通知预算。结构扩大 focused218 tests/25 suites全过；最终正常完整 `build --rerun-tasks`1636 tests/207 suites全零、全部15任务实际执行，含最终成本与身份修正。39结构 Java 本机 IDEA MCP全级零、完整源文读回39/39一致、MCP build成功；证据 `build/staging/structure-async-budget/continued-full-result.json`、`continued-idea-final.json` 与原日志/XML。未启动游戏/服务端/Bootstrap/FML；native World/chunk与实际 GUI/GL 运行仍须实机验收。

已迁入 PM 模板/切片的 Java 定义、24 向旋转、声明读取、脱离世界的事实快照和独立匹配结果，并接入宿主发布复核、公开仓室发现、实际控制器和调度器。2026-11-08 已补齐 A 启动期严格两阶段 JSON 与共享目录/机器装配原子发布，以及 B 运行中重载、准确宿主配置代撤销/刷新和客户端权威目录/缓存同步，C 基础预览模型、切片/层/BOM 消费与实际世界盒投影。源码依据和未迁完内容见[结构审计](structure-migration-audit.md)；库不内置正式机器或配方。

## 朝向与定义

直接复用 AE2S `BlockOrientation` 的 24 种朝向。`StructureTransform` 只缓存 24 个整数有符号置换，初始化时从 AE2 得到基向量，热路径不做矩阵/浮点旋转；支持基准变换、任意 from→to、坐标/方向与逆变换。坐标运算检查溢出，不允许绕回到另一世界位置。

PM 默认 NORTH/UP 绕 UP 到 EAST 的约定已验证。AE2S `rotateClockwiseAround(UP)` 实际把 NORTH 变为 WEST，因此没有拿这个方法直接替代 PM 旋转；目标朝向通过 AE2 的前/上方向表示。

`MachineStructure` 保留外部形状的真实扩展职责：转换、声明潜在读取、产生完整匹配结果。`TemplateStructure`、`SliceStructure` 是实际实现类，没有接口/Impl 镜像；定义保持对象身份，不引入递归结构相等。`StructurePattern` 冻结相对位置和谓词，模板/切片冻结偏移、验证器与子结构；`StructureDefinition` 另存名字及 display，启动 JSON/Java 共用完整 `StructureCatalog`。客户端权威目录/缓存、基础预览/盒投影与D1原生方块模型世界路径已接，D2 AEBaseGui/JEI/代表项/owned TESR已实现验证，成型模型/动画/Bloom/隐藏继续D3。

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

## C：基础预览模型与世界盒投影

客户端 Java 入口为 `StructurePreviews.client()`，沿 ClientProxy 惰性创建服务并注册实际 ClientTick END / RenderWorldLast 适配器；独立 `StructurePreviews(ClientStructureCatalog)` 供纯逻辑消费。仅使用服务端已完整发布的 StructureCatalogView，不读取本地 JSON、定义工厂或 matcher。调用 `request(id, settings)` 后按 `Request.advance(maxSteps)` 增量展开，只有 READY 交付完整不可变 model；BUILDING 不泄露半成品，旧代/关闭后的请求为 STALE，异常保留 FAILED 与原 cause。默认服务使用按权威代和设置的 32 项 LRU；原单参 StructurePreviewCache 构造保持无限制缓存契约。

StructurePreviewSettings 保存根朝向、按 StructurePreviewPath 子边 occurrence 路径选择的切片数，以及 occurrence/cell-visit 上限；未选择使用服务端 min，选择夹在 min/max 内。展开保持模板/切片层级及每次子边 occurrence，不能以共享 node/ID 合并重复实例；全部相对坐标按根朝向旋转。零片 children 沿 base - sliceOffset，所有层仅排除固定控制器原点，坐标重叠以遍历后项覆盖。OPAQUE occurrence 明确记录，不猜几何。Model 同时交付 cells、包围盒、occurrences、LocalizationEnum 名字和全结构 BOM；Layer 仅筛选一个相对 Y 平面，不改变 BOM。BOM 以完整冻结 StructureDisplay（候选块/meta/NBT/tileNbt/key）等价值累计 long，fastutil addTo；缺表示以 null 行明确未知，不合并只有相同展示 key 的不同候选。Builder 自己拥有私有列表，单 owner 构造后一次 freeze 为零复制只读结果，冻结后拒绝任何追加。

`project(id, settings, anchor, options)` / `moveProjection(anchor)` / `stopProjection()` 已接实际世界投影意图；Style 提供 BOX_OUTLINE、TRANSLUCENT_BOX、BOX_AND_OUTLINE。原生实现以 CPU BufferBuilder 绘制单位盒轮廓或半透明盒，保存/恢复使用的 GL 状态，支持距离、相对层、每 tick 展开/观察与每 frame 访问额度及隐藏已观察 DISPLAY_MATCH。保留普通白色轮廓和未加载黄色轮廓。展示观察为 DISPLAY_MATCH/MISSING/DIFFERENT/UNLOADED/UNKNOWN：比较权威 display 的代表 state；不运行 matcher，也不证明成型或全量匹配。缺注册 ID 先 containsKey，禁止 Forge 默认 AIR 回退冒充有效代表；观察使用 isBlockLoaded(position, false)，不强加载或将 EmptyChunk 当已加载；NBT/tileNbt 无法代表性验证时明确 UNKNOWN。

ProjectionState 每轮绑定精确世界、意图、请求、模型、目录代、状态表和游标；观察回调返回后重新核对整个私有 round，回调重入移动/停止/换代/换世界不会覆盖替代状态，旧异常不能取消新意图或新轮。目录完成代切换会重新申请/展开，断连清意图；替代连接与世界按原身份隔离。持有 Model 的外部消费者使用前必须 `current(model)`，READY 不授予未来世界或目录使用权。

C 新增 26 项纯行为（2 suites），focused 26 tests / 2 suites、扩大受影响 126 tests / 14 suites，以及正常完整 `gradlew.bat build --console=plain` 1417 tests / 178 suites，failure/error/skipped 均 0。12 Java 本机 IDEA MCP 逐文件 get_file_problems(errorsOnly:false) 零错误/警告/弱警告，无 timeout/isError；完整 read_file 项目文件文本/磁盘 12/12 一致，不据此声称未保存 Document。MCP build_project 成功；12 Java 构建前后及最终 MCP SHA 稳定。原候选、三项独立 P2 修正与 mapped MC/Forge 源依据、IDE 格式化旧文本回退的真实首次 compile 失败、必要公开 API/局部 inspection 修正、8 文件 CRLF-only Spotless 失败/等价修正、原日志/XML、准确 formal-before 与最终 15 文件统一清单/快照保留于 `build/staging/structure-preview/verification/`。

本阶段实现与验证已完成；未启动 Minecraft、服务端或 Bootstrap/FML/native Block/Item 测试初始化。真实 OpenGL/World/注册表实机视觉仍未验收。完整纹理方块模型、模型隐藏/碰撞/复杂渲染、VBO/Bloom、预览 GUI 与 JEI 联动、结构准备工作预算及其他未取消能力继续后续；本阶段盒投影不缩减这些范围。

## D1：原生方块模型世界投影

`StructureProjectionOptions.Style.BLOCK_MODEL` 已沿同一 Java project 入口接实际世界绘制。现有无参和7参构造保持兼容；新增三个独立原生预算字段：blockModelBuildVisitsPerFrame默认4096、blockModelChunkVisitsPerFrame默认96、blockModelRenderAllChunksIfUnder默认128。C的展开/观察/每帧cell访问额度保持原含义，VBO一次区块draw不按其全部cell加权；不超过128个区块全部访问，否则每帧96个，空/远区块也付一次访问并前移游标。

具体PreviewBlockModels/PreviewBlockModelCache/QuadCursor复用当前完整且current的C模型，按相对16³分组烘焙SOLID、CUTOUT_MIPPED、CUTOUT、TRANSLUCENT，真实atlas/tint、fullbright和0.65顶点alpha保持。display只选择首个有效已发布block/meta，facing/axis用C同一变换，已展开位置只平移；NBT展示条件不解析成blockstate properties，不调用matcher/factory。静态构建逐cell/layer/getQuads/quad/上传layer续办，8项LRU精确释放；外部模型回调和GL driver调用本身仍是合作/原生边界。

隐藏展示命中或选择Layer时全静态VBO无权绕过过滤；无VBO与动态路径只消费本帧C Sample。逐格CPU复用同一QuadCursor增量准备，按当前model/cell保留轮换样本间的准确pending与quad绘制游标，稳定可见列表跨帧公平分配工作，最终实际提交仍按当前样本远→近排序。静态pending仅暂显本轮C BOX，不通过fallback重复同步烘整模型；缺display/有效代表项或尚未就绪样本明确BOX，之后准备完成到达原生模型，BOX不替代目标效果。代表项显式选择和邻居face裁剪、TESR-NBT继续D2。

所有模型/GL操作在client owner。目录完成代、resource reload、断连和服务关闭先撤销epoch/权限再drain，回调返回及upload前后复核；迟到CPU/VBO结果不能发布旧代。addon关闭同步通知原生资源，逆序订阅、重入/重复close、失败继续清理及原VMError抛出均保持；closed不表示driver删除已被实机证明。恢复包括矩阵/纹理绑定与unit/client数组、blend/depth/alpha/颜色/polygon offset；VBO COLOR数组绘制后先GlStateManager.resetColor再恢复RGBA，避免MC颜色缓存跳过真实glColor调用。

18项新增行为（含稳定双样本极低预算与透明提交回归）及focused18/1、受影响130/12通过；最终正常完整build1435 tests /179 suites零失败/错误/跳过。12 Java本机MCP errorsOnly:false全级零、完整项目文件读回/磁盘12/12一致、MCP build成功，构建和最终MCP的当前raw源码SHA稳定。准确C最终重接/写前/候选/修正delta/原诊断、正常日志XML和15路径统一清单见 `build/staging/structure-preview-block-models/verification/`。未启动游戏或Bootstrap/FML/native测试初始化；真实GL/驱动视觉不由纯测试证明。D1只闭合世界BLOCK_MODEL路径，D2 AEBaseGui/JEI/替代选择/TESR-NBT、D3成型模型/动画/Bloom/隐藏及整体其他保留能力仍继续。

## D2：AEBaseGui、交互、JEI 与 owned TESR

StructurePreviewGui 直接继承 AEBaseGui；公开 StructurePreviewScreens 与 concrete StructurePreviewScene/Panel 供 MachineGui 及不继承默认页的 RecipePoolJeiPage 组合。机器子页复用准确原 Container/window；HEI embedded wrapper 和机器独立 structure category 都接实际目录/机器关联。GuiOpen/InitPre/InitPost 原生事件保持，即时父页包括HEI RecipesGui；嵌套事件替换、owner撤销、取消与close reentry由原Session/Guard精确隔离。viewer阻止库存点击/AE滚轮/MouseTweaks和HEI槽位，原Slot/x/y/enabled快照在取消和返回重入中恢复；Back/Esc/背包等待已消费按下释放，embedded按准确panel/host刷新drag并跨普通关屏消费准确button的一次up，新同button down撤销旧up凭据。

相机轨道/平移/缩放/平滑/自转、相对层、同viewport的scissor/ray/点击选择及完整long BOM已接。代表项以完整StructureDisplay选择，切片以StructurePreviewPath的子边occurrence选择，不合并同ID共享节点；层不改变全BOM。View沿Request.advance剩余visits逐cell/BOM/membership/prune续办，完整索引后才publish；观察只保存稀疏已见状态及knownCounts，unknown由完整line.count扣除。native名字缓存按model/代表项/resource与语言重载失效，材料rows按token/count/name/query/sort/filter/scan复用，稳定帧不重建/重排。变更时全BOM排序、单次点击全cell精确拾取与第三方native回调仍是显式CPU边界。mesh与overlay样本游标独立且保留D1准备进展。新ProjectionOrientations直接复用AE rotateClockwiseAround(axis.getOpposite())，适配实际v1.1.0正UP→WEST到预览契约NORTH/UP→EAST；C/D1既有变换未改。

D1真实quad/atlas/VBO在GUI Scene复用，代表block/meta状态按完整邻接BlockAccess增量准备。owned TileEntity独立create，附client world/模型相对position但不插入World、不tick、不发包；SNBT每实体深拷贝覆盖x/y/z，再唯一调用一次handleUpdateTag(ownTag.copy())，应用后重设准确world/position。实际AE有#upd时handleUpdateTag仅读stream并返回、readFromNBT之后还loadTag，二者不等价，禁止异常后readFromNBT兼容回退。PE controller不伪造isAttached/authoritative Display更新，D3typed成型视觉另接。create/draw异常立即传播；取得实体后的失败invalidate，clear/close继续全部清理并聚合抛出，GL/lightmap/matrix恢复在finally。

原PM界面40 PNG（gui_structure_preview目录38、根a/b两张）及NOTICE按原relative路径保留，按钮/菜单/材料/替代列表沿原生GuiTextField与LocalizationEnum文案。命令/pe_preview gui <id>、<id>投影及stop/off，机器预览按钮与HEI独立结构页/内嵌页均为实际入口；世界朝向lock/follow、正/逆旋和HUD已接，GUI/chat输入不排队到世界控制。

focused47 tests /3 suites、受影响82 tests /8 suites及最终正常完整build1486 tests /183 suites全过，零失败/错误/跳过。41 Java本机MCP errorsOnly:false全级零、完整项目文件读回/磁盘41/41一致，MCP build成功，85源码资源raw SHA构建及MCP后稳定。原编译/首转/Spotless行尾失败、MCP诊断、固定推广/8路径窄修/准确before/89路径final交接与日志/XML见build/staging/structure-preview-gui；独立静态增量审计闭环。未启动Minecraft/服务端/Bootstrap/FML，不把fake-control/TE fixture当真实GL/TESR/HEI输入或网络包游戏验收。D2本阶段实现与验证已完成，D3成型模型/动画/Bloom/隐藏和其他未取消功能继续。

## D3-A：完整成型显示事实、typed动画与纯锚点

A本块实现和验证已完成；随后实际复杂renderer由下方D3 B+C+D完成。`api.machine.render.MachineRenderSnapshot`保存服务器lifecycle已安装的完整accepted occurrence树、origin/step/count、实际coverage和controller bounds、当前ComponentMap IDs及typed动画。capture只在joined owner boundary取样并复验原machine/host/configuration/catalog，要求机器声明type；不调用客户端matcher、不持有World/TE执行权。重复ID按parent/child-edge分别保留；模板origin已经含offset，PER_SLICE直接origin+i*acceptedStep，count0没有自身slice anchor但child保留服务器origin-step+自身offset接受坐标。模型自己的offset另旋转一次。bounds仅为实际coverage/controller，后续模型geometry AABB另合并。

`MachineAnimationState.TYPE/CODEC`沿原MachineType.Builder.component与MachineAssembly.Builder.component(TYPE,CODEC)声明/恢复，公开setLayers/setAnimation/clear只更新immutable ordered layers和checked long revision；实际执行context可更新，TE joined observer冻结同步，worker不操作TE/World。`api.client.render.MachineRenderBindings`按machine、实际component ID、machine内structure ID精确登记；原token关闭不能删除替代登记。GeckoModelBinding只声明普通geo/texture/animation位置、默认层/名称、model offset/yOffset/pass/global属性，不创建Gecko实例。formed且有structure声明时只走accepted树；unformed或无structure声明才machine/component fallback。有animation时typed非空层→默认层→默认名，无animation则层为空；后层覆盖骨骼的实际baker/driver已由下方D3 B接通。

原Display五字段不变，Render sibling使用strict独立schema/full signed xyz varint和16 KiB片。getUpdateTag/getUpdatePacket共用冻结首片，其余及ChunkWatch全片补发沿原SPacketUpdateTileEntity同pos/type；只向原世界、原connection和当前chunk观看者发送，不加载chunk。准确catalog输入/view配对覆盖reload短窗口撤权；连接/attached TE/坐标/type/Display generation/sequence/catalog session/sequence核验后，完整解码和完整目录相遇才一次发布。新header/目录/binding/resource/world/TE失效先撤current view，部分及晚到数据不恢复隐藏资格；native NBT tag不能改冻结Part，invalidate/installer重入和安装失败不覆盖或重新授予错误fact。root.hideWorldBlocks只是whole-tree coverage+controller的隐藏事实门禁，真正隐藏还要formed+current+drawable替代。

支持上限显式为64 MiB/4096片、65,536 occurrences/components、1,048,576实际position memberships/重复/anchors、256动画层（每名1024 Java chars/4096 UTF-8 bytes）。合法范围完整分片，不截断positions或count；超限与坐标/revision溢出清晰拒绝。API不可变事实无执行授权，实际render消费者须复验ClientMachineRenderSnapshots原View current token。

focused24/4、受影响122/17与最终正常完整build1510 tests /187 suites均通过，failure/error/skipped为0。20 Java本机MCP errorsOnly:false全级零、完整项目文件读回/当前磁盘20/20一致、MCP build成功；16main仅CRLF规范化后normalized诊断输入不变，20当前raw SHA构建及最终MCP后稳定。准确写前/原与fix-p2候选/三项审计/4shared D2重接/原compile和Spotless失败/最终运行XML、读回、23路径差异见 `build/staging/machine-rendering/a/integration/`。未启动Minecraft/服务端/Bootstrap/FML，不据纯行为或项目读回证明真实World watcher/网络包、未保存Document或GL验收。

A当时按本块范围停止；B+C+D随后完整接通以下渲染链，本轮在整个B+C+D完成后停止。

## D3 B+C+D：资产、原生模型渲染与形成模型预览

B使用原Gecko资源字节/动画loader、CPU骨骼层级/24向/静态和动画分区，保留独立任务调度、普通builder/packed bulk/mapped两槽、bounded合批和owned池/reader lease。native独立ZipFile的owner只选定并打开句柄，读取/解压在worker；FolderResourcePack保留原验证入口，覆盖的manager/custom pack在owner冻结原结果。资源消费者仅需Gecko JSON/ImageIO字节，metadata/getAllResources不属于等价声明。资源代/真实active reader退出和失败清理保持原义务，unmap失败整个结果不发布。world origin复用原camera-snapped coarse grid和负安全floorDiv，数值与int转换显式检查；不同机器共用origin，同origin/texture/light的实际drawMultiple继续合批，origin边界变化撤旧owner与hide权。

C外部native扩展继承公开 `MachineControllerRenderer<T extends AEBaseTile & MachineControllerHost>`，提供真实 `Class<T>` 和typed view/snapshots/models；public bounds/maximumDistanceSquared按原生调用路径派发，调用 `register()` 绑定真实类型，仅Basic选择默认TE。native RenderChunk worker分类不读World/view，owner读取完整current view，accepted bounds与CPU发射模型world AABB合并；未知/新view/origin/force-global采用有限保守边界，native clipping不会零乘无穷产生NaN，distance SRG refmap已验证。DEFAULT/TRANSPARENT/BLOOM复用原uploader，ordinary控制器combinedLight与独立lightmap、透明中心远→近、数组/绑定/GL恢复保持。可用native Bloom ticket/FBO/layer的0/1/2 worldPass回调保留同一fork至第二回调或pass结束；CMH准确slot/immutable collection身份和重叠保护，仅formed+current+完整且有真实vertices的替代才隐藏，旧view撤draw/hide权；延迟Bloom native draw失败经实际State/Owner/View scope回调撤权，后继View与独立同cache外部State不被默认proxy代替。native isInvalid查询与stale状态/hider/队列/snapshot及GL恢复的逐项错误聚合已闭环。

D在原D2 Scene/Panel/AEBaseGui/HEI模型上显式传可选MachineType，形成模型开关复用B+C；按selected occurrence/用户slice锚点构建owned Frame，不创建执行中的machine或临时服务器宿主。GUI原camera矩阵只应用一次，ZERO仅用于translation，透明排序使用同一camera ray的model-space eye。controller代表项通过公开typed previewState；原2D/相机/BOM/代表项/导航/父页返回及controller排除保持，40PNG和NOTICE未改。

最终focused53/10、受影响161/19与正常完整build1563 tests /197 suites全过；56Java本机MCP全级零、完整项目读回/磁盘56/56一致、MCP build成功，61源码资源构建及最终MCP后raw SHA稳定。最早before、固定V1/V2/V3/最终源码、几何/生命周期P2和native SRG映射审计、真实原失败/最终XML/MCP与64路径精确本地提交入口 `build/staging/machine-rendering/`。保留572背景修改和INDEX备份，不推送、不启动Minecraft/服务端/Bootstrap/FML；纯行为/静态审计不证明真实World/网络包/GL/Bloom视觉、driver删除成功或HEI输入实机行为。本轮完成整个B+C+D后停止，其他保留能力在后续授权下继续。

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

实际控制器/机器类型生命周期与调度入口已有集成，A 启动期 JSON/共享发布与 B 运行中重载/准确宿主撤销刷新/客户端权威目录缓存同步已实现验证。C 基础预览/盒投影与切片/层/BOM/display override 消费、D1原生BLOCK_MODEL/VBO世界路径均已接并验证。D2 AEBaseGui/JEI、代表项选择/邻居裁剪/owned TESR-NBT与D3-A完整成型状态同步/纯绑定锚点、D3 B+C+D资产/CPU bake/native TESR/Bloom/隐藏及形成模型开关均已实现验证；本轮整个B+C+D完成后停止，结构准备工作预算及整体其他保留能力属于后续授权。基本盒投影和D1不缩减这些后续迁移范围。游戏内集成未验收；本阶段实现与验证已完成。
