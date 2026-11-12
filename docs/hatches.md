# 仓室核心与扩展接入

## AE 资源包插入

输入仓、输出仓和混合仓都接受手动插入 AE 资源包。手持资源包点击资源槽时，将包内的完整 AEKey 和 long 数量存到该槽；部分接受时把剩余量写回资源包，完全接受后清除空包。类型不支持、槽位不允许或容量不足而完全拒绝时保留原包。

背包 Shift 快移会在对应仓室的全部槽位中寻找空间，不受当前显示页限制。混合仓优先尝试输入侧；输入侧完全拒绝时再尝试输出侧，部分存入输入后剩余量保留在包内。点击输出侧则直接把资源放入输出侧。操作沿原服务器窗口和存储版本检查，遵守库存和容量预留。

普通物品与管道仍遵守原方向：手动普通物品放入输入、从输出取出；资源包允许在输出侧解包，自动化不能借此向输出写入。显示用 GenericStack 包装栈不属于可插入的 AE 资源包。

2026-11-10 容器操作tooltip按AE2S原生文案及效果统一：ResourceHatchGui直接复用GuiMEStorage的Tooltips.getEmptyingTooltip(StoreAction)，左右键分别显示存入手持物品、存入所含资源，两行使用AE深灰操作/灰色对象及内嵌物品图标。可装填容器使用FillAction配合Tooltips.muted/of、TextComponentItemStack；操作提示优先，沿原生drawTooltipWithHeader绘制，不追加原自有“倒入材料仓；Shift处理全部容器”单行。空光标的资源信息和真实点击/Shift传输行为保持。仅1Java，未改语言文件，源码提交 `cf27716`；验证与真实渲染边界见[实施进度](implementation-status.md)。

2026-11-10 破坏掉落按用户新要求改为AE原生路径：资源仓的 `addAdditionalDrops` 接住输入/输出/交互三种方向的全部材料，经AEKey掉落；大数量物品及流体等非物品资源沿AE资源包，原long数量不裁剪。仓室物品的BlockEntityTag只保存profile、槽位/容量、自动IO和限额等配置，两侧库存列表为空，重新放置不会再恢复已掉出的材料。普通世界存档仍保存真实库存，独立FE仓原有保存行为保持。

销毁在原两侧有序锁下单向失效旧引用，每侧只交付一次。没有未结算token的侧立即清空并掉落；存在预留、已应用输入、临时产物或外部传输回执的侧保留原记录，最后提交/补偿完成后再交付最终余额，不能提前清空或把未确认产物掉出。延后回调在物理锁/fence退出后发布，worker只安排服务器线程产生掉落实体。AE原生挖掘、创造破坏、爆炸及扳手拆卸共用同一入口，未增加第二套世界破坏事件。纯行为及源码核对完成，真实掉落实体/资源包拾取仍待用户游戏复验。

2026-11-10 退出世界提示修复：Forge可在Server thread恢复注册表时调用物品tooltip。资源和能源提示仅在真实客户端owner读取同步配置actor与connection；非owner保留纯物品完整EffectiveProfile/资源legacy配置，缺少目标则明确AwaitingConfiguration。未取消actor线程门，也不用本地默认冒充远端同步事实。数字格式器按线程保存独立实例，避免搜索索引和GUI并发使用可变NumberFormat。源码/纯行为验证完成，真实退出链复验见[实施进度](implementation-status.md)。

2026-11-10 仓室模型反馈已落实窄修：普通外壳保持原光照，彩色发光面单独关闭方向漫反射并后绘制；CUTOUT 处理透明像素。CTM 缺少原生 BLOOM 枚举时，仅自有标记元数据回退 CUTOUT 并保留 light=15；安装 Lumenized 时保留原 BLOOM 层。方块/物品继续同源，不改贴图、动画、外壳 AO 或实体存储。原因、可选组合和证据见 [仓室美术来源](assets.md)。

资源仓后端已替换为本库的独立 `ResourceStorage` / `SlottedResourceStorage` 接口和 `SlottedResourceBuffer` 实现。AEKey、AEKeyType 和 GenericStack 继续表示资源身份、类型及数量；MEStorage、GenericInternalInventory 和 GUI 库存只承担适配职责，不拥有另一份服务器余额。独立 FE 已接入同批预留/补偿；完整调度及持久化事务边界仍按[存储重构约束](storage-redesign.md)继续实现。

当前已接入独立资源/FE 存储、公开发现接口、方块实体、资源/能源各三方向十阶的 60 种方块及物品、能力适配、Java 容器和 GUI。资源独立滚动、能源设置与纵向条已实现。完整构建、逻辑用例与 IDEA MCP 零诊断证据见[实施进度](implementation-status.md)，游戏内验收仍待补齐。

## 资源仓室

`api.storage.ResourceHatch` 提供 `input()`、`output()` 和 `isValid()`。前两者返回本库的 `ResourceStorage`；没有对应方向时返回 null。机器从输入存储取料、向输出存储交付产物。宿主与能力发现仍在服务端线程执行，取得的资源存储通过共享锁支持跨线程读写。

内置 `storage.BufferedResourceHatch` 接收方向、每侧槽位数、按 AEKeyType 指定的每槽容量及内容变化回调。输入和输出分别由独立的 `SlottedResourceBuffer` 持有数量、槽位和预留状态，并通过 `SlottedResourceStorage` 暴露。每槽可容纳任一支持类型，同一个 key 可以出现在多个槽位；按 key 的数量查询与按槽操作共用真实余额。`HatchTier` 和 `HatchConfig` 配置十阶默认槽数及容量。

每侧 `StorageContext` 是该真实余额的稳定身份，持有 ReentrantLock、revision、失效状态与变更通知；同一余额的所有视图沿用这个 context，混合仓两侧的 context 不同。`StorageSnapshot` 包含 context、revision 和不可变的可用数量映射。预留输入数量或输出容量时核对快照身份及版本，失效、过期快照和数量不足由 `ReservationResult` 明确返回。普通插入、抽取和原子槽位替换均遵守已有预留。

`ResourceReservation` 区分准备、应用、完成与回滚。应用输入扣取后保留返还容量，应用输出后保留新数量，直到完成时才释放补偿权；因此自动化不能提前取走待结算产物。`ResourceTransactions` 按 context 顺序锁定参与者，在同一边界内应用或补偿，并在释放所有锁后发布变更通知。调度归属、epoch 与执行 lane 的检查仍由外围运行流程负责，不能把存储锁视为完整机器调度已经完成。

`automation()` 保留为 AE2 MEStorage 视图：插入只写输入侧，抽取及 `getAvailableStacks` 只读取输出侧未预留的可用数量。保存数据的 `Input` 与 `Output` 按槽位分别编码，不会把相同 key 的两侧数量合并。保存和加载使用两侧 context 锁；加载先验证两侧内容，再恢复，活动预留期间拒绝恢复。卸载或失效会单向失效原 context，已取得的机器及自动化引用随之停止转移；重新挂载创建新核心和视图。跨 TileEntity 保存仍不构成磁盘事务。

核心读写重入同一个 context 锁，变更回调在外层释放后发布。`ResourceHatchBlockEntity` 的回调只通过共享 VarHandle 标记待保存，服务端 tick 及失效/卸载回调调用 `markDirty()`，不让 worker 直接操作世界状态。

## 当前仓室 GUI（2026-11-09）

按最新实机反馈修正：仓室没有升级槽，所有材料/能源仓相关装饰已从PNG擦除；能源输入/输出仓电池右移并扩展左侧字段。材料交互仓修正Lv3/4输出区偏移，按实际主体宽度居中；tooltip显示所属方向、占用槽数、当前量/容量和AE资源提示。后文早期Java界面176×214、流体清空/限额等布局说明不代表当前界面；当前以[GUI修复记录](gui-parity.md)为准。

## 独立 FE

`api.energy.EnergyInput` 表达机器取能职责，`EnergyOutput` 表达机器交付能量的职责，`EnergyHatch` 同时实现两者并提供容量接口。外部实现可以只提供需要的职责。`EnergyBuffer` 用 long 余额与输入/输出 holds 保存数量，在共享 StorageContext 锁下访问；模拟不修改余额或通知。EnergySnapshot 包含 context、revision、未预留余额/空间及 maxReceive/maxExtract；reserveExtractEnergy/reserveReceiveEnergy 取得精确令牌，与资源和业务需求共同应用、完成或补偿。真实 planned lane 和生命周期集成仍须后续完成。

默认能源输入仓允许外部充入、机器消耗；输出仓允许机器产出、外部抽取。能源交互仓按用户最新要求分别创建输入/输出EnergyBuffer与StorageContext，每侧沿用该阶容量和原操作限额；机器的EnergyInput/EnergyOutput capability分别返回对应余额，不能相互借用。ForgeEnergyPort(input, output)接收只访问输入，抽取只访问输出；Forge单表查询提供两侧信息性汇总并饱和到Integer.MAX_VALUE，操作仍返回实际数量。旧ForgeEnergyPort(storage, mode)构造保留供明确共享单缓存的外部适配使用。

能源GUI宿主增加inputStorage()/outputStorage()，默认方法兼容既有单storage外部实现；内置实体覆写为真实独立侧。原storage()在交互仓指输入侧，单输出仓指其唯一输出侧。GuiSync分别传输输入/输出电量和容量；两根电池、两行数量、两个tooltip各读自己的侧。NBT双侧写InputEnergy/OutputEnergy，并在有序双锁内保存/失效；旧单Energy仅迁入输入，不复制。

能源实体已接入模式、等级、输入/输出限额及自动标志，并写入 NBT 的 `Mode`、`Tier`、`MaxInRate`、`MaxOutRate`、`AutoInput`、`AutoOutput`。每次操作限额同时约束机器与 Forge，修改后会动态更新机器访问视图；设置为零恢复配置默认限额。PM 的 EnergyStorageImpl 也按每次操作限制 maxReceive/maxExtract，旧配置的 FE/t 标签不构成累计 tick 预算。PM 自动标志尚未找到邻居 tick 传输调用链；本项目保留标志的设置/保存，不据此宣称已实现邻居自动传输。

`storageIdentity()` 默认返回真实余额的 StorageContext，所有适配视图必须沿用同一 context，供机器避免重复预算。降低限额会重新验证待应用令牌，精确补偿不受新限额裁剪。活动令牌或失效 context 不允许 restore，保存仍能读取失效余额；恢复/限额/失效会推进版本并置脏。能源 TileEntity 也通过共享 VarHandle 接收工作线程脏信号，在服务端 tick、卸载或失效回调冲刷。Applied-Flux 接入当前暂缓。

## 外部实现

R31 的物理基类保留实体泛型：`HatchBlock<T extends AEBaseTile>` 是抽象基类，protected 构造接受完整 `ResourceLocation`、`Class<T>` 和硬度，protected `createHatch(World, IBlockState)` 返回同一个 `T`。真实类令牌交给 AE `setTileEntity`，继承的 typed `getTileEntity`、库存识别、实体状态桥接及公开 `createTileEntity` 保持类型一致；共同 GUI、掉落与物品逻辑保留在基类，`createItem()` 可覆写。默认 `ResourceHatchBlock` / `EnergyHatchBlock` 才绑定对应默认实体及模式/等级；外部独立 AEBaseTile 实体直接继承泛型方块基类，不依赖内置实体。注册数组使用 `HatchBlock<?>[]`，十阶三方向共60方块和资产 ID 保持原值。

AE 的 final `createNewTileEntity` 通过真实 `Class<T>` 的 public 无参构造创建实体，不经过状态创建钩子；正常 Forge 放置使用 typed `createTileEntity`，由具体方块创建带模式/等级的实体。默认资源/FE 无参实体在 `onLoad` 仅对尚未显式配置、未读过 NBT 且仍是对应方块的准确当前实体补入模式/等级，弃用的旧存储视图失效；任何已读 NBT（包括缺字段标签）沿用既有恢复语义。`loadTag/saveAdditional`、`setRemoved/clearRemoved/onChunkUnloaded` 继续保留缓存失效和服务端脏信号冲刷，不新增第二套实体基类。

原 `ResourceHatch` / `EnergyInput` / `EnergyOutput` 仍用于能力发现、结构捕获和机器配方消费。需要共享槽位适配器的外部实现可实现 `SlottedResourceHatch`，其双侧返回 `SlottedResourceStorage`；`HatchInventoryPort` 和 `HatchFluidHandler` 接受该接口，不再要求 `BufferedResourceHatch`。默认容器通过 `ResourceHatchGuiHost` / `EnergyHatchGuiHost` 取得准确 AEBaseTile、模式、等级和真实存储；FE 用户设置独立为 `EnergyHatchSettings`。这些 GUI 职责是可选接入能力，不成为所有机器侧仓室的额外要求。

外部 TileEntity 可以在 `getCapability` 中暴露 `api.HatchCapabilities.RESOURCE`、`ENERGY_INPUT` 或 `ENERGY_OUTPUT`，不需要继承内置 TileEntity。机器通过 `findResource` / `findEnergyInput` / `findEnergyOutput` 获取仍有效的接口实例；接口实例与存档均由宿主管理，能力注册不提供脱离宿主的默认实例或通用 NBT 格式。能力入口已经声明，外部宿主参与完整机器运行的验收仍待后续集成。

外部资源仓后端实现本库 `ResourceStorage`，有槽位时可实现 `SlottedResourceStorage`，不需要实现或继承 MEStorage / GenericStackInv。实现必须提供一致的 context、快照、预留、实际转移数量、补偿及失效行为；不能通过另一个入口绕过预留。`inputStorageIdentity()` / `outputStorageIdentity()` 默认返回对应存储的 context，适配别名必须共享同一底层 context。机器据此避免把同一份库存的多个视图重复预算；输入与输出独立时身份也必须独立。

## 内置方块与适配

资源方块 ID 为 `prototype_eng:input_hatch_<等级>`、`output_hatch_<等级>`、`mixed_hatch_<等级>`；能源方块为 `energy_input_hatch_<等级>`、`energy_output_hatch_<等级>`、`energy_mixed_hatch_<等级>`。等级为 1–10，共 60 个方块及对应物品，目前放在红石创造物品栏。默认等级参数沿用 PM 并可配置，合成配方与正式玩法由使用方定义。

`ResourceHatchBlockEntity` 暴露公开资源仓接口及指向同一核心的 AE2 MEStorage / GenericInternalInventory 视图；物品及其他已注册资源能力通过 AE2 的 GenericInternalInventoryAdapters 接入。`HatchInventoryPort` 保留每个槽位的方向，外部不得从输入取出或向输出写入。输出槽的 getStack/getAmount 只展示未预留数量，完全预留时按空槽处理。直接 setStack 以真实已提交内容作条件替换，方向反转、越容量和非法替换立即失败，预留争用明确拒绝；正常传输使用 insert/extract 的实际数量返回值。通知批处理按 context 顺序加锁，不建立另一份余额。

流体 fill 与 drain 分别交给输入、输出视图。AE2 的 GenericStackFluidStorage 在无指定 key 的 drain 中选中第一个流体槽后直接返回；把混合仓所有槽直接交给同一个适配器，会让输入流体遮住另一种输出流体。这里使用两个方向视图，并由输出视图过滤完全预留的槽位，避免已被预留的首槽遮住后面的可抽资源。一次 fill/drain 及每侧属性查询在该侧 context 锁内完成，不跨持两侧锁，也没有复制余额。

能源实体按模式暴露公开 EnergyInput/EnergyOutput，并使用 ForgeEnergyPort 暴露 Forge ENERGY。卸载/失效使已取得的转移引用失效；重新挂载时创建新视图，旧引用不会重新激活。保存仍可读取失效核心的实际内容；三方向、动态限额、重载和旧引用失效已由能源逻辑用例验证。

资源仓掉落以BlockEntityTag携带方向、槽位、容量及配置，缓存材料由AE附加掉落入口独立掉出；挖掘路径保留实体至捕获配置和交付掉落之后，再移除方块。重新放置使用AEBaseBlockItem标准NBT恢复配置，库存为空；丝触沿同一材料掉落规则。独立FE仓仍按原方块物品数据保存电量。

PM `FluidHatchBlockEntity` 与混合 `FluidIOHatchBlockEntity` 的真实每次 fill/drain 限额已恢复到可选 `FluidHatchSettings`：输入/输出分别为非负 long，0 不限，正数限制每次操作的 mB 数量。`HatchFluidHandler` 对整个模拟或实际请求先裁剪，混合两侧仍分别访问；`ResourceHatchContainer` 的手持容器填充/倒入也仅对 AEFluidKey 应用对应限额，失败填充的完整补偿不受限额裁剪。配方直接访问 ResourceStorage 不受自动化限额影响，物品及其它 key 保持原转移语义；没有把每次操作限额改成累计 tick 预算。设置保存为 `MaxFluidInRate/MaxFluidOutRate`，GUI 使用 GuiSync 同步，不为每次设置替换客户端正在使用的存储视图。

PM 单侧/混合流体 GUI 原有清空效果已恢复为资源 GUI 的“清空流体”：服务器检查原玩家、openContainer、准确有效实体、当前存储身份、距离、服务器线程和方块操作权限后，分别在两侧 context 锁内按 AEKeyType.fluids() 抽取可用数量。物品、其它 key、设置、槽位布局不清除，也不产生退款物品。被输入预留或待结算输出持有的数量保留，已应用输入的退款容量和输出的补偿权不受破坏；没有复制旧 storage.clear() 绕过当前事务合同。公开 `HatchSlotActions.clearAvailable(storage, type)` 提供同一受预留保护的存储行为，调用方仍须授权其 owner/玩家动作。

清空先使用现有 `StorageContext.tryOrdinaryLock` 的非阻塞普通动作入口；当前正在保留的 StorageFence、fence scope 或繁忙物理锁直接拒绝本次动作，不让 GUI 等待自己拥有的 pending 事务，也不跨入它的源 scope。未锁定成功前不读真实内容。该新增边界已实现，新增 retained-fence 用例及最新逐文件检查随主线最终验证，不能用前一轮通过结果替代。

## Java 界面

ResourceHatchGui 和 EnergyHatchGui 直接继承 AEBaseGui，复用 AE2 背景生成、资源图标/数量显示、物品容器转移策略及服务器同步，没有另造 GUI 基类或编辑器。Mouse Tweaks API 仅作为 AEBaseGui 声明的可选父接口加入编译依赖，不作为新运行时必需依赖。

`localization.GuiText` 实现 AE2S 的 `LocalizationEnum`，客户端标签调用 `getLocal()`，容器标题使用 `text(...)` 组件。GUI 不拼接翻译键，也不直接传裸翻译键调用翻译函数。

资源输入/输出区域各有独立的 AE2 ScrollBar，最多显示 5 行并随分辨率压缩；默认布局读取各侧实际槽数，允许外部非对称槽位仓室。滚动保留原始槽位索引，隐藏槽禁用，避免界面位置变化造成操作对象混淆。网格后保留流体限额/清空区域，限额可 Enter 或“应用”提交。能源界面为 176×214，使用 PM 原 16×66 纵向能量条，支持输入/输出限额与自动标志设置、Enter 提交及完整 long 数值 tooltip；纹理等级从容器的公开宿主接口读取，不强转内置实体。实际 GUI 交互与不同分辨率显示尚待游戏内验证。

AE2S 的 ConfigGuiInventory 构造参数和 delegate 固定为 GenericStackInv，不能直接接收 GenericInternalInventory。这里使用薄适配器 `HatchGuiInventory` 实现 InternalInventory 和 GenericStackDisplayInventory：服务端直接读取 SlottedResourceStorage 的已提交内容，客户端只缓存收到的同步 ItemStack。它不持有服务器余额，服务端 setItemDirect 明确拒绝，所有玩家写入由容器授权动作完成。GUI 显示完整已提交量，自动化视图则只显示可转移量，两者用途不同。

资源容器及 `HatchSlotActions` 在真实 SlottedResourceStorage 上处理物品转移。普通物品可以放入输入侧并手动取回，输出侧普通物品只可取出；AE资源包允许在两侧手动解包，混合仓界面分组显示两侧。支持普通点击、右键拆分/单个放入、Shift 转移、数字键和丢弃；大缓存按原生物品堆叠上限取入玩家库存。点击及交换使用共享锁和 tryReplaceSlot，Shift 的抽取、背包合并及余量回退处于同一侧锁范围，防止并行操作抢走回退容量。收集物品时依次访问两侧，不持有输入锁再进入输出。资源槽不参与原版拖拽分配，防止显示包装栈进入玩家物品栏；玩家库存仍保留原版拖拽。

手持可被 AE2 策略识别的流体等资源容器：右键输入槽倒入，左键资源槽装出，配合 Shift 请求整容器操作。ResourceHatchContainer 自行路由四种装入/倒出动作，复用 AEBaseContainer 的 held-item 转移策略；模拟、实际转移和失败回退处于同一侧 context 锁内。服务端仍禁止向输出槽发送 EMPTY_ITEM/EMPTY_ENTIRE_ITEM 写入，禁止 AE2 通用 SwapSlotsPacket 直接移动资源显示包装栈；数字键交换走实际数量路径。客户端限制不作为库存权限依据。

打开界面前向该玩家发送实体布局和容量，再由容器同步资源槽/能源数值。转移时不重复发送整份 TileEntity NBT，以免替换正被其他玩家界面使用的缓存。GUI 与模型类只由客户端代理引用。

## 开发默认值与资源来源

`config/prototype_eng/hatches.cfg` 在初始化时读取，按 `tier1`–`tier10` 配置槽数、混合仓每侧槽数、物品/流体每槽容量、能源容量与操作限额；槽数允许 1–81。十阶槽数/物品容量源自 PM `ItemHatchConfig` / `ItemIOHatchConfig`，流体每槽容量源自 `FluidHatchConfig`，能源源自 `EnergyHatchConfig`。

| 等级 | 输入/输出槽数 | 混合仓每侧槽数 | 物品每槽 | 流体每槽（mB） | 能源容量（FE） | 能源每次操作限额（FE） |
| ---- | ------------: | -------------: | -------: | -------------: | -------------: | ---------------------: |
| 1    |             1 |              1 |       64 |         16,000 |        128,000 |                     32 |
| 2    |             2 |              1 |      128 |         32,000 |        512,000 |                    128 |
| 3    |             4 |              2 |      128 |         64,000 |      2,000,000 |                    512 |
| 4    |             9 |              4 |      256 |        128,000 |      8,000,000 |                  2,048 |
| 5    |            16 |              8 |      512 |        256,000 |     32,000,000 |                  8,192 |
| 6    |            25 |             12 |    1,024 |        512,000 |    128,000,000 |                 32,768 |
| 7    |            36 |             18 |    2,048 |      1,024,000 |    512,000,000 |                131,072 |
| 8    |            49 |             24 |    4,096 |      2,048,000 |  2,000,000,000 |                524,288 |
| 9    |            64 |             32 |    8,192 |      4,096,000 |  8,000,000,000 |              2,097,152 |
| 10   |            81 |             40 |   16,384 |      8,192,000 | 32,000,000,000 |              8,388,608 |

多类型资源共用物品仓槽位布局，不另建流体专用槽位等级。其他已注册 AEKeyType 优先采用 AE2 GenericSlotCapacities 值，否则取 64 个该类型单位。这些是通用组件默认参数，可由使用方配置，库不新增正式科技路线。

资源仓已有实例保留保存的槽位和容量，配置默认值用于新放置实例；能源仓重载以当前等级配置容量为准，将保存量裁剪到该容量。能源重载行为已有逻辑测试，资源完整保存恢复仍待运行验证。数量使用 long；非法配置/已保存容量立即失败，不捕获后换成默认值。容量表使用 fastutil Reference2LongMap，按已注册 AEKeyType 的对象身份查找。

资源仓使用 PM `item_hatch`，能源使用 `energy_hatch` 的十阶三方向原模型和依赖纹理，保留原模型 `credit`、`bloom_*` 元素及 CTM/Bloom/动画元数据，资源命名空间改为 `prototype_eng`。原仓室走标准 Forge 模型和纹理元数据，不依赖机器 Gecko/TESR；发光后处理需要兼容的 CTM 与 BLOOM 层提供者，实际组合未验证。来源及原作者署名见 [美术来源](assets.md)。

## 验证范围

本轮 R31/流体效果新增 5 项行为用例：外部非对称 SlottedResourceHatch 的共享适配及目标类型清空、输入预留/输出待结算/退款权保留、失效旧引用拒绝转移、下游 FE 实体 NBT 与重新挂载、下游控制器 NBT 与无 World 生命周期。合并定向 141 项初次运行仅新测试失效余额读取断言错误，其余 140 项无失败；修正为读取保存槽量后，该 3 项外部仓室 suite 重跑全通过。逐文件 IDEA MCP 和最终补查状态见[实施进度](implementation-status.md)及 `build/reports/idea/ae-physical-extension-final.json`，输出摘录和修正后 XML 见 `build/reports/tests/ae-physical-extension/`。没有把 native Forge 流体/GUI 运行记为通过。

上一轮仓室与能源实现的构建、逻辑用例及逐文件 IDEA MCP 证据见[实施进度](implementation-status.md)，覆盖多类型双缓存流转、失效、方向/key 限制、鼠标转移守恒，以及能源三方向、动态操作限额和重载裁剪等行为。它们不能替代本轮独立存储后端的验证。本轮适配层编译和逐文件 IDEA MCP 检查已完成，完整逻辑测试与全量检查仍在进行，最终结果以实施进度记录为准。

NBT 完整往返、Forge 实际能力发现、玩家交互和重启恢复尚未验证。直接在普通 JUnit 类加载器调用 AE2 的 key 持久化，会触发需要 FML 可选接口处理的物品初始化；当前不为此引入整套替代游戏环境，也不声称已覆盖实际保存恢复。

后续运行验收需覆盖：不同流体同时占据输入和输出时的 drain；玩家打开两个界面时的数量同步；满背包 Shift 转移；手持桶/其他资源容器的填充和倒入；挖掘及重新放置；卸载/重载与重启；服务器和客户端默认配置不同时打开界面。执行这些检查前仍遵守当前不启动客户端/服务端的要求。
