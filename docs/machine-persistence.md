# 附宿主装配与机器持久化

入口是 `api.machine.persistence.MachineAssembly` 和扩展接口 `MachineComponentCodec<C>`。保留 PM 的组件工厂、属性和可序列化组件职责，直接复用现有 `MachineAttributesNbt`、`MachineWorkSlotsNbt`、`RecipeProcessNbt`，没有另做属性/配方类型或存储后端。本阶段是实际控制器需要调用的装配入口，尚未增加控制器 TileEntity 或 Forge 机器注册。

## 调用顺序

`MachineAssembly.builder(type, revision)` 固定一个规范 `MachineType` 对象及持久化修订号。使用方为组件显式绑定 codec；无持久状态的组件可声明 `recreated`，工位使用 `workSlots(codec)`，并可增加完整状态校验。修订号必须随不兼容的工厂、行为、布局和 codec 改变；相同 ID 不能证明任意 Java 行为兼容。

`create(host, registry)` / `restore(host, tag, registry)` 先创建带类型实例、附着宿主生命周期，才调用组件工厂。完成全部组件/属性恢复及校验后，才调用 `load(FRESH/RESTORED)`。返回的是成功加载、尚未登记 scheduler 的实例，控制器在最后登记。省略 registry 的重载使用已有全局生命周期监听注册表。

工厂可读取已附着宿主并初始化本地属性，不能在工厂内自行修改组件图。工厂和 restore 只构造未发布的本地状态，世界/全局注册等副作用放在 `onLoaded`。装配失败不向从未加载的监听器发送卸载事件；`load` 已进入监听发布后的失败，仍使用现有 `LOAD_FAILED` 完整清理与异常聚合。

## 保存内容与恢复顺序

机器 NBT Version 1 保存 `Type`、`Revision`、`Attributes`、`Components`、`PendingDependencies`。组件条目保存描述符 ID、实际动态依赖与独立 `Data`。未知或重复条目、错误字段类型、坏修订/ID、循环组件/系统依赖都拒绝，不能跳过坏项加载半台机器。

恢复先解析并验证完整保存图，再创建所有已保存组件；随后按组件依赖顺序恢复数据，最后完整校验。保存的实际图优先于类型默认图：已删除的声明组件不被重新添加，运行时新增组件必须已有显式绑定，尚未安装 dependent 的 pending 约束保留。`ComponentMap.validateGraph` 与实际 dispatch 复用同一系统排序逻辑，预检不调用工厂。

组件 codec 可以修改自己的新组件并返回它，也可以返回一个不可变替代对象。`ComponentMap.replaceValue` 保留节点位置、当前依赖与 pending 约束，不用普通 `put` 将替代节点移到末尾。factory/restore/validator 前后按对象身份校验装配图；相同 ID 或 `equals` 相等不能掩盖对象替换。

恢复值整图替换工厂初始属性，随后只为不存在的属性 ID 补类型默认 base，再进入组件codec恢复，使组件看到完整根属性。已存在的 modifier-only 项也不补 base，避免改变保存的含义。PM 的旧流程先补默认、随后属性 NBT 清表，会丢失保存缺项的默认值，本阶段修正这一点。codec/validator 不得暗中修改 owner 属性；检测比较包含修饰器对象身份和顺序。

`MachineScheduling` 使用内置 codec 保存实例模式，未保存权限代或执行状态；fresh 只在缺席时补入类型默认模式。它不会自动给所有机器增加工位或 scanner。工位 codec 继续保存完整进程状态，普通卸载保存后恢复不重跑 START，重新成型后才能继续配方。

`MachineHatches` 只来自当前结构发布，不作为组件条目保存/恢复，也不能绑定 codec 或声明为待装配组件；其他组件仍可保留指向其规范 TYPE 的软依赖。形成状态、结构绑定、配置代、epoch、lease 和 ready 都不会从机器 NBT 恢复。合法持久设备身份仍由后续 PN 组件 codec 保存，不能临时编造 owner。

## 生命周期门限

`MachineLifecycle.assemble` 在已附宿主的服务器线程执行一次。`withPersistence` 覆盖一次完整保存；两者允许同一线程的子属性/组件/工位 codec 检查持久化边界，但拒绝递归保存、提前 load、执行和生命周期重入。保存期间普通调度资格关闭，其他线程不能借此获得 codec 访问权。

任何装配或保存回调故障都会使实例不可再执行/保存。装配失败直接退休尚未加载实例；已加载实例保存失败后仍可执行正常卸载清理。故障不会通过重复 load 或保存重试复活。

这些 gate 拒绝重叠，不替代真实 worker join。实际控制器在保存、卸载或变更配置前仍须遵守 scheduler 的独占边界。公开低级属性/组件 API 不具有独立并发安全语义；扩展仍须遵守不可变定义和纯 codec 契约，检测不使用反射检查任意对象内部状态。

## 验收与后续

逻辑测试覆盖恢复/创建顺序、默认补缺、组件和标签隔离、动态图与不可变替换、失败不发布、保存故障、已提交进程恢复，以及实际线程上的边界拒绝。最终数量与逐文件 IDEA MCP 证据见[实施进度](implementation-status.md)。未创建真实 World 或启动游戏。

`MachineController` 已接 load 后登记、登记失败清理、延后卸载和未装配原 NBT 保存，见[控制器运行时](machine-controller.md)。下一步仍是空 TE 暂存 NBT、实际 Forge 调用、方向/放置身份、客户端显示同步，以及正式 planned/PN 权限预算。尚未装配的 TE 必须保存原暂存数据，不能用空运行状态覆盖；二次 readFromNBT 也不能直接覆盖已加载实例。完整控制器规则继续见[迁移审查](machine-controller-migration-audit.md)。
