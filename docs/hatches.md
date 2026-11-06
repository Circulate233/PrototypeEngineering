# 仓室核心与扩展接入

**后端设计正在纠正：** 用户明确要求独立存储契约，只复用 AE2 资源类型。下文记录的是目前旧 MEStorage/GenericStackInv 后端的实现行为；它需要按[存储重构约束](storage-redesign.md)替换，不再作为最终仓室 API。方向隔离、GUI 交互及既有测试所覆盖的效果仍须保留。

当前已接入临时存储后端、公开发现契约、方块实体、资源/能源各三方向十阶的 60 种方块及物品、能力适配、Java 容器和 GUI。资源独立滚动、能源设置与纵向条已实现，通过最终构建及逐文件 IDEA MCP 检查；游戏内验收仍待补齐。

## 资源仓室

`api.storage.ResourceHatch` 提供 `input()`、`output()` 和 `isValid()`。前两者返回 AE2 的 `MEStorage`；没有对应方向时返回 null。机器从输入存储取料、向输出存储交付产物；所有调用发生在宿主服务端线程。

内置 `storage.BufferedResourceHatch` 接收方向、每侧槽位数、按 AEKeyType 指定的每槽容量及内容变化回调。槽位可容纳任一支持类型，混合仓输入与输出使用不同的 GenericStackInv。`HatchTier` 和 `HatchConfig` 已接入十阶默认值；它们配置临时后端，不表示独立存储重构已完成。

`automation()` 是外部自动化视图：插入只写输入侧，抽取及可抽取数量查询只读取输出侧。保存数据的 `Input` 与 `Output` 分别编码；不会把相同 key 的两侧数量合并。`invalidate()` 后，已取得的机器和自动化存储引用也停止转移资源。宿主负责在卸载或失效时调用它。

## 独立 FE

`api.energy.EnergyInput` 表达机器取能职责，`EnergyOutput` 表达机器交付能量的职责，`EnergyHatch` 同时实现两者并提供容量契约。外部实现可以只提供需要的职责。`EnergyBuffer` 用普通 long 字段保存服务端线程持有的余额；模拟操作不修改余额或发出内容变化通知。真实多线程调度及其存储一致性仍须在后续重构中接入。

当前三方向适配使用 `ForgeEnergyPort`：输入仓允许外部充入，输出仓允许外部抽取，混合仓允许两者；机器使用对应的 EnergyInput/EnergyOutput 职责访问同一余额。Forge 的 int 查询值饱和到 Integer.MAX_VALUE，内部余额仍为 long，传输返回值表示本次实际数量。

能源实体已接入模式、等级、输入/输出限额及自动标志，并写入 NBT 的 `Mode`、`Tier`、`MaxInRate`、`MaxOutRate`、`AutoInput`、`AutoOutput`。每次操作限额同时约束机器与 Forge，修改后会动态更新机器访问视图；设置为零恢复配置默认限额。PM 的 EnergyStorageImpl 也按每次操作限制 maxReceive/maxExtract，旧配置的 FE/t 标签不构成累计 tick 预算。PM 自动标志尚未找到邻居 tick 传输调用链；本项目保留标志的设置/保存，不据此宣称已实现邻居自动传输。

`storageIdentity()` 返回真实余额的稳定对象身份，后续的适配视图必须沿用同一身份，供机器避免重复预算。Applied-Flux 接入当前暂缓。

## 外部实现

外部 TileEntity 可以在 `getCapability` 中暴露 `api.HatchCapabilities.RESOURCE`、`ENERGY_INPUT` 或 `ENERGY_OUTPUT`，不需要继承内置 TileEntity。机器通过 `findResource` / `findEnergyInput` / `findEnergyOutput` 获取仍有效的契约；接口实例与存档均由宿主管理，能力注册不提供脱离宿主的默认实例或通用 NBT 格式。能力入口已经声明，外部宿主参与完整机器运行的验收仍待后续集成。

当前临时接口仍要求资源后端提供 MEStorage，模拟和执行遵循其返回数量契约；最终接口须改为本库独立存储契约，AE2 存储只保留在适配层。失效后的旧引用不得继续改变真实存储。`inputStorageIdentity()` / `outputStorageIdentity()` 分别标识两侧真实存储；当前默认使用对应的 MEStorage 对象，临时适配视图必须覆盖为稳定的底层身份。机器据此避免把同一份库存的多个视图重复预算；输入与输出独立时身份也必须独立。

## 内置方块与适配

资源方块 ID 为 `prototype_eng:input_hatch_<等级>`、`output_hatch_<等级>`、`mixed_hatch_<等级>`；能源方块为 `energy_input_hatch_<等级>`、`energy_output_hatch_<等级>`、`energy_mixed_hatch_<等级>`。等级为 1–10，共 60 个方块及对应物品，目前放在红石创造物品栏。默认等级参数沿用 PM 并可配置，合成配方与正式玩法由使用方定义。

`ResourceHatchBlockEntity` 暴露公开资源仓契约、AE2 MEStorage 和 GenericInternalInventory；物品及其他已注册资源能力通过 AE2 的 GenericInternalInventoryAdapters 接入。`HatchInventoryPort` 保留每个槽位的方向，外部不得从输入取出或向输出写入。它的直接 setStack 是有方向约束的契约调用；方向反转、越容量和非法替换立即失败，正常传输使用 insert/extract 的实际数量返回值。

流体 fill 与 drain 分别交给输入、输出视图。AE2 的 GenericStackFluidStorage 在无指定 key 的 drain 中选中第一个流体槽后直接返回；把混合仓所有槽直接交给同一个适配器，会让输入流体遮住另一种输出流体。这里使用同一核心上的两个方向视图，没有复制余额。

能源实体按模式暴露公开 EnergyInput/EnergyOutput，并使用 ForgeEnergyPort 暴露 Forge ENERGY。卸载/失效使已取得的转移引用失效；重新挂载时创建新视图，旧引用不会重新激活。保存仍可读取失效核心的实际内容；三方向、动态限额、重载和旧引用失效已由能源逻辑用例验证。

方块掉落以 BlockEntityTag 携带缓存、方向、槽位和容量；挖掘路径保留实体至生成掉落之后，再移除方块。重新放置使用 ItemBlock 的标准 NBT 恢复路径。丝触走同样的内容掉落规则。当前没有新增可丢弃内容的清空按钮。

## Java 界面

ResourceHatchGui 和 EnergyHatchGui 直接继承 AEBaseGui，复用 AE2 背景生成、资源图标/数量显示、物品容器转移策略及服务器同步，没有另造 GUI 基类或编辑器。Mouse Tweaks API 仅作为 AEBaseGui 声明的可选父接口加入编译依赖，不作为新运行时必需依赖。

`localization.GuiText` 实现 AE2S 的 `LocalizationEnum`，客户端标签调用 `getLocal()`，容器标题使用 `text(...)` 组件。GUI 不拼接翻译键，也不直接传裸翻译键调用翻译函数。

资源输入/输出区域各有独立的 AE2 ScrollBar，最多显示 5 行并随分辨率压缩。滚动保留原始槽位索引，隐藏槽禁用，避免界面位置变化造成操作对象混淆。能源界面为 176×214，使用 PM 原 16×66 纵向能量条，支持输入/输出限额与自动标志设置、Enter 提交及完整 long 数值 tooltip；实际 GUI 交互与不同分辨率显示尚待游戏内验证。

资源容器在实际 GenericStackInv 上处理物品转移，避免修改 ConfigGuiInventory 返回的显示副本。输入可以放入和手动取回，输出只可取出；混合仓界面分组显示两侧。支持普通点击、右键拆分/单个放入、Shift 转移、数字键和丢弃；大缓存按原生物品堆叠上限取入玩家库存。资源槽不参与原版拖拽分配，防止显示包装栈进入玩家物品栏；玩家库存仍保留原版拖拽。

手持可被 AE2 策略识别的流体等资源容器：右键输入槽倒入，左键资源槽装出，配合 Shift 请求整容器操作。服务端额外禁止向输出槽发送 EMPTY_ITEM/EMPTY_ENTIRE_ITEM 写入，禁止 AE2 通用 SwapSlotsPacket 直接移动资源显示包装栈；数字键交换走实际数量路径。客户端限制不作为库存权限依据。

打开界面前向该玩家发送实体布局和容量，再由容器同步资源槽/能源数值。转移时不重复发送整份 TileEntity NBT，以免替换正被其他玩家界面使用的缓存。GUI 与模型类只由客户端代理引用。

## 开发默认值与资源来源

`config/prototype_eng/hatches.cfg` 在初始化时读取，按 `tier1`–`tier10` 配置槽数、混合仓每侧槽数、物品/流体每槽容量、能源容量与操作限额；槽数允许 1–81。十阶槽数/物品容量源自 PM `ItemHatchConfig` / `ItemIOHatchConfig`，流体每槽容量源自 `FluidHatchConfig`，能源源自 `EnergyHatchConfig`。

| 等级 | 输入/输出槽数 | 混合仓每侧槽数 | 物品每槽 | 流体每槽（mB） | 能源容量（FE） | 能源每次操作限额（FE） |
|---|---:|---:|---:|---:|---:|---:|
| 1 | 1 | 1 | 64 | 16,000 | 128,000 | 32 |
| 2 | 2 | 1 | 128 | 32,000 | 512,000 | 128 |
| 3 | 4 | 2 | 128 | 64,000 | 2,000,000 | 512 |
| 4 | 9 | 4 | 256 | 128,000 | 8,000,000 | 2,048 |
| 5 | 16 | 8 | 512 | 256,000 | 32,000,000 | 8,192 |
| 6 | 25 | 12 | 1,024 | 512,000 | 128,000,000 | 32,768 |
| 7 | 36 | 18 | 2,048 | 1,024,000 | 512,000,000 | 131,072 |
| 8 | 49 | 24 | 4,096 | 2,048,000 | 2,000,000,000 | 524,288 |
| 9 | 64 | 32 | 8,192 | 4,096,000 | 8,000,000,000 | 2,097,152 |
| 10 | 81 | 40 | 16,384 | 8,192,000 | 32,000,000,000 | 8,388,608 |

多类型资源共用物品仓槽位布局，不另建流体专用槽位等级。其他已注册 AEKeyType 优先采用 AE2 GenericSlotCapacities 值，否则取 64 个该类型单位。这些是通用组件默认参数，可由使用方配置，库不新增正式科技路线。

资源仓已有实例保留保存的槽位和容量，配置默认值用于新放置实例；能源仓重载以当前等级配置容量为准，将保存量裁剪到该容量。能源重载行为已有逻辑测试，资源完整保存恢复仍待运行验证。数量使用 long；非法配置/已保存容量立即失败，不捕获后换成默认值。容量表使用 fastutil Reference2LongMap，按已注册 AEKeyType 的对象身份查找。

资源仓使用 PM `item_hatch`，能源使用 `energy_hatch` 的十阶三方向原模型和依赖纹理，保留原模型 `credit`、`bloom_*` 元素及 CTM/Bloom/动画元数据，资源命名空间改为 `prototype_eng`。原仓室走标准 Forge 模型和纹理元数据，不依赖机器 Gecko/TESR；发光后处理需要兼容的 CTM 与 BLOOM 层提供者，实际组合未验证。来源及原作者署名见 [美术来源](assets.md)。

## 验证范围

最终 `gradlew.bat build` 通过 25 项逻辑用例（其中 7 项能源），覆盖多类型双缓存流转、失效、方向/key 限制、鼠标转移守恒，以及能源三方向、动态操作限额和重载裁剪等行为。57 个 src Java 文件与生成 Tags 的 IDEA MCP 检查均无错误、警告及弱警告；GUI/模型资源静态核对通过，详细证据见 [实施进度](implementation-status.md)。

NBT 完整往返、Forge 实际能力发现、玩家交互和重启恢复尚未验证。直接在普通 JUnit 类加载器调用 AE2 的 key 持久化，会触发需要 FML 可选接口处理的物品初始化；当前不为此引入整套替代游戏环境，也不声称已覆盖实际保存恢复。

后续运行验收需覆盖：不同流体同时占据输入和输出时的 drain；玩家打开两个界面时的数量同步；满背包 Shift 转移；手持桶/其他资源容器的填充和倒入；挖掘及重新放置；卸载/重载与重启；服务器和客户端默认配置不同时打开界面。执行这些检查前仍遵守当前不启动客户端/服务端的要求。
