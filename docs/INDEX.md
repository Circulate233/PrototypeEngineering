# 开发索引

恢复上下文先读本文件并检查 `git status --short`。按入口读取所需章节和源码；需求以用户当前指示及 [development-spec.md](development-spec.md) 为准，实施状态、历史检查点和验证证据集中在 [implementation-status.md](implementation-status.md)。

## 当前检查点

- 2026-11-11 本地化/集合全量审查收尾：用户指出的测试addon缺pack.mcmeta已补，安装Jar metadata=3/两语言各94条/SHA一致；结构显式标题和机器fallback、工具客户端名称、native/external小写key及固定key枚举已修，527枚举项EN/ZH无缺。带primitive集合的字段/参数/返回值全部fastutil专用接口（AEKeyType为Reference2LongMap、选择表Object2IntMap、路径IntList），不留装箱JDK ABI重载；扫描735main＋19addon＋11脚本直接装箱集合/包装数组零。普通队列/栈迁移并保留身份/顺序，补entry.setValue不可变保护。完整build含Spotless为1923 tests/264 suites全零，11脚本实际SDK编译/97核心断言通过；75正式Java本机MCP全级零，11修改run Java被排除/未知，资源全级零及MCP build成功。示例已安装，无子代理/游戏启动/推送，原背景及暂存保留；详见 [本地化与集合审查](localization-collections-audit.md)，证据localization-collections-audit/。

- 2026-11-11 补齐真实子结构/可拓展结构示例：新增12/23两个实际CT脚本，nested_structure共享pod＋marker跨左右分支复用；expandable_structure为1–4段Slice及随尾段移动的一次性金盖。run新增基于canonical结构图的搭建、真实World冻结断言和显式段数客户端预览命令，具体BOM26及22/31/40/49与操作见 [组合结构示例](../run/scripts/README-composite.md)。同时贯通重复次数滑块→view/用料/投影→工具NBT→服务端occurrence上下界预验→实际构建计划；换绑清理，重开保留。19 addon Java、11脚本SDK编译和核心97断言通过；24 tests/2 suites全零含1项必要的共享occurrence/持久段数/构建对照回归，官方7Java本机MCP全级零，run修改Java因排除不可分析，两语言全级零。示例已重编安装，未启动游戏/推送；World成型、搭建、支付、退出重进及画面仍待用户，总体Goal保持暂停。证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/composite-examples/`。

- 2026-11-11 五张实机截图反馈已修：共享材料网格5列×8行横向逐行，内容与滚动条独立；构造器/独立预览使用真实只读展示槽，JEI发布实际ingredient槽，正常书签/检索/高亮；单格与整种选择共享view settings并按具体候选拆分右树数量。候选选择后、外部点击、Esc关闭，旧槽立即撤销；层滑块上高下低，滚轮上加下减。HEI原生转移按钮在异步材料就绪/选择代变化后重新init，消除初次未就绪缓存；客户端名称走getLocal，示例enum/lang统一小写。10主Java及示例两语言本机MCP全级零，示例Java因run排除不可分析；22项既有专项及build/MCP build通过，未新增测试。示例包已重编；首次Jar被运行客户端占用，用户回复“已退出”后安装成功并核对SHA256。需用户完整重启复验本次实际效果，总体Goal保持暂停；不启动游戏、不推送。入口 [结构工具](structure-tools.md)、[JEI](jei.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/preview-feedback/`。

- 2026-11-11 run 示例套件已收尾，统一入口 [run/README.md](../run/README.md) 与 [覆盖矩阵](../run/COVERAGE.md)。18 个 addon Java 编译打包并安装成功；全部9脚本实际SDK718编译、97项核心行为断言通过。补齐扫描纯计算/存储结算驱动、区分准入探测与首tick付款、固定最终5并行的不足付款拒绝，原与恢复进程END输出均通过。19个run Java（含验证器）本机MCP逐文件 errorsOnly:false 均因排除目录拒绝分析，错误/警告/弱警告未知。原生世界/FTB/保存/GUI/HEI/视觉仍待用户测试，总体Goal按用户最新指示保持暂停；未启动游戏、未推送。详细证据见实施进度。

- 2026-11-11 原生复验交接补齐：[CT文档](crafttweaker.md#用户原生复验入口)现有可复制的普通loader SDK member/INDEX/IData/snapshot完整脚本，并按SDK→既有169结构/工具→运行中退出重进排序。脚本局部data不冒充owner保存/同步；现有示例未安装componentData及研究/网络/复杂渲染场景，仍须真实接入验收。仅文档补缺，未重跑build/tests或启动游戏，整体Goal仍active；写前和源码依据在 `resumed-goal/native-handoff-review/`。

- 2026-11-11 CT第四阶段及全树Java门已完成本轮源码验证与精确本地提交：`8c5ba388` 渲染公共注册/普通data动画12Java，`f4b749e5` 真实owner工厂/最终并行选择/简单池与JEI选项20Java，`58fc925f` 全树诊断窄修7路径（6Java＋删除私有旧数量帮助类）。39路径无交叠；最终完整build含Spotless成功50s，1921 tests/264 suites全零。官方735main＋271test＋generated Tags共1007Java本机MCP errorsOnly:false全级零，38修改源码及Tags完整读回/当前bytes匹配，MCP build成功。入口 [CT使用与原生复验](crafttweaker.md)、[实施进度](implementation-status.md)；全树证据 `resumed-goal/full-idea-current/final/`、提交/背景保护 `resumed-goal/ct-fourth-exact/`。G1–G5及已确认代码scope已闭合，独立审计未发现其它确证代码缺口；旧待迁移条目按历史时点阅读。整体Goal仍active，等待用户SDK真实注入/World/FTB/数据库/网络/GUI/HEI及渲染验收。原5背景和暂存保持，未推送、未启动游戏。

- 2026-11-11 CT第三阶段已精确本地提交：`d1684042` controller-aware/custom结构15Java，`1c67c9b3` weighted/attribute/CUSTOM allocation/typed partial overlay配方12Java，`33e09a88` resource/research/native network及共享注册phase33Java，三路共60且无交叠。最终build含Spotless成功25s，1918 tests/261 suites全零；60Java本机MCP errorsOnly:false错误/警告/弱警告全零，完整读回及当前普通bytes匹配，MCP build成功。入口 [CT使用与验证边界](crafttweaker.md)、[实施进度](implementation-status.md)，最终manifest/三源码提交及背景保护在 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/resumed-goal/ct-third-exact/`。原5背景/暂存保持，未推送、未启动游戏。总体Goal active，完整scope独立审计继续；真实SDK注入/World sampler/FTB/原生数据库/网络和已列视觉实机验收仍由用户重启复验。

- 2026-11-11 CT第二阶段与原生保存修复已精确本地提交：`b08e17ef` dirty-save三Java，`b5543fdc` 客户端独立variant/准确session更新及SDK成员写入桥十Java＋Mixin配置，`b40ed52b` typed结构/仓室薄桥十Java。原自动保存早于END新work，现本tick控制器队列在实际join后标记，STOP在最终save前标记，保留comparator效果；不读NBT或提前close。最终build（含Spotless）1910 tests/257 suites全零，本阶段23Java＋配置本机MCP全级零/完整读回当前匹配，MCP build成功。真实SDK原生Mixin注入未实机，当前验证为同helper真实Compiler及descriptor/profile核对，须用户重启客户端复验。入口 [实施进度](implementation-status.md)，独立复核/原生与SDK依据/最终XML/MCP/精确提交在 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/resumed-goal/`。当时完整custom-validator/controller-aware/facts/JSON phase、高级配方与resource/research/network为后继，现已随最新第三阶段提交。原5背景及暂存保持，未推送、未启动游戏。

- 2026-11-10 材料仓容器tooltip按截图反馈统一AE2S：直接调用Tooltips.getEmptyingTooltip(StoreAction)显示左右键两行原生存入提示，FillAction/muted/of/TextComponentItemStack显示装填提示；优先显示操作，不再拼一行自有“倒入材料仓；Shift处理全部容器”，颜色/内嵌图标/富文本绘制与GuiMEStorage同路。仅1Java，无语言或库存行为改动；Spotless/build -x test、本机MCP全级零/全文一致及MCP build通过，未新增或运行测试、未启动游戏。源码本地提交 `cf27716`，未推送，原背景/暂存保留；入口[仓室](hatches.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/hatch-container-tooltip/`，总体Goal仍暂停。

- 2026-11-10 结构JEI空样板已修：结构UID接入普通/PEA终端完整资源导入链，按当前model与材料选择统计全结构用料、仅排除原点控制器，层预览不截断；AE2S事件把控制器×1输出本地化为“原名-结构”。未知/partial材料拒绝，原生pick/流体及long数量保持。6Java与两语言MCP全级零/全文一致、必要构建（跳过test）及MCP build通过，未新增或运行测试。可视示例GLASS/STAINED_GLASS候选及本地化结构标题已补，5源码javac/jar并安装run/mods成功；169完整材料、各层不变及候选/命名观察步骤在run/visual-examples/README.md。源码本地提交 `ac66590`，未推送、未启动游戏，原背景/暂存保留。入口[JEI](jei.md)、[结构](structures.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/structure-jei-import/`；总体Goal仍暂停。

- 2026-11-10 构造器新交互已实现：不同控制器右键只绑定、已绑定打开、潜行绑定并打开；固定材料面板显示完整可滚动BOM/候选网格，右侧单击线框、双击整种替换，预览点击只改单格。GUI投影30秒，手持对应构造器Shift＋滚轮循环底层至顶层及完整投影，初始采用GUI片层；材料替换后保留片层，两工具补用法tooltip。16Java及两语言本机MCP全级零/完整读回、Spotless及必要构建（跳过test）、MCP build通过，未新增或运行测试。源码本地提交 `2f4cdac`，未推送、未启动游戏；原依赖/两语言背景/暂存测试/INDEX.md~保留。入口[结构工具](structure-tools.md)、[结构](structures.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/builder-ux/`，总体Goal仍暂停。

- 2026-11-10 层预览空白已修：取消Scene对方块/TESR的三维限定，接通已有层渲染；隐藏层不再剔除切面，零层控制器及整层线框/高亮正确按层消费。滑块范围含独立控制器零层。仅3Java，源码核对、Spotless/必要构建（跳过test）、本机MCP全级零/完整读回及MCP build通过；未新增或运行测试。入口[结构](structures.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/preview-layer/`。源码已本地提交 `edfff6b`，未推送、未启动游戏。

- 2026-11-10 结构预览截图反馈已修：预览/构建树/实际用料统一原生getPickBlock；本地原生Slot及正常物品tooltip；候选/材料各自滚动；悬停和已选亮线框无深度遮挡；相机pitch射线纠正，最近实际命中直接切换选择。17Java本机MCP全级零/完整读回、必要构建与MCP build通过。按最新指示撤去全部新增静态测试，仅更新既有选择预期；细节与真实验证轨迹见[实施进度](implementation-status.md)。证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/preview-native-items/`，源码已本地提交 `a1aa5f1`，未推送。需重启客户端复验新增Mixin桥，未自行启动游戏。

- 2026-11-10 仓室破坏掉落已按新要求修复：输入/输出/交互资源仓沿AE附加掉落路径弹出材料；大数量及非物品资源用AE资源包。仓室物品只留配置与空库存，避免重新放置复制；活动事务保留到准确提交/补偿后在服务器线程掉出最终余额。专项49/7、完整1898 tests/252 suites全零，7Java本机MCP全级零、完整读回与MCP build通过；背景字节/暂存保持。入口 [仓室](hatches.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/hatch-drops/`。源码已本地提交 `5f87ce7`，未推送、未启动游戏。

- 2026-11-10 接续上一对话完成三项实机反馈的源码修复与验证：JEI 静态回退共享同代 descriptions/布局量测身份；具体 block/state 与结构匹配同源派生预览，不再要求示例另注册显示表；仓室 tooltip 仅客户端 owner 查询同步 actor，非 owner 保留完整物品配置或明确未就绪，数值格式器按线程隔离。入口 [JEI](jei.md)、[结构](structures.md)、[仓室](hatches.md)。

- 最新完整构建（含 Spotless）1890 tests/251 suites，失败/错误/跳过均零；33 Java 本机 MCP `errorsOnly:false` 全级零，完整源码读回一致，MCP build 成功。复核补齐空 NBT 的方块实体存在约束和宽 OR 搜索展开预算。可视示例五源码已重编并更新 `run/mods`；未启动游戏，真实 HEI、结构视觉及退出世界仍待用户复验。准确日志/XML/MCP/源码快照在 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/preview-fixes/resumed-validation/`，原诊断保留在 `preview-empty-diagnosis/`。源码已本地提交 `35de846`，未推送；总体 Goal 保持暂停，当前仅完成用户另行要求的修复。

- 总体 Goal 保持暂停。本次单独处理用户仓室方块/物品白灰及 Lumenized 物品发暗反馈，源码已本地提交 `16640be`，未推送。44 模型/52 发光元素关闭方向漫反射并改为外壳先绘制；30 标记元数据缺 BLOOM 时仅复制回退 CUTOUT，保留 light=15，有原生层时保留 Bloom；共享仓室 CUTOUT 保透明覆盖。专项4/1、完整1872/248均全零，4 Java 与 Mixin 配置本机全级零、MCP build 成功。入口 [仓室美术](assets.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/hatch-model-parity/`；需重启客户端复验，新 Mixin 不能仅靠资源刷新应用。

- 2026-11-10 局部定义替换 A–E、外部 JEI 页面绑定和 AE 网络包结构已完成本轮实现与验证。专项 67 tests/12 suites、完整构建 1868 tests/247 suites，失败/错误/跳过均为零；67 Java 本机 MCP 全级零、完整读回匹配，MCP build 成功。源码已本地提交 `644e710`，未推送。入口 [定义替换](recipe-definition-replacement.md)，新证据位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/unified-final/`。
- 历史仓室/结构批次已本地提交 `e35604e` 结构纯计划缓存、`2c55f70` 仓室配置/自动IO、`812c0f9` 大槽窗口/禁用滑块；未推送。完整1814 tests/237 suites全零，73Java与两语言本机全级零/完整读回，MCP build成功；精确候选编译与独立复核通过，原暂存及工作字节保留。自动IO部分结算/回调重入、邻居和同TE内容变化的公平刷新已修复；材料提示仅槽位数量/槽位容量，AE默认等价与比例API保持，自动IO默认关闭，能源用户限额默认-1、0禁止且保留profile配置上限。证据从 `build/staging/chat-goal-recovery-2026-11-10/handoff.md` 进入。
- 2026-11-10 总 goal 已恢复。本批已实现输入来源策略、跨控制器周期结构检查并发、PN 外部网络参与者；独立复核发现的问题均已修复。完整构建 1744 tests / 224 suites 全零，37 Java 本机全级零及完整读回一致，MCP build 成功。已本地提交 `b49f62c` / `239b52d` / `ad8c584`，未推送；入口 `build/staging/resumed-goal-plan/plan.md`、`unified/third-results/` 及同目录 `exact-commit-preparation/`。
- GUI/两件工具修复已提交 `73233cb` / `7bcb5e5`；用户最新回复“尚未复验”。范围结构选择器、机械构建器、仓室/控制器、JEI 与样板导入的实机结果仍待用户，不能以构建通过替代。入口 [结构工具](structure-tools.md)、[GUI 修复](gui-parity.md)、[JEI](jei.md)。新反馈优先。
- 后继 Java 工作为局部配方/命名matcher及modifier定义替换，再接CraftTweaker；纯结构计划和安全仓室重配置已完成本批。`definition_replacement_audit` 正把既有独立方案收束为实际scanner/codec/JEI和远端本地声明边界，入口 `build/staging/definition-replacement/`。Applied-Flux继续暂缓。

## 下一步

- 用户完整启动客户端，先复验本轮metadata修复后的机器/结构中文（包含World子节点、网络、渲染与组合结构），再沿下面既有材料槽/JEI/构造器及组合结构步骤验收。原生注入、World、保存/支付与画面仍待用户；总体Goal保持暂停。后续扩展调用严格primitive fastutil API，见[专项审查](localization-collections-audit.md)。

- 用户完整重启，按 [组合结构实机步骤](../run/scripts/README-composite.md) 使用 `kit composite` 和两类build：嵌套26材料且两份pod/marker，拓展1–4段分别22/31/40/49材料、唯一金盖跟随末段；执行真实structure-check正/负例、投料和显式段数预览。构造器拖重复次数时左右/用料/投影同步，关闭重开/退出重进保留，对空控制器选3段实际构建40材料。SDK编译及NBT/几何行为回归不能代替这些原生结果。

- 优先复验2026-11-11本批：完整重启后核对机器中文名；JEI与构造器材料网格横向逐行且不盖滚动条，材料及候选能按原生书签键/R/U识别。构造器玻璃单格替换后左网格和右树同时108＋1，整种后109；选择后自动关闭，外部点击及Esc直接关闭。打开结构页等待预览就绪后转移键应恢复可用，任一片层仍导入完整169材料；层滑块上方对应高层、下方低层，滚轮方向一致。以上为待用户原生结果，不以22项行为测试或IDE检查替代。

- 材料仓手持水桶/空桶时复验AE原生左右键存入、左键装填提示的分行、深灰文案/灰色资源与内嵌图标；原Shift整批交互保留，但不再使用自有拼接说明。空光标时原资源名称、数量/容量提示保持。用户自行重启客户端观察，未以源码检查代替渲染验收。

- 先由用户重启客户端从样板终端对示例boundary_structure_controller按R进入结构页，复验完整169输入/任一片层仍169、当前玻璃候选替换及“原名-结构”控制器×1输出并实际编码；步骤见run/visual-examples/README.md。当前JEI候选修改整种材料，构造器单格/整种设置独立，不用工具NBT解释结构页导入结果。忽略目录示例已编译安装，但IDE拒绝分析该目录，不能以主源码全级零冒充示例IDE结果。

- 新实机反馈优先：由用户重启客户端，先复验构造器三种绑定/打开行为、右侧双击整种与左侧单格替换、完整材料网格/滚动、30秒到期及Shift＋滚轮片层循环；再核对层预览零层控制器/线框、原生图标/tooltip、最近命中和真实搭建用料。截图紫黑物品本批仅静态审查当前pick/model链，不宣称新增视觉修复；仓室掉落/拾取/重新放置及退出世界也待实机。此前新增Mixin方法不能仅F3+T应用。

1. CT第四阶段已本地提交，G1–G5渲染/真实factory/简单池/最终并行选择/JEI选项已接实际公共消费者；先读 [CT使用与原生复验](crafttweaker.md)、`resumed-goal/ct-fourth-review.md`、`ct-fourth-exact/approval.json` 和全树 `full-idea-current/final/current-complete-summary.json`。结构custom/facts/JSON phase、高级配方、resource/research/network及此前代码scope均有后继闭合，不沿用旧缺口清单。
2. 总体Goal按用户最新指示保持暂停，等待用户测试。使用 [run统一入口](../run/README.md) 及覆盖矩阵，按顺序复验真实SDK注入、World sampler、FTB/数据库/五网络角色、保存/同步与GUI/工具/HEI/复杂渲染；记录实际结果后再判断整体完成。名称/回调冻结对象、历史codec与准确owner保持，GUI DSL/独立Modern不恢复，Applied-Flux暂缓。编译、Compiler行为测试和全树IDE门不替代实机。
3. 游戏由用户自行启动，GUI/工具/真实 HEI 页面和 AE 客户端调度仍待实机复验；纯行为测试不冒充原生运行通过。
4. 用户已确认手动清理 build/.idea。原 stage 的 before/XML/MCP 原文件未恢复，旧条目只作历史；后续证据均保存在上述 build 外 recovery 目录。

## 必须遵守

- 可静态推断的公式、限位、getter/赋值不另增测试；优先源码核对、本机IDEA检查和必要构建，仅为静态不能确认的真实行为补必要测试。
- 纯库 `prototype_eng` / `com.circulation.prototype_eng`；不添加正式机器、配方或玩法数值。先审查 PM/PN 的实际目的和实现，再复用/修正；保留未明确取消的效果及多线程能力。
- Java 25；API按真实外部职责划分，不制造 XxxImpl 镜像。集合优先fastutil，AEKey数量用AEKey2LongMap，累加/扣减优先addTo，保留溢出及事务预检；必要原子字段优先共享VarHandle，不用非必要反射或异常控制流。
- AEKey/AEKeyType负责身份，核心不用MEStorage；资源/FE仓保留十阶、三方向及公开扩展，混合仓两侧独立且各侧支持多类型，FE独立。
- GUI直接AEBaseGui，无编辑器/第二套基类/ModularUI依赖或资源命名空间。LocalizationEnum：客户端getLocal、服务器text，GUI不拼翻译键或裸调用翻译。JEI普通池每池一个默认或自定义实例；有效外部UID仅注册机器入口。
- IDEA只用本机MCP。未挂载时连接已启用的 `http://127.0.0.1:64343/sse`，先tools/list，逐文件get_file_problems(errorsOnly:false)，记录/消除错误、警告与弱警告；不得操作桌面或修改IDE设置，构建不代替检查。
- 阶段完成本地提交、不推送；不自行启动Minecraft客户端/服务端或Bootstrap/FML测试初始化，保留其他工作区修改及 `docs/INDEX.md~`。当前检查点更新本索引，详细状态/证据写实施进度。

## 按需阅读

| 内容 | 入口 |
|---|---|
| 已确认需求/功能范围 | [development-spec.md](development-spec.md) |
| 实际完成度/验证证据 | [implementation-status.md](implementation-status.md) |
| CT声明/结构SAM/配方分配/研究网络及验证边界 | [crafttweaker.md](crafttweaker.md) |
| 旧实现及已知问题 | [migration-audit.md](migration-audit.md)、[machine-controller-migration-audit.md](machine-controller-migration-audit.md) |
| 检查点分段执行 | [checkpoint-execution-audit.md](checkpoint-execution-audit.md) |
| 配方阶段/预设事件的真实迁移状态与功能优先顺序 | [recipe-feature-parity.md](recipe-feature-parity.md)、[pm-recipe-events-audit.md](pm-recipe-events-audit.md) |
| 普通数据库下载/自动升级/真实物理授权/数据库 GUI | [data-centers.md](data-centers.md) |
| 普通物品转换/材料和来源费用/nonce与原生输出恢复/分级盘边界 | [research-downloads.md](research-downloads.md) |
| 研究 GUI/分页同步/文案/开始接管黑名单与领取 | [research-gui.md](research-gui.md) |
| 研究定义/资格/入队/队列/完成传播/奖励与服务器缺口 | [research-features.md](research-features.md)、[pn-research-execution-audit.md](pn-research-execution-audit.md) |
| 研究站显式消费者/真实来源/材料周期/END共同提交 | [research-stations.md](research-stations.md) |
| 服务器研究 owner/单一存档/FTB/玩家事件/手动入口 | [server-research.md](server-research.md) |
| 服务器奖励领取/准确会话/物品确认与证据清理 | [research-rewards.md](research-rewards.md) |
| 手动研究逐项投料/玩家回执/九状态恢复/暂停接管 | [manual-research.md](manual-research.md) |
| 配方/组件/事务/扫描 | [recipes.md](recipes.md) |
| 普通机器/进程数据、显式 codec、原生 dirty 同步 | [实施进度](implementation-status.md)、[DataComponents](../src/main/java/com/circulation/prototype_eng/api/data/DataComponents.java)；独立复核 `resumed-goal/data-review.md` |
| 配方输入来源分配策略 | [input-allocation.md](input-allocation.md) |
| 配方池 JEI 默认/独立自定义/外部已有页面 | [jei.md](jei.md) |
| 局部定义替换/历史版本/远端声明 | [recipe-definition-replacement.md](recipe-definition-replacement.md) |
| 机器 GUI/自定义入口 | [machine-gui.md](machine-gui.md) |
| 方向工具/原交互数学 | [orientation-tool.md](orientation-tool.md) |
| 范围结构选择器/机械构建器 | [structure-tools.md](structure-tools.md) |
| 机器声明/整批绑定 | [machine-types.md](machine-types.md) |
| 装配/NBT/稳定边界 | [machine-persistence.md](machine-persistence.md) |
| PN 持久身份/准确登记/后续授权 | [machine-identity.md](machine-identity.md)、[pn-identity-migration-audit.md](pn-identity-migration-audit.md) |
| 队伍/FTB/中心规划/计算贡献 | [network-planning.md](network-planning.md) |
| 计算资源注册/单物品限额/旧预算与lease审计 | [network-resources.md](network-resources.md) |
| 实际tick预算/lease/当前代工作凭据/原代补偿 | [network-budgets.md](network-budgets.md) |
| PN 配方扩展/并行占用/真实数据中心/服务器自动协调 | [network-recipes.md](network-recipes.md) |
| 网络角色/服务器发布/共同配方门限 | [network-runtime.md](network-runtime.md) |
| 外部网络参与者/工作责任 | [network-participants.md](network-participants.md) |
| 生命周期/卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md) |
| 可暂停机器组件/持续执行gate/调度接线风险 | [machine-tick-boundaries.md](machine-tick-boundaries.md) |
| 控制器运行时/退休 | [machine-controller.md](machine-controller.md) |
| 真实 TE/朝向/结构接线 | [controller-integration.md](controller-integration.md) |
| 普通调度/服务器边界 | [scheduling.md](scheduling.md) |
| scheduler持有的planned cycle/取消/实际退出/可重试关闭 | [scheduler-planned-ownership.md](scheduler-planned-ownership.md) |
| planned 屏障/取消/实际退出与后续接线 | [planned-execution.md](planned-execution.md)、[planned-migration-audit.md](planned-migration-audit.md) |
| 真实跨宿主阶段协调/source屏障/pending/实际退出 | [planned-recipe-coordinator.md](planned-recipe-coordinator.md) |
| 工位批次/宿主lease/多进程round/结果发布 | [work-slot-batches.md](work-slot-batches.md) |
| 真实阶段/一次tick会话/延迟post/准确恢复 | [recipe-tick-sessions.md](recipe-tick-sessions.md) |
| 跨lane资源独占/批次结算/准确token恢复 | [deferred-resource-batches.md](deferred-resource-batches.md) |
| 业务query/需求作用域/复合事务/revision额度 | [transaction-recovery.md](transaction-recovery.md) |
| 结构匹配/快照/发布 | [structures.md](structures.md)、[structure-migration-audit.md](structure-migration-audit.md) |
| 存储/预留/FE | [storage-redesign.md](storage-redesign.md) |
| 仓室/美术来源 | [hatches.md](hatches.md)、[assets.md](assets.md)、[controller-assets.md](controller-assets.md) |

## 本地源码

PE `F:/new/PrototypeEngineering`；PM `F:/clone/PrototypeMachinery`；PN `F:/new/PrototypeNetwork`；AE2S `F:/clone/Applied-Energistics-2`；MMCE `F:/clone/ModularMachinery-Community-Edition`。PM存储入口 `src/main/kotlin/{api,impl}/storage`，planned入口 `impl/scheduler/planned`；PE按专题进入 `src/main/java/com/circulation/prototype_eng/{api,machine,recipe,scheduler,structure,research,integration}`。

索引不替代源码审查。精简前完整索引只作恢复审计，保留于 `build/staging/controller-shared-parallel/exact-commit/index-before-compaction.md`；历史事实及证据按实施进度/专题查阅。
