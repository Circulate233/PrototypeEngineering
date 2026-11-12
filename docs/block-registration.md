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
`DecorativeBlockModels` 注册物品模型。均不创建 TileEntity、配方、ME 节点或机器控制行为。通风口沿AE水平朝向放置；电路块保留原几何及动画BLOOM覆盖。

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

## ME 美术准备

来源独立的 `block_casing_plain_side.png` 已作为素面外壳侧面注册，顶部及底部沿用机械外壳。来源框架控制器外皮保留为 `prototype_eng:block_controller_frame`，可由 Java/ZS 选择模型，不另加未确认的控制器功能。`ventilation_port_tiled_cover` 仅为空目录，`block_factory_controller` 为来源缺失模型引用，`fluxnetwork_link` 仅有语言条目，均未据此臆造正式功能方块。

`导出/mix.zip` 优先于原目录的 `textures/blocks/bus/mix` 及样板供应器纹理，包含通用输入/输出共用外壳、接口外壳及样板总成各三个面。九张
PNG 的可见像素与包内一致；当前共享工作树有 PNG 重编码和三图各四个透明像素的 RGB 清理，因此不再宣称文件原字节相同。输入与输出标记沿用原
`bus_in`、`bus_out` 及其 BLOOM 元数据，添加当前已有的 `prototype_eng_bloom` 回退标记。模型保留原 credit、几何和
UV，迁移命名空间，并沿现有仓室规范把发光覆盖层后绘且设为 `shade=false`。

四个 `me_*` 均有独立方块、物品与模型注册。三种资源总成已接入独立事务库存及实际 ME 节点；样板总成按下节规则接入，最终验证见实施进度。

GUI输入/输出原图采用用户指定的 `prototypemachinery/textures/gui/gui_bus/gui_item_bus.png`，接口采用 `gui_item_io_bus.png`
。保留下角标记槽及实际槽两个18×18图案，擦除无功能的升级列与自动IO按钮；源导出同名mix原图缺少这两个图案，不作为上述GUI主图。样板背景和操作图来自
`导出/gui/gui_bus/gui_pattern_bus*.png`，配套 `io_mode.png`、`color_palette.png`
。样板升级列擦除，退回、模式与颜色控件保留。所有修改均有像素范围及原字节证据，界面不再显示不存在的升级或邻居自动IO。

## 通用 ME 库存

`MeAssemblyConfig` 从 `config/prototype_eng/me_assemblies.cfg`
读取输入槽数（默认18）与样板页数（默认1）；每页固定36样板。输入总成与接口共用输入槽数配置。输入标记表示期望保有量，真实输入槽按缺口从ME补足；标记清除或换种先尝试把旧材料退还ME，拒收时保留旧材料。输出没有标记或槽数上限，使用无序
`AEKey2LongMap`，按实际资源类型动态生成可滚动的显示槽。每种资源最多 `Long.MAX_VALUE`，只做必要溢出及事务容量预检。

ME仓不暴露Forge物品/流体自动化能力，玩家不能把物品写入真实输入/输出显示槽。玩家可用手持物品、资源包或容器选择标记，不消耗物品；中键调用AE原生long数量编辑页，Shift/Ctrl＋滚轮使用AE数量设置。输入标记版本与补料库存版本分离，过期编辑不能改动新标记。ME传输只访问未预留的可用量，并在真实外部传输前保留退款与容量责任。

## 颜色与样板隔离

`ResourceHatch.inputGroup()` 返回 `ResourceInputGroup(AEColor, partition)`，默认福鲁伊克斯透明色（无色）；无色库存作为独立输入组，并可由任一有色组通用读取，不会把其他不同颜色库存并入该组。所有内置材料仓和ME仓实现
`ResourceHatchGrouping`，GUI可选16种染色与无色并保存到NBT。FE仓不参与颜色分组。颜色相同才形成同一输入库存，配方发现分别查询各组并合并候选身份；准入探测逐组进行，已选进程持续使用选定组，保存/恢复也保留颜色与partition。找不到原组时不回退到其他颜色。

`inputPartitions()` 供样板总成或外部仓室暴露独立视图；普通仓室默认单个自身视图。显式partition优先于颜色，分区标识统一使用`AEColor.TRANSPARENT`并保留partition，不能把它误当作通用无色库存。样板隔离模式下，每个样板槽始终按总成身份和槽号独立扫描，与总成设为无色或任何染色均无关。

每个隔离槽可读取自己的缓存、该总成`sharedInput()`催化剂视图以及其他未分区的无色库存；不能拼接其他样板槽、其他总成的催化剂或有色普通仓库存。样板输出仍回到本总成输出缓存，可用未分区无色输出作为通用输出。未启用样板隔离时，才按总成设置的颜色分组。底层缓存与事务去别名依然使用原物理`StorageContext`，各槽共享无色库存不会复制余额或重复预留。

`ResourceRecipeObserver` 接收真实进程attach/detach、实际来源 `inputsCommitted`
和整阶段成功结算通知。预留只在真实完整阶段提交后、存储锁外发布供料回执，探测和回滚不发布；回调不得从worker读写World，需自行同步纯状态。样板总成据此关联实际供料进程的持久化UUID，避免无关同色仓或同配方同seed进程完成时解除锁定。

## 样板总成

按原布局保留每页36个样板槽、可配置页数、翻页、批量×/÷2、3、5、8，修改前预检所有样板并保留AE2S配方类型UID。输入、催化剂及输出均为无槽数/总容量上限的key→long缓存；催化剂合并同种资源并支持手动存取。默认、阻挡、配方阻挡、样板隔离、合成锁定五模式按用户确认的顺序循环，输入退回按钮仅把实际ME接受的数量从本地移走。

真实节点提供 `ICraftingProvider` 并由节点宿主实现 `PatternContainer`
，接入样板管理终端、编辑和合并推送。CPU传入的材料已经按批次数缩放，接收前对完整key与long数量预检；失败不能留下部分材料。加工样板提供者不额外伪装成
`ICraftingRequester`，CPU继续拥有任务账本。

PAT 远程管理复用 AE2S 1.1.1 的 `PatternContainerGuiReturnContext`。仅当原终端会话有效、目标区块已加载、两端节点在线且同网时豁免本地8格距离；Esc/背包键沿 AE 原生流程返回原 PAT 窗口。本地方块打开保持正常距离与编辑权限检查。

## 并行控制器

五阶沿MMCE：普通4、强化16、精英64、超级256、终极512，默认上限可在配置文件修改。各阶使用单独生成的染色PNG；原始单纹理保留为外部Java/ZS注册的默认模型，不使用动态染色。

`ParallelController` 暴露不可变selected/maximum/revision/valid快照，结构组件自动发现；GUI可选0以禁用贡献。机器内部
`PROCESS_PARALLELISM` 加各控制器选定贡献，受 `MAX_PROCESS_PARALLELISM`
裁剪（默认整数上限），在扫描/共享并行准入时捕获并复核。已有运行进程不因调整控制器被改写。类型注册、Java/ZS参数和示例见根目录 [api.md](../api.md)
与 [api-zs-reference.md](../api-zs-reference.md)。

## 验证范围

静态资源检查、实际库存/扫描/事务行为测试、IDEA MCP全级诊断及构建证据集中在 [实施进度](implementation-status.md)
。未自行启动Minecraft；原生模型、透明面、CTM/Bloom、AE连接及实际GUI操作需用户运行客户端复验。来源的 `ctm:optifine_full_3tile`
不受当前CTM原生类型支持，对应PNG保留但不激活不合法元数据；基础面纹理正常引用，不能宣称全部连接纹理效果已在游戏验证。
