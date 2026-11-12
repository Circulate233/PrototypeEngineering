# Mixin 使用与审查

新增 Mixin 前必须先核对 Minecraft、Forge、AE2 和目标模组现有的注册、事件、子界面及继承接口。能在本模组持有的对象中处理的行为必须放回对应对象，禁止为了覆盖假设中的外部修改而拦截全体玩家、物品、容器或界面；否则会影响无关模组并扩大版本兼容范围。

## 已删除的全局干预

奖励与下载材料已删除 NetHandlerPlayServer、Container、InventoryPlayer、EntityPlayer、EntityPlayerMP、EntityLivingBase、ItemStack、ArmorProperties、EnchantmentThorns、EntityItem 和 TileEntityHopper 共11个保护 Mixin。对应的玩家事件拦截、全局保护查询、光标专用存档和重生库存搬运一并删除。发放器创建独立 `RewardItemEntity`，只管理本次待确认物品。

预览已删除 `MinecraftPreviewGuiMixin` 和 `GuiScreenPreviewMixin`。不再改写 `Minecraft.displayGuiScreen`、事件总线派发结果、全局 GUI 初始化、输入或更新。`PreviewScreenGuard` 及全局 retired/blocked 状态已移除。预览自身用 AE 的 `switchToScreen`、`returnFromSubScreen` 与原生 `displayGuiScreen` 打开和返回，原生事件取消及替换由 Minecraft 处理。

## 保留的10项及实际消费者

| Mixin | 保留原因 |
|---|---|
| `NBTTagLongArrayMixin` | `CanonicalKeyEncoding` 逐元素读取 long 数组。依赖的1.12类型缺少相应公开读取接口，桥接长度和单个值可以避免复制数组，不改变 NBT 内容。 |
| `ZenComponentDataMemberMixin` | `ZenComponentDataMembers` 为本模组 `ComponentData` 动态成员提供可赋值表达式。普通 getter 不能完成动态属性赋值；已有方法和扩展成员优先，不处理其它脚本类型。 |
| `RenderGlobalMachineModelsMixin` | 在普通和 global TESR 提交完成的位置绘制机器模型队列，并用模型实际包围盒进行原生剔除。两个位置对应实际不同的 TESR 提交路径。 |
| `TileEntityRendererDispatcherMachineMixin` | 为已注册 `MachineControllerRenderer` 的实体提供模型渲染距离，并避免模型隐藏模组把替代模型的 TESR 同时隐藏。普通 TESR 继续调用原生实现。 |
| `SimpleReloadableResourceManagerAccessor` | `ModelResourceSources` 读取实际命名空间资源管理器，不猜测资源包来源。只读访问，没有取消原生调用。 |
| `FallbackResourceManagerAccessor` | `ModelResourceSources` 按实际资源包优先级打开独立 ZIP 读取句柄，供异步模型解析使用，避免资源重载关闭旧包后读取失败。只读访问，没有改写资源优先级。 |
| `GuiContainerPreviewMixin` | 只阻止本模组只读预览把本地显示容器写入 `player.openContainer`，以及父界面进入预览时清空光标；另桥接原生 `drawSlot`。源码确认原版 `GuiContainer.initGui` 会写容器，`Container.onContainerClosed` 会丢弃并清空光标，这两处是当前子页实际调用路径。没有失效界面黑名单或全局输入暂停。 |
| `ModRegistryRecipePoolPagesMixin` | 等 HEI 全部插件注册结束后解析外部分类 ID，再冻结本模组配方池页面。注册时序有实际要求，运行时回调已经晚于分类创建，不能替代此处。 |
| `HeiPreviewInputMixin` | 材料选择和相机拖动必须先于 HEI 物品查询。实际 HEI 入口仍接收已经取消的 Forge 鼠标事件，因此只靠 Forge 事件取消不能处理这个交互。 |
| `CtmHatchBloomMetadataMixin` | CTM 在没有 BLOOM 枚举的环境解析带标记纹理会失败。只处理显式 `prototype_eng_bloom` 元数据，在没有该层时使用 CUTOUT；有 BLOOM 层时保留原数据。 |

`GuiScreenPreviewMixin` 原来提供的 tooltip 桥已改由本模组 GUI 实现 `PreviewNativeGui` 并直接调用继承的 `renderToolTip`。外部宿主使用公开 Forge tooltip 绘制接口和宿主的原生 `getItemToolTip`，保留自定义字体及 tooltip 事件，不给全部 `GuiScreen` 添加接口。

配置保留2个通用和8个客户端 Mixin。必须同步删除配置和产物中的失效类；禁止只删除配置而留下没有消费者的守卫类及对应模拟流程测试。
