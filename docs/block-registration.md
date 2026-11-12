# 方块补齐与 ME 总成

## 已确认范围（2026-11-12）

用户要求核对并补齐美术包中的方块，来源为 `F:/[PM]-原型机械/[PM]-原型机械/prototypemachinery` 及同级 `导出`
，不以资源是否已迁入本库作为筛选条件。明确的预览、标识及测试模型不注册为正式方块。

物品、流体、气体专用仓和专用 ME 总线继续弃用，使用支持多种 AEKeyType 的通用型。新增 ME 组件采用以下名称：

| 资源入口              | 中文名         | 英文名                        |
|-----------------------|----------------|-------------------------------|
| `me_input_assembly`   | ME机械输入总成 | ME Machinery Input Assembly   |
| `me_output_assembly`  | ME机械输出总成 | ME Machinery Output Assembly  |
| `me_interface`        | ME机械接口     | ME Machinery Interface        |
| `me_pattern_assembly` | ME机械样板总成 | ME Machinery Pattern Assembly |

用户已逐项确认下面的 ME 存储、隔离、样板五模式和并行控制器行为；未根据图标新增红石、相邻容器传输或升级功能。

## 当前装饰方块

外壳、素面外壳、框架、六种玻璃、机械电路块和四种通风口共15项，由 `DecorativeBlocks` 使用 `AEBaseBlock` / `AEBaseBlockItem` 注册，由客户端
`DecorativeBlockModels` 注册物品模型。均不创建 TileEntity、配方、ME 节点或机器控制行为。通风口沿AE水平朝向放置；电路块保留原几何及动画BLOOM覆盖。六种玻璃均使用非opaque、非fullCube、lightOpacity=0及邻居亮度采样，避免实心AO遮阴；完整碰撞箱保持。AE的setOpaque()/setFullSize()分别将对应标志设为false，不能按方法名理解为开启。

| ID                                  | 中文名       | 英文名                            |
|-------------------------------------|--------------|-----------------------------------|
| `block_casing`                      | 机械外壳     | Machinery Casing                  |
| `block_casing_plain`                | 素面机械外壳 | Plain Machinery Casing            |
| `casing`                            | 机壳改造块   | Casing Modifier Block             |
| `casing_frame`                      | 框架外壳块   | Casing Frame Block                |
| `cartesian_glass`                   | 笛卡尔玻璃   | Cartesian Glass                   |
| `pure_cartesian_glass`              | 纯笛卡尔玻璃 | Pure Cartesian Glass              |
| `rounded_glass`                     | 圆角玻璃     | Rounded Glass                     |
| `pure_rounded_glass`                | 纯圆角玻璃   | Pure Rounded Glass                |
| `slanted_glass`                     | 斜面玻璃     | Slanted Glass                     |
| `pure_slanted_glass`                | 纯斜面玻璃   | Pure Slanted Glass                |
| `block_control_circuit_1`           | 机械电路块   | Machinery Circuit Block           |
| `ventilation_port`                  | 通风口       | Ventilation Port                  |
| `ventilation_port_cover`            | 通风口盖     | Ventilation Port Cover            |
| `ventilation_port_horizontal_cover` | 横向通风口盖 | Horizontal Ventilation Port Cover |
| `ventilation_port_vertical_cover`   | 纵向通风口盖 | Vertical Ventilation Port Cover   |

## 背包与HEI逐层物品渲染

CTM发光纹理extra.light=15经Quad.rebake转为带UV1的BLOCK格式，BLOCK无NORMAL；HEI批量快路径直接LightUtil.renderQuadColor转回ITEM，缺失法线被填0且没有执行Forge的逐面shade/光照处理。底壳仍为带正常法线的ITEM，因而可出现仅叠加层发暗。真实native LightUtil验证覆盖ITEM法线0x00810000、BLOCK无normal及转回ITEM零法线；不是几何法线生成错误。

本库方块物品统一继承PrototypeBlockItem（仍是AEBaseBlockItem），通过JEI公开ISlowRenderItem进入原生ItemStackRenderer→Forge.renderLitItem，遵守逐层shade及UV1，保持原模型/贴图。JEI不存在时FML Optional.Interface剥离空marker；不增加全局渲染Mixin。此证据定位HEI发光覆盖层的差异，最终GL显示与此前库存侧反向现象仍需实机复验，不将路径统一冒充全部画面已验收。

## ME 美术准备

来源独立的 `block_casing_plain_side.png` 已作为素面外壳侧面注册，顶部及底部沿用机械外壳。来源框架控制器外皮保留为 `prototype_eng:block_controller_frame`，可由 Java/ZS 选择模型，不另加未确认的控制器功能。`ventilation_port_tiled_cover` 仅为空目录，`block_factory_controller` 为来源缺失模型引用，`fluxnetwork_link` 仅有语言条目，均未据此臆造正式功能方块。

`导出/mix.zip` 优先于原目录的 `textures/blocks/bus/mix` 及样板供应器纹理，包含通用输入/输出共用外壳、接口外壳及样板总成各三个面。九张
PNG 的可见像素与包内一致；当前共享工作树有 PNG 重编码和三图各四个透明像素的 RGB 清理，因此不再宣称文件原字节相同。输入与输出标记沿用原
`bus_in`、`bus_out` 及其 BLOOM 元数据，添加当前已有的 `prototype_eng_bloom` 回退标记。模型保留原 credit、几何和
UV，迁移命名空间，并沿现有仓室规范把发光覆盖层后绘且设为 `shade=false`。

四个 `me_*` 均有独立方块、物品与模型注册。三种资源总成已接入独立事务库存及实际 ME 节点；样板总成按下节规则接入，最终验证见实施进度。

GUI输入/输出原图采用用户指定的 `prototypemachinery/textures/gui/gui_bus/gui_item_bus.png`，接口采用 `gui_item_io_bus.png`；当前资源文件分别使用通用命名 `gui_resource_bus.png`、`gui_resource_io_bus.png`，正式GUI不再引用item命名。保留下角标记槽及实际槽两个18×18图案，移除无功能的升级列与自动IO按钮；导出的mix原图没有这两个图案，保留其原布局但不替换正式GUI主图。

2026-11-12按用户截图重新修图：这批10张新增GUI纹理全部从上述原始目录和`导出/gui`恢复后再修改。五张背景分别定位：资源图升级列为`[199,4,225,176)`，mix图为`[217,4,243,176)`，样板图为`[346,4,372,176)`，坐标右下不包含。不能跨图片套用同一擦除框。资源图左侧自动IO挂耳完整清除，并从邻近完好边框补回两列接缝；mix图只清除两处空按钮轮廓。标题、正文、背包、滚动槽和底部slot/箭头图案保留，样板左侧退回/模式/颜色控件保留。

`gui_bus_slot.png`、`gui_pattern_bus_slot.png`、`io_mode.png`及两张调色板从原文件恢复，不作像素修改。五张背景逐像素核对确认白名单外RGBA与原图完全一致；mix及样板主界面完整区域与原图一致。共享工作区出现PNG重编码，最终以完整RGBA一致性验证，不把文件字节变化当作像素改变。修图清单与逐张放大前后对比保存在`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261112/gui-artwork-repair/`，资源构建与游戏画面验证分别记录于实施进度。

随后2026-11-12纹理使用清理移除了确认无消费者的`gui_mix_bus.png`、`gui_mix_io_bus.png`、`gui_bus_slot.png`和`color_palette_shadow.png`。正式ME GUI仍使用上述通用命名两张资源主图、样板主图及其slot图集，`io_mode.png`和`color_palette.png`保留。未被模型或CTM元数据引用的导出备用图也已删除，注册装饰方块实际使用的基础面和已有CTM元数据引用不变。

## 通用 ME 库存

`MeAssemblyConfig` 从 `config/prototype_eng/me_assemblies.cfg`
读取输入槽数（默认18）与样板页数（默认1）；每页固定36样板。输入总成与接口共用输入槽数配置。输入标记表示期望保有量，真实输入槽按缺口从ME补足；标记清除或换种先尝试把旧材料退还ME，拒收时保留旧材料。输出没有标记或槽数上限，使用无序
`AEKey2LongMap`，按实际资源类型动态生成可滚动的显示槽。每种资源最多 `Long.MAX_VALUE`，只做必要溢出及事务容量预检。

ME仓不暴露Forge物品/流体自动化能力，玩家不能把物品写入真实输入/输出显示槽。玩家可用手持物品、资源包或容器选择标记，不消耗物品；中键调用AE原生long数量编辑页，Shift/Ctrl＋滚轮使用AE数量设置。输入标记版本与补料库存版本分离，过期编辑不能改动新标记。ME传输只访问未预留的可用量，并在真实外部传输前保留退款与容量责任。

## 颜色与样板隔离

GUI提示使用“隔离组：红/无色”（英文Isolation group），分组身份仍沿AEColor保存。普通按钮中心从(2,2)9×9铺满，三态外沿/阴影与原自动IO逐像素一致；ME保留原9×9中心。选择面板局部关闭深度测试和写入及光照，再恢复原状态，覆盖已绘制槽内物品。ME资源及样板GUI的滚轮入口仅消费非零滚轮事件，不能在展开时把点击/松开也取消；颜色选择沿AE原生widget matching release执行一次并关闭。

`ResourceHatch.inputGroup()` 返回 `ResourceInputGroup(AEColor, partition)`，默认福鲁伊克斯透明色（无色）；无色库存作为独立输入组，并可由任一有色组通用读取，不会把其他不同颜色库存并入该组。所有内置材料仓和ME仓实现
`ResourceHatchGrouping`，GUI可选16种染色与无色并保存到NBT。FE仓不参与颜色分组。颜色相同才形成同一输入库存，配方发现分别查询各组并合并候选身份；准入探测逐组进行，已选进程持续使用选定组，保存/恢复也保留颜色与partition。找不到原组时不回退到其他颜色。

`inputPartitions()` 供样板总成或外部仓室暴露独立视图；普通仓室默认单个自身视图。显式partition优先于颜色，分区标识统一使用`AEColor.TRANSPARENT`并保留partition，不能把它误当作通用无色库存。样板隔离模式下，每个样板槽始终按总成身份和槽号独立扫描，与总成设为无色或任何染色均无关。

每个隔离槽可读取自己的缓存、该总成`sharedInput()`催化剂视图以及其他未分区的无色库存；不能拼接其他样板槽、其他总成的催化剂或有色普通仓库存。样板输出仍回到本总成输出缓存，可用未分区无色输出作为通用输出。未启用样板隔离时，才按总成设置的颜色分组。底层缓存与事务去别名依然使用原物理`StorageContext`，各槽共享无色库存不会复制余额或重复预留。

`ResourceRecipeObserver` 接收真实进程attach/detach、实际来源 `inputsCommitted`
和整阶段成功结算通知。预留只在真实完整阶段提交后、存储锁外发布供料回执，探测和回滚不发布；回调不得从worker读写World，需自行同步纯状态。样板总成据此关联实际供料进程的持久化UUID，避免无关同色仓或同配方同seed进程完成时解除锁定。

## 样板总成

按原布局保留每页36个样板槽、可配置页数、翻页、批量×/÷2、3、5、8。乘除逐槽处理：无效、溢出或不能整除的样板保持原样，继续处理其他槽；只有实际修改时更新版本，并保留AE2S配方类型UID。输入、催化剂及输出均为无槽数/总容量上限的key→long缓存；催化剂普通物品按原生ItemStack取放，空手左键最多一组、右键半量且不超过一组，Shift向背包转移；非物品通过容器取放，不自动封成AE资源包。已有资源包仍可解包存入。

资源槽在匹配的鼠标松开时仅发送一次服务器操作，避免自定义点击后继承松键再次存回。五模式左键松开循环、右键松开打开AE原生GridSelectionPopup选择器；按钮按下图保持原模式，松开才变更。退回按钮仅把实际ME接受的数量从本地移走。自动IO保留原states.png的14×14图、位置(4,13)/(4,31)及原状态；普通仓室颜色按钮使用独立HATCH样式的同款14×14空框；ME输入/输出/接口及样板总成明确使用ME样式，保留io_mode原17×18框及原挂耳，位置分别(1,13)/(1,43)，不套用普通仓小框。两种样式各自定义色块、点击边界及展开后的无色选择区域，共享松开执行行为。小框框面悬停/按下分别下移1/2px，色块同步纵移；ME大框内嵌色块保持固定。框与面板按AE2Button方式启用局部透明混合，Gui.drawRect后恢复白色和混合，避免透明源像素里的白色RGB显露成白条；展开面板hover先转换屏幕坐标。材料仓背景仅延伸颜色挂耳[0,49,21,71)，原自动IO区域不变，不添加LED。标题框内居中，自有帮助显式分行和限宽。ME与普通仓共用HatchScrollbar，30px滚轮步长/180–360ms平滑、按轨定位及拖动相同；ME内轨x175、单仓y25/高70，接口y25与98/高67，按实际68/72px视口计算范围。启用图复用PM原像素，禁用图直接用AE原big/small sprites；样板7×12启用切片从源slot图裁取，删除旧hatch_scrollbars自造图集。

真实节点提供 `ICraftingProvider` 并由节点宿主实现 `PatternContainer`
，接入样板管理终端、编辑和合并推送。CPU传入的材料已经按批次数缩放，接收前对完整key与long数量预检；失败不能留下部分材料。加工样板提供者不额外伪装成
`ICraftingRequester`，CPU继续拥有任务账本。

CPU读档后的样板匹配必须按完整`AEItemKey`定义相等判断；CPU任务与总成各自重新解码，不能要求`IPatternDetails`是同一Java实例。总成先找原实例对应槽，未命中再按`getDefinition().equals(...)`找当前样板，合并推送、最大批次预检和实际发放共用该入口。完整定义包含物品/meta/NBT及样板输入、输出、数量和配方类型UID；同输入的不同样板仍各自识别。

PAT 远程管理复用 AE2S 1.1.1 的 `PatternContainerGuiReturnContext`。仅当原终端会话有效、目标区块已加载、两端节点在线且同网时豁免本地8格距离；Esc/背包键沿 AE 原生流程返回原 PAT 窗口。本地方块打开保持正常距离与编辑权限检查。

## 并行控制器

五阶沿MMCE：普通4、强化16、精英64、超级256、终极512，默认上限可在配置文件修改。各阶使用单独生成的染色PNG；原始单纹理保留为外部Java/ZS注册的默认模型，不使用动态染色。

放置复用机械控制器的`ControllerOrientation.placement`，使用full orientation；五阶与外部默认类型均有24向状态，水平spin复用既有ControllerRotatedModel。模型元素、UV和display沿原作者文件，不重新拼造立方体。

`ParallelController` 暴露不可变selected/maximum/revision/valid快照，结构组件自动发现；GUI可选0以禁用贡献。机器内部
`PROCESS_PARALLELISM` 加各控制器选定贡献，受 `MAX_PROCESS_PARALLELISM`
裁剪（默认整数上限），在扫描/共享并行准入时捕获并复核。已有运行进程不因调整控制器被改写。类型注册、Java/ZS参数和示例见根目录 [api.md](../api.md)
与 [api-zs-reference.md](../api-zs-reference.md)。

## 机械外壳与仓室基底连接

机械外壳原三面CTM只允许连接block_casing，仓室则使用带自身图案的独立16px复合底图。现三面和64个正式仓室模型的123个基底sprite接入prototype_eng:casing：同一渲染面实际使用该type的机械外壳与HatchBlock双向互连，仓室之间跨种类/模式/等级互连；自定义HatchBlock复用同基底sprite可加入，不硬编码逐阶ID。外围其他方块不新增组成员，其原connect_to规则保持。324个发光/IO叠加面不参与。

连接仍使用原生TextureContextCTM的方向、转角和遮挡算法。按原32px外壳sheet各子图对应的唯一象限，将原复合图的细节像素带入123张派生sheet；原16px纹理、模型、等级ring及图标不改，库存/无CTM沿用原图。125952个派生像素、187项原模型/纹理哈希和8×前后/壳仓混排检查已核对；作者原图中的底纹差异保留，不使用阈值抠图或强行抹平等级边框。

原生CTM自己的ModelBake handler也在LOWEST，因此不依赖同优先级事件顺序扫描。事件仅换缓存epoch，第一次实际渲染时读取已完成的模型，从CTM未转换parent的对应face/sprite识别基底；每state每reload只读一次，并发查询使用ConcurrentHashMap，不全局锁住邻接热路径。无CTM的subscriber类加载及反射已在明确移除CTM JAR的进程验证，真实native模型/TextureContextCTM及缓存竞争测试不启动Minecraft/FML/GL。

## 验证范围

静态资源检查、实际库存/扫描/事务行为测试、IDEA MCP全级诊断及构建证据集中在 [实施进度](implementation-status.md)
。来源的`ctm:optifine_full_3tile`未被当前CTM识别，且有失效proxy及旧namespace。现由本模块注册`prototype_eng:optifine_full`，沿CTM原生TextureContextCTM进行邻接/遮挡/状态判断，将47种有效邻域映射到原作者三张sheet。30个面接独立16×16基础面和三张连接图，恢复90张源sheet；库存/关闭CTM时使用独立基础面，不把32/64px分图作为整张方块面。

未自行启动Minecraft；原生模型、透明面、CTM/Bloom、AE连接及实际GUI操作需用户运行客户端复验。native CTMLogic、Submap和原始图片的256邻域/47图块行为已验证，真实texture atlas及Quad重烘仍须客户端；编译和图片检查不代替实机视觉结果。
