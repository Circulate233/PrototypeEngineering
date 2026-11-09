# GUI 原效果修复

## 2026-11-09 控制器第二页与 JEI 补漏

PM `DefaultMachineUI.buildMainPage` 将玩家背包挂在第一页，`buildExtensionPage` 只有内容区及扩展说明。PE 原先仅对 AppEngSlot 调用 setActive/setSlotEnabled，而 AEBaseContainer.addPlayerInventorySlots 实际创建普通 Slot，因此两处判断均未生效。

MachineContainer 现使用随页显隐的普通 PlayerSlot，保持27格背包/9格快捷栏、原坐标和 AE SlotSemantics。客户端选择页面立即更新可见性，服务器验证同一个 PAGE 动作后禁止第二页插入、取出、槽位点击；已有锁定槽限制保留。库存槽和物品不删除、不重建。MachineGui 去掉无效的 AppEngSlot 循环，仍使用 PM 原 A/B 背景和页面内容。没有擅加新仓室槽或改造原扩展页面。

JEI 原背景、材料提示与重复机器标签的修正见[JEI说明](jei.md)。专项39项/完整1693项通过，12 Java与两语言本机全级零，MCP build成功；证据 `build/staging/jei-catalyst-only/`。真实第二页显隐、切回第一页和游戏内拖放仍待用户复验。


2026-11-09 用户实机否决当前 GUI 迁移效果。迁移总 goal 已由用户暂停，主代理已中断全部子代理；当前仅处理本页范围。此前构建/纯逻辑检查不证明 GUI 已等价。未经本阶段修复，不恢复其他迁移或子代理工作。

用户要求：原本已实现的 GUI 保持完全一致的布局、尺寸、纹理、控件、交互和本地化效果；通用输入仓槽位采用 AE 实现，兼容非 AEItemKey。用户后续明确能源数值解析也直接复用 AE 已有实现，不依赖或复制 EvalEx。继续遵守直接 AEBaseGui、无 ModularUI 运行依赖；这不授权替换 PM 原纹理或重设计界面。

## 2026-11-09 交互仓与装饰槽修复

最新指示取消仓室的一切升级槽外观；能源交互仓按用户明确要求改为双独立容器。本地PM实体实际上只有单storage，不能用这个旧实现覆盖当前要求。

- 所有材料/能源各级纹理删除独立装饰槽栏；能源单向仓移除内部四格，原电池右移23px，左侧标题/数量/输入框等宽扩展，atlas尺寸与电池像素保留。能源主体195px完整显示；材料主体按195/213及交互204/209/245px确定居中，Lv3/4输出区左边界修正为119，资源区顶部24。
- 能源交互仓两根电池、两行数量与各自tooltip分别使用输入/输出同步值；全部电池外框、数量行/图标、速率行/图标都纳入对应区域。两侧独立余额、机器/Forge方向、NBT、失效重建和预留均已接线；旧单Energy只迁入输入侧。数量框的I/O图标来自原PNG，没有新增操作控件。
- 材料交互仓tooltip标明输入/输出；已用槽位显示真实占用数量，同key占两槽计两槽。原usedTypes公开方法保留；GUI改用usedSlots，AEKey、当前量/最大量及手持容器提示继续保留。

专项28项和完整1680项通过，10 Java本机全级零，42图静态检查与原始PNG对照见 `build/staging/hatch-io-layout/`。`texture-review.png` 是离线纹理对照，非游戏截图；实际hover/文字/点击仍需实机复验。

## 2026-11-09 实机反馈后的再次修复

用户截图再次否决标题位置/溢出和 tooltip。当前指示覆盖上轮“照抄原坐标”的做法：关闭按钮直接用 AE；通用仓正式称材料仓，纹理/模型同步改名；所有文本应在实际控件区域内布局，不得关闭重开吞掉物品。

- 标题按实际 PNG 内框重设文字区域：材料仓 x=29、宽 138（低阶混合仓 100），能源仓宽 106，均 y=8、高 10；完整富文本在区域内等比收缩、垂直居中，悬停保留全名。能源数量框按 89×10 限制宽度，输入框垂直位置修正 1px；机器主/扩展页及结构标题使用相同区域约束。研究页已使用 fit/分页，不属于 PM 原 GUI，未重设计。
- 自动 IO、控制器分页、关闭按钮注册到 AE WidgetContainer，不能仅放入 Minecraft buttonList，否则 ITooltip 不被 AE 读取。能源框移入前景层，在 AE tooltip 之前绘制；结构关闭按钮的 tooltip 优先于其背后的场景控件。原 PM 自动 IO 有 tooltip；分页按钮没有，本次补充明确页名；能源速率框补说明与完整值，数量文字和能源条都能显示完整当前量/上限。
- 材料槽显式调用 AEKeyRendering + AE 图片 tooltip，追加原 PM 的种类数和当前量/容量，并补手持非物品容器的灌入/取出/Shift 操作提示。AE findSlot 独立于原版 isPointInRegion，现已同时限制 isHovering 的滚动可视区。超原版堆叠上限的物品会被 GenericStack 包装，AE 基类原先拦截包装槽 CLONE；本次仅把材料槽真实 AEItemKey 的中键动作送到自己的服务端校验，仍要求创造模式、空光标和有效当前容器，返回原版最大堆叠。
- 原 PM DefaultMachineUI 的 4×4 是每次构建新建 ItemStackHandler 的占位区，未绑定存储/升级用途。上轮把临时库存当作可放真实物品的槽是错误迁移；现保留图案并提示预留区域，不再注册可收取物品的槽，不虚构持久机器库存。原固定 Status: Idle 改为完整机器快照的聚合状态，包含当前页之外的运行/阻塞/失败；增加跨页状态测试和同步字段，保留旧公开构造器。
- 关闭控件使用 AE SimpleIconButton(Icon.CLEAR, GuiText.Close)，删除 PreviewCloseButton 和三张 MUI 图片及 NOTICE。104 个纹理/模型文件从 item_hatch/gui_item(_io)_hatch 改为 material_hatch/gui_material(_io)_hatch，60 个模型/方块状态引用同步更新；材质像素保持原图，方块注册 ID 不改以保留世界兼容。中英文材料仓名称同步修正。236 个关联资源检查无缺失；第一次检查遗漏 Forge blockstate 的 models/block 隐式前缀，原始无效路径报告保留，未据此制造额外模型。

证据入口 `build/staging/gui-feedback/`：before.json、asset-renames.json、asset-reference-check.json、packaged-assets.json。专项 40 tests/6 suites 全零；完整 full-build 1676 tests/216 suites 全零，最后绘制顺序修正后的 final-build 曾出现结构节拍测试一次失败（5ms 默认切片的实时预算路径），原失败 XML 保留；不改后台/测试以掩盖问题，单独复查 12 tests/1 suite 已通过，最终构建见 verified-build.log/result.json。14 Java 及两语言文件本机 MCP 全级诊断和最终构建报告同目录保存。实机文字、hover、中键和重新打开仍需用户复验，不能用这些检查代替。

用户额外授权的 6.1-sol/xhigh 子代理已完成 run 示例：13 台机器、13 池、106 配方，含能源输入/输出/混合仓与最大 170 个真实方块的结构；Java 25 编译并安装到 run/mods。示例五 Java 的 IDEA 全级检查因 run 被排除而拒绝，未改设置；实际成型/GUI/JEI 待用户运行。命令入口 run/visual-examples/README.md，独立报告 build/staging/run-boundary-examples/handoff.md。其余子代理和总 goal 保持暂停。

## 首次核对的偏离

- ResourceHatchGui 使用 BackgroundGenerator 与 drawRect 重画背景/槽框，分辨率和坐标也改动；额外流体限速/清空控件来自流体仓而非用户指定的物品仓 GUI。PM 原 ItemHatchGUI/ItemIOHatchGUI 使用分等级图、states.png、(26,25)/(26,24) 槽区、固定玩家背包、自动输入/输出图标与平滑滚动。
- MachineGui 只用了 gui_controller_states.png 的进度条，未使用 gui_controller_a/b.png 背景，列表移位、缺原分页及原布局；“工位/无工位”并非 PM 原文。PM 原进度行是 #编号、百分比、空闲/工作中/错误，没有新增的工位标题。
- EnergyHatchGui 改为 176×214 灰底/应用按钮；PM 原为 192×168 分等级背景、(146,10) 16×66 能源条、(40,52)/(40,68) 无背景输入框与侧边图标。
- 结构预览此前重画控件、初始相机偏离原控制器中心，并将世界投影的透明系数用于 GUI。控制器缺原 ModelBake 包装，仓室缺部分原 inventory variant/alias 链；本轮按实际矩阵、资产和朝向实现修正，实机视觉仍待验收。

## 实际阅读入口

- PM：`F:/clone/PrototypeMachinery/src/main/kotlin/common/block/hatch/item/{ItemHatchGUI,ItemIOHatchGUI}.kt`、`common/block/hatch/energy/EnergyHatchGUI.kt`、`client/gui/DefaultMachineUI.kt`、`client/gui/scroll/PM{Thin,}VerticalScrollData.kt`、`client/preview/ui/StructurePreviewUiScreen.kt`。
- 原图：`F:/clone/PrototypeMachinery/src/main/resources/assets/prototypemachinery/textures/gui/`。主代理已直接查看原 item_hatch_1、controller_a、structure_preview_a PNG。
- AE：`F:/clone/Applied-Energistics-2/src/main/java/ae2/client/gui/AEBaseGui.java` 的 drawSlot/drawGenericStackSlot 与 handleMouseClick；AppEngSlot 接收 GenericStackDisplayInventory，AEKeyRendering 负责图标与单位，ContainerItemStrategies 负责非物品容器交互。
- 当前 PE：`client/gui/{ResourceHatchGui,EnergyHatchGui,MachineGui,StructurePreviewGui}.java`、`container/ResourceHatchContainer.java`、`storage/HatchGuiInventory.java`、`client/preview/StructurePreviewPanel.java`。

## 上一轮源码修改（部分由上节反馈修正）

- 仓室恢复分级 ItemHatch/ItemIOHatch 与 EnergyHatch 原 PNG、槽区/玩家背包位置、原自动输入/输出纹理按钮、原滚动皮肤。ResourceHatch 的槽内容仍直接交 AEBaseGui.drawSlot/GenericStackDisplayInventory 绘制，保留 ContainerItemStrategies 非物品容器传输；删除额外流体 GUI 控件，内部流体传输限额保留。
- 默认机器页恢复 384×256、两张 controller_a/b 背景、两页标签、原 4×4 临时占位 inventory、原内容区域及 Status: Idle/Extension Page 原文、(27,8) 七行进度、原 38×15 拉伸滚动手柄；移除额外预览按钮和工位文案。MUI GREY.darker(2)/brighter(1) 实际是 #424242/#E0E0E0，已按源码纠正。
- StructurePreviewPanel 已改回 184×220 原界面，scene (6,13,171,200)，原顶部/底部图标及裁剪动画、材料栏与左侧替换栏、右侧层滑块。移除新增搜索/排序/扫描/切片次数文字控件。原材料区是 (33,35) 垂直 Column、最多 24 个槽，不擅自改为列表/网格。选择同一方块取消选中，材料固定开关默认关闭。
- 原初始相机锁控制器中心，距离 3.5*sqrt(maxDim)（zoom .25..8），重置才对齐结构中心；拖动 .65、平移 .25 倍、滚轮 1.08。默认无 compass，切片只画线框。线框已恢复原状态色/按 key 的稳定颜色/轴线，并以逐步构建的固定边界集合替代每帧轮换 512 个方块的闪烁。
- 原 PM 界面用 BlockRendererDispatcher.renderBlock，本轮已接回界面专用 native bake，保留原扩展状态/面着色；场景 VBO/CPU 缓存按 layer 绘制实体和透明层，移除界面复用 world ghost 的 .65 顶点透明度。世界投影路径保留。此修改仍需实机验证，不能宣称解决了所有 GL 问题。
- 控制器补 LOW ModelBake 包装，ControllerRotatedModel 转写 PM 实际使用的 RotatedBakedModel。PM twist 由真实 front/top 与 TwistMath 的 front×top 关系计算，不能用统一的反向 spin 公式；水平初始 top=UP，UP 朝向为 NORTH，DOWN 朝向为 SOUTH。全 24 朝向、水平几何/光照法线/UV/源顶点不变测试通过，实际映射保存于 controller-orientations.xml。
- 仓室资产按原依赖图核对 324 文件，补回原 inventory variant 与 alias parent 路径共 90 文件，记录 `hatch-assets-audit.json`；最初脚本错误处理 blockstate 模型路径产生的 150 issues 不是真实结论，已被正确报告替换。
- 能源输入框直接调用 AE MathExpressionParser，保留非负 long 提交边界；独立 EvalEx Jar/依赖、临时完整源码和相应新增许可证均已移除，最终没有新增依赖，无需为此重载 Gradle。原库溯源为 ezylang/EvalEx，PM 的 MUI 复制版最接近 3.6.0，并有百分比/取模补丁和 Java 8 移植；业务实际只有两个速率框使用，不再引入其 74 个函数。
- HatchRateField 保留 PM 的 97×10 无背景外观、左右 4px padding、白字无阴影、蓝色选区、细白光标与内侧裁剪；补右键清空、拖动选择、300ms 双击整行、方向/Ctrl 方向/删除键行为。原 escRestoreLastText=false，因此 Esc 提交并退出焦点，非恢复旧文本；失焦显示回到行首。
- 材料数量恢复原 AMOUNT_TEXT 的 4 位限制、DOWN 舍入与 SI 缩写、18px 槽右下角缩放字；补选中状态 tooltip。替换候选按原 state:id:meta 标识，替换重建后恢复选中位置。
- 控制器在预览中作为独立显示/拾取格覆盖原点，不污染原结构数据或重复渲染；Y=0 切片显示其线框。控制器也经 native renderBlock 绘制，slice 不绘制实体 formed model。场景 scissor 与父裁剪取交集，保持原 viewport，退出恢复矩阵、深度和相关 GL 状态。
- 关闭按钮已核对原 MC_BUTTON adaptable(2).tiled()，小中心区域按平铺裁切而不是拉伸；恢复左/右键关闭，按钮优先于重叠的场景接收点击。

## JEI 默认页与样板导入（用户后续已确认）

- 原 DefaultJeiMachineLayout 是固定 170×82 自动排布：左右各两列三行物品，内侧各一个 18×54/18×18 流体槽，中间能源、向右进度模块和 tick 文字，使用 PM 原 jei_recipeicons 资源。额外需求尝试剩余区域，空间不足即停止，无自动扩页/完整避让。
- 原 example_recipe_processor_hatches 使用独立 160×80 示例布局，左右 2×2 物品槽、中间箭头及 tick/秒文字，不能当作默认布局。
- 用户确认保留按池自动排布和溢出可达，同时采用 PM 原纹理。AutoLayoutRecipePage 移除灰底重画、灰色槽框与额外 Input/Output 标题；保留机器标签、候选与分页；默认所有资源用 PM item plaid，进度用原 18×18 模块和连续裁剪，FE 信息用原尺寸 18×18 图标，信息每页两项避免缩扁或重叠。
- 用户最终更正：物品使用原生物品 ingredient，所有非物品统一 GenericStack 包装，不因注册了原生流体或其他 converter 而切换。沿 AE2S CellViewRecipe/CellViewIngredientRenderer 使用 GenericStack.wrapInItemStack、AEKeyRendering、StackSizeRenderer、完整数量与原 AE tooltip；原生物品与包装资源合并到同一 ITEM bucket，索引/清槽不会互相覆盖。
- 样板导入调查确认 GenericIngredientHelper→ItemIngredientConverter→GenericStack.fromItemStack 能还原包装资源；ConfigGuiInventory/PatternEncodingLogic 的加工模式接受通用 key 与 long，InventoryActionPacket 验证当前窗口后写入 filter。此前 PE 原生物品仅有一单位图标、AE 默认仅读取当前分页，会造成少量/漏项。本轮每池注册普通与 PEA 终端导入，读取完整 wrapper 数据，复用 AE 候选优先级、PatternRecipeTransferEvent、SET_FILTER 与加工样板元数据接口；先检查终端容量与加总溢出，不截断。
- 用户最终明确“怎么显示就怎么导入”：JeiPatternRecipe 从完整显示分组取数据，候选布局/展开方式与当前默认页一致，概率、多可选材料、动态示例等不作额外拒绝；逐 tick 资源按 durationTicks×每 tick 数量导入，概率不另折算。曾加入的固定配方限制已移除。多可选材料（SelectiveRequirement）使用 JEI 同槽候选轮播，按各候选中对应的输入/输出槽顺序对齐；嵌套多可选材料及 fuzzy 候选继续归入同一槽，保留原数量/阶段/提示。同 key 不同数量也继续轮播，导入交给 AE2S 的候选优先级，不执行 matcher。自定义 wrapper 可提供其对应显示分组。

## 验证与下一步

- 写前文件保护集中 `build/staging/gui-original-parity/before.json`、`before/`；继续修改新路径前运行 `save-before.py`。不 add 全工作区、不回退背景。
- 最终 AE/控制器/预览专项 ae-focused-2：49 tests/5 suites 全部通过，含控制器原点拾取/选择/切片与替换后选择恢复。旧 evaluator/spin 阶段失败日志原样保留，不作为最终结果。
- 完整 build --rerun-tasks 的 full-build-1 在 16 个新文件的行尾检查失败；限定本阶段 Java scope 的 Spotless 修正后，full-build-2 成功，15 tasks 全执行、1667 tests/215 suites，0 failures/errors/skipped。关闭按钮输入修正后的 final-build 成功（9 执行/6 up-to-date）；最后只删除等价的末尾 return 警告，由最终 MCP build 覆盖。
- 样板导入与按显示策略的 pattern-displayed-focused 为 29 tests/7 suites，旧 displayed-full-build 最终为 1673 tests/216 suites，全零。多可选材料修正后 alternatives-focused 为 30 tests/6 suites 全零，覆盖同槽列表、嵌套候选、同 key 不同数量的 tooltip、对应输入/输出及逐 tick 数量。最终正常完整 build --rerun-tasks 为 alternatives-full-build，48 秒成功、15 tasks 全执行、1675 tests/216 suites 全零；统计和原始 XML 同目录保存。
- 本机逐文件 get_file_problems(errorsOnly:false) 的 49 个 Java 全级全零结果由 idea-complete-final.json、idea-displayed-final.json 和 idea-alternatives-final.json 合并记录于 idea-final-summary.json；两语言文件本机检查零。最终 MCP build 的成功结果已实际保存为 idea-build-final.json。早先 Gradle 文件未再修改；保留真实编译/测试失败及 idea-round-1 诊断，不用历史结果冒充最终验证。
- PN 源码路径及 GUI 全文搜索未发现原已有实际 GUI，不凭空设计 PN 界面。下一步在用户自行启动后核验仓室物品/非物品 slot、能源编辑、机器分页、控制器朝向、独立/JEI 结构预览及两类样板终端导入。逻辑测试和源码链审查不能代替实际网络/GUI 验收；不得启动 Minecraft、不得恢复子代理或总 goal。

本页记录代码与验证检查点，不表示实机视觉已完全一致或崩溃已由用户复验。精确本地提交从本阶段 before→final 组合候选，保留背景工作区，见 exact-commit/。上一阶段 1661 项测试和依赖修复保留在 `build/staging/dev-runtime-crashes/`；示例 v2 已编译，但此前旧 run/mods Jar 被用户客户端占用，未替换，不擅自启动客户端。
