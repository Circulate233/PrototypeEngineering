# 仓室核心与扩展接入

当前已接入存储核心、公开发现契约、方块实体、四种方块及物品、能力适配、Java 容器和 GUI。源码与构建验证不等于游戏内验收；本文末尾列出仍需补齐的运行证据。

## 资源仓室

`api.storage.ResourceHatch` 提供 `input()`、`output()` 和 `isValid()`。前两者返回 AE2 的 `MEStorage`；没有对应方向时返回 null。机器从输入存储取料、向输出存储交付产物；所有调用发生在宿主服务端线程。

内置 `storage.BufferedResourceHatch` 接收方向、每侧槽位数、按 AEKeyType 指定的每槽容量及内容变化回调。槽位可容纳任一支持类型，混合仓输入与输出使用不同的 GenericStackInv。当前没有固化旧项目的十阶数值。

`automation()` 是外部自动化视图：插入只写输入侧，抽取及可抽取数量查询只读取输出侧。保存数据的 `Input` 与 `Output` 分别编码；不会把相同 key 的两侧数量合并。`invalidate()` 后，已取得的机器和自动化存储引用也停止转移资源。宿主负责在卸载或失效时调用它。

## 独立 FE

`api.energy.EnergyInput` 表达机器取能职责，`EnergyHatch` 额外提供接收与容量契约。`EnergyBuffer` 用普通 long 字段保存服务端线程持有的余额；模拟操作不修改余额或发出内容变化通知。

`ForgeEnergyInput` 将能源输入仓暴露为 Forge `IEnergyStorage`：外部只能充入，机器通过 EnergyInput 取出。Forge 的 int 查询值饱和到 Integer.MAX_VALUE，内部余额仍为 long，传输返回值表示本次实际接收量。

`storageIdentity()` 返回真实余额的稳定对象身份，后续的适配视图必须沿用同一身份，供机器避免重复预算。Applied-Flux 接入当前暂缓。

## 外部实现

外部 TileEntity 可以在 `getCapability` 中暴露 `api.HatchCapabilities.RESOURCE` 或 `ENERGY_INPUT`，不需要继承内置 TileEntity。机器通过 `findResource` / `findEnergyInput` 获取仍有效的契约；接口实例与存档均由宿主管理，能力注册不提供脱离宿主的默认实例或通用 NBT 格式。

资源后端可自行实现 MEStorage；模拟和执行须遵循其返回数量契约。失效后的旧引用不得继续改变真实存储。`inputStorageIdentity()` / `outputStorageIdentity()` 分别标识两侧真实存储；默认使用对应的 MEStorage 对象，临时适配视图必须覆盖为稳定的底层身份。机器据此避免把同一份库存的多个视图重复预算；输入与输出独立时身份也必须独立。

## 内置方块与适配

四种通用组件方块 ID 为 `prototype_eng:input_hatch`、`output_hatch`、`mixed_hatch`、`energy_hatch`，目前放在红石创造物品栏。合成配方、等级体系与参数由使用方定义。

`ResourceHatchBlockEntity` 暴露公开资源仓契约、AE2 MEStorage 和 GenericInternalInventory；物品及其他已注册资源能力通过 AE2 的 GenericInternalInventoryAdapters 接入。`HatchInventoryPort` 保留每个槽位的方向，外部不得从输入取出或向输出写入。它的直接 setStack 是有方向约束的契约调用；方向反转、越容量和非法替换立即失败，正常传输使用 insert/extract 的实际数量返回值。

流体 fill 与 drain 分别交给输入、输出视图。AE2 的 GenericStackFluidStorage 在无指定 key 的 drain 中选中第一个流体槽后直接返回；把混合仓所有槽直接交给同一个适配器，会让输入流体遮住另一种输出流体。这里使用同一核心上的两个方向视图，没有复制余额。

能源实体暴露公开 EnergyInput 和只允许外部充入的 Forge ENERGY。卸载/失效会使已取得的转移引用失效；重新挂载时创建新视图，旧引用不会重新激活。保存仍可读取失效核心的实际内容。

方块掉落以 BlockEntityTag 携带缓存、方向、槽位和容量；挖掘路径保留实体至生成掉落之后，再移除方块。重新放置使用 ItemBlock 的标准 NBT 恢复路径。丝触走同样的内容掉落规则。当前没有新增可丢弃内容的清空按钮。

## Java 界面

ResourceHatchGui 和 EnergyHatchGui 直接继承 AEBaseGui，复用 AE2 背景生成、资源图标/数量显示、物品容器转移策略及服务器同步，没有另造 GUI 基类或编辑器。Mouse Tweaks API 仅作为 AEBaseGui 声明的可选父接口加入编译依赖，不作为新运行时必需依赖。

资源容器在实际 GenericStackInv 上处理物品转移，避免修改 ConfigGuiInventory 返回的显示副本。输入可以放入和手动取回，输出只可取出；混合仓界面分组显示两侧。支持普通点击、右键拆分/单个放入、Shift 转移、数字键和丢弃；大缓存按原生物品堆叠上限取入玩家库存。资源槽不参与原版拖拽分配，防止显示包装栈进入玩家物品栏；玩家库存仍保留原版拖拽。

手持可被 AE2 策略识别的流体等资源容器：右键输入槽倒入，左键资源槽装出，配合 Shift 请求整容器操作。服务端额外禁止向输出槽发送 EMPTY_ITEM/EMPTY_ENTIRE_ITEM 写入，禁止 AE2 通用 SwapSlotsPacket 直接移动资源显示包装栈；数字键交换走实际数量路径。客户端限制不作为库存权限依据。

打开界面前向该玩家发送实体布局和容量，再由容器同步资源槽/能源数值。转移时不重复发送整份 TileEntity NBT，以免替换正被其他玩家界面使用的缓存。GUI 与模型类只由客户端代理引用。

## 开发默认值与资源来源

`config/prototype_eng/hatches.cfg` 在初始化时读取；槽数为每侧 9 格（允许 1–18），物品每格 4096，流体每格 16000 mB，独立能源 1000000 FE。其他已注册 AEKeyType 优先采用 AE2 GenericSlotCapacities 值，否则取 64 个该类型单位。这些是通用组件默认参数，可由使用方配置；库不规划最终平衡或等级设计。

配置只影响新放置的仓室，已有仓室保存自己的槽位和容量。数量使用 long；非法配置/已保存容量立即失败，不捕获后换成默认值。容量表使用 fastutil Reference2LongMap；它按已注册 AEKeyType 的对象身份查找。

方块模型复用 PM 第一级仓室美术，输入/输出标记与底图合成为普通静态纹理。混合仓保留 PM 原外观；未引入旧 Bloom 渲染和十阶系统。来源及原作者署名见 [美术来源](assets.md)。

## 验证范围

已保留七个逻辑用例：多类型双缓存的模拟/实际流转、宿主失效后已有引用的行为、槽位适配的方向与 key 替换限制、超原生堆叠上限的鼠标转移守恒、交换的完整接收条件、Forge 多次传入与机器大数量扣取共用余额、从 Long.MAX_VALUE 附近恢复后充入并消耗。

NBT 完整往返、Forge 实际能力发现、玩家交互和重启恢复尚未验证。直接在普通 JUnit 类加载器调用 AE2 的 key 持久化，会触发需要 FML 可选接口处理的物品初始化；当前不为此引入整套替代游戏环境，也不声称已覆盖实际保存恢复。

后续运行验收需覆盖：不同流体同时占据输入和输出时的 drain；玩家打开两个界面时的数量同步；满背包 Shift 转移；手持桶/其他资源容器的填充和倒入；挖掘及重新放置；卸载/重载与重启；服务器和客户端默认配置不同时打开界面。执行这些检查前仍遵守当前不启动客户端/服务端的要求。
