# JEI 配方池页面

## 2026-11-12 结构候选点击与空槽

结构预览左侧候选使用原生ingredient槽，但鼠标左键优先只替换当前选中的cell，右键替换该StructureDisplay对应的全部可替换同类并清理旧单格覆盖。当前材料也支持右键统一整类。原生物品tooltip附双语操作提示；R/U、书签及非左右鼠标绑定仍由HEI处理，固定BOM只读槽保留原生浏览行为。

HEI的InputHandler会以HIGHEST/receiveCanceled接收Forge鼠标事件，并先于wrapper点击检索ingredient；单纯取消Forge事件不能保证候选操作先执行。client-only、Pseudo的HeiPreviewInputMixin在其真实handleMouseEvent入口优先交给已有PreviewEmbeddedInput，按当前实际绘制/owner/token及捕获release确认输入；不对其它页面或区域消费，原drag/wheel/关闭身份门保留。

嵌入式候选侧栏直接使用展开后的槽位坐标，避免滑入期间因“未完全处于clip内”而只画白框不发布材料。wrapper按实际物品/位置变化更新并复用原生GuiIngredient，不每帧清空重建；隐藏、滚动或模型/选择改变仍及时撤销旧槽。原生pick首次返回空图标不缓存为永久空值，后续帧继续取实际材料。不自行启动游戏，构建和源码检查不代替原生鼠标/GL复验；新增Mixin须完整重启，F3+T不足以应用。

## 2026-11-11 结构页原生材料槽与转移就绪

结构页的候选和固定用料注册到该layout实际IGuiItemStackGroup，由HEI绘制物品、数量、高亮和tooltip，书签/配方/用途键可识别实际槽位；隐藏、滚动或选择改变时撤销旧槽。固定用料与构造器共用5列横向逐行网格和独立滚动条，保持完整用料及单格选项拆分，不因点中某方块改成1件清单。

转移按钮原先在异步预览未创建时缓存了“等待完整预览”错误，之后未重查。现在材料真正就绪或选择代改变时，对同一个原生RecipeTransferButton重新init；仍从完整模型统计全结构材料，仅排除原点控制器，任意片层不改变169材料，未知/partial材料仍拒绝。用户从普通/PEA样板终端完整重启后复验：等待可见预览就绪，按钮应恢复可用，实际编码名称应本地化。此次没有新增Mixin或改终端写包语义。

## 2026-11-10：静态回退与资源重建修复

原故障是 frozen provider 基线生成了新的 display 对象，而静态页面继续复用旧引用量测。现在 category 的最终 descriptions Snapshot 同时供默认页测量、初始 wrapper 和 query index 使用；静态重建只有 recipe/provider 身份相同时复用旧描述，默认页仍按当前 Snapshot prepareUpdate。无 prepareUpdate 的独立旧自定义页只在 pool、description 和已确认 page Data 均准确相同时保留原 factory；发生部分安装失败后不能误用该路径。原未测量断言、wrapper身份校验和 managed declaration/owner/token/原生确认门保持。

资源/category 重建在没有已解析 managed declaration 时采用原 local baseline catalog与本次注册事件最终 provider来源，不继承上个远端 managed池。新增六项实际默认量测/独立引用缓存/wrapper行为回归覆盖静态gen0、断连、重建、远端后回本地和拒绝错误pool/provider/Data。最终执行结果见[实施进度](implementation-status.md)；真实HEI启动和查询仍待用户复验。

本阶段对应 [开发文档](development-spec.md) R18/R19 与 4.7。当前已恢复 AE2S 所需 HEI `mezz:jei:4.35.1`，直接使用 `mezz.jei.api` 原生分类、包装、ingredient 与 Forge 输入生命周期。下文各阶段验证记录是历史证据，最新 GUI/样板导入边界以下节为准。没有新增正式机器、配方或玩法数值。

## 2026-11-09 默认布局实机反馈修复

### 催化剂、提示与原背景后续反馈

取消默认页面的“适用机器”标题、悬停列表和点击选择面板。控制器仍沿 `PrototypeJeiPlugin` 的原生 catalyst 展示；独立结构预览分类/控制器配方入口保留，canonical `pool.machines()` 仍供自定义页和外部查询使用。

对照 PM `JeiPanelRuntime` / `JeiBackgroundSpec`：恢复 `jei_base.png` 原九宫格背景，边框 2px、fillCenter=false，PNG 与原文件逐字节一致。移除标题预留空间后，为原边框留内距：材料槽框从 (3,3) 与 (99,3) 起，完整保留两侧4×4；说明从 y=80 开始、左右内距4，能源图标x=4且原生ingredient范围同步，最小高度148，长文仍按真实字体扩展。页脚留出底边框。

物品与 GenericStack 非物品提示共用 `appendRecipeDetails`：按现有 Start/Tick/End 标记和输入/输出 role 显示“开始时 消耗 …”“每 Tick 产出 …”“完成时 产出 …”；去掉独立阶段行及“每单位 1”，概率分别为“消耗概率”/“产出概率”。原生物品保留完整 long，非物品保留 AE 的完整数量/单位及其资源名称提示。候选/检查点/属性说明保留；阶段标记仍在原数据中，因此样板 Tick×duration 不受文案重组影响。未指定阶段的自定义条目只写消耗/产出，不虚构阶段。

39 tests/10 suites专项、完整1693 tests/221 suites全零，12 Java与两语言本机全级零，MCP build成功。首轮新增文案断言空格不一致的失败记录保留，未放松阶段/数量断言。证据 `build/staging/jei-catalyst-only/`；没有启动游戏，视觉/鼠标交互仍待用户复验。


### 能量条目恢复

用户截图复核：当前默认页同样使用 PM 原 18×18 `energy/1x1` 空/满电池纹理、输入/输出 LED 与同一填充比例；四张 PNG 已逐字节核对。当前排布遵守双侧4×4材料框和完整说明要求，不等同于截图中的自定义示例布局。本次将原本错误覆盖整个说明行的 JEI 能量交互槽收回电池自身 18×18 范围，说明文字区域不再触发能量 ingredient 检索。随后用户指出已显示的文本不应再悬停重复：删除整个说明区的重复 tooltip，并去掉箭头上重复的加工时间提示；保留页面上的完整文字、电池模块的原生能量条目提示/检索、材料说明和操作按钮提示。默认布局未替换。证据 `build/staging/jei-energy-hitbox/`；实际游戏视觉待用户复验。

PM 的 `EnergyJeiIngredientListRenderer` 在 JEI 列表借用红石表示消耗、荧石粉表示产出；实际注册的是自定义能量 ingredient，并未注册新的实体物品。配方页由布局选择文字或能量模块：`DefaultJeiMachineLayout` 为首个能量节点明确选择 1×3 模块；用户截图对应的 `scripts/examples/io_test_jei.zs` 则明确选择 1×1 模块，输入/输出分别放在 (56,6)/(102,6)，逐 tick 放在下一行。只有未指定 variant 时才使用 renderer 的文字默认值。透明 `EnergyJeiIngredientSlotRenderer` 提供 tooltip、焦点和点击检索。原 `EnergyRequirementJeiRenderer` 顶部“energy is not a JEI ingredient type”注释已经与其实际 `declareJeiSlots` 接线不符，以代码为准。

本项目通过 `JeiEnergyIngredients` 恢复这套能力：注册同一个 TYPE 和消耗/产出×一次性/逐 tick 四个列表条目；`RecipePoolJeiRecipe` 对完整能量数据建立索引，默认每条能源说明附透明 JEI 槽。Helper 的查询身份只含方向和时机，忽略数量；tooltip 保留真实 FE 或 FE/t 数量。自定义页面可调用同一 `slot` 方法。旧 `Energy(amount, role)` 与 Builder.energy 重载仍保留，默认一次性。

该 FE 条目与原生物品/非物品 GenericStack 材料分组各自注册；材料候选和样板导入仍读取原分组，独立 FE 不伪装成可导入的物品。两项回归测试覆盖数量无关的检索身份以及完整能量索引，完整 1693 tests / 221 suites 全零，末次注解/record 修改后的专项两项及 build 通过。本机全级检查和 MCP build 成功；实际 JEI 点击查询待游戏验证。证据 `build/staging/pm-tools-and-lighting/`。


用户截图中的第二页由“说明每页两条”产生，与材料溢出无关。本次取消说明分页：页数只按两侧材料组每页16槽计算，每个材料分页重复完整说明。输入、输出固定各4×4槽位，category在原生ingredient之前独立绘制全部PM plaid背景，不加入占位ingredient，也不污染查询或样板导入。

参照MMCE的CategoryDynamicRecipe/DynamicRecipeWrapper，将说明实际换行所需高度计入分类。生产使用当前Minecraft FontRenderer的行高和listFormattedStringToWidth，同池取最大高度；最小高度现为148px（已移除机器标题并为原边框留出内距），说明超过时向Y展开，页脚按钮、悬停与滚轮边界使用同一实例高度。能源图标留18×18，文字另留宽度，按图标与多行文字的较高者排版；所有说明不截断。语言/资源重载重建category时重测，不复用旧语言高度。加工时间只在说明区出现一次并参与换行，箭头保持原PM模块并对齐材料区中心；机器信息由原生控制器催化剂显示。

文案沿MMCE的“加工时间”“能量消耗/产出”表达，分清一次启动/完成FE与FE/t；移除默认配方ID和基础定义/modifier/overlay介绍，缩短阶段、概率、候选及动态示例提示；属性的添加/移除分别说明，不再输出布尔开关。资源的Tick语义标记、原生物品/非物品GenericStack、同槽候选与全量样板导入保持。

专项33 tests/8 suites通过，包括全材料页重复37条说明、真实默认wrapper的长说明高度/完整提示/滚轮边界/33组导入、字体变更重算，以及能源图标与多行段落不重叠。正常完整build为1682 tests/217 suites，12 Java及两语言本机全级零，MCP build成功。证据在`build/staging/jei-default-layout/`；未启动游戏，实际OpenGL与屏幕布局待用户复验。首次机器标签边界旧断言失败、清理固定参数时遗漏引用的编译失败以及新Java行尾格式检查失败均保留，最终结果见implementation-status。

## 2026-11-09 GUI 修复后的显示与样板导入（历史）

保留按池最大槽数、候选分组和全量分页；移除重画灰底/槽框及 Input/Output 标题，恢复 PM item plaid、18×18 进度模块、原能源图标。为能源图标保留原尺寸，信息每页两项；资源仍每侧每页最多 16 项。没有改变每池默认/自定义实例的公开边界。

用户最终指示覆盖早先 converter-first 方案：仅物品走原生物品 ingredient，所有非物品统一 GenericStack.wrapInItemStack，按 AE2S 元件详情页使用 AEKeyRendering、StackSizeRenderer 和 AE tooltip。包装资源和原生物品共享 ITEM bucket，索引/清理一次完成，不互相覆盖。自定义流体皮肤 API 仍可用，但默认不再切回原生流体槽。下文历史“无 converter 仅展示/不能查询”的描述已由包装槽位接线替代；实际查询仍沿 AE/HEI 自身的包装与 helper 语义。

AE2S 原导入链可以解包 GenericStack，但 PE 旧一单位查询图标及只暴露当前分页会导致导入数量为 1 或漏掉其他页。本轮按池为普通样板终端和 PEA 终端注册 PatternRecipeTransfer；从当前实际 wrapper 的完整显示分组构建 JeiPatternRecipe，复用 AE 的候选优先级/书签、导入事件、SET_FILTER 包、窗口校验和加工样板元数据。无需新 Mixin；读取 wrapper 使用 AE2S 已有 AccessorRecipeLayout。

用户明确按展示导入：概率/多可选材料/动态示例不额外阻止；多可选材料使用 JEI 同槽候选轮播，对应输入/输出槽按声明顺序对齐，嵌套候选继续归入同一材料槽，导入交由 AE2S 的候选优先级选择；逐 tick 资源数量乘配方 durationTicks，概率不折算，不执行 matcher。合并输入遵从终端开关，候选交集合并沿 AE 规则；超出当前终端输入/输出槽或 long 算术范围时，在任何写入前返回明确错误。自定义页可通过 wrapper.patternRecipe() 提供与其显示一致的分组。

证据入口 `build/staging/gui-original-parity/`：pattern-displayed-focused 的 29 tests/7 suites、最终 displayed-full-build、idea-complete-final 与 idea-displayed-final。本机源码核对从 GenericIngredientHelper/ItemIngredientConverter/GenericStack，到 InventoryActionPacket/ConfigGuiInventory/PatternEncodingLogic/AEProcessingPattern；真实客户端、网络及样板编码尚待用户实机复验。

## 注册与外部扩展

`PrototypeJeiPlugin` 由客户端 JEI `@JEIPlugin` 扫描发现。`registerCategories` 使用已冻结的 `RecipeRegistry.INSTANCE.pools()`，发出客户端 `RecipePoolJeiRegistrationEvent`，随后冻结展示提供器与页面绑定并注册分类。事件提供真实池列表、`IGuiHelper`、页面注册表和展示注册表；外部客户端订阅者可调用 `event.pages().register(pool.id(), page)`，页面直接实现 `RecipePoolJeiPage`，无需继承默认具体类。

普通规范配方池对应一个生效页面实例。未自定义的池创建一次 `AutoLayoutRecipePage`；自定义实例替代该池默认绑定。分类 UID 由稳定池 ID 生成；重复绑定、未知池、伪造同 ID 池、错误 UID 和错误 wrapper 归属被拒绝。页面绑定完整验证后统一发布。R32 从 canonical pool.machines() 的静态绑定匹配 canonical 控制器并引用同池 catalyst，不再根据 recipe group/workslot 猜关联；公开 `catalyst` 入口可追加导航物品，没有伪造控制器产物或第二份配方目录。

`RecipePool.hasExistingJeiPage()` 为 true 且 `existingJeiPageId()` 是有效已注册分类 UID 时，直接使用外部页面，仅追加本池机器及显式 catalyst；不创建本池分类、配方 wrapper 或样板 transfer handler。`DefaultRecipePool` 和 `SimpleRecipePool.withExistingJeiPage(uid)` 保留设置，canonical freeze/定义替换也保留。存在外部页面请求时，注册通过 `ModRegistry.createRecipeRegistry` HEAD 的无反射 Mixin 延后到全部插件注册结束，覆盖后注册的旧插件分类。空/未知 ID 回退普通页面；明确禁用的分类保持禁用。多个池指向同一 UID 时机器入口去重。

局部定义替换沿原分类准备新 wrapper，确认 HEI 的 category/focus/recipe-ID 映射后激活 token；旧 wrapper 和样板交互立即拒绝旧 token。外部页只保留绑定，不向外部追加本库配方。远端需要准确本地声明与服务器 notice/Ready 阶段；缺声明或 runtime 明确未就绪。当前工作区完整 1868 tests/247 suites 全零，原生 HEI 启动与真实网络调度仍待实机。协议与证据见 [定义替换](recipe-definition-replacement.md)。

`RecipePoolJeiPage` 直接扩展 `IRecipeCategory<RecipePoolJeiRecipe>`。自定义页面负责原生背景、布局与槽位，可重写 `createRecipe` 返回自己的 wrapper 子类，并实现原生绘制、tooltip 和 click。当前 JEI 的 IRecipeWrapper 没有 scroll/drag/release 回调；wheel 使用 RecipePoolJeiRecipe.handleMouseScroll 和原生 Forge bridge。自定义 wrapper 重写 drawInfo 时调用 super.drawInfo 或 JeiRecipeInputs.drawn，登记实际绘制边界。wrapper 保留传入的规范 page、recipe、display 与 ingredients。`RecipeJeiDisplays.register` 按精确 requirement type 注册自定义展示职责，事件结束后不可追加；未知类型保留明确自定义入口说明。

页面与 JEI 类型位于明确的 `api.client.jei` / `integration.jei` 客户端边界。common `RecipePool`、`RecipeRegistry` 和 `MachineRecipe` 不持有页面或 JEI 类型，普通注册/服务端初始化没有新增客户端类引用。标题保留现有 translationKey，由 `RecipePoolTitle` 适配 `LocalizationEnum.getLocal`；库文案用 `JeiText`，两语言各追加 31 键。

## 默认显示与查询边界

默认页面固定两侧各4×4材料背景与中间加工箭头；每侧每页最多16组材料，说明完整换行且不单独分页。每个wrapper自持材料分页状态，按钮与原生Forge wheel bridge可遍历全部材料；页脚位置和点击/滚轮边界随池级测量高度向Y展开。每次切换配方或分页先清空全部已初始化类型/槽位；索引与样板导入使用所有分页数据。scope随每条资源/信息保存。

`DefaultRecipeDisplays` 只读已知准确 built-in type 的定义，展示 duration、START/TICK/END 资源、独立 FE 参数、概率/属性乘数、fuzzy 候选、dynamic 显式示例、weighted 权重/无放回/pickCount、selector 多可选材料、checkpoint 完整子生命周期/时间缩放、属性与 void 输出语义。它不执行 matcher、modifier apply、随机采样、运行阶段或未知 children 回调，多可选材料保持同槽候选关系。

资源以 AEKey/GenericStack 保存真实 long 数量。原生物品 converter 转换一个单位的身份图标，非物品包装保留真实 long 数量，数量标签使用 AE2 格式器，tooltip 提供精确原始单位；FE 是独立说明，不引入虚拟能量物品或 Applied-Flux 重复需求。

原生物品转换仍核对准确 AEKey 往返和已知 helper UID；其余资源统一使用包装 GenericStack 进入 ITEM 槽位及索引，渲染走 AEKeyRendering，缺少 renderer 时保留明确占位。JeiQueryIdentityIndex 保留已知集合的碰撞诊断；包装资源不再因缺少原生 converter 被限制为仅展示。

正常查询仍使用 JEI 原生 helper 匹配语义。已知集合碰撞检查不证明未知后续 NBT 变体的全域单射：若以后出现未枚举变体，而第三方 helper 忽略其 NBT，它仍可能按该 helper 语义匹配已有配方。本阶段没有解析 UID、反射或覆盖其他模组全局 subtype 规则。

## 初次接入的历史验证与范围

最终验证：17 Java 本机 IDEA MCP `errorsOnly:false` 零错误/警告/弱警告，逐文件完整 `read_file` 项目文本与磁盘一致；两个 suite 12 项行为全通过，完整正常 `gradlew.bat build --console=plain` 为 1259 tests / 159 suites，零失败/错误/跳过。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`，19 源码资源构建前后 SHA 稳定，17 Java 在 MCP 验证后无变化。此读回不声称未保存 Document buffer。语言最终仅恢复原 CRLF，统一 LF 文本完全相同；MCP build 在行尾恢复前成功，17 Java 未变，证据 `language-line-ending-equivalence.json`。证据位于 `build/staging/jei-pool-migration/` 的 `verified-jei-tests-result.json`、`verified-full-build-result.json` 与对应 XML 目录、`mcp-final-problems.json`、`mcp-final-read-disk-consistency.json`、`mcp-verified-build-project.json`、`verified-*-sha.json`；精确基线/22 路径最终清单与不可变快照为 `formal-baseline.json`、`docs-write-baseline.json`、`final-manifest.json`、`final-verified/`。

行为覆盖默认阶段/long/FE、nested checkpoint/selector、dynamic 回调不执行、weighted 语义、精确 provider 分派、多类型槽位/输出偏移/清槽、准确 converter 拒绝、全部资源与信息分页、默认池和不继承默认类的独立 custom page、重复/伪造绑定失败不部分发布、同池多配方/大小切换隔离；复用已有轻量 AEKey/ingredient fixture 和明确 helper UID，验证跨池与 registered 集合冲突双方降级、独立正常查询对照及原 long/图标来源保留。实际平台 AEItemKey NBT 与 HEI 原生 helper 行为由源码审计支撑，不把本 fixture 当作原生 NBT/HEI 运行验证。

未启动 Minecraft 客户端/服务端。非最终 NBT 用例的 setRegistryName 意外触发 FML Loader，并发生日志轮转；该 fixture 已改为已有轻量 AEKey/ingredient 夹具并通过正常项目测试，失败记录与日志保留，不能声称早先运行没有 FML 初始化尝试，不能以 Bootstrap/FML 初始化绕过测试边界。真实 OpenGL 可见效果和客户端原生查询运行验收尚未取得。配方转移/一键填充、额外书签、独立结构预览及历史 decorator 全套资产不计本阶段；整体迁移继续，CT 最后。

## R32 机器绑定

R32 机器绑定已实现验证：JEI 新增 1 项行为包含在 focused 37 tests / 4 suites 中，完整 build 为 1391 tests / 176 suites 全通过；19 个变更 Java 的本机 MCP 全级零诊断、完整读回/磁盘一致及 MCP build 成功，21 源码资源构建前后/最终 MCP SHA 稳定。证据见 `build/staging/machine-recipe-pools/verification/`。`PrototypeJeiPlugin.register` 直接消费池 `machines()` 列表并绑定实际 canonical controller 的原生 catalyst，空池也保留静态绑定。运行时动态选择不重写该列表或分类，不枚举世界实例。

默认 `AutoLayoutRecipePage` 在顶部显示本地化机器数量，悬停列出静态关联机器的本地化名称与 ID；没有 Item 代表的合法 MachineType 仍能被说明，不虚构物品或输出。自定义页直接使用实际 `page.pool().machines()` 接口。新增文案沿 `JeiText.Machines` / LocalizationEnum，两语言各追加 1 键。必要轻量 fixture 验证实际默认 wrapper tooltip 消费准确关联，并排除只有同 group 的无绑定机器；未初始化 FML/Bootstrap/nativeItem，也不代表 OpenGL 或原生 catalyst 实机验收。

## 2026-11-10 结构页完整样板导入

空样板根因：StructurePreviewJeiRecipe只登记控制器输出索引，Category未创建原生JEI材料槽，AE通用导入器从空layout取得空资源；原PE长数量导入器只注册普通RecipePool UID。现对独立结构UID为ContainerPatternEncodingTerm/ContainerPEATerm注册同一PE导入器，直接消费准确wrapper的完整材料快照，保留原controller输出索引供JEI检索，不用屏幕槽位或渲染采样推算用料。

Scene.materials统计完整model.cells并仅排除原点控制器占用，整种及单格选择共同参与；切片数量由当前model/settings决定，Y层、scan与visible不截断材料。无显式选择时与已准备的实际显示状态匹配，不能固定取缺失的候选0。采用不含片层过滤的独立StructurePickWorld调用原生getPickBlock；空气不需要材料，流体沿构造器既有规则计1000mB/格。按显示/选项先分组，再用AEKey2LongMap.addTo按完整AEKey/NBT累计long并预检溢出，不经ItemStack.count传大数量。快照按model/token/场景/世界及资源重载撤权，缺表示、partial或无真实物品时明确拒绝，不静默遗漏。缓存避免稳定帧重复扫描整个结构。

导入将已选材料作为singleton候选，避免AE书签/网络优先级更改当前选择；继续使用既有事件、前后容量预检、候选元数据及GenericStack SET_FILTER包。StructurePatternOutput是仅客户端的Forge静态订阅者，在实际PatternRecipeTransferEvent中仅处理结构UID的加工输出，将控制器副本按JeiText.PatternStructureName本地化为“原名-结构”/“original name-Structure”，数量1及原NBT保留；名称以导入时语言写入物品NBT。普通机器配方和其他分类不受该命名逻辑影响。事件取消、重载失效或序列化后撤权时均不写终端。

本地源码提交 `ac66590`。6Java及两语言本机MCP errorsOnly:false全级零、完整读回匹配，Spotless/必要构建（-x test）和MCP build通过；未新增或运行测试。可视addon已编译安装到run/mods，boundary_structure完整输入169、各片层仍169、JEI候选替换109及本地化控制器输出的操作说明见 `run/visual-examples/README.md`；构造器单格108+1与整种109的设置独立于JEI，不把工具NBT当结构页当前选择。原始证据位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/structure-jei-import/`；真实终端编码和客户端视觉仍待用户重启复验，未启动游戏。

## D2 结构预览联动

PrototypeJeiPlugin注册实际StructurePreviewJeiCategory/Recipe；静态canonical MachineType.structureId关联独立机器结构页和已有controller catalyst，完整服务端客户端目录供view选择，不枚举世界实例或执行matcher。AutoLayoutRecipePage的嵌入预览使用公开concrete Panel/Scene；独立custom RecipePoolJeiPage可调用StructurePreviewScreens.panel，不继承默认实现。全屏viewer直接AEBaseGui，保留即时HEI父页和准确机器原容器返回。原生GuiOpen/InitPre/InitPost、取消与嵌套owner撤销保持；嵌入的Mouse/Keyboard事件仅消费实际绘制控件，拖拽跨draw继续且关闭后准确一次up不泄露到父槽位。

材料层显示保持完整long BOM，代表项/子边occurrence切片独立；同一Scene复用D1真实原生模型和owned TESR-SNBT，唯一handleUpdateTag，不运行异常兼容回退。材料rows和native名称按状态/查询/过滤及resource/语言变化失效，稳定draw复用。原路径40 PNG与NOTICE保留；全部文案沿实现LocalizationEnum的StructurePreviewText。focused47/3、受影响82/8、最终正常完整build1486/183全过；41Java本机MCP全级零、完整项目读回/磁盘一致、MCP build成功，证据见build/staging/structure-preview-gui/verification。未启动Minecraft/服务端/Bootstrap/FML；真实HEI、GL/TESR和网络包实机未验收。D3成型模型/动画/Bloom/隐藏、其他未取消能力继续。

## MUI 撤除约束（2026-11-09）

用户明确撤除ModularUI及其依赖。当前JEI/HEI消费者没有MUI导入、Widget/Drawable调用或modularui资源ID；mezz.jei.api.gui.IDrawable属于实际HEI API，继续保留。项目全部Gradle configuration精确排除com.cleanroommc:modularui；实际9个compile/runtime/processor配置、135个Jar和当次主/dev/sources产物均无MUI，证据 `build/staging/mui-removal/`。

后续绘制/控件只走AE或Minecraft原生Java，不采用MUI GUI基类、Widget/Drawable或替代UI框架，不依赖modularui资源命名空间。Arrow/Cycle历史3.0.6来源/许可证据仅保留在 `build/staging/jei-remaining-plan/modularui-3.0.6/`，不是项目依赖或主产物，不授权重新引入MUI；恢复后若实现效果须用AE/native Java。本次只完成排除约束与核验，不实现尚未迁的decorator。该次排除约束完成后的历史暂停已由后续恢复指示结束，JEI 原生显示迁移继续。

## 原生装饰、皮肤和候选布局（2026-11-09 续作）

沿 PM 真正的消费者补充公开 `JeiRecipeDecorator`、`JeiSlot.Skin`、`JeiVisuals` 与 `JeiRenderOptions`。独立自定义页可直接组合九宫背景、模块进度、native Arrow/Cycle、耗时模板、item plaid、fluid base/top 与独立 FE mask/LED；默认 `AutoLayoutRecipePage` 实际使用同一入口。布局选项在页面注册时捕获，默认修改只影响以后创建的页面；默认页的 `inputLayout` / `outputLayout` / `decorations` / `renderOptions` 可由子类选择，独立页不要求继承默认实现。Arrow/Cycle 用原生 Java 几何绘制，保留 shape/direction 独立、四方向、BL↑/TL→/TR↓/BR← 循环裁剪与离散进度；不宣称恢复 MUI 的原 PNG 像素。耗时默认和 `{seconds}` 模板都固定两位小数。

191 张 PM 原 PNG 以原 basename/像素保留到 `prototype_eng/textures/gui/jei_recipeicons`，NOTICE 指向 PM MIT 来源。只迁入实际有消费者的显示组合；95 张残留资源的存在不代表新模块功能。当前 `mezz.jei.api.gui.IDrawable` 是 JEI 原生接口，未新增 MUI 依赖、资源命名空间或 GUI/Widget/Drawable 基类。

原生 JEI 的 `RecipeLayout.drawRecipe` 在 wrapper 之后绘制 ingredient，因此 skin background → ingredient → skin foreground → chance → long amount 都在实际 ingredient renderer 内消费，category-relative slot 转为当前 native absolute 坐标。当前默认页所有非物品均使用 GenericStack 包装和 AEKeyRendering；原生流体渲染分支仅供显式自定义桥接使用，默认页不使用它。

fuzzy、dynamic 显式候选、weighted 和 selective 多可选材料均使用独立身份 CandidateGroup。默认 ALTERNATIVES 交给 JEI 同槽候选列表轮播；相同 key 的不同数量仍保留各自的行、数量和提示，不额外展开。多可选材料按各候选的输入/输出槽顺序对齐，嵌套 fuzzy/selective 候选保持同一槽，多个 weighted pick 保留各自槽位。每个槽的原始候选及 long 数量直接用于样板导入，AE2S 自己决定候选优先级。显式 EXPANDED 自定义布局仍按其显示分组导入。候选超出视口继续按原分页访问。

wheel observation 仅来自当前 draw 的实际 wrapper bounds。DrawScreen.Pre、GuiOpen、InitGui.Pre、KeyboardInput.Pre 和 MouseInput.Pre 的按钮 press/release 撤销旧授权；未被 PE 消费或在 PE bounds 以外的 wheel 也撤销，因为 JEI 可在同一 RecipesGui 内直接 `updateLayout`。下一次 draw 前的后继 buffered wheel 不再命中已经撤权的旧页；被 PE 消费的内部 panel wheel 可在当前绘制 scope 内继续翻页。当前 API 没有 wrapper drag/release，D2 结构预览已有原生输入 owner 继续使用原链。

续作的当前证据在 `build/staging/jei-native-decorators/`：`review.md` 保留此前三项 P2，真实当前 Jar 的 renderer、input/layout 与 wrapper contract 保存为 `current-jei-*-bytecode.txt` / `current-jei-wrapper-contract.txt`。13 个 owned Java 的本机 IDEA 全级检查已清零，完整项目 read_file 与磁盘逐文件一致；统一 owner 已实际运行 compileJava/compileTestJava/test，JEI focused 为 20 tests / 5 suites 全部通过（合并窗口 73 tests / 9 suites），新增 candidate 五项和 native input 两项均在其中，原始 XML 与摘要为 continued-focused-xml/、continued-focused-result.json。独立稳定源码复审已确认此前三项 P2 闭合且无新增 P1/P2，见 completion-source-review.md；唯一 owner 的最终正常 gradlew.bat build --rerun-tasks --console=plain 已成功，15 tasks 全部实际执行，1636 tests / 207 suites 零失败/错误/跳过；本阶段 20 tests / 5 suites 也从该轮原始 XML 独立核对。MCP build 成功，15 个 JEI/资源索引冻结 Java 原字节在完整 build 后与已检查候选来源一致。证据为 verified-full-result.json、verified-full-xml/、post-full-owned-java-preservation.json 及结构 stage 的 continued-full-rerun.log、continued-full-result.json、continued-mcp-build.json。精确本地提交使用最早 before 增量和独立保护，不纳入用户依赖、原背景或结构阶段改动。真实 OpenGL 可见效果、原生 JEI 查询与鼠标设备交互仍待用户实机，未启动 Minecraft 客户端/服务端或 Bootstrap/FML。
