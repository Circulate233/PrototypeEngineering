# 机器 GUI 与 Java 扩展

默认机器界面由 [MachineGui](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/client/gui/MachineGui.java) 直接继承 AE2S 的 `AEBaseGui<MachineContainer>`，默认 [MachineContainer](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/container/MachineContainer.java) 直接继承 `AEBaseContainer`。背景与滚动条组合复用 AE2；没有本项目 GUI 中间基类、ModularUI、编辑器或 GUI DSL。

## 打开与默认显示

`MachineControllerBlock.onBlockActivated` 在玩家未潜行、手持物品不是方向工具且目标为控制器 TE 时打开 GUI 0。手持方向工具的交互由物品处理。PreInit 注册 `CommonProxy` 为 Forge `IGuiHandler`；common 端按准确 TE 创建容器，客户端代理再创建对应 screen。

默认界面显示机器声明的本地化标题、真实 active/formed 状态、工位进度与玩家主背包和快捷栏。工位行保留 PM 的 89×33 进度素材、工位编号、百分比、状态/阻塞或失败原因，并支持滚轮、点击及拖动滚动条；完整工位名、配方 ID 和原因可在悬停提示中查看。窗口最高七行，较小窗口按可用高度减少可见行；工位可以来自多个 `MachineWorkSlots` 组件，按组件及工位声明顺序合并展示。

进度由绝对工作刻数除以配方时长并裁剪至 0..100，零时长进程显示 100。工位没有进程时仍显示其保存的结果，因此失败/取消后的原因不会被固定 Idle 文本覆盖。机器不存在或 inactive 时同步空视图，尚未成型的 active 机器显示 Unformed。

对照 PM 的 [DefaultMachineUI.kt](F:/clone/PrototypeMachinery/src/main/kotlin/client/gui/DefaultMachineUI.kt)：其七行工位列表与玩家背包是实际功能；`buildGridSlots` 的 4×4 区域新建临时 `ItemStackHandler`，`Status: Idle` 是固定文本，扩展页只含占位说明。当前默认 GUI 展示真实状态，未给这些占位内容增加机器存储或业务职责。PM 的 [DefaultMachineUIBindings.kt](F:/clone/PrototypeMachinery/src/main/kotlin/impl/ui/binding/DefaultMachineUIBindings.kt) 为旧 bindKey/脚本 GUI 服务；本项目扩展改用 Java 容器与 screen，不迁入字符串绑定运行时。

## 服务端读取与有界同步

[MachineGuiSnapshot](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/container/MachineGuiSnapshot.java) 是实现 AE2 `PacketWritable` 的不可变显示值，由 `@GuiSync(0)` 同步。容器仅发送客户端请求的可见页，最多七个工位，不发送全部工位进程 NBT、runtime、组件、存储或 Forge capability 对象。

`broadcastChanges` 最多排队一次 capture；`MachineControllerBlockEntity.readGuiAtBoundary` 将读取排入 scheduler owner FIFO，在 accepted worker 完整 join 后执行。回调内再次确认玩家的 `openContainer` 正是本容器，TE 有效、World 相同、区块已加载且该位置仍是同一 TE，并复用 AE2 的 `canInteractWith` 检查。capture 还要求机器持久化稳定边界、各工位宿主 quiescent 且 owner 是同一机器，返回的条目不保留进程引用。

客户端 `view` action 只包含非负 offset 和 1..7 的行数，payload 上限 80；它仅调整查看范围。capture 根据当前布局裁剪 offset。每条 name、recipe、reason 及每个原因参数最多 1024 个字符，参数最多 16 项；接收端在建立行/参数列表前检查数量，并验证状态序号、进度及页范围。默认视图提供读取与正常玩家背包操作，PN owner/team/研究权限仍须后续集成，显示状态和 placementPlayer 不构成授权。

`MachineGuiText` 实现 AE2S `LocalizationEnum`；客户端标签调用 `getLocal`，容器标题用机器声明的 `displayName().text()`。内置 `RecipeText` 原因按枚举翻译并传递参数；未知外部原因保留 ID/文本及参数作为可读 fallback，不在 GUI 拼接翻译键。需要特殊原因表现的使用方可注册自己的 Java screen。

## 默认与自定义注册

common 入口 [MachineGuiRegistry](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/gui/MachineGuiRegistry.java) 按机器类型 ID 注册 `BiFunction<InventoryPlayer, MachineControllerHost, ? extends AEBaseContainer>`。两侧 mod 初始化时安装相同容器绑定；重复 ID、null 结果或容器目标不是宿主的准确 `blockEntity()` 都拒绝。没有绑定时创建默认容器。外部实体可独立实现宿主接口，无需继承默认控制器实体。

客户端入口 [MachineScreenRegistry](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/client/gui/MachineScreenRegistry.java) 只在 client 初始化使用，以明确容器类和有类型的 factory 注册：

```java
// common 初始化：MyContainer 直接继承 AEBaseContainer，构造调用 super(player, host.blockEntity())。
MachineGuiRegistry.register(typeId, MyContainer::new);

// client 初始化：MyGui 直接继承 AEBaseGui<MyContainer>。
MachineScreenRegistry.register(typeId, MyContainer.class, MyGui::new);
```

screen factory 的参数是 `(container, playerInventory)`。它通过声明的 `Class<C>` 校验容器类型，不进行构造器反射或无检查转型；重复绑定和 null 结果拒绝。默认容器可使用默认 screen，自定义容器若没有对应客户端绑定则明确失败。默认两类均为具体 final 实现，下游可独立安排布局、控件和同步，无需继承默认界面。

自定义容器继续负责自身访问与 mutation 权限、稳定边界读取和同步限制；注册入口的目标身份检查不替代这些契约。common 定义不引用客户端 screen，客户端类只经客户端代理/注册入口加载。

## 验证边界与后续入口

`MachineGuiSnapshotTest` 覆盖跨宿主页、布局收缩 offset、快照不随活进度变化、失败原因保留、packet round-trip 及行/参数限制。实际 GUI 打开、玩家槽位操作、关闭重开和渲染仍无游戏内证据；构建与逐文件 MCP 的最终证据统一见[实施进度](implementation-status.md)。

完整结构预览、投影、切片、BOM、复杂模型/动画与 PN 权限继续保留待迁移。控制器宿主入口见[世界接线](controller-integration.md)，自动配方入口见[配方扫描](recipes.md#自动扫描组件声明与恢复)，资源记录见[控制器资产](controller-assets.md)。
