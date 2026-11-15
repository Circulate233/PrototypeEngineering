# 机器 GUI 与 Java 扩展

默认机器界面由 [GuiMachine](../src/main/java/com/circulation/prototype_eng/client/gui/GuiMachine.java) 直接继承 AE2S 的 `AEBaseGui<ContainerMachine>`，默认 [ContainerMachine](../src/main/java/com/circulation/prototype_eng/container/ContainerMachine.java) 直接继承 `AEBaseContainer`。背景与滚动条组合复用 AE2；没有本项目 GUI 中间基类、ModularUI、编辑器或 GUI DSL。当前各界面的实现范围见[核对表](implementation-status.md#2026-11-15-gui-文档与源码核对)。

## 打开与默认显示

`MachineControllerBlock.onBlockActivated` 在玩家未潜行、手持物品不是方向工具且目标为控制器 TE 时打开 GUI 0。手持方向工具的交互由物品处理。PreInit 注册 `CommonProxy` 为 Forge `IGuiHandler`；common 端按准确 TE 创建容器，客户端代理再创建对应 screen。

默认界面只在顶部显示一次机器声明的本地化标题，正文显示结构未成型/已成型、运行状态、运行线程/容量或运行配方数，以及全机并行占用/上限，并保留玩家主背包和快捷栏。结构名称不在正文重复；结构状态始终按正式formed值显示，不把周期刷新时的processing gate渲染成检查中提示或切换状态。执行模式/策略属于作者的内部调度选择，不在玩家GUI或tooltip中显示。参考 MMCE 的 `GuiMachineController`/`GuiFactoryController` 的结构、状态、线程与并行信息；本库尚无对应事实的蓝图、红石及耗时统计不作虚构显示。

多线程列表按组件及声明工位顺序显示线程，包括闲置线程；跨配方列表只按相同顺序显示实际持有进程的行，每行标题为其配方、附实际进度。行tooltip只补充完整配方ID、有效并行数及被截断的原因，不再重复已经完整显示的状态；没有补充内容的闲置行不弹tooltip。标题和正文不弹重复说明，4×4区域使用真实控制器库存。动态空槽不占显示位置，结束/取消后总行数和offset随真实列表裁剪。窗口最多七行，跨页汇总仍包含全机进程。进度素材保留 PM 的89×33区域，13×15滚动条移回背景的独立槽道（x=120），绘制、悬停和拖动命中一致，避免原先放大到38像素后覆盖行内容。

进度由绝对工作刻数除以配方时长并裁剪至0..100，零时长进程显示100。多线程工位没有进程时仍显示其保存的结果，因此失败/取消后的原因不会被固定Idle文本覆盖；跨配方取消后的失败仍参加全机状态汇总。机器不存在或inactive时仍同步宿主真实formed和结构身份，不显示虚假的执行计数。结构状态只读宿主formed，不把周期结构检查的临时processing gate映射成跳动的“等待结构检查”；未成型时不再在运行状态处重复“未成型”。

跨配方成员的进度分母使用[统一批次时长](cross-recipe-batches.md)，共同完成后一起清除运行行；保持机器标题/结构状态/实际运行数量等玩家信息，不增加批次调度或线程策略提示。

默认扩展页没有任何实际内容提供者，只显示主页面；背景按主体和主tab两块绘制，裁去原图中仍然存在的第二页空线框，自定义内容继续通过既有Java容器/screen注册入口提供。主tab提示在客户端调用枚举getLocal；已退役的扩展/占位、执行策略和检查中枚举及双语条目直接删除，不保留占位翻译。

对照 PM 的 [DefaultMachineUI.kt](F:/clone/PrototypeMachinery/src/main/kotlin/client/gui/DefaultMachineUI.kt)：其七行工位列表与玩家背包是实际功能；`buildGridSlots` 的 4×4 区域新建临时 `ItemStackHandler`，`Status: Idle` 是固定文本，扩展页只含占位说明。当前默认 GUI 展示真实状态，4×4区域现已接入控制器持久化物品库存，不再创建临时占位库存。PM 的 [DefaultMachineUIBindings.kt](F:/clone/PrototypeMachinery/src/main/kotlin/impl/ui/binding/DefaultMachineUIBindings.kt) 为旧 bindKey/脚本 GUI 服务；本项目扩展改用 Java 容器与 screen，不迁入字符串绑定运行时。

## 控制器库存

默认控制器左下角提供16个物品格。`controllerInventorySlots(int)` 设置开放数量，默认0；`controllerInventorySlots(ToIntFunction<MachineInstance>)` 按当前机器实例计算数量。配置动态函数后优先使用其返回值；没有函数、函数被设为null、机器尚未装配或已注销时使用固定值。返回值必须在0到16之间，越界会抛出 `IllegalArgumentException`，不会自动裁剪。动态函数在服务端工作线程结束后的边界执行，必须只读取状态，禁止在函数内修改机器、库存或世界。

`machine.controllerInventory()` 返回物理控制器的原生 `InternalInventory`，可用 `getStackInSlot(slot)` 按索引读取，用原生迭代器遍历。默认宿主的具体实现为 `ControllerInventory`，`availableSlots()` 返回当前开放数，`forEachAvailable((stack, slot) -> ...)` 遍历开放格，`forEachStored(...)` 只遍历实际有物品的格子，包括临时关闭的格子。`MachineControllerHost.controllerStack(slot)` 和 `forEachControllerStack(...)` 供自定义控制器使用。必须在服务端拥有机器执行权时读取这些物品，禁止从配方工作线程直接访问物品堆栈；需要异步计算时，先在服务端采集所需的只读事实。

格子从左到右、从上到下开放。未开放格子覆盖半透明白色，普通点击、拖动、双击、数字键及Shift移动都必须通过相同的服务端开放状态检查。关闭格子保留其中物品，重新开放后恢复访问；全部物品随控制器保存，拆除控制器时参与正常物品掉落。

## 服务端读取与有界同步

[MachineGuiSnapshot](../src/main/java/com/circulation/prototype_eng/container/MachineGuiSnapshot.java) 是实现 AE2 `PacketWritable` 的不可变显示值，由 `@GuiSync(0)` 同步。容器仅发送客户端请求的可见页，最多七个工位，不发送全部工位进程 NBT、runtime、组件、存储或 Forge capability 对象。

`broadcastChanges` 最多排队一次 capture；`MachineControllerBlockEntity.readGuiAtBoundary` 将读取排入 scheduler owner FIFO，在 accepted worker 完整 join 后执行。回调内再次确认玩家的 `openContainer` 正是本容器，TE 有效、World 相同、区块已加载且该位置仍是同一 TE，并复用 AE2 的 `canInteractWith` 检查。capture 还要求机器持久化稳定边界、各工位宿主 quiescent 且 owner 是同一机器，返回的条目不保留进程引用。

客户端 `view` action 只包含非负 offset 和 1..7 的行数，payload 上限 80；它仅调整查看范围。capture 根据当前布局裁剪 offset。每条 name、recipe、reason 及每个原因参数最多 1024 个字符，参数最多 16 项；接收端在建立行/参数列表前检查数量，并验证状态序号、进度及页范围。默认视图提供读取与正常玩家背包操作，PN owner/team/研究权限仍须后续集成，显示状态和 placementPlayer 不构成授权。

`MachineGuiText` 实现 AE2S `LocalizationEnum`；客户端标签调用 `getLocal`，容器标题用机器声明的 `displayName().text()`。内置 `RecipeText` 原因按枚举翻译并传递参数；未知外部原因保留 ID/文本及参数作为可读 fallback，不在 GUI 拼接翻译键。需要特殊原因表现的使用方可注册自己的 Java screen。

## 默认与自定义注册

2026-11-15 网络中心、计算机和研究站默认界面的布局改版见 [设备界面改版](device-gui-redesign.md)，注册方式不变。

网络中心默认使用 `ContainerNetworkCenter` / `GuiNetworkCenter`，计算机的 `ComputerController.DEVICE` 默认使用 `ContainerComputer` / `GuiComputer`，研究站的 `ResearchStation.DEVICE` 默认使用 `ContainerResearchStation` / `GuiResearchStation`。三个专用界面均不添加玩家背包槽。计算机只有概览和组件两页，显示已结算的费用、资源及组件状态；配方列表、输入和转换产出由 JEI 展示，详情见[计算机](computers.md)。数据库专用 GUI 尚未接入，没有外部自定义绑定时使用通用机器界面。显式注册的容器与 screen 继续优先使用调用方提供的工厂。

common 入口 [MachineGuiRegistry](../src/main/java/com/circulation/prototype_eng/api/gui/MachineGuiRegistry.java) 按机器类型 ID 注册 `BiFunction<InventoryPlayer, MachineControllerHost, ? extends AEBaseContainer>`。两侧 mod 初始化时安装相同容器绑定，重复 ID 拒绝。工厂负责创建绑定准确宿主的有效容器；当前注册入口直接调用工厂，不再重复检查返回值和容器目标。没有绑定时按上述设备类型创建默认容器。外部实体可独立实现宿主接口，无需继承默认控制器实体。

客户端入口 [MachineScreenRegistry](../src/main/java/com/circulation/prototype_eng/api/client/gui/MachineScreenRegistry.java) 只在 client 初始化使用，以明确容器类和有类型的 factory 注册：

```java
// common 初始化：MyContainer 直接继承 AEBaseContainer，构造调用 super(player, host.blockEntity())。
MachineGuiRegistry.register(typeId, MyContainer::new);

// client 初始化：MyGui 直接继承 AEBaseGui<MyContainer>。
MachineScreenRegistry.register(typeId, MyContainer.class, MyGui::new);
```

screen factory 的参数是 `(container, playerInventory)`。它通过声明的 `Class<C>` 校验容器类型，不进行构造器反射或无检查转型；重复绑定拒绝，工厂负责返回有效 screen。默认容器可使用默认 screen，自定义容器若没有对应客户端绑定则明确失败。默认 `GuiMachine` / `ContainerMachine` 均为具体 final 实现，下游可独立安排布局、控件和同步，无需继承默认界面。

自定义容器继续负责准确目标绑定、自身访问与 mutation 权限、稳定边界读取和同步限制。common 定义不引用客户端 screen，客户端类只经客户端代理/注册入口加载。

## 验证边界与后续入口

`MachineGuiSnapshotTest` 覆盖跨宿主页、布局收缩offset、快照不随活进度变化、失败原因保留、packet round-trip及行/参数限制；新增真实CrossRecipeController进程分页、空动态槽排除、有效并行汇总和完整详情包往返。实际GUI打开、中文按钮、滚动/拖动、结束时列表收缩、玩家槽位及渲染仍需用户重启游戏复验；构建与逐文件MCP证据见[实施进度](implementation-status.md)。

结构预览、投影、切片和材料清单已有实现，当前范围与实机复验入口见[结构工具](structure-tools.md)；旧“待迁移”描述不再作为这些界面的实现状态。复杂模型/动画及 PN 权限分别按[结构](structures.md)与[网络运行时](network-runtime.md)核对。控制器宿主入口见[世界接线](controller-integration.md)，自动配方入口见[配方扫描](recipes.md#自动扫描组件声明与恢复)，资源记录见[控制器资产](controller-assets.md)。

## ModularUI 排除（2026-11-09 历史记录）

以下为当时的实施与验证记录。其中 `GuiResearch` 已于 2026-11-13 删除，后续仅恢复了研究站专用界面，当前研究页面范围见[研究 GUI](research-gui.md)。

用户要求撤除MUI。当时GuiMachine、GuiResourceHatch、GuiEnergyHatch、GuiResearch和GuiStructurePreview均直接继承AEBaseGui；项目没有MUI GUI基类、Widget/Drawable调用或modularui资源命名空间。清理前实际直接/传递依赖已无MUI，所有Gradle configuration精确排除com.cleanroommc:modularui，避免后续mod依赖重新引入。AE/HEI及必要核心依赖完整保留；after真实9个compile/runtime/processor配置与135个Jar检查、当次3个产物Jar均无MUI，见 `build/staging/mui-removal/`。

GUI控件和绘制继续用AE或Minecraft标准Java，不引入第二GUI基类或替代UI框架。后续JEI的Arrow/Cycle效果同样用AE/native Java表达，不使用MUI Widget/Drawable、GUI类或modularui命名空间。旧素材来源和许可只保留staging审计；此块不新增尚未迁的decorator。用户已暂停整体目标，本块验证和本地提交后停止，结构WIP继续入口见 `build/staging/structure-async-budget/STOPPED-WIP.md`。
