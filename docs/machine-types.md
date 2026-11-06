# 机器声明与结构绑定

本阶段迁移 PM 的机器定义字段与 PN 有序关闭注册思路，修正逐个发布导致半批可见的问题。入口为 `api.machine.MachineType`、`MachineTypeRegistry`。附宿主装配与机器级 NBT 已由 [MachineAssembly](machine-persistence.md) 接入；当前没有向 Forge 注册正式机器，也没有完成控制器 TileEntity 或 PN 设备定义。

## 声明保持一个规范对象

`MachineType` 是不可变具体类，不保留 `MachineTypeImpl`、属性默认接口或执行模式默认接口的镜像层。builder 接收 ID、AE2S `LocalizationEnum` 显示名称、结构 ID，并保留以下数据：

- 按声明顺序的公开 `ComponentType<MachineInstance, ?>`。不会运行工厂，也不会因为一个组件声明了排序依赖就自动增加该组件。
- 配方组与有序工位定义，含每槽范围、核心标志与重启策略。
- 默认属性 base，使用 fastutil 原始 double 映射；不预造属性/资源类型系统。
- 默认执行模式（PM 的 `CONCURRENT`），可选自定义控制器模型位置。

集合复制并冻结，builder 后续修改不会改变已发布声明。重复 ID/槽名/属性、非有限默认 base 会拒绝。组件与系统排序验证复用 `ComponentMap` 实际 dispatch 的拓扑路径；不创建实体或读取世界。缺席依赖仍是软排序约束，同一 ID 指向不同组件描述符则拒绝。

显示名称由使用方提供本地化枚举，容器调用 `text()`，客户端调用 `getLocal()`；没有裸翻译键或服务端客户端工厂混用。模型位置只是公共数据，具体 GUI/模型绑定后续在客户端注册阶段接入。

## 两个发布边界

1. `register` / `registerAll` 收集声明。整批检查完才加入 pending 表；任何重复或坏项都不加入本批的任何类型，也不影响先前成功声明。
2. `freeze` 关闭声明并按登记顺序发布。冻结前不能枚举或查询，冻结后不能再添加或二次关闭。空库同样可以关闭。
3. Forge 方块已就绪后，`resolveStructures` 接收完整结构表，验证表的 key/结构 ID 以及每个机器引用，全部成功后一次发布机器到结构的绑定。失败不留下部分绑定，可以修正后重试；成功后不能再次绑定。

结构 ID 使声明不依赖早期方块解析；后置绑定始终保留同一个 `MachineType` 对象，不构造第二份 PM/PN 壳。`structure(type)` 要求该规范对象本身，同 ID 仿造对象不能取代它。结构实现仍须满足 `MachineStructure` 的不可变契约。

注册器使用启动阶段低频的同步边界保护完整操作；集合迭代和外部结构 `id()` 回调中的重入变更被拒绝。结构表通过校验仅表示引用完整，不授予 owner/team/planned 权限，也不表示配方、研究和计算索引已可运行。

## 与现有实例和工位的接线

`new MachineInstance(type)` 只保留声明，不创建组件，不在空 TileEntity 构造期接触 World。带类型的实例在附着生命周期前不能调度、处理配方或序列化；`MachineLifecycle` 附着时检查宿主类型 ID，`load` 成功后沿用原有运行门限。原无参构造仍供独立组件/配方逻辑使用。

调度模式优先读取实例已有的 `MachineScheduling`，然后回退到类型默认，最后是无类型实例的 `CONCURRENT`。声明模式不能覆盖已选择或恢复的实例组件。

只有使用方显式创建 `MachineWorkSlots.TYPE` 时才装配工位；该工厂已读取类型布局与配方组。布局为空时仍用 PM 的隐式 `main` 和机器组，显式布局保留各槽自己的组。此处没有自动添加 scanner，也没有让研究/计算机器意外拥有配方处理。

机器组为空时，当前 scanner 返回 `NO_GROUPS`，工位直接 admission 的空组仍为不过滤，这是旧迁移中已记录的不一致。本阶段没有把它定为新的统一规则；正式控制器自动配方装配前必须统一，保留原有可表达的全局候选能力。

## 下一步装配与恢复

MachineAssembly已接附宿主后建组件、恢复属性与组件图、load的顺序；类型默认在属性恢复后、组件codec恢复前按整条属性ID补缺，modifier-only项不补base。PM先补默认再恢复的旧流程在 `MachineAttributeNbt.readMachineMap` 清表后会丢失缺项默认，此处已修正。下一阶段继续接空TE、暂存原始NBT、最后scheduler登记。

组件工厂与 restore 只构造尚未发布的局部状态；世界注册等副作用进入 `onLoaded`。若今后允许装配时分配外部资源，必须先有明确的中止清理契约，不能指望 CREATED 的普通 unload 调用已加载监听器。load 成功但 scheduler 登记失败也必须卸载并撤销确切注册。

仍未完成：通用控制器零类型 TE 注册、实际TE调用装配/持久化入口、持久身份与放置 token、完整方向变更边界、自卸载延迟清理、客户端显示视图、PN 角色/研究/向量预算配置、planned 绑定和最终运行索引。结构绑定不替代这些步骤；详见 [控制器迁移审查](machine-controller-migration-audit.md)。

## 验证范围

新增逻辑用例覆盖整批拒绝/关闭、缺失结构不部分发布、规范对象和重入拒绝、无工厂调用、声明与实际 dispatch 共用循环检查、附着/load 调度门限、真实线程上的默认/覆盖模式，以及声明工位的配方准入与推进。现有完整逻辑测试继续运行。逐文件 IDEA MCP 与构建的最终结果记录在 [实施进度](implementation-status.md)。没有启动 Minecraft、创建真实 World 或加入 Bootstrap/FML 测试初始化。
