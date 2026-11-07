# 控制器注册与世界接线

当前规范机器定义、装配和运行时已接到真实 `Block`、`ItemBlock`、`TileEntity`、服务器调度、客户端模型、默认/自定义 Java GUI 与方向工具。自动扫描须由下游显式声明组件。没有声明正式机器、配方或研究数值；所有控制器方块来自下游的机器声明。现有纯逻辑测试及编译不能代替游戏验证，本阶段按要求不启动 Minecraft 或 Bootstrap/FML。

## 两阶段注册

Forge 方块注册时，`MachineControllerBlocks` 发布 `MachineTypeRegistrationEvent`，监听者通过 `event.registry().register/registerAll` 提交不可变 `MachineType`。事件返回后 freeze，按声明顺序生成各类型的 `<namespace>:<path>_controller` 方块和物品。无声明时仍注册通用 `prototype_eng:machine_controller` TileEntity，保证库的类型映射不依赖正式内容。

PostInit 在现有配方事件完成且配方注册表冻结后发布 `MachineRuntimeRegistrationEvent`。同步监听者用 `structure(definition)` 和 `assembly(assembly)` 收集绑定。事件结束后调用 `resolveRuntime`，整批校验结构引用、重复 ID、装配完整覆盖及规范类型对象身份，再同时发布结构与装配；失败不发布一半，不调用组件 factory。事件快照不可修改，后续收集不会改写已取得的快照。注册事件只在其同步发布期间用于声明，不是运行中追加入口。

单独的 `resolveStructures` API 仍可用于独立库场景，但完成后不能再升级为完整 runtime 发布。游戏全局实例使用 `resolveRuntime`。结构/装配解析成功仍不代表 PN owner/team/研究/epoch 或 planned ready。

## 附着、NBT 与替换

`MachineControllerBlockEntity` 无参构造不访问 World、不创建组件。方块 factory 仅给类型和朝向提示；`readFromNBT` 只解码复制的 `ControllerData`。保存外层保留 vanilla/Forge 的基本数据，`Machine` 子标签包含版本、规范类型、完整 AE 朝向及可选 runtime。物品数据不能把一种控制器变成另一种，附着后也不能通过 NBT 改位置。

当前实际依赖 `build/moddev/artifacts/cleanroom-0.6.13-alpha-sources.jar` 的调用顺序已复核：

- ItemBlock 放置可以先附 World/位置并 `onLoad`，然后合并 BlockEntityTag，再调用 `onBlockPlacedBy`；processingLoadedTiles 路径也存在先 NBT/placement、后 onLoad 的反向顺序。
- Forge 的 PlaceEvent 取消窗口位于 Item.onItemUse 返回后。因此 `onLoad` 只标记，下一次服务器 update 确认仍是准确、有效且已加载的本 TE，再在 scheduler 边界装配。tick 0 不被去重条件跳过。
- 未装配 `writeToNBT` 保存原始副本，支持 ItemBlock 先 write、merge、再 read 的路径。客户端更新另有显示格式，不能走机器恢复。
- Forge BlockSnapshot 回滚还会再次读取已装配 TE。此时先要求旧机器处于合法持久化边界，接受卸载并退休旧 controller，再保存替换数据；不会向活组件直接 restore。普通保存保留此替换，下一次有效更新由新 controller 完整装配、load 和登记。执行中/故障机器不能借 NBT 替换绕过门限。
- 正常卸载后重复附着使用新 controller，旧对象不复活；旧原始数据或健康旧机器的保存用于恢复。load 被排队停止取消、未返回机器时保留原数据，后续有效附着可以重新创建。

Cleanroom 的正常 dormant 缓存会在 `ForgeChunkManager.storeChunkNBT` 保存 NBT 并清空 TE map，`loadChunkEntities` 重新 `TileEntity.create`；不能把它描述为复用同一个 Java TE。保存可能先于 TE.onChunkUnload，保存必须独立遵守已完成 worker 的边界；当前 server END 调度在返回前真实 join。

装配后还须配置结构、建立刷新器、同步完整方向和显示。任一步失败都会请求已加载 controller 卸载/注销，并聚合错误；不能仅故障 scheduler 而留下生命周期外部注册。普通卸载保进度，破坏通过原运行时取消工位并清理，最后执行 Forge 移除。详细顺序见[运行时说明](machine-controller.md)。

## 朝向与结构刷新

方块使用 AE `OrientationStrategies.full()` 的 facing/spin，实际朝向完整保存在 TE。meta 只存 facing；恢复后写回完整 state。`getActualState` 提供 TE 的 spin/formed。Block.withRotation/withMirror 和 TE.rotate/mirror 都变换 front/top，竖直 front 的 top 同样旋转；模板的 read → mirror → rotate 得以保留。镜像只定义控制器方向变换，不自动把任意不对称结构定义变成反射结构。

方向工具、TE API 及外部同方块 state 变化走 owner 边界：失效旧匹配与仓室，更新方向，轮换 `StructureConfiguration`，再更新实际 state。相对变换到执行时才读取当前方向，连续操作按 FIFO 复合；外部 A→原方向也记录，避免数据与物理 state 分离。排队操作绑定 attachment epoch，不能修改已经替换的代。同块方向变化不销毁 TE。

`ControllerStructureUpdates` 保留 PM 的 formed 每 20 tick、unformed 每 5 tick 检查节奏，首次立即检查。owner 捕获快照，真实 CONCURRENT worker 只 evaluate，所有 accepted worker 退出后 owner 发布；正常匹配期间旧机器按原行为继续处理。当前发布失稳则明确 invalidate 暂停旧配方，下一 tick 重试；旧请求成功/失配不改变新请求，旧错误仍报告但不凭旧配置故障新 lifecycle。当前错误经过既有失败协议永久停止对应机器/调度，不吞异常。

## 放置者与显示

`onBlockPlacedBy` 只记录实际 EntityPlayer 的 UUID，并在本 TE 保存 World、位置和规范类型，附着完成后消费一次。`MachineLifecycleHost.placementPlayer()` 公开此代的实际放置者；非玩家或磁盘加载为空，不根据普通方块事件补 owner。带数据物品也可有真实放置者，具体持久 machine UUID/owner 搬运规则仍须 PN 身份集成完成，当前字段不授予任何权限。

客户端只收 `ControllerDisplayData`：类型、朝向、formed、单调显示代和序号；不发完整机器 NBT、工位或 Forge capability 内容。两个更新通道都显式走此格式，先过滤位置和类型，再拒绝旧代/重放。新 TE 的空视图接受当前服务器快照，客户端 world 自身限定连接上下文；显示代不持久化，不是 machine/owner 标识。符合当前类型却格式损坏的数据仍严格报错，不使用异常控制流掩盖协议错误。

世界模型和 inventory 均按 `MachineType.controllerModel()` 自定义，缺省使用 PM 默认资产，名字使用声明的 LocalizationEnum。资源、credit、原旋转效果及尚未注册的 CTM 外壳连接目标见[控制器资产](controller-assets.md)。GUI 独立使用有界容器显示协议，不扩展世界显示包为完整 runtime 同步；复杂模型/动画/投影/隐藏缓存仍待接。

## 自动配方、GUI 与方向工具入口

自动配方使用 `RecipeScanning.component`，机器组件声明先 `MachineWorkSlots.TYPE` 后 scanner，装配以工位 codec 保存进程、以 recreated 重建 scanner；只声明工位不扫描。catalog 在装配时解析完整 freeze 的共享目录。空 recipeGroups/槽位白名单统一为全局候选，包含无组配方，显式组仍按交集限制具体工位。扫描系统排在工位处理前，二者都检查成型/退休门限。详细合同与示例见[配方扫描](recipes.md#自动扫描组件声明与恢复)。

控制器非潜行普通激活打开 GUI 0，手持方向工具时交还物品处理。默认 `MachineGui` / `MachineContainer` 直接继承 AE2 基类，保留 PM 可滚动工位进度与真实玩家背包。容器在 owner FIFO、worker join 后重新检查 openContainer/准确 TE，再同步最多七行不可变显示值；屏幕不读取服务器 runtime。默认或自定义容器绑定由 common `MachineGuiRegistry` 提供，client `MachineScreenRegistry` 用明确容器类绑定有类型 factory。自定义界面直接继承 AEBaseGui，不需要默认类或 GUI DSL。完整入口与同步限额见[机器 GUI](machine-gui.md)。

`prototype_eng:orientation_tool` 保留 PM 的两模式、武器/铁镐斧锹、耐久、附魔、成就和原模型。开启后普通点击 front 扭转、其他面重置 canonical，潜行遍历 24 向；相对操作使用上文 owner FIFO。PM 的 DOWN canonical top=SOUTH 单独保留，配置十项默认值/范围沿用原实现。详细交互、权限与资源见[方向工具](orientation-tool.md)。

## 验证与继续范围

新增逻辑测试覆盖注册原子性/坏引用/重入/快照，真实刷新 worker join、失稳重试和旧请求隔离，保存标签隔离、24 向组合与显示重放拒绝。没有直接 new TileEntity：其父构造会触发 AttachCapabilitiesEvent 和 Forge 初始化，违反当前测试约束。真实 ItemBlock/BlockSnapshot/Template/World 调用由依赖源码审查、编译与 IDEA MCP 检查覆盖，未宣称实机通过。完整计数和逐文件记录见[实施进度](implementation-status.md)。

仍须完成 PN 身份/搬运冲突/owner/研究/队伍/计算与正式 planned，严格 JSON 阶段与注册、结构预览/投影/切片/BOM、复杂渲染与隐藏缓存、JEI 和最后 CraftTweaker。默认及自定义 Java GUI、自动 scanner 和方向工具已接，真实交互与渲染尚无实机证据；最新构建及逐文件 MCP 记录见[实施进度](implementation-status.md)。缺少剩余能力的声明仍不会被当成正式完整机器；Applied-Flux 继续暂缓。
