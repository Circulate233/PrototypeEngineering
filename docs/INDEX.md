# 开发索引

恢复上下文先读本文件并检查 `git status --short`。按入口读取所需章节和源码；需求以用户当前指示及 [development-spec.md](development-spec.md) 为准，实施状态、历史检查点和验证证据集中在 [implementation-status.md](implementation-status.md)。

## 当前检查点

- 2026-11-13 网络中心控制器：新增NetworkCenter等级接口与默认NetworkCenterController，直接使用通用机器生命周期，不继承多线程/跨配方控制器、不创建配方工位/费用组件、不登记机器业务tick。沿现有结构检查和网络唯一活动中心选择；Java/ZS示例均为控制器上方铁块、等级1。只处理控制器，未制作HTML/GUI。入口[网络中心控制器](network-runtime.md#网络中心控制器2026-11-13)，验证见[实施进度](implementation-status.md)；后续界面等待用户逐项要求。

- 2026-11-13 按用户要求移除全部现有研究相关GUI：研究/队列/奖励/数据库/遗忘与管理员页面、专用容器/入口/同步/图标注册/文案与测试删除；后台研究能力保留。研究GUI恢复为延缓实现，后续仅按用户逐项要求处理。入口[研究GUI状态](research-gui.md)，删除范围与必要验证见[实施进度](implementation-status.md)。此前研究GUI已完成或待验记录已撤销。

- 2026-11-13 再次全仓复审与统一提交：修复BlockPos Map普通构造器漏复制；清理结构临时坐标、归并排序重复缓冲、分组/样板全表扫描、每帧NBT字符串及剩余Frozen包装，改为Javadoc只读交接。1105份Java均已通过IDEA MCP格式化并逐份检查：1076全级零、5份CTM仍有IDE依赖解析诊断、24份文档/run被排除；完整Gradle build的1883项测试全通过，MCP build及21Java addon编译安装成功。入口[复审](defensive-programming-audit.md#2026-11-13-再次复审与统一格式化)、[集合契约](blockpos-maps.md)，最终证据见[实施进度](implementation-status.md)。本次按用户授权统一提交，日志严格为`Clean and Spoless code`；下一步读取最新实施证据，实际画面/游戏行为由用户复验。不启动游戏、不推送。

- 2026-11-13 构建器六开关与实际策略已接入：完整材料需求/等待材料补充、完整空间需求/忽略世界流体、ME取料/自动AE合成三组；三项从属开关在客户端和服务器校验前置，关前置时清除从属开启状态。严格空间只认空气（可选择将世界流体视为空气），关闭则用原采掘/保护事件挖掘占位；缺料等待保留当前位置，补料后继续，不等待则跳过。直接复用结构选择工具20×13开关及21×14底框阴影，24组完整像素对照全相等；标签和tooltip显式分行。入口[工具布局](structure-tools.md#2026-11-13-构建器预检me与外部结构)，证据builder-policies/见[实施进度](implementation-status.md)。独立17项测试和本轮IDEA全级通过；整仓构建仍受其他进行中的接口重构影响。下一步用户重启复验六开关/前置、草和石块挖掘、世界流体、等待后补料及AE确认；本地提交、不推送/启动游戏，背景与暂存保留。

- 2026-11-13 分组按钮按自动IO基准修正：保留自动IO全部源码、states及40张仓室底图，仅给普通分组增加原底图同款固定底框/阴影，生成42×16三态专图；分组y49与IO y13/31等距。240组中心外像素对照差异0，展开面板无色按钮也复用。入口[隔离组控件](block-registration.md#颜色与样板隔离)，证据hatch-button-consistency/见[实施进度](implementation-status.md)。2Java本机IDEA errorsOnly:false全级零及MCP构建通过，独立输出Gradle build/Spotless与既有4交互测试通过；下一步用户重启复验三态。只本地提交，不推送/启动游戏，保留原重命名/暂存及其他修改。

- 2026-11-13 大小写重命名启动崩溃：用户日志的旧MeResourceHatchBlock来自run/mods/pe_visual_examples-1.0.0-dev.jar，旧Jar为11-12产物，主工程和示例源码已改ME；Windows按旧大小写找到新class后触发JVM wrong name。重新导出当前开发类路径，删除示例一处已移除RewardItemProtection检查，21Java重编译并安装成功；65class/383个PE引用严格大小写均匹配，无旧Me类名。证据stale-example-classpath/见[实施进度](implementation-status.md)。下一步用户重新启动复验；后续主API/类名改动先run/prepare-tests.ps1更新独立addon，直接runClient不会编译它。未启动游戏，示例目录IDEA被排除不可分析，主类全级零/MCP构建通过，本次增量本地提交，原重命名/暂存保持。

- 2026-11-13 构建器底图配套修正：按截图要求，右侧材料树底色/收口与按钮底色/边框分区；下方按钮区直接复用扫描器结构导出区浅底色和2px框角/阴影，保持按钮y154/177、原384×228界面及384×256纹理。树裁剪/输入/平移高度同步136，PNG差异仅右侧x190..377/y145..202，其余像素不变。入口[工具布局](structure-tools.md#2026-11-13-构建器预检me与外部结构)，证据builder-gui-background/见[实施进度](implementation-status.md)。业务功能沿2964ab1；底图分区取代上一条“仅29像素/树144”的首版布局，用户重启复验实际框线、底色和按钮。未启动游戏/推送，现有背景和暂存保持。

- 2026-11-13 构建器批准范围已接入：四按钮放右侧原面板内部、进度条上方，原384×228尺寸保持，树裁剪/输入同步缩至144，底图仅清除29像素旧坐标图标。齐料/位置预检、准确缺料与本次跳过、严格当前规划、ME物品/流体取料及原生缺料合成确认、整轮匹配隐藏/自动取消投影、builder.cfg间隔/批量、流体格命中/图标/每格1B样板、普通外部Java/CT锚点注册均已接线。14项新增行为/真实ZS编译，工作树1996 tests/283 suites及独立候选1994 tests/282 suites全通过，28Java+2语言本机IDEA全级零、MCP指定构建成功。入口[当前构建器](structure-tools.md#2026-11-13-构建器预检me与外部结构)，证据builder-expansion/见[实施进度](implementation-status.md)。下一步用户重启复验按钮上方位置、缺料门槛/跳过、有效无线终端取料、AE确认后补建、完整投影取消、3格流体3000mB导入及外部锚点；本轮不自动替代/推进层，不新增TileNBT写入/合成后自动恢复；本地提交、不推送/启动游戏，背景及现有暂存保留。

- 2026-11-13 自动构建器与NovaEngineering-Core只读对照完成：ME物品/流体取料、缺料AE确认队列、整批缺料报告/齐料门槛、库存候选自动选择/保留合法已有块、障碍策略、自动投影层推进、构建速率配置及普通外部方块结构适配仍有差距；DE五配置档案为次要便利项。动态段数、库存/流体搭建、排序、暂停/拆卸已有；30秒投影和绑定操作沿用户明确要求，不改回Nova。本次实际工具两边都不写TE NBT，PE另缺整组世界高度预检。逐项来源和边界见[构建器对照](builder-nova-audit.md)，证据builder-nova-audit/见[实施进度](implementation-status.md)。下一步由用户确认扩展范围；仅审查文档本地提交，未改生产源码/启动游戏/推送，原背景及暂存保留。

- 2026-11-12 创造物品栏入口补齐：注册事件已有，但缺少独立PE创造页，84内置方块分散原版REDSTONE、3工具在TOOLS。新增PrototypeCreativeTab（原型工程/Prototype Engineering），图标注册后懒取orientation_tool；6类构造入口统一页，继承的Java/CT示例控制器及扩展并行组件自动纳入。原生ItemBlock.getCreativeTab委托block、AE getSubItems及创造SEARCH沿正常枚举，未复制物品清单。8Java和2语言本机IDEA全级零、compileJava/processResources/定向Spotless及MCP build通过。入口[创造页](block-registration.md#创造物品栏)，证据creative-tab/见[实施进度](implementation-status.md)。下一步用户重启后翻到“原型工程”页及创造搜索复验，未启动游戏或推送，背景和原暂存保留。

- 2026-11-12 隔离组选择器截图反馈：ME资源仓/样板总成的handleAeMouseWheelInput在delta=0前因expanded返回true，AE AppEngClient每个MouseInputEvent.Pre都调用该入口并取消true，导致点击/松开永不到达palette。本轮先检查真实滚轮再拦截展开面板；普通/ME面板与按钮局部禁depth写入/测试和lighting，保存恢复depth、mask、blend函数/开关，修槽物品遮挡及透明帧白像素。普通9×9中心从(2,2)铺满，悬停/按下跟随0/1/2px；ME原9×9不改，原PNG保持。中英提示改隔离组/Isolation group，各17名字独立本地化，中文“隔离组：红/无色”。4项交互（含原生WidgetContainer全部17选择）、定向Spotless、5Java本机IDEA全级零及MCP构建通过。入口[隔离组控件](block-registration.md#颜色与样板隔离)，证据isolation-palette/见[实施进度](implementation-status.md)。boundary_cross审查确认cross_1–4显式fixed(1)，整机4并行和每配方线程上限1为不同约束，未改示例。下一步用户重启复验选择/穿透及全部hover/pressed效果，未启动游戏或推送，背景及原暂存保留。

- 2026-11-12 CPU工作中退出重进后无法发放样板：确认为本仓库`MEPatternAssemblyBlockEntity.entry`仅用对象身份匹配；AE2S 1.1.1读档会独立解码任务样板，原生提供者按定义相等识别。本轮保留原实例槽优先，补完整AEItemKey定义匹配，合并判断/倍数预检/实际push共用修复；8项既有存储与恢复回归、定向Spotless、本机IDEA错误/警告/弱警告全零及MCP构建通过。入口[样板总成](block-registration.md#样板总成)，证据pattern-cpu-reload/见[实施进度](implementation-status.md)。下一步用户在CPU尚有待发放工作时退出重进，确认继续供料；未启动游戏或推送，背景及原暂存保留。

- 2026-11-12 仓室基底CTM/按钮透明与玻璃光照：64仓室/123基底与机械壳双向连接，native邻接/转角/遮挡，原图与324叠加面不变；epoch懒缓存避开CTM同LOWEST事件排序。颜色小框0/1/2px色块位移、ME大框固定，修局部透明混合白条。六玻璃补非fullCube与neighbor brightness，保留零opacity和碰撞箱。7专项、完整工作树1981 tests/278 suites及独立候选1979 tests/277 suites全通过，Spotless与MCP build成功；GUI/玻璃Java本机IDEA全级零，3个CTM Java仍有IDE未同步compileOnly的解析诊断，实际javac/Gradle通过。入口[方块与GUI](block-registration.md)、[实机步骤](../run/scripts/README-me-assemblies.md)，证据hatch-casing-ctm/、color-button-press/、decorative-glass-light/见[实施进度](implementation-status.md)。未启动游戏/附加JVM/推送，真实atlas/GL仍由用户复验。

- 2026-11-12 颜色按钮样式拆分：普通仓室显式HATCH（states原14×14）；ME输入/输出/接口及样板总成显式ME（io_mode原17×18），分别恢复默认位置、色块和点击/无色选择范围，不再共享小框尺寸。现有3项交互测试覆盖两种样式并通过，Spotless通过；6Java本机IDEA errorsOnly:false全级零，纹理文件未改。入口[方块与GUI](block-registration.md)，证据color-selector-styles/见[实施进度](implementation-status.md)。机械外壳基底跨仓室连接需求同步处理中。

- 2026-11-12 GUI二次纠正与分层渲染：恢复自动IO原14×14图/位置，只改颜色小框与20张材料仓挂耳；逐图清除x20旧竖线，白名单外像素不变。ME共用普通HatchScrollbar，内轨x175/y25与98、相同像素平滑/按轨拖动。真实LightUtil定位CTM发光BLOCK格式无normal、HEI快路径转ITEM补零且忽略shade，PE方块物品经公开marker改走原生Forge逐面渲染，保留模型/纹理。9专项、完整工作树1978 tests/277 suites及独立候选1976 tests/276 suites全部通过，Spotless通过，14Java本机IDEA全级零、MCP build成功。入口[方块与GUI](block-registration.md)、[实机步骤](../run/scripts/README-me-assemblies.md)，证据gui-model-correction/见[实施进度](implementation-status.md)。本轮未启动游戏/附加JVM/推送；实际GL及此前库存反向现象留实机复验，不宣称全局光照已修复。

- 2026-11-12 GUI/模型截图反馈修复：催化剂一次release一次结算、物品真实ItemStack取放；模式左键松开循环/右键AE选择器，乘除逐槽跳过不可改项。统一17×18工具条/居中标题/限宽分行，新增独立disabled滚动条；AEKey/JEI/预览局部恢复light0/1及光照状态。30个CTM面接本模块47状态类型，恢复90张原sheet、独立16px基础面；并行控制器沿机械控制器placement/full/24向及原模型。34专项、完整工作树1976 tests/277 suites及独立候选1974 tests/276 suites全零，Spotless通过。20个Java本机IDEA全级零；新CTM2Java受IDE尚未导入新增compileOnly影响，诊断非零，实际Gradle编译/native邻域及资源验证通过。入口[方块与GUI](block-registration.md)、[实机步骤](../run/scripts/README-me-assemblies.md)，证据gui-model-feedback/见[实施进度](implementation-status.md)。需用户完整重启复验原生GUI、光照、CTM/透明面和放置方向；未启动游戏/推送，按精确增量保留背景及原暂存。

- 2026-11-12 其余Mixin逐项审查：删除MinecraftPreviewGuiMixin、GuiScreenPreviewMixin及PreviewScreenGuard/retired/blocked全局界面接管；预览只管理本身AE子页打开/关闭/返回，tooltip由自有GUI或Forge公开接口绘制。保留10项（2通用+8客户端），逐项实际消费者及理由见[Mixin审查](mixins.md)。原24项导航测试保留4项真实返回键/客户端队列行为，撤去20项旧守卫模拟流程。完整1970 tests/274 suites、Spotless、JAR/reobf、MCP build通过；7个修改Java及其余9个Mixin本机IDEA全级零，产物无删除类。证据remaining-mixin-audit/；未启动游戏/开子代理/推送，原暂存及其它对话保留。

- 2026-11-12 奖励交付移除全局保护：删除11个玩家/库存/护甲/掉落物/漏斗/网络Mixin及全局交互事件，取消光标专用存档和重生库存搬运；奖励发放器直接创建并注册RewardItemEntity，仅该实体持有待确认物品，确认后恢复普通掉落物行为。下载COMMITTED恢复只核对原扣料记录，不冻结玩家或要求库存永久等于after。1990项/274 suites与Spotless通过，18Java本机IDEA全级零，MCP build及独立JAR检查通过（2通用+10客户端Mixin，无11个旧class）。入口[奖励交付](research-rewards.md)、[下载](research-downloads.md)，证据reward-local-delivery/。其余Mixin按用户补充继续审查；未启动游戏/开子代理/推送，其他对话和原暂存保留。

- 2026-11-12 JEI重复皮肤入口移除：按用户要求直接删除`JeiVisuals.Fluid`与`FluidSkin`，不保留弃用枚举、尺寸别名或转发兼容层；资源槽直接使用`JeiSlot`与通用AEKey皮肤。全仓正式/测试/示例/ZS无旧入口引用，build -x test含Spotless通过，Java本机IDEA全级零、MCP定向构建成功；JAR及两套开发输出无旧内部类。本轮只删除声明并同步说明，验证证据见[实施进度](implementation-status.md)，入口[JEI外观](jei.md)。

- 2026-11-12 未使用纹理清理：以模型/元数据、实际Java/ZS调用及动态GUI/公开JEI枚举为消费者，740PNG删除305、保留435；JEI191→62，删除流体/气体槽68、旧能源尺寸40、连接箭头15及plaid覆盖6。资源槽统一使用通用AEKey皮肤，重复的Fluid/FluidSkin入口已删除。另删无引用CTM导出图100、旧GUI69、旧方块纹理6及扳手发光备用1；实际模型/CTM/Bloom和GUI状态资源保留。完整build含Spotless与1994 tests/275 suites全零、本机Java MCP全级零及指定文件build成功；JAR和两套开发输出均无305项残留。入口[JEI外观](jei.md#原生装饰皮肤和候选布局2026-11-09-续作)，证据见[实施进度](implementation-status.md)。下一步用户重启复验通用资源槽和装饰/结构GUI；未启动游戏/推送，背景及原暂存独立保留。

- 2026-11-12 装箱回调检查：材料单格/整类选择和构建树分段改用fastutil `ObjectIntBiConsumer`，JEI换行宽度直接接收int，快照测试改用`BooleanSupplier`。编译器AST覆盖1078份main/test Java，只保留统一任务执行器所需的`StructureWork`布尔Supplier；规则与原因见[分配检查](allocation-performance.md)。相关50项测试、Spotless和MCP build通过，3个修改生产Java、2个直接调用方及1个测试Java的IDEA全级诊断均0；字节码确认调用参数为int。证据primitive-callbacks/。本轮仅本地提交，其他对话同文件修改和原暂存保留。

- 2026-11-12 结构预览子界面类型修正：`GuiStructurePreview<C extends AEBaseContainer>`直接继承`AEBaseGui<C>`，父界面构造器接收`AEBaseGui<C>`；独立入口明确返回`GuiStructurePreview<ContainerStructurePreview>`。导航和Mixin只使用有界通配符，不再固定AEBaseContainer或使用原始类型。既有24项导航回归及Spotless通过，4Java本机IDEA全级零、MCP构建成功。入口[结构GUI](structures.md)，证据preview-subscreen-types/。下一步用户复验父页返回和槽位恢复；未开子代理、启动游戏或推送，其他对话改动及暂存独立保留。

- 2026-11-12 ME仓室GUI纹理返工：截图指出首版擦除框宽度套用错误，造成mix图右边框缺损和半列升级栏、资源图自动IO按钮残片。10张新增图均从原始`prototypemachinery/textures/gui`或`导出/gui`逐文件恢复后重做；资源图、mix图、样板图分别按原分辨率独立擦除，白名单外像素与原始纹理一致。5张sprite/palette atlas全部像素按原图恢复；item命名输入/接口图改为`gui_resource_bus.png`/`gui_resource_io_bus.png`并更新GUI引用。Gradle build -x test含Spotless及最终MCP定向构建通过，GUI引用Java本机MCP全级零；中途并行预览编译错误已由其负责线程修复，未代改其源码。10张资源及JAR显示一致，旧item资源名无残留。逐图对比与像素清单见`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261112/gui-artwork-repair/`，入口[纹理规则](block-registration.md)。需用户重启检查真实纹理上传和GUI绘制；未启动Minecraft/推送，本轮目标外既有背景及暂存保持。

- 2026-11-12 控制器库存：固定槽数默认0或ToIntFunction<MachineInstance>动态返回，无回调按固定值，范围0..16。真实4×4原生槽位、关闭格前景白色覆盖/服务端拒绝、缩小保留物品、TE保存/正常掉落以及读取遍历入口均接通；Java与ZS说明已补。完整1994 tests/275 suites及Spotless通过，16Java本机IDEA全级零、MCP构建成功。入口[机器GUI](machine-gui.md#控制器库存)、[实施进度](implementation-status.md)。下一步用户复验真实物品存取、关闭重开、存档/掉落和白色覆盖；未启动游戏/开子代理/推送，原背景和暂存保留。

- 2026-11-12 样板槽隔离修正：开启后每槽以总成身份/槽号独立扫描，脱离总成颜色；只共享本总成催化剂和外部未分区无色库存，不能拼别槽、借其他总成催化剂或有色普通仓。普通无色默认值和未隔离模式颜色分组保持。显式partition保留并规范为透明色，恢复兼容原有色槽标识且不切换槽位。红/蓝/无色真实供料与存档恢复用例及完整build共1991 tests/274 suites全零、Spotless通过；3个修改Java本机IDEA全级零，MCP指定文件构建成功。入口[颜色规则](block-registration.md#颜色与样板隔离)、[样板验证](../run/scripts/README-me-assemblies.md)，证据见[实施进度](implementation-status.md)。下一步用户重启验证三种颜色均逐槽隔离、催化剂和无色供料；未启动游戏/推送，背景和原暂存保留。

- 2026-11-12 run示例补齐：新增6台ME机器/5池/9配方，以及从实际注册表生成的连接纹理全方块结构；每种正式PE方块27格，附Java/ZS并行组件。`/pe_examples build ctm_gallery 0 80 0` 搭建，`/pe_me_examples gallery-check/gallery-list` 核对覆盖及展台坐标；ME材料包/真实加工样板/颜色和标记命令已接入。21个addon Java编译安装、13脚本SDK编译和97核心断言通过，MCP主工程构建成功；6个修改run Java被IDEA排除，诊断为unknown，未修改IDE设置。入口[ME与连接纹理示例](../run/scripts/README-me-assemblies.md)、[run入口](../run/README.md)，详细证据见[实施进度](implementation-status.md)。下一步用户完整重启客户端验证真实ME/PAT和CTM/Bloom；未启动游戏/推送，原背景与暂存保留。

- 2026-11-12 排序与集合容量：ResourceLocation直接参与排序，清单校验及同步/预算化要求排序一致；动态候选复用namespace/path字符游标。全仓构造清单含1758正式源码入口，360余处按来源数量、槽数、解码长度或常见6项预分配；31个有序AE数量表改用可指定容量、实现AEKey2LongMap的fastutil实现。独立提交源码完整1961测试通过；包含并发功能的工作树1976项有4项失败，撤去本轮改动对照全部复现。修改Java本机IDEA全级检查及MCP构建、定向Spotless通过；原暂存及并发功能保留。入口[分配规则](allocation-performance.md)、[实施进度](implementation-status.md)，证据collection-capacity/；未启动游戏/开子代理/推送。

- 2026-11-12 枚举缓存完成：22个重复读取的本库枚举公开 VALUES，序号解码/遍历及示例统一复用；外部优先原生缓存，无公共缓存的4类集中到 ExternalEnumValues。必须复用 VALUES，禁止修改共享数组。57Java本机IDEA全级零、MCP指定文件build、编译及独立改动Spotless通过，run示例javac通过。全量24项失败在撤去本轮改动的隔离对照中全部复现，详见[实施进度](implementation-status.md)及 enum-values/test-comparison.json；最新MCP构建受另一对话尚缺的 MEPatternAssemblyBlockEntity 阻塞；并发新样板容器的3处枚举调用待该组源码完成后复查；并发仓室内容与用户暂存保留，未启动游戏/推送。

- 2026-11-12 方块/ME总成已完成源码与全量验证：15个AE装饰方块（含素面外壳）、4个通用ME总成及5阶独立染色并行控制器，共24项；物品/气体/流体专仓继续弃用。ME目标补足、无限输出、36槽可配置样板页、五模式、AE2S合并输入/PAT远程返回与全资源仓颜色隔离已接通。真实供料回执/持久化UUID用于锁归属，恢复后按原颜色/分区重新绑定当前结构。完整build含Spotless为1987 tests/274 suites全零；65Java本机MCP全级零，提交候选独立验证及最终MCP构建见[实施进度](implementation-status.md)。入口[方块与ME总成](block-registration.md)、[Java/ZS API](../api.md)，证据`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261112/me-assemblies/`及同级me-gui/decorative-blocks/parallel-controller。下一步为用户客户端复验GUI、ME网络、CTM/Bloom；未启动游戏或推送，保留背景与原暂存。

- 2026-11-12 [Java/ZS API 文档](../api.md)与[ZS 使用参考](../api-zs-reference.md)保留在仓库根目录，按用户提供的 AE2S 示例改写为功能说明、参数解释和使用示例，Java/ZS各分调用与扩展部分。文中3段Java、7段ZS均通过实际编译器，两文档本机IDEA全级零；示例脚本未执行。22个API文档链接及代码围栏检查通过；文档已本地提交`2877f19`供用户核对，后补研究builder参数和资源包操作说明；记录见[实施进度](implementation-status.md)。

- 2026-11-12 AE资源包插入：输入/输出/混合仓GUI均按完整AEKey和long数量解包，普通点击、数字键/双击和Shift快移接通；部分接受保留余量包，mixed快移在input完全拒绝后才尝试output，不占预留容量。34项定向测试、Spotless、build -x test及MCP build通过，4Java本机全级零。需求由用户明确授权的单个子代理实现，主线程复核后精确本地提交，不推送；尚未启动客户端验证原生包ItemStack与GUI。入口[仓室](hatches.md#AE-资源包插入)，证据与限制见[实施进度](implementation-status.md)。

- 2026-11-12 测试精简：清点277个测试Java/1963项，针对静态数学及重复覆盖候选核对实际断言；删除方向旋转的抽象恒等式测试及已被四边形渲染测试覆盖的twist映射测试，保留用户可见方向约定和真实交互/渲染测试；GUI数字格式的8×1000重复断言缩为8线程各执行一次完整边界检查。没有按名称批量删掉事务、并发、持久化等独立行为覆盖。完整 `build --rerun-tasks`：1961 tests/270 suites，失败/错误/跳过0，Spotless通过；三修改Java IDEA `errorsOnly:false` 全级零，MCP build成功。详见[实施进度](implementation-status.md)；背景和暂存保护记录不变，未开子代理/推送/启动游戏。

- 2026-11-12 集合复制/顺序流审查：AST覆盖746正式Java/初始1000候选，64个stream和21个toList入口已清零（原无parallelStream）。组件/宿主/扫描器、库存单key/全量、网络不可变向量、资源窗口、团队/黑名单、结构重载与客户端成员表按图/存储/成员变化缓存；绘制/Bloom队列移交并复用，骨骼估算仅cache miss冻结key，frustum无锚点整表构建，研究定义排序移到freeze，移除私有builder/分页的重复复制。必要外部输入/真实工作账本/变化事实/排序及故障隔离保留，不能宣称剩余复制全在冷路径。完整Spotless/build1963tests/270suites全零，66Java本机MCP全级零、MCP build通过。详见[分配与复制审查](allocation-performance.md)，证据collection-allocation-audit/；未新增镜像测试/启动游戏/开子代理/推送，原背景和暂存保留。

- 2026-11-12 共享仓室边界核实：内置仓室复用同一物理StorageContext；库存按context去别名，资源/FE按共享锁/revision/预留结算，调度已有shared-affinity传递串行。新增真实双控制器共仓/重复别名/四策略流程验证：两台均选2并行、仅一台足额执行，另一台不付款不推进；第一台失去结构后共享仓室仍可用，补料后第二台继续。49专项/7suites及Spotless/build通过，修改test本机MCP全级零、MCP build通过；本轮未改运行语义。注意库存建议并非全局配额，准入未长期持有物料，仍可超额选中后等待，不承诺公平分摊；设计建议与源码依据见[共享仓室](storage-redesign.md#共享仓室的并发与并行边界2026-11-12)。证据shared-hatch-audit/；未开子代理/启动游戏/推送，原背景与暂存保留。

- 2026-11-12 单输入不限输出/JEI动态布局：SimpleRecipePool.Entry改为完整outputs列表（可为空），保单输出便捷重载、原AEKey/long输入索引、freeze/CT/codec。自动选择SingleInputRecipePage，一个输入常驻、输出最多24组/页；通用页每侧最多16组，按当前真实数量动态网格，仅绘实际槽框，箭头居中、说明接在材料区后，同池按最大需要量测。翻页撤旧原生槽/同步坐标与FE，不累加tooltip；索引和样板保持完整。参考MMCE布局原则，保PM资产/自定义和外部页优先。52专项及完整1962tests/270suites全零，10Java本机MCP全级零及MCP build通过。入口[JEI](jei.md#2026-11-12-单输入多输出与动态材料布局)、[简单池](recipes.md#配方池接口与简单配方索引)；证据single-input-multi-output/。未开子代理/启动游戏/推送，原背景/暂存保留；实际GL和鼠标/样板操作待用户重启复验。

- 2026-11-12 JEI候选实机反馈：嵌入侧栏使用最终原生槽坐标，复用真实ingredient、不再每帧清空重建，pick暂空不永久缓存。左键改当前cell、右键整类替换清旧local，操作提示接原生物品tooltip；R/U/书签和只读BOM浏览保留。HEI InputHandler接收已取消Forge事件且先检索ingredient，故新增client-only Pseudo入口Mixin先交现有准确draw/owner/token及release桥。Spotless/build -x test、本机6Java＋配置＋两语言全级零和MCP build通过；未新增/运行测试。入口[JEI](jei.md)，证据jei-material-input/，需用户完整重启复验新Mixin；未启动游戏/推送，原背景与暂存保留。

- 2026-11-12 跨配方统一批次：按Σ原时长×最终并行/Σ并行向上取整，START改数量后首TICK前重算，封闭整机宿主成员不补入，共同速度/进度/END；TICK输入和输出均相同才合批，不同组保留到下一批。固定IO、FE及PN数值原总量沿共同时间精确分摊，尾差随批次UUID/时长/并行/路径保存，阻塞TICK/END共同补偿。原planner/lanes和owner门不变，GUI只改真实进度分母。完整Spotless/build1960tests/270suites全零，18Java本机MCP全级零及MCP build通过。入口[跨配方批次](cross-recipe-batches.md)，证据cross-recipe-batch/；新增三项真实行为回归，无静态镜像测试。未开新子代理/启动游戏/推送，原背景/暂存保留。

- 2026-11-11 默认GUI截图反馈：背景裁去原图第二页空线框；标题只显示一次，删除正文/标题/预留图案的重复tooltip，线程行只补全配方ID、有效并行及截断原因。结构仅显示formed的未成型/已成型，不读取临时processing gate显示检查中；执行模式/四策略不进入玩家正文或tooltip。同步删除10个无用枚举及中英各10键，保留作者调度配置。Spotless/build -x test及MCP build通过，两Java＋两语言本机MCP全级零。入口[机器GUI](machine-gui.md)，证据controller-gui-cleanup/；未新增或运行测试、未启动游戏/推送，原背景/暂存保留。

- 2026-11-11 异步配方策略/组合池：按用户默认FULL_ASYNC，另有ASYNC_SCAN/ASYNC_WORK/SYNCHRONOUS，Java/CT/保存/GUI接通；候选worker批量并发＋main复核准入，stock空库存不建Process。多线程优先上次真实完成ID，失败同预算回发现，单项PP上界避免百万线性probe；跨配方下一pass无复制轮转。实际多池Selection组合缓存16/worker冷编译，保child策略/全局priority/宽松bound/freshness，纯库存beginMatch与CT Pool.combine保准确codec。work fixedlane/原coordinator，shared affinity串行、独立组重叠，明确owner-only PN账本保main/纯kernel并发；独占claim真实退出归还及中断/异常回归。完整Spotless/build1957tests/270suites全零，459枚举key无缺，45Java＋两语言本机MCP全级零、MCP build通过。入口[配方执行策略](recipe-execution-policy.md)，证据async-recipe-policy/及各专项。原背景/暂存保留，未启动游戏/推送，总体Goal暂停。

- 2026-11-11 无用lang清理：7枚举删除92个无调用常量，中英各删104键（含4旧无等级仓室＋8旧预览键）。正式各438、示例各94；455枚举键无缺，77真实生成键和38个JSON/脚本间接枚举逐项保留，四语言无重复/不对称。完整Spotless/build1928tests/264suites全零，7Java＋两语言本机MCP全级零及MCP build成功。入口[本地化审查](localization-collections-audit.md)，证据unused-lang/；未开子代理/启动游戏/推送，原背景/暂存保留，总体Goal暂停。

- 2026-11-11 默认控制器GUI/库存匹配收尾：参考MMCE补真实成型/本地化结构名、状态、线程或配方数、long并行占用/上限和执行模式；空扩展隐藏、tab提示本地化。多线程保留声明线程，跨配方仅显示实际进程，独立13像素滚动条槽道不盖列表。RecipePool新增库存首项/库存＋并行候选组，两默认池共享缓存索引；单输入key定向捕获、stock线性路径数量上界及小预算完成proof续办，FE/权限/START仍权威。21专项与完整1928tests/264suites全零，16Java＋两语言本机MCP全级零及MCP build成功，547枚举key无缺/正式两语言无重复。入口[机器GUI](machine-gui.md)、[配方池](recipes.md#配方池接口与简单配方索引)，证据controller-gui-pool-matching/。未开子代理/启动游戏/推送，背景/原暂存保留，总体Goal暂停。

- 2026-11-11 控制器视角放置/九宫格：放置正面保留六面规则，顶部/spin改读视角投影；启用转向工具命中面显示20/60/20网格，正面中心90°，非正面中心对齐，四边刚体翻转front/top，四角无请求/无耐久。主副手、普通/潜行共用，空气潜行切模式保留；旧潜行24向循环已替换。共享Part/面坐标连接点击与绘制，纯相对变换仍在owner FIFO消费。完整Spotless/build为1924tests/264suites全零，6main＋1test及两语言本机MCP全级零；入口[结构工具](structure-tools.md)，证据controller-face-grid/。未启动游戏/子代理/推送，背景与暂存保留，总体Goal暂停。

- 2026-11-11 构造器工具融合与流体回收：动态无序合成永久消耗工具，仅保留当前档案，覆盖无返还，tooltip双语说明。按最新要求档案只用于拆卸的等级/类别及附魔掉落，不作为物品工具效果；已撤去造成AE误认扳手的中间接口，原绑定/打开交互保持。流体按真实源量填直接及一层背包容器，余量掉AE资源包保留NBT；取消事件不抽，流水不制造源。完整Spotless/build为1923tests/264suites全零，7Java/两语言本机MCP全级零及MCP build通过。入口[结构工具](structure-tools.md)，证据builder-tool-fusion/；无游戏启动/子代理/推送，背景与暂存保留，总体Goal仍暂停。

- 本轮源码与审查记录已精确本地提交 `1a05dd9`，未推送；105路径不含原暂存测试和背景，Row背景可见性单行另行保留，候选Tree单独编译通过。提交后原暂存patch/保护文件工作字节一致，详见专项证据 `exact-commit-result.json`。

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

- 用户重启复验跨配方不同原时长但相同TICK输入/输出的统一批次：短配方不提前出料，运行时不补入，全部完成后一起结算；更换逐TICK材料量或输出后应留到其它批次。复验FE/材料总量、缺电/输出堵塞共同暂停、退出重进后的时间和尾差。源码/事务/真实executor验证入口[批次说明](cross-recipe-batches.md)，未自行启动游戏。

- 用户重启复验本轮配方线程策略：新控制器默认FULL_ASYNC，事件需主线程的声明选择ASYNC_SCAN或SYNCHRONOUS；作者Java/CT四策略配置、旧Mode存档恢复、混合池来源/JEI与主线程准入、运行完成后的每工位优先重试及跨配方轮转均可观察。策略不展示在玩家GUI。PN permit/预算保持owner，外部requiresOwnerThread声明要覆盖结构children。单元/Compiler/真实Executor证据不代替World/自定义事件实际线程安全，具体入口[策略说明](recipe-execution-policy.md)。

- 用户完整重启复验默认GUI：标题只显示一次，结构只显示未成型/已成型，周期结构检查不跳“检查中”，正文/标题没有重复tooltip，也不展示执行策略；空扩展按钮和背景线框均不出现。多线程保留闲置线程，跨配方每行准确对应一个活进程，结束/取消后列表和分页收缩，跨页汇总及有效并行一致。超过七行时滑块在独立槽道，拖动/滚轮不盖内容；玩家背包操作与主tab中文正常。库存API与stock单配方百万父并行→库存可用2的一次probe已有行为回归，实际World/FE/保护回调和画面仍待用户，不以编译替代。

- 完整重启复验本轮控制器：朝上/朝下分别换yaw放置，顶部随视角变化；启用转向工具，六个面的大中心、四边、四角显示/命中一致。正面中心只转spin，非正面中心对齐，边翻转保留完整朝向，四角不耗耐久；主/副手与潜行规则一致，空气切模式正常；退出重进保留方向。GL画面与真实放置/输入仍待用户，不以几何/模型测试替代。

- 用户完整重启复验本轮：构造器普通/潜行右键控制器只绑定/打开，不旋转/扳手拆卸；合成和二次覆盖不返还工具，物品自身无工具效果，结构拆卸使用当前档案的等级/精准采集/时运。再验空桶、同流体部分容器、无容量/异种流体、背包满时余物、AE包数量/NBT与保护事件取消。详见[最新操作与边界](structure-tools.md)。

- 用户完整启动客户端，先复验本轮metadata修复后的机器/结构中文（包含World子节点、网络、渲染与组合结构），再沿下面既有材料槽/JEI/构造器及组合结构步骤验收。原生注入、World、保存/支付与画面仍待用户；总体Goal保持暂停。后续扩展调用严格primitive fastutil API，见[专项审查](localization-collections-audit.md)。

- 用户完整重启，按 [组合结构实机步骤](../run/scripts/README-composite.md) 使用 `kit composite` 和两类build：嵌套26材料且两份pod/marker，拓展1–4段分别22/31/40/49材料、唯一金盖跟随末段；执行真实structure-check正/负例、投料和显式段数预览。构造器拖重复次数时左右/用料/投影同步，关闭重开/退出重进保留，对空控制器选3段实际构建40材料。SDK编译及NBT/几何行为回归不能代替这些原生结果。

- 优先复验2026-11-11本批：完整重启后核对机器中文名；JEI与构造器材料网格横向逐行且不盖滚动条，材料及候选能按原生书签键/R/U识别。构造器玻璃单格替换后左网格和右树同时108＋1，整种后109；选择后自动关闭，外部点击及Esc直接关闭。打开结构页等待预览就绪后转移键应恢复可用，任一片层仍导入完整169材料；层滑块上方对应高层、下方低层，滚轮方向一致。以上为待用户原生结果，不以22项行为测试或IDE检查替代。

- 材料仓手持水桶/空桶时复验AE原生左右键存入、左键装填提示的分行、深灰文案/灰色资源与内嵌图标；原Shift整批交互保留，但不再使用自有拼接说明。空光标时原资源名称、数量/容量提示保持。用户自行重启客户端观察，未以源码检查代替渲染验收。

- 先由用户完整重启客户端从样板终端对示例boundary_structure_controller按R进入结构页，复验展开左栏立即显示材料、左键玻璃候选只改单格(108＋1)、右键整类(109)、原生tooltip的双语左右键说明且不误跳配方。再验完整169输入/任一片层仍169及“原名-结构”控制器×1输出并实际编码；步骤见run/visual-examples/README.md。JEI与构造器各自选择独立，不用工具NBT解释结构页导入结果。忽略目录示例已编译安装，但IDE拒绝分析该目录，不能以主源码全级零冒充示例IDE结果。

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
| Java 与 CraftTweaker API 使用和扩展 | [api.md](../api.md) |
| 已确认需求/功能范围 | [development-spec.md](development-spec.md) |
| 实际完成度/验证证据 | [implementation-status.md](implementation-status.md) |
| CT声明/结构SAM/配方分配/研究网络及验证边界 | [crafttweaker.md](crafttweaker.md) |
| 旧实现及已知问题 | [migration-audit.md](migration-audit.md)、[machine-controller-migration-audit.md](machine-controller-migration-audit.md) |
| 检查点分段执行 | [checkpoint-execution-audit.md](checkpoint-execution-audit.md) |
| 配方阶段/预设事件的真实迁移状态与功能优先顺序 | [recipe-feature-parity.md](recipe-feature-parity.md)、[pm-recipe-events-audit.md](pm-recipe-events-audit.md) |
| 普通数据库下载/自动升级/真实物理授权/数据库 GUI | [data-centers.md](data-centers.md) |
| 普通物品转换/材料和来源费用/nonce与原生输出恢复/分级盘边界 | [research-downloads.md](research-downloads.md) |
| 研究 GUI（已移除，延缓实现） | [research-gui.md](research-gui.md) |
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
| 集合复制/流调用/热路径分配 | [allocation-performance.md](allocation-performance.md) |
| 结构匹配/快照/发布 | [structures.md](structures.md)、[structure-migration-audit.md](structure-migration-audit.md) |
| 存储/预留/FE | [storage-redesign.md](storage-redesign.md) |
| 仓室/美术来源 | [hatches.md](hatches.md)、[assets.md](assets.md)、[controller-assets.md](controller-assets.md) |

## 本地源码

PE `F:/new/PrototypeEngineering`；PM `F:/clone/PrototypeMachinery`；PN `F:/new/PrototypeNetwork`；AE2S `F:/clone/Applied-Energistics-2`；MMCE `F:/clone/ModularMachinery-Community-Edition`。PM存储入口 `src/main/kotlin/{api,impl}/storage`，planned入口 `impl/scheduler/planned`；PE按专题进入 `src/main/java/com/circulation/prototype_eng/{api,machine,recipe,scheduler,structure,research,integration}`。

索引不替代源码审查。精简前完整索引只作恢复审计，保留于 `build/staging/controller-shared-parallel/exact-commit/index-before-compaction.md`；历史事实及证据按实施进度/专题查阅。
