# 机器类型、控制器与 PN 接线迁移审查

本文件是 2026-11-07 对 PM/PN 本地源码的审查，给出合并库的实现入口与必须保留的行为。当前项目已有机器组件执行、宿主生命周期和结构快照发布；普通真实调度及服务器接线进展见[调度说明](scheduling.md)。已接规范[机器声明](machine-types.md)及附宿主后的[装配/恢复入口](machine-persistence.md)；实际控制器 TileEntity调用、Forge 注册、客户端和正式 planned 仍未完成。以下“已确认”指源码证据，不代表游戏行为验证。

约束依据为 [development-spec.md](F:/new/PrototypeEngineering/docs/development-spec.md:36) 的 R02、R08–R12、R16、R20–R22、R25：纯库；公开扩展不依赖内置具体类；完整保留 GUI、预览、复杂渲染、组件/系统、多工位、并行与 planned 能力；GUI 改用 AEBaseGui 和 LocalizationEnum；不迁入旧 GUI 编辑器/DSL/Runtime JSON；不要求旧格式兼容。PN 的四台开发机器、铁块结构、固定仓室与数值仅用于理解原接线，不能进入库的正式内容。

## 1. 声明字段与默认装配

PM 的 [MachineType](F:/clone/PrototypeMachinery/src/main/kotlin/api/machine/MachineType.kt:53) 是真实机器定义，不能缩减成只有 ID 和结构的注册条目。

| 原字段/扩展 | 必须保留的含义与合并建议 |
|---|---|
| `id`（61） | 稳定 ResourceLocation；定义、控制器、持久化、调度绑定均引用同一个规范定义。保持命名空间，不建立 PN → PM 的第二份字符串物化模型。 |
| `name`（70） | 保留显示名称/标题能力。按 R25 改为本地化枚举或等价的服务器文本契约，不能照搬裸翻译键及 GUI 拼键。 |
| `structure`（79） | 保留 24 向、模板、切片、预览/BOM/display override 表达；复用现有 MachineStructure 与 StructureConfiguration。 |
| `componentTypes`（88） | 明确的组件装配声明、创建顺序、错误传播；扩展组件仍是公开职责，不能固定为资源/FE/配方三种组件。冻结集合并使顺序确定。 |
| `recipeGroups`（101，默认空） | 对应新配方池归属/选择范围。空集合含义必须一致，并与工位选择、索引扫描一致。 |
| `workSlots`（113，默认空） | 保留多个独立工位、允许池、自动重启、锁定重试与进程状态。隐式单工位仅是配方处理组件的默认策略。 |
| `controllerModelLocation`（123，默认 null） | 保留自定义控制器模型与默认模型；客户端按类型 ID 绑定，公共定义不直接加载客户端类。 |
| 执行模式/属性默认扩展 | [SchedulingModeComponentImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/SchedulingModeComponentImpl.kt:18) 默认 CONCURRENT；保留主线程、并发、PLANNED_PARALLEL 的选择与合法性检查。[MachineInstanceImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:95) 先创建组件，再只补缺失的类型属性默认值，随后恢复存档值。 |

[MachineInstanceImpl.createComponents](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:133) 除声明组件外，确保 StructureRenderData 与 SchedulingMode 组件存在，创建失败直接失败。这两种默认能力不能遗漏；可重划实现位置，不要求机械复制旧组件类。FactoryRecipeProcessor **没有被所有机器自动补入**。[其默认工位](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/FactoryRecipeProcessorComponentImpl.kt:131) 只在此配方组件存在且声明没有工位时创建 `main`，并以类型的 recipeGroups 作为范围；最大并发缺省为 1（152）。新装配不能让原来仅研究/计算的声明意外运行配方。

PN 的 [NetworkMachineType](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/api/pm/NetworkMachineType.java:26) 直接扩展 PM 定义及执行默认契约。[不可变实现字段](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/pm/NetworkMachineTypeImpl.java:32) 还包含角色、等级、数据中心容量、设备研究条件、每工位资源需求、每 tick 资源贡献及资源修改器，必须进入合并定义的设备/研究/计算配置职责。它们不是控制器 ID 的附属标签，也不能用一张固定 FE 成本表替代。原实现冻结集合、按角色校验容量和贡献，并要求 planned 类型有 runtime 组件、真实工位和非空池（159）；可复用校验目的，具体玩法值由库使用方提供。

[NetworkMachineTypes](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/api/pm/NetworkMachineTypes.java:49) 的开发工位使用自动重启与 LOCKED_RETRY，四角色默认 PLANNED_PARALLEL（110）；所有角色加 runtime 组件，数据中心再加 storage（161）。保留这些可配置能力，不注册开发机器。原固定输入仓 [DevelopmentItemInputHatchPredicate](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/pm/DevelopmentItemInputHatchPredicate.java:78) 强制内部 ItemHatchBlock、阶数和默认状态，不可成为新库通用发现规则；外部仓室通过公开契约/能力发现，精确美术块可作为使用方明确的结构条件。

## 2. 动态声明和注册顺序

PM 在 [PreInit](F:/clone/PrototypeMachinery/src/main/kotlin/PrototypeMachinery.kt:101) 先读结构 DTO（不解析方块），处理机器声明队列，然后 proxy.preInit；[PostInit](F:/clone/PrototypeMachinery/src/main/kotlin/PrototypeMachinery.kt:121) 解析结构方块，再构建配方索引。队列 [MachineTypeRegisterer](F:/clone/PrototypeMachinery/src/main/kotlin/common/registry/MachineTypeRegisterer.kt:21) 按入队顺序逐个注册，但捕获 IllegalArgumentException 后继续并清空队列，失败可留下部分声明。

[MachineTypeRegistryImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/MachineTypeRegistryImpl.kt:21) 在注册时验证 planned factory 与显式 provider 互斥、factory 必须有 context provider、planned 默认必须有执行支持；这些约束要保留。其 ConcurrentHashMap 的 `containsKey` 后 `put`（48）不是原子拒重，`all()`（58）没有确定顺序。不能因容器并发安全就认为复合注册和完整发布安全。

[BlockRegisterer](F:/clone/PrototypeMachinery/src/main/kotlin/common/registry/BlockRegisterer.kt:32) 在 Forge block 事件遍历当时的机器注册表，为每种类型生成 MachineBlock；[ItemRegisterer](F:/clone/PrototypeMachinery/src/main/kotlin/common/registry/ItemRegisterer.kt:23) 再为已创建方块生成普通 ItemBlock。Block 处理异常后继续，零类型提前返回（35）还跳过通用 TE 类型注册（62）。类型表没有 close 边界，Block 事件后再加声明不会自动补出控制器。源码注释所述 PreInit/Block 先后不能替代跨模组的真实运行验证；新接线要明确要求所有使用方声明在生成控制器前完成，不依赖不受控的晚到队列。

PN 的 [PreInit 顺序](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/bootstrap/PrototypeNetworkLifecycleImpl.java:176) 是角色类型 → planned context/factory → 机器声明 → 锁角色/机器声明 → 发布到 PM；研究、资源与配方在 PostInit 完成后再校验引用（222）。[NetworkMachineTypeRegistryImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/pm/NetworkMachineTypeRegistryImpl.java:22) 用 fastutil 有序表和 lock，这种确定顺序与关闭发布边界比 PM 的开放并发表更适合合并库。原 [PM 适配器](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/pm/PrototypeMachineryMachineTypeRegistrationImpl.java:30) 只预查 null/重复 ID，后续仍逐个发布；中途 planned 校验失败会保留前面注册结果，不能作为事务发布。

最小合并顺序：公开 Java API 收集一份规范不可变定义及各 ID 的 planned 绑定 → 完整校验整个待发布批次（包括组件、工位、模式、绑定冲突、依赖引用）→ 关闭定义批次并发布确定顺序快照 → Forge Block/Item/TE 注册 → 客户端模型/GUI 绑定 → 结构实际方块解析及研究/资源/配方最终引用校验 → 发布运行索引。运行索引必须在全部校验成功后可见。允许分阶段解析，不能在失败后把半个图当可调度图。通用 TE 的注册不依赖已有正式机器数量，空库也应可供下游扩展。

PN [builtIns](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/api/pm/NetworkMachineTypes.java:65) 每次创建新定义；注册声明（128）和 planned 绑定（140）分别调用，现阶段内容相同但对象不同。合并后 factory/context/控制器都引用同一个规范定义，避免未来两个构造路径漂移。不必保留 PN 壳注册表和 PM 适配器。

## 3. 控制器附着、NBT 与生命周期

### 已确认的创建/恢复冲突

PM [MachineBlock 的两个 TE factory](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/MachineBlock.kt:245) 都直接调用带类型的构造器；[MachineBlockEntity 构造](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:80) 马上 new MachineInstanceImpl。实例字段初始化就 [new StructureComponentMapImpl](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:63)，后者立即读取 [world.provider.dimension](F:/clone/PrototypeMachinery/src/main/kotlin/impl/machine/component/StructureComponentMapImpl.kt:23)。factory 的 World 参数尚未绑定到该新 TE，形成源码可见的无 World 创建链。NBT 恢复 [readFromNBT](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:159) 也直接创建并恢复机器；PN [readNBT](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeComponentImpl.java:92) 又要求真实已附着服务器线程（119），两条路径都不能直接照搬。

另外，readFromNBT 拒绝覆盖已初始化 runtime（161）。带类型 factory 路径或后续 BlockEntityTag 应用如果再次进入此方法，会命中该限制；本轮没有运行 vanilla 拾取/放置流程，不能断言哪种流程已触发。合并实现须明确 fresh 放置与 RESTORED 加载的状态机，不依赖“readFromNBT 只会调用一次”的假设。

### 必须保留的边界

| 原调用 | 合并接线 |
|---|---|
| `readFromNBT` / `writeToNBT`（159 / 187） | 空 TE 构造只存类型提示；read 调 super 并复制/校验原始机器数据、方向和持久身份，附着后再创建组件并恢复。保存前真实 join，并调用 MachineInstance.requirePersistenceBoundary。未知类型/坏数据失败，不创建虚假权限。尚未装配时不得静默写空 runtime 丢失已暂存数据。 |
| [MachineInstanceImpl NBT](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:180) | 保留属性、可序列化组件、多工位/进程和持久身份。类型默认只补缺项；新格式按当前 NBT V2 保证预留/清理一致性，不必兼容旧格式。旧 formed、配置代、scheduler 标志、lease、epoch、ready 不能恢复为运行许可。 |
| [onLoad](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:285) | **所有组件 NBT 完整恢复 → load(FRESH/RESTORED) 成功 → scheduler 注册**。当前已有 MachineLifecycle.load；真实 host 只在 World/位置已附着后创建。 |
| [update](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:140) | 保留 server tick 结构检查、客户端同步 flush 和每 world tick 去重；用真实调度器连接 StructureRefresh，不直接在 TE tick 把所有并行机器改为串行执行。旧 currentTotalTick 初值 0 可能跳过 tick 0，是次要问题。 |
| [onChunkUnload / invalidate](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:300) | 先停止新任务/撤销 authority，真实 join，失效结构，unload 并在 finally 注销 scheduler；重复到达幂等。普通卸载保存进度，不能走 END、cancelAll 或退历史 IO。invalid TE 仍可用绑定 host 的服务器线程完成清理。 |
| [onControllerBroken](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:324) | 在 TE 移除前停止新任务并 join，再调用 MachineLifecycle.controllerBroken：broken 通知、当前进程取消、unloaded 完整聚合。即使某项清理失败，也尝试剩余清理、scheduler 注销和 Forge 移除。 |

PM [MachineInstanceImpl.onLoaded](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:348) 的 listener 抛错后仅把状态改成 UNLOADED，没有向已执行 listener 发卸载清理；新 MachineLifecycle.load 已提供 LOAD_FAILED 聚合清理，实际控制器必须调用它。scheduler.register 自身失败还需撤销已完成的 load/外部注册，不能只维护一个“注册过”布尔值。旧 [onSchedule](F:/clone/PrototypeMachinery/src/main/kotlin/impl/MachineInstanceImpl.kt:249) 逐系统吞异常并继续不能迁入；新组件和生命周期的错误传播/故障边界应继续生效。

客户端 [handleUpdateTag](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:217) 在查不到类型后仍访问未初始化 runtime（235、247），已有 runtime 时又没有核对类型 ID 变化。新客户端同步是独立的视图更新：先校验类型及包代/实例身份，创建合法客户端数据容器，应用完整/增量显示数据，方向/formed 改变触发重绘。服务器恢复、owner 初始化、生命周期 load 和 planned 授权不能由客户端包触发。[getUpdateTag](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:197) 与数据包保留方向、成型及同步组件效果，但不能把持久化身份/工位状态全部当公开显示数据复制；super.getUpdateTag 的具体数据路径需在实现时核验。

## 4. 24 向、破坏和拾取

[MachineBlock](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/MachineBlock.kt:54) 用六个 FACING 与 TE 的四个 twist 表达 24 向，meta 仅存 FACING（95），actual state 从 TE 读 twist/formed（143）。放置方向允许上/下和水平（105），方向工具 [applyToController](F:/clone/PrototypeMachinery/src/main/kotlin/common/item/ControllerOrientationToolItem.kt:282) 支持循环 24 向、正面旋转和换正面。保留完整方向效果，统一转换为 AE BlockOrientation 的 front/top；不只保留水平四向。

旧 [setTwist](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:92) 仅 markDirty/sync；工具分别修改 blockstate 和 twist，没有原子配置边界。旧 [withRotation](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/MachineBlock.kt:88) 只旋转 FACING，不旋转 top/twist，尤其 front 为 UP/DOWN 时，完整朝向不能由旋转后的 FACING 单独表达。新放置、方向工具、外部方块旋转、定义重载和 owner/权限变化都走同一服务器操作：禁止新 dispatch → join → StructureRefresh.invalidate → 修改完整方向/权限 → configureStructure 生成新代 → 新 capture/match/publish 成功后恢复调度；外部修改也不能留下半更新朝向。应明确镜像是否支持及其完整 front/top 映射，当前源码没有证明镜像已正确实现。

旧 [breakBlock](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/MachineBlock.kt:218) 在 super 移除 TE 前通知 broken，finally 保证 super 调用，这一顺序保留。没有自定义 getDrops/getPickBlock/harvestBlock 或控制器物品 NBT 恢复；普通 ItemBlock 是本次查到的接线。因此只能说“没有审查到自定义状态保留拾取”，不能推断 vanilla Ctrl 中键绝不携带 TE NBT。新库应显式规定普通破坏掉落、创造拾取和带数据搬运各自的持久化策略；任何重新放置都重新生成运行配置/epoch，并重新确认身份与当前位置、权限及结构，物品 NBT 不得携带活 scheduler/formed/owner epoch 许可。是否转移合法持久 machine UUID/owner 由搬运契约决定，须防止原实例与复制品同时获授权。

## 5. PN owner、运行身份与 planned 接线

[NetworkDeviceRuntimeComponentImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeComponentImpl.java:47) 在真实服务器线程用真实玩家初始化 owner，并使 dirty；[NBT](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeComponentImpl.java:81) 只保存 schema 和 machine/owner UUID，恢复后回到 PENDING_DISCOVERY、epoch=-1（99）。保留持久身份与瞬时权限的分离：RESTORED 是身份恢复成功，不意味着团队/研究/资源/结构已授权；新 runtime 必须重新解析当前 owner/team、当前世界与结构，产生新 epoch 和 ready 状态。

[RuntimeIntegration.onMachineLoaded](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeIntegrationImpl.java:348) 对 RESTORED 要求 identity ready 再注册；FRESH 通过按 dimension/position 的 pending placement 两种顺序配对真实玩家，随后 [initializeFresh](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeIntegrationImpl.java:723) 初始化 owner 后注册。这说明必须处理 load 与放置事件先后，但现有实现有缺陷：[onEntityPlace](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeIntegrationImpl.java:397) 缓存所有普通玩家放置，没有 machine block/TE/type/实例代过滤。虽然每次 epoch 解析结束 [清空未配对项](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeIntegrationImpl.java:773)，同 epoch 内同坐标后来的 fresh 机器仍可能消费无关旧 actor。不能描述为永久增长；需要修正的是错误配对与当前 epoch 内无关缓存。

最小替换是控制器专属的 fresh placement token：包含真实玩家 UUID、World/维度、位置、规范类型以及新 TE/装配代；在实际放置入口产生并由同一个新控制器消费一次。若 Forge 事件与 load 的顺序要求短暂等待，等待对象同样绑定具体 TE/代，卸载/破坏/覆盖/epoch 尾部撤销。不伪造 owner，不从旁边普通方块事件补 owner；缺少合法身份时保持不可调度/不可持久化并报告明确失败。

[unload/broken](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeIntegrationImpl.java:375) 都清 pending 和当前运行注册；[clearMachineRuntime](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/device/NetworkDeviceRuntimeIntegrationImpl.java:733) 聚合清研究材料源、设备 registry、machine planned runtime 的异常，这种尽力清理保留。新 identity 必须同时关联规范机器类型、固定 World/位置、活 TE 及代，避免老实例的迟到卸载删除新实例的注册。PN 原设备 registry 有 UUID 冲突统计、owner/team 状态重算与每 epoch 状态发布；合并仍应保留这些拒绝/重新授权效果，不能以组件存在作为 ready。

[NetworkMachinePlannedContextProviderImpl](F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network/impl/machine/NetworkMachinePlannedContextProviderImpl.java:73) 读取形成状态、TE/World、设备 UUID、当前 runtime，核对维度/world tick/owner/PN epoch（102），owner token 明确包括 owner UUID 和 PN epoch（129）。保留这些校验目的，但不能让新 worker 继续读取 World/TE 或活动 registry：在服务器 prepare 边界捕获不可变 owner/epoch/world/config/source 上下文；worker 只处理快照；resolve/commit 在服务器边界核对当前代和权限，再进行预留/提交/补偿。必须继续保留 PM prepare 与 resolve 的两次 join、稳定 source affinity lane、PN 向量预算/研究与团队条件、超时/卸载后的迟到任务隔离。当前 MachineLifecycle 的 VarHandle gate 不是 join，也没有 PN/planned 授权语义。

## 6. GUI、模型、预览与复杂渲染

[MachineBlock.onBlockActivated](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/MachineBlock.kt:175) 优先让方向工具处理，再从服务器打开 GUI；[MachineBlockEntity.buildUI](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:355) 原优先级是自定义 UIProvider → 类型 UI 注册 → 默认机器 UI。保留自定义与默认界面、工位/状态/属性、结构预览入口及服务器容器权限。R08/R09 要求用直接继承 AEBaseGui 的具体 Java GUI 与 AEBaseContainer 重实现，通过组合控件复用；旧 ModularUI panel、WidgetDefinition、Runtime JSON 和编辑器无需保留。公共定义按类型 ID 连接服务器容器/客户端工厂，不能让服务器注册加载客户端类或创造第二套 GUI 基类。

[ModelRegisterer](F:/clone/PrototypeMachinery/src/main/kotlin/client/registry/ModelRegisterer.kt:27) 为 blockstate 及 inventory 使用 controllerModelLocation 或默认模型，状态包含方向/twist/formed。[ClientProxy](F:/clone/PrototypeMachinery/src/main/kotlin/client/ClientProxy.kt:38) 注册模型 bake、世界投影、隐藏普通块模型、渲染 flush/frame/cache、扫描选择，并在 init 绑定 MachineBlockEntitySpecialRenderer（64）、动画、Bloom、资源重载/世界卸载 cache 清理（69）和投影方向按键（72）。这些不是装饰性可选能力，后续要接完整渲染/预览链；本次不宣称审完每个 renderer 内部算法。开发压力命令不属于正式玩法。

旧 [无限渲染包围盒/距离](F:/clone/PrototypeMachinery/src/main/kotlin/common/block/entity/MachineBlockEntity.kt:133) 保证大结构可见但不是新实现的性能证明。应按结构/模型实际范围、动画边界及缓存 owner 设计剔除，保留大模型与透明/Bloom 效果；TE 替换、invalidate、世界卸载、资源重载都释放相关缓存。客户端 formed 是显示状态，不授予服务器执行权限。

## 7. 下一阶段最小实现入口与验收

已有入口可直接装配，避免新建第二套结构匹配或生命周期：

| 当前入口 | 控制器需要做的具体接线 |
|---|---|
| [MachineInstance](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/MachineInstance.java:16) | 在独占装配边界创建一份机器，按规范定义补组件和属性默认值；已有 attachLifecycle、onSchedule 和持久化边界检查。当前实例不自带机器类型/World/scheduler。 |
| [ControllerLifecycleHost](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/machine/ControllerLifecycleHost.java:31) | 附着后的真实服务器 TE 创建，固定 World/identity；configureStructure（56）每次轮换配置身份，revokeStructure（65）撤销。自身不做 join，调用者先 join/invalidate；其 structureConfiguration（72）检查当前位置/活 TE/World 归属。 |
| [MachineLifecycle](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/api/machine/lifecycle/MachineLifecycle.java:54) | 构造即绑定实例；load（79）、controllerBroken（199）、unload（177）接 TE 生命周期。结构成型只来自当前结构发布，worker 不携带该 lifecycle 作授权。 |
| [StructureRefresh](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/structure/StructureRefresh.java:38) | 一个 loaded lifecycle 配一份 coordinator。服务器 prepare（47）→ worker Request.evaluate（173）→ join 后服务器 publish（78）；worker 异常走 fail（102）。publish=false 包含旧结果或当前失稳，当前刷新必须重试/明确暂停后才释放发布边界，不能直接恢复旧调度许可。 |
| [公开仓室发现](F:/new/PrototypeEngineering/src/main/java/com/circulation/prototype_eng/machine/ControllerLifecycleHost.java:90) | 已有 resource/energy capability 查询，StructureRefresh 原子发布结构树与新 MachineHatches。实际控制器复用它，不能退回 PN 的内部具体仓过滤。 |

建议本阶段只把规范机器定义/确定注册、空构造 TE/暂存 NBT、真实 host/lifecycle/refresh、完整方向、放置 owner token、GUI/渲染绑定点接起来；真实 scheduler 后端与 planned 仍是必须完成的后续范围，不能以“先能 tick”为由删掉能力。绑定点必须能容纳它们需要的独占 dispatch、双 join 和迟到任务撤销，不能先设计成难以替换的无屏障直接 tick。

实现前优先处理的高风险项：无 World 的构造/恢复；initialized runtime 与二次 NBT；普通 placement actor 错配；方向仅修改显示而旧配置仍获授权；开放注册/半批发布；load 或 scheduler 注册失败的清理；客户端未知/换类型包；卸载旧实例误删新代运行注册；planned worker 读取实时 World。空 recipeGroups 在隐式工位中表示 accept-all，而 [PostInit 索引](F:/clone/PrototypeMachinery/src/main/kotlin/PrototypeMachinery.kt:130) 为空，另需用统一池选择语义修正；不能把不一致写成新的合法默认行为。

后续静态/逻辑验收至少覆盖：整批注册拒绝和确定顺序、零类型 TE 注册、重复/晚到声明；附着前 read 与 fresh/restored 的两种顺序；默认属性不覆盖恢复值；自定义组件/GUI/模型真实绑定；24 向及 UP/DOWN 外部旋转；所有方向/owner/定义变化拒旧 worker；结构失稳 publish=false 的调度暂停；load/注册失败聚合；unload/invalidate/break 重入；同 epoch 同坐标旧 placement 与旧 unload 拒新实例；保存 join、身份复制冲突、客户端未知类型/旧包。新增 Java 逐文件 IDEA MCP errorsOnly:false，记录错误/警告/弱警告；IDEA 和逻辑测试不能替代 World/TE/Forge 实际运行证据。按照当前用户约束不自行启动游戏，运行证据保持待验证，并由主任务同步 INDEX/implementation-status 后阶段本地提交、不推送。
