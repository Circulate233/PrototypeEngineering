# JEI 配方池页面

本阶段对应 [开发文档](development-spec.md) R18/R19 与 4.7。当前用户依赖为 JEI `curse.maven:jei-238222:9407746` 和 AE2S `v1.1.1:dev`，直接使用 `mezz.jei.api` 原生分类、包装、ingredient 与 Forge 输入生命周期。下文早期 HEI 4.35.1 / AE2S v1.1.0 验证记录是历史证据，不能替代当前平台的验证。没有新增正式机器、配方或玩法数值。

## 注册与外部扩展

`PrototypeJeiPlugin` 由客户端 JEI `@JEIPlugin` 扫描发现。`registerCategories` 使用已冻结的 `RecipeRegistry.INSTANCE.pools()`，发出客户端 `RecipePoolJeiRegistrationEvent`，随后冻结展示提供器与页面绑定并注册分类。事件提供真实池列表、`IGuiHelper`、页面注册表和展示注册表；外部客户端订阅者可调用 `event.pages().register(pool.id(), page)`，页面直接实现 `RecipePoolJeiPage`，无需继承默认具体类。

每个规范配方池对应一个生效页面实例。未自定义的池创建一次 `AutoLayoutRecipePage`；自定义实例替代该池默认绑定。分类 UID 由稳定池 ID 生成；重复绑定、未知池、伪造同 ID 池、错误 UID 和错误 wrapper 归属被拒绝。页面绑定完整验证后统一发布。R32 从 canonical pool.machines() 的静态绑定匹配 canonical 控制器并引用同池 catalyst，不再根据 recipe group/workslot 猜关联；公开 `catalyst` 入口可追加导航物品，没有伪造控制器产物或第二份配方目录。

`RecipePoolJeiPage` 直接扩展 `IRecipeCategory<RecipePoolJeiRecipe>`。自定义页面负责原生背景、布局与槽位，可重写 `createRecipe` 返回自己的 wrapper 子类，并实现原生绘制、tooltip 和 click。当前 JEI 的 IRecipeWrapper 没有 scroll/drag/release 回调；wheel 使用 RecipePoolJeiRecipe.handleMouseScroll 和原生 Forge bridge。自定义 wrapper 重写 drawInfo 时调用 super.drawInfo 或 JeiRecipeInputs.drawn，登记实际绘制边界。wrapper 保留传入的规范 page、recipe、display 与 ingredients。`RecipeJeiDisplays.register` 按精确 requirement type 注册自定义展示职责，事件结束后不可追加；未知类型保留明确自定义入口说明。

页面与 JEI 类型位于明确的 `api.client.jei` / `integration.jei` 客户端边界。common `RecipePool`、`RecipeRegistry` 和 `MachineRecipe` 不持有页面或 JEI 类型，普通注册/服务端初始化没有新增客户端类引用。标题保留现有 translationKey，由 `RecipePoolTitle` 适配 `LocalizationEnum.getLocal`；库文案用 `JeiText`，两语言各追加 31 键。

## 默认显示与查询边界

默认页面按池内配方的输入/输出最大槽数排布两侧四列资源区与加工箭头；每侧每页最多 16 项，信息每页最多 4 项。每个 wrapper 自持分页状态，按钮与原生 Forge wheel bridge 可遍历内容。每次切换配方或分页先清空已初始化的全部类型/槽位；索引使用所有分页数据。长说明缩略后有完整悬停文本，scope 随每条资源/信息保存，FE 参数与所属阶段不拆开。

`DefaultRecipeDisplays` 只读已知准确 built-in type 的定义，展示 duration、START/TICK/END 资源、独立 FE 参数、概率/属性乘数、fuzzy 候选、dynamic 显式示例、weighted 权重/无放回/pickCount、selector 条件分支、checkpoint 完整子生命周期/时间缩放、属性与 void 输出语义。它不执行 matcher、modifier apply、随机采样、运行阶段或未知 children 回调，也不把选择分支当作必然产物。

资源以 AEKey/GenericStack 保存真实 long 数量。AE2S converter 仅转换一个单位的身份图标，数量标签使用 AE2 格式器，tooltip 提供精确原始单位；FE 是独立说明，不引入虚拟能量物品或 Applied-Flux 重复需求。

显示能力与查询能力分别判断：已注册 ingredient type、converter 精确 AEKey 往返、原生 helper UID 已知集合检查全部满足后，资源才进入原生 ingredient 槽位和配方/用途索引。`JeiQueryIdentityIndex` 先捕获所有池描述与所有当前已注册 ingredient，在任何池 wrapper/index 发布前完成 UID→AEKey 碰撞检查。同 UID 涉及不同精确 key 时，所有相关 key 一致降级为仅展示，保留原图标、long 数量和条件并提示查询身份冲突。缺少 converter/注册类型或不准确转换的资源用 AEKeyRendering 展示；缺少 renderer 有明确占位说明。

正常查询仍使用 JEI 原生 helper 匹配语义。已知集合碰撞检查不证明未知后续 NBT 变体的全域单射：若以后出现未枚举变体，而第三方 helper 忽略其 NBT，它仍可能按该 helper 语义匹配已有配方。本阶段没有解析 UID、反射或覆盖其他模组全局 subtype 规则。

## 验证与保留范围

最终验证：17 Java 本机 IDEA MCP `errorsOnly:false` 零错误/警告/弱警告，逐文件完整 `read_file` 项目文本与磁盘一致；两个 suite 12 项行为全通过，完整正常 `gradlew.bat build --console=plain` 为 1259 tests / 159 suites，零失败/错误/跳过。MCP `build_project` 返回 `isSuccess:true`、`problems:[]`，19 源码资源构建前后 SHA 稳定，17 Java 在 MCP 验证后无变化。此读回不声称未保存 Document buffer。语言最终仅恢复原 CRLF，统一 LF 文本完全相同；MCP build 在行尾恢复前成功，17 Java 未变，证据 `language-line-ending-equivalence.json`。证据位于 `build/staging/jei-pool-migration/` 的 `verified-jei-tests-result.json`、`verified-full-build-result.json` 与对应 XML 目录、`mcp-final-problems.json`、`mcp-final-read-disk-consistency.json`、`mcp-verified-build-project.json`、`verified-*-sha.json`；精确基线/22 路径最终清单与不可变快照为 `formal-baseline.json`、`docs-write-baseline.json`、`final-manifest.json`、`final-verified/`。

行为覆盖默认阶段/long/FE、nested checkpoint/selector、dynamic 回调不执行、weighted 语义、精确 provider 分派、多类型槽位/输出偏移/清槽、准确 converter 拒绝、全部资源与信息分页、默认池和不继承默认类的独立 custom page、重复/伪造绑定失败不部分发布、同池多配方/大小切换隔离；复用已有轻量 AEKey/ingredient fixture 和明确 helper UID，验证跨池与 registered 集合冲突双方降级、独立正常查询对照及原 long/图标来源保留。实际平台 AEItemKey NBT 与 HEI 原生 helper 行为由源码审计支撑，不把本 fixture 当作原生 NBT/HEI 运行验证。

未启动 Minecraft 客户端/服务端。非最终 NBT 用例的 setRegistryName 意外触发 FML Loader，并发生日志轮转；该 fixture 已改为已有轻量 AEKey/ingredient 夹具并通过正常项目测试，失败记录与日志保留，不能声称早先运行没有 FML 初始化尝试，不能以 Bootstrap/FML 初始化绕过测试边界。真实 OpenGL 可见效果和客户端原生查询运行验收尚未取得。配方转移/一键填充、额外书签、独立结构预览及历史 decorator 全套资产不计本阶段；整体迁移继续，CT 最后。

## R32 机器绑定

R32 机器绑定已实现验证：JEI 新增 1 项行为包含在 focused 37 tests / 4 suites 中，完整 build 为 1391 tests / 176 suites 全通过；19 个变更 Java 的本机 MCP 全级零诊断、完整读回/磁盘一致及 MCP build 成功，21 源码资源构建前后/最终 MCP SHA 稳定。证据见 `build/staging/machine-recipe-pools/verification/`。`PrototypeJeiPlugin.register` 直接消费池 `machines()` 列表并绑定实际 canonical controller 的原生 catalyst，空池也保留静态绑定。运行时动态选择不重写该列表或分类，不枚举世界实例。

默认 `AutoLayoutRecipePage` 在输入/输出标题中间显示本地化机器数量，悬停列出静态关联机器的本地化名称与 ID；没有 Item 代表的合法 MachineType 仍能被说明，不虚构物品或输出。自定义页直接使用实际 `page.pool().machines()` 接口。新增文案沿 `JeiText.Machines` / LocalizationEnum，两语言各追加 1 键。必要轻量 fixture 验证实际默认 wrapper tooltip 消费准确关联，并排除只有同 group 的无绑定机器；未初始化 FML/Bootstrap/nativeItem，也不代表 OpenGL 或原生 catalyst 实机验收。

## D2 结构预览联动

PrototypeJeiPlugin注册实际StructurePreviewJeiCategory/Recipe；静态canonical MachineType.structureId关联独立机器结构页和已有controller catalyst，完整服务端客户端目录供view选择，不枚举世界实例或执行matcher。AutoLayoutRecipePage的嵌入预览使用公开concrete Panel/Scene；独立custom RecipePoolJeiPage可调用StructurePreviewScreens.panel，不继承默认实现。全屏viewer直接AEBaseGui，保留即时HEI父页和准确机器原容器返回。原生GuiOpen/InitPre/InitPost、取消与嵌套owner撤销保持；嵌入的Mouse/Keyboard事件仅消费实际绘制控件，拖拽跨draw继续且关闭后准确一次up不泄露到父槽位。

材料层显示保持完整long BOM，代表项/子边occurrence切片独立；同一Scene复用D1真实原生模型和owned TESR-SNBT，唯一handleUpdateTag，不运行异常兼容回退。材料rows和native名称按状态/查询/过滤及resource/语言变化失效，稳定draw复用。原路径40 PNG与NOTICE保留；全部文案沿实现LocalizationEnum的StructurePreviewText。focused47/3、受影响82/8、最终正常完整build1486/183全过；41Java本机MCP全级零、完整项目读回/磁盘一致、MCP build成功，证据见build/staging/structure-preview-gui/verification。未启动Minecraft/服务端/Bootstrap/FML；真实HEI、GL/TESR和网络包实机未验收。D3成型模型/动画/Bloom/隐藏、其他未取消能力继续。

## MUI 撤除约束（2026-11-09）

用户明确撤除ModularUI及其依赖。当前JEI/HEI消费者没有MUI导入、Widget/Drawable调用或modularui资源ID；mezz.jei.api.gui.IDrawable属于实际HEI API，继续保留。项目全部Gradle configuration精确排除com.cleanroommc:modularui；实际9个compile/runtime/processor配置、135个Jar和当次主/dev/sources产物均无MUI，证据 `build/staging/mui-removal/`。

后续绘制/控件只走AE或Minecraft原生Java，不采用MUI GUI基类、Widget/Drawable或替代UI框架，不依赖modularui资源命名空间。Arrow/Cycle历史3.0.6来源/许可证据仅保留在 `build/staging/jei-remaining-plan/modularui-3.0.6/`，不是项目依赖或主产物，不授权重新引入MUI；恢复后若实现效果须用AE/native Java。本次只完成排除约束与核验，不实现尚未迁的decorator。该次排除约束完成后的历史暂停已由后续恢复指示结束，JEI 原生显示迁移继续。

## 原生装饰、皮肤和候选布局（2026-11-09 续作）

沿 PM 真正的消费者补充公开 `JeiRecipeDecorator`、`JeiSlot.Skin`、`JeiVisuals` 与 `JeiRenderOptions`。独立自定义页可直接组合九宫背景、模块进度、native Arrow/Cycle、耗时模板、item plaid、fluid base/top 与独立 FE mask/LED；默认 `AutoLayoutRecipePage` 实际使用同一入口。布局选项在页面注册时捕获，默认修改只影响以后创建的页面；默认页的 `inputLayout` / `outputLayout` / `decorations` / `renderOptions` 可由子类选择，独立页不要求继承默认实现。Arrow/Cycle 用原生 Java 几何绘制，保留 shape/direction 独立、四方向、BL↑/TL→/TR↓/BR← 循环裁剪与离散进度；不宣称恢复 MUI 的原 PNG 像素。耗时默认和 `{seconds}` 模板都固定两位小数。

191 张 PM 原 PNG 以原 basename/像素保留到 `prototype_eng/textures/gui/jei_recipeicons`，NOTICE 指向 PM MIT 来源。只迁入实际有消费者的显示组合；95 张残留资源的存在不代表新模块功能。当前 `mezz.jei.api.gui.IDrawable` 是 JEI 原生接口，未新增 MUI 依赖、资源命名空间或 GUI/Widget/Drawable 基类。

原生 JEI 的 `RecipeLayout.drawRecipe` 在 wrapper 之后绘制 ingredient，因此 skin background → ingredient → skin foreground → chance → long amount 都在实际 ingredient renderer 内消费，category-relative slot 转为当前 native absolute 坐标。流体查询仍传一单位 identity，流体显示使用当前 JEI 原生 `FluidStackRenderer(1, false, innerWidth, innerHeight, null)`，按真实面板 inset 尺寸平铺 full still sprite；不把真实 long 数量写回 int。18×72 面板的内区为 16×70，小量与大 long 使用相同满填充，overlay/概率/数量继续覆盖在其上。没有准确 native query converter 的 key 沿 AEKeyRendering fallback 和相同 foreground/数量顺序绘制。

fuzzy、dynamic 显式候选和 weighted 都使用独立身份 `CandidateGroup`；只有同一准确 native type、无重复 exact key 的组可用 ALTERNATIVES 合并。dynamic 不补 pattern、不执行 matcher；weighted ALTERNATIVES 每个 declared pick 一个轮播槽，EXPANDED 仍只逐候选显示，完整 index 始终收录原始候选一次。重复 key/数量歧义、unsupported 候选或 UID 冲突保留展开及 fallback，不丢 long、scope 和解释。候选超过视口或多个 pick 继续按原分页访问。

wheel observation 仅来自当前 draw 的实际 wrapper bounds。DrawScreen.Pre、GuiOpen、InitGui.Pre、KeyboardInput.Pre 和 MouseInput.Pre 的按钮 press/release 撤销旧授权；未被 PE 消费或在 PE bounds 以外的 wheel 也撤销，因为 JEI 可在同一 RecipesGui 内直接 `updateLayout`。下一次 draw 前的后继 buffered wheel 不再命中已经撤权的旧页；被 PE 消费的内部 panel wheel 可在当前绘制 scope 内继续翻页。当前 API 没有 wrapper drag/release，D2 结构预览已有原生输入 owner 继续使用原链。

续作的当前证据在 `build/staging/jei-native-decorators/`：`review.md` 保留此前三项 P2，真实当前 Jar 的 renderer、input/layout 与 wrapper contract 保存为 `current-jei-*-bytecode.txt` / `current-jei-wrapper-contract.txt`。13 个 owned Java 的本机 IDEA 全级检查已清零，完整项目 read_file 与磁盘逐文件一致；统一 owner 已实际运行 compileJava/compileTestJava/test，JEI focused 为 20 tests / 5 suites 全部通过（合并窗口 73 tests / 9 suites），新增 candidate 五项和 native input 两项均在其中，原始 XML 与摘要为 continued-focused-xml/、continued-focused-result.json。独立稳定源码复审已确认此前三项 P2 闭合且无新增 P1/P2，见 completion-source-review.md；唯一 owner 的最终正常 gradlew.bat build --rerun-tasks --console=plain 已成功，15 tasks 全部实际执行，1636 tests / 207 suites 零失败/错误/跳过；本阶段 20 tests / 5 suites 也从该轮原始 XML 独立核对。MCP build 成功，15 个 JEI/资源索引冻结 Java 原字节在完整 build 后与已检查候选来源一致。证据为 verified-full-result.json、verified-full-xml/、post-full-owned-java-preservation.json 及结构 stage 的 continued-full-rerun.log、continued-full-result.json、continued-mcp-build.json。精确本地提交使用最早 before 增量和独立保护，不纳入用户依赖、原背景或结构阶段改动。真实 OpenGL 可见效果、原生 JEI 查询与鼠标设备交互仍待用户实机，未启动 Minecraft 客户端/服务端或 Bootstrap/FML。
