# 结构迁移源码审计

本文是 2026-11-07 对 PM 本地源码及新项目已有仓室契约的审计，记录旧行为、缺陷和迁移约束。**这不是新结构实现完成报告；真实世界快照、完整结构匹配与控制器宿主接线仍须实现和验证。** 本次审计没有修改旧项目，没有启动 Minecraft，也没有真实 World/TileEntity 运行证据。

需求依据是当前用户指示及 [development-spec.md](development-spec.md)：保留 24 向、模板/切片、子结构、JSON 加载、投影/预览和复杂渲染效果；后台计算只能读取受控快照。新格式可以不兼容，已确认的旧缺陷可以修正，不能将完整结构能力简化成固定模板。

## 1. 模板、切片与递归锚点的实际旧行为

| 行为 | 源码证据 |
|---|---|
| 模板先计算 `offsetOrigin = origin + offset`，按 pattern 顺序检查；随后 validators，再 children；全部条件为 AND。 | [TemplateStructure.kt:46](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:46)、[73](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:73)、[81](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:81)。 |
| 模板 validators 接收本层 `offsetOrigin`；所有 children 都以这个位置为传入 origin，随后各自再加自己的 offset。它不是始终指向机器控制器的位置。 | [TemplateStructure.kt:74](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:74)、[82](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:82)。 |
| 切片从 `origin + offset` 开始，沿 sliceOffset 检查连续 pattern，贪心累计至第一次不匹配或 maxCount。不是任意选择满足 min 的长度，也没有 children 失败后的缩短重试。 | [SliceStructure.kt:51](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:51)、[63](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:63)、[89](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:89)、[122](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:122)。 |
| count 先满足 min/max，再写入本层 instance data；切片 validators 只在基础 offsetOrigin 执行一次，不是每片一次。 | [SliceStructure.kt:101](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:101)、[107](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:107)、[112](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:112)。 |
| 切片 children 只匹配一次，传入 origin 是 `offsetOrigin + sliceOffset * (matchCount - 1)`，即最后成功切片的锚点。 | [SliceStructure.kt:120](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:120)。 |
| transform 旋转 orientation、offset、pattern、sliceOffset 和 children；validators 原样共享。 | [TemplateStructure.kt:26](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:26)、[SliceStructure.kt:28](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:28)。 |

### 零片、空 pattern 与加载边界

- `minCount=0` 不代表跳过探测。只要 `maxCount>0`，仍先尝试第一片；第一片 pattern 不匹配才得到 count0，第一片区域未加载则整结构失败。[SliceStructure.kt:63](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:63)、[68](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:68)、[89](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:89)。
- `minCount=maxCount=0` 不进入 pattern 循环，仍要求 world 存在并执行 validators/children。[SliceStructure.kt:54](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:54)、[63](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:63)、[112](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:112)。
- 空 pattern 的 bounds 是 `(0,0,0)`；每次已加载的切片自动匹配，累计至 maxCount。它不是永远 count0。模板空 pattern 同样仍检查本层 origin 的加载状态，再执行 validators/children。[SimpleStructurePattern.kt:13](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/SimpleStructurePattern.kt:13)、[TemplateStructure.kt:51](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:51)。
- 每个实际尝试的 pattern 在 predicates 之前检查整个 bounds；稀疏 pattern 中间没有谓词的区块仍属于检查范围。调用 `world.isAreaLoaded(from, to, false)`，不强制加载区块。[StructurePattern.kt:45](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/structure/pattern/StructurePattern.kt:45)。
- 切片下一次探测区域未加载时直接整结构失败，即使此前 count 已达 min；未加载不是普通 pattern 不匹配。达到 max 后则不再探测下一片。[SliceStructure.kt:63](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:63)、[68](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:68)。
- sliceOffset 为零时旧代码仍进行有限的 maxCount 次检查，最终位置集合去重；旧构造器/加载器没有禁止这种配置。迁移时如增加限制，应明确记录格式约束变化，不能把它误当旧规则。

## 2. 已确认的缺陷与迁移取舍

### 控制器位置与子结构 origin 混淆

模板用 `actualPos == origin` 跳过谓词，切片也相同。根结构时 origin 是控制器，递归时它已是父层 offsetOrigin 或最后切片锚点。因此子结构会跳过错误的局部锚点，而真实控制器也可能不再被排除。[TemplateStructure.kt:62](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:62)、[SliceStructure.kt:79](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:79)。

JSON 转换又无条件删除 `offset=(0,0,0)` 且 `pattern.pos=(0,0,0)` 的元素，没有区分根结构、子结构和切片；切片删除后的元素也不会在后续片重新出现。[StructureLoader.kt:685](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:685)。

迁移应在匹配上下文中保留独立、固定的真实 controllerPos，递归 anchor 独立传递，仅在运行时按真实控制器坐标排除。加载阶段不能把局部原点当控制器删除。是否将控制器加入渲染 bounds、是否在结构组件发现中排除控制器，也应使用这个固定身份；旧位置收集未排除它。

### count0 的 children 位置不一致

匹配使用 `sliceOffset*(count-1)`，count0 时得到 `offsetOrigin-sliceOffset`；纯预览 builder 也如此。位置收集和客户端渲染 anchor 则把 `(count-1)` clamp 到零，得到 offsetOrigin。

- [SliceStructure.kt:120](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:120)。
- [StructurePreviewBuilder.kt:71](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/preview/StructurePreviewBuilder.kt:71)。
- [StructureBlockPositions.kt:58](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/StructureBlockPositions.kt:58)。
- [ClientStructureRenderAnchors.kt:91](F:/clone/PrototypeMachinery/src/main/kotlin/client/impl/render/binding/ClientStructureRenderAnchors.kt:91)、[150](F:/clone/PrototypeMachinery/src/main/kotlin/client/impl/render/binding/ClientStructureRenderAnchors.kt:150)。

必须确定一套零片规则，并同步匹配、capture footprint、位置收集、预览和渲染。保留旧匹配/纯预览布局对应 `-sliceOffset`；选择 clamp 规则属于明确修正，不能只改 matcher 而保留其余旧偏移。

### 失败分支与 instance 发布

退出时先 pop frame，只有 matched=true 才构造 instance 并追加到父层或发布根。因此某 child 成功、后续兄弟失败，随后失败父 frame 被丢弃，不会把该失败分支的 children 发布到外层。[StructureMatchContextImpl.kt:45](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/match/StructureMatchContextImpl.kt:45)。

但 rootInstance 未在新一轮进入或失败退出时清空：同一 context 先成功后失败，getRootInstance 仍可取得旧根。[StructureMatchContextImpl.kt:22](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/match/StructureMatchContextImpl.kt:22)、[37](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/match/StructureMatchContextImpl.kt:37)、[65](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/match/StructureMatchContextImpl.kt:65)。

真实旧宿主每次创建新 context，而且只在 matched=true 时读取 root，避免了这个复用风险。[MachineInstanceImpl.kt:444](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:444)、[455](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:455)。新实现应使用每轮独立结果或入口清根，只发布完整成功树。上述 frame 机制并不会撤销外部 predicate/validator 自身的副作用。

### 旋转与计数身份缺陷

- validators 在 transform 中原样共享，而 tile_nbt validator 直接 `offset + check.pos`，没有旋转 check.pos。非默认方向的检查位置因此不随结构旋转。迁移应提供明确的 validator 转换或局部坐标读取。[TemplateStructure.kt:33](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/TemplateStructure.kt:33)、[SliceStructure.kt:38](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/SliceStructure.kt:38)、[TileEntityNbtStructureValidator.kt:103](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/validator/TileEntityNbtStructureValidator.kt:103)。
- 普通 StatedBlockPredicate 旋转 EnumFacing 和 Axis 属性；NBT 版本和 AnyOf 只处理 EnumFacing，存在状态旋转不一致。[StatedBlockPredicate.kt:51](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/StatedBlockPredicate.kt:51)、[StatedBlockNbtPredicate.kt:83](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/StatedBlockNbtPredicate.kt:83)、[AnyOfBlockPredicate.kt:54](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/AnyOfBlockPredicate.kt:54)。
- 同 ID 的多个 slice instance 被扁平计数映射覆盖，不能表示不同 occurrence 的不同长度。[StructureSliceCounts.kt:40](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/StructureSliceCounts.kt:40)。迁移结果应保留完整 instance 树及 occurrence/path 身份，渲染同步再按其需要编码。

## 3. 结构位置与世界事实捕获边界

旧位置收集从成功 instance 出发，收集模板 pattern、实际 matchedCount 个切片及成功 children，用 LinkedHashSet 去重。validator 的额外检查位置不会自动成为组件位置。收集硬编码具体 TemplateStructure/SliceStructure，未知实现只递归 children；外部公开结构类型不能直接复用这个限制。[StructureBlockPositions.kt:30](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/StructureBlockPositions.kt:30)、[46](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/StructureBlockPositions.kt:46)、[69](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/StructureBlockPositions.kt:69)。

### 最大 capture 范围不能变成新的成型前置条件

最大范围只是可读取数据的上界。可以捕获潜在切片、每个可能 count 的 children 锚点，并记录未加载/无法取得事实，但 matcher 只检查真实走到的分支。

例如 slice0 成功，slice1 已加载但 pattern 不匹配，旧代码即停止并可能接受 count1；slice8 未加载不能让 capture 提前拒绝。若 slice1 本身未加载，旧代码则失败，不能把该事实改成普通不匹配而接受 count1。匹配达到 max 时也不能要求 max 之外的探测数据。

同样，潜在且最终未访问分支的 NBT/capability 捕获失败不能自动否决根结构。实际要求读取该事实的分支应失败；捕获阶段不应因整个最大 envelope 有未加载区块而直接生成“结构不成型”。该边界适用于每个可变长度子结构的候选锚点。

### 主线程读取与不可变快照

- 主线程完成 World/区块加载检查、方块状态读取、getTileEntity、writeToNBT、Forge hasCapability/getCapability 和外部 provider 查询；后台只处理安全发布的不可变事实。不能把 TE 或可变 NBTTagCompound 暴露给 matcher/predicate。
- 捕获需求不能只覆盖 pattern.cells。tile_nbt validator 可以读取 pattern 外的位置；dimension、day/night、weather、biome 等 validator 也需要对应世界事实。所有可能的 child 锚点下的已声明读取必须能被捕获。
- `has_tile_entity` 的旧语义是 `state.block.hasTileEntity(state)`，不是“当前存在一个 TE”。该事实宜在 capture 求值；改成 TE 非空会改变语义。[HasTileEntityPredicate.kt:20](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/HasTileEntityPredicate.kt:20)。
- 精确方块和 alternatives 使用 canonical IBlockState 身份相等；not_air 同时排除 Blocks.AIR 和 Material.AIR。[StatedBlockPredicate.kt:19](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/StatedBlockPredicate.kt:19)、[AnyOfBlockPredicate.kt:30](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/AnyOfBlockPredicate.kt:30)、[NotAirBlockPredicate.kt:20](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/NotAirBlockPredicate.kt:20)。
- 旧 NBT 匹配是 root 浅层比较：String tag 用 getString，其余 tag 用 toString；字段缺失即失败。TileEntityNbtPredicate 的空约束直接 true，StatedBlockNbtPredicate 先验证 state，再判断是否有 NBT 约束。[TileEntityNbtPredicate.kt:24](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/TileEntityNbtPredicate.kt:24)、[StatedBlockNbtPredicate.kt:39](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/pattern/predicate/StatedBlockNbtPredicate.kt:39)、[TileEntityNbtStructureValidator.kt:117](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/validator/TileEntityNbtStructureValidator.kt:117)。
- 内置 dimension/day/weather validators 直接读取 World；这些条件可改为对捕获事实判断，不必把 World 带到 worker。[BuiltinStructureValidators.kt:16](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/validator/BuiltinStructureValidators.kt:16)。
- 旧外部 validator 能从 context.machine 取得世界，Zen 包装甚至直接暴露 IWorld 并查询 biome。新 predicate/validator 面向只读快照是必要边界；外部扩展须声明其捕获需求，不能通过补回 World getter 继续后台读世界。[StructureMatchContext.kt:20](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/structure/match/StructureMatchContext.kt:20)、[ZenStructureMatchContext.kt:36](F:/clone/PrototypeMachinery/src/main/kotlin/integration/crafttweaker/zenclass/ZenStructureMatchContext.kt:36)、[47](F:/clone/PrototypeMachinery/src/main/kotlin/integration/crafttweaker/zenclass/ZenStructureMatchContext.kt:47)。

旧宿主把“只读匹配”投到 concurrent worker，实际 predicates/validators 仍直接读 World/TE，所以只读描述不能证明线程安全。[MachineInstanceImpl.kt:417](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:417)、[442](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:442)、[505](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:505)。

结果提交到主线程也不能仅检查机器 active：还须核对控制器身份/朝向、输入事实仍有效和本轮任务权限，避免迟到结果发布。旧 apply 只检查 world、remote、active，并且已成型、朝向未变时不重建结构组件，即使切片数量或 provider 已变化；这些都是实际宿主迁移需要补齐的边界。[MachineInstanceImpl.kt:519](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:519)、[543](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:543)、[558](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:558)。

### 仓室发现与组件创建

旧结构组件 provider 的创建在主线程 apply 中进行，读取 TE 并调用 createStructureComponents；这个过程不是纯快照计算，不能搬到 worker。[MachineInstanceImpl.kt:498](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:498)、[582](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:582)、[588](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:588)。

新项目已有公开 HatchCapabilities 查询入口及 MachineHatches 不可变绑定。结构识别与运行发现应查询同一套公开契约；主线程捕获 capability 存在性/所需事实，在成功结构的提交边界安装绑定，不能只凭内置 TE 类名排除外部仓室。传给配方执行的同步存储/能源契约也不等于允许结构 predicate 在 worker 上任意查询 TE。

- [HatchCapabilities.java:34](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/HatchCapabilities.java:34)、[43](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/HatchCapabilities.java:43)、[50](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/HatchCapabilities.java:50)。
- [MachineHatches.java:18](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/component/MachineHatches.java:18)：发现属于服务器线程，绑定安装在机器所有权边界。

## 4. JSON 加载能力与缺陷

必须保留的能力包括 PreInit DTO 读取、PostInit 方块解析、模板/切片、children ID 引用和循环检测、同定义引用复用、validators 参数、组合 AND predicates、方块 alternatives、regex 以及独立于匹配的 display override。新格式不要求复刻旧序列化形式，但应覆盖这些实际职责。

- [StructureLoader.kt:95](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:95)、[163](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:163)、[349](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:349)、[608](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:608)、[752](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:752)。
- [StructureData.kt:241](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/serialization/StructureData.kt:241)；predicate/validator 字符串或 object 参数形式见 [110](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/serialization/StructureData.kt:110)、[204](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/serialization/StructureData.kt:204)。

可修缺陷：

| 缺陷 | 源码证据与影响 |
|---|---|
| 缺 child、未知 predicate/validator、validator 参数解析失败后跳过。 | [StructureLoader.kt:395](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:395)、[641](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:641)、[790](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:790)、[798](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:798)：会放宽定义，应可严格拒绝坏定义。 |
| alternatives 任一分支带 NBT 时退化为仅 base option。 | [StructureLoader.kt:719](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:719)：旧限制不是完整 alternatives+NBT 功能，新实现不能将该降级误记成完整支持。 |
| 重复 pattern 坐标后项覆盖。 | [StructureLoader.kt:769](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:769)：可以拒绝重复，避免悄悄丢条件。 |
| minCount/maxCount 仅检查字段存在。 | [StructureLoader.kt:422](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:422)、[521](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:521)：未校验非负和 min≤max。 |
| registry replaceAll 是 clear 后逐个填充。 | [StructureRegistryImpl.kt:74](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/structure/StructureRegistryImpl.kt:74)：ConcurrentHashMap 不保证整批原子发布，读者能看到空或部分版本。 |
| 单个文件 parse 失败仍可能转换并发布其余定义。 | [StructureLoader.kt:243](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:243)、[286](F:/clone/PrototypeMachinery/src/main/kotlin/common/structure/loader/StructureLoader.kt:286)：报告 ok=false 不代表旧注册表未变化。可修为完整候选验证后一次发布。 |

上述为源码证据与迁移约束，不替代新实现的构建、逐文件 IDEA MCP 检查和行为验证。真实加载/卸载、NBT/capability capture、控制器失效与迟到结果隔离仍须在后续阶段记录实际证据。
