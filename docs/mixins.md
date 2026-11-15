# Mixin 使用与审查

新增 Mixin 前必须先核对 Minecraft、Forge、AE2 和目标模组现有的注册、事件、子界面及继承接口。能在本模组持有的对象中处理的行为必须放回对应对象，禁止为了覆盖假设中的外部修改而拦截全体玩家、物品、容器或界面；否则会影响无关模组并扩大版本兼容范围。

## 已删除的全局干预

奖励与下载材料已删除 NetHandlerPlayServer、Container、InventoryPlayer、EntityPlayer、EntityPlayerMP、EntityLivingBase、ItemStack、ArmorProperties、EnchantmentThorns、EntityItem 和 TileEntityHopper 共11个保护 Mixin。对应的玩家事件拦截、全局保护查询、光标专用存档和重生库存搬运一并删除。发放器创建独立 `RewardItemEntity`，只管理本次待确认物品。

预览已删除 `MinecraftPreviewGuiMixin` 和 `GuiScreenPreviewMixin`。不再改写 `Minecraft.displayGuiScreen`、事件总线派发结果、全局 GUI 初始化、输入或更新。`PreviewScreenGuard` 及全局 retired/blocked 状态已移除。预览自身用 AE 的 `switchToScreen`、`returnFromSubScreen` 与原生 `displayGuiScreen` 打开和返回，原生事件取消及替换由 Minecraft 处理。

## 2026-11-15 公开 API 复审

已按 Cleanroom 0.6.13-alpha、CraftTweaker 4.1.20.718、HEI 4.35.1 以及当前 CTM 的真实源码和 jar 复审10项，删除5项。

`NBTTagLongArrayMixin` 与 `NbtLongArrayAccess` 已删除，`CanonicalKeyEncoding` 直接读取 Cleanroom 公开的 `NBTTagLongArray.data`。`ZenComponentDataMemberMixin` 已删除，`ZenComponentDataType` 在 construction 阶段通过公开类型 Map 和根符号注册；删除自动注册注解，避免 CT 的 preInit 再次注册普通 native 类型。`CtmHatchBloomMetadataMixin` 已删除，CTM 元数据通过公开 serializer 注册替换，先处理本模组 BLOOM 标记，再调用 CTM 原解析器；依赖顺序加入 `after:ctm`。

`SimpleReloadableResourceManagerAccessor` 和 `FallbackResourceManagerAccessor` 已删除。仓库现有 Access Transformer 增加 `field_110548_a` 与 `field_110540_a` 两个字段的 public 声明，`ModelResourceSources` 直接读取真实域管理器和资源包列表。字段映射已按当前 SRG 核对，新的依赖转换、Java 编译与现有测试通过；独立 ZIP 读取继续使用实际资源包顺序。

`StructureQueries.BIOME_NAME` 通过 Access Transformer 公开的 `Biome.field_76791_y`（`biomeName`）读取原始名称。禁止调用仅限客户端的 `Biome.getBiomeName()`；本模组自行声明字段访问权限，不依赖可选模组提供转换。

## 保留的5项及实际消费者

| Mixin | 保留原因 |
|---|---|
| `RenderGlobalMachineModelsMixin` | 普通和 global TESR 都在 `drawBatch` 前提交，必须在该调用后绘制模型，公开 `RenderWorldLastEvent` 发生在后续透明块渲染后，无法保持阶段顺序。包围盒查询也发生在注册的 renderer 调用前，没有公开替换接口。删除误称 global 阶段的 RETURN 刷新。 |
| `TileEntityRendererDispatcherMachineMixin` | 为已注册 `MachineControllerRenderer` 的实体提供模型渲染距离，并避免模型隐藏模组把替代模型的 TESR 同时隐藏。普通 TESR 继续调用原生实现。 |
| `GuiContainerPreviewMixin` | 只阻止本模组只读预览把本地显示容器写入 `player.openContainer`，以及父界面进入预览时清空光标；另桥接原生 `drawSlot`。源码确认原版 `GuiContainer.initGui` 会写容器，`Container.onContainerClosed` 会丢弃并清空光标，这两处是当前子页实际调用路径。没有失效界面黑名单或全局输入暂停。 |
| `ModRegistryRecipePoolPagesMixin` | HEI 没有全部插件注册完成且冻结前的公开回调，分类 UID 集合私有且没有 getter。运行时回调时分类和 catalyst 已冻结，无法完成外部分类识别与默认分类生成。 |
| `HeiPreviewInputMixin` | HEI 鼠标监听使用 `HIGHEST` 和 `receiveCanceled=true`，原生材料查询早于 wrapper 的公开点击、拖动和释放回调。Forge 取消事件仍会进入原生查询，必须先处理预览交互。 |

`GuiScreenPreviewMixin` 原来提供的 tooltip 桥已改由本模组 GUI 实现 `GuiPreviewNative` 并直接调用继承的 `renderToolTip`。外部宿主使用公开 Forge tooltip 绘制接口和宿主的原生 `getItemToolTip`，保留自定义字体及 tooltip 事件，不给全部 `GuiScreen` 添加接口。

配置保留5个客户端 Mixin，没有通用 Mixin。必须同步删除配置和产物中的失效类；禁止只删除配置而留下没有消费者的守卫类及对应模拟流程测试。逐项真实依赖依据与检查结果位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261115/review-mixins-complete.json`。
