# 仓室核心与扩展接入

当前已实现存储核心和公开发现契约，方块实体、资源能力适配和 GUI 正在接入。这里不表示仓室已经可以在游戏内使用。

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

资源后端可自行实现 MEStorage；模拟和执行须遵循其返回数量契约。失效后的旧引用不得继续改变真实存储。单次字段原子性不能代替跨仓室提交，配方执行阶段还需处理多来源一致性。

## 验证范围

已保留四个逻辑用例：多类型双缓存的模拟/实际流转、宿主失效后已有引用的行为、Forge 多次传入与机器大数量扣取共用余额、从 Long.MAX_VALUE 附近恢复后充入并消耗。

NBT 完整往返、Forge 实际能力发现、玩家交互和重启恢复尚未验证。直接在普通 JUnit 类加载器调用 AE2 的 key 持久化，会触发需要 FML 可选接口处理的物品初始化；当前不为此引入整套替代游戏环境，也不声称已覆盖实际保存恢复。
