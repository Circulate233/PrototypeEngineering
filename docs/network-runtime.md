# 网络角色、整批发布与配方门限

## 网络中心控制器（2026-11-13）

本轮仅实现控制器与Java/ZS创建示例，不制作HTML或GUI。`api.network.NetworkCenter`是网络等级与供给职责接口；自定义机器运行时可独立实现它。默认`api.machine.NetworkCenterController`直接继承通用`MachineInstance`，不继承多线程或跨配方控制器，不声明配方、工位或`NetworkRecipeWork`。它的`requiresTick()`返回false，物理控制器不将它登记到机器业务tick队列；原有物理宿主、结构成型检查及网络发布服务仍负责加载、结构变化、卸载和选中中心。

每队只有一个活动中心，沿既有规则选择最高等级、同级按持久机器UUID；其他已成型中心为`SAME_TYPE_CONFLICT`。默认控制器提供正整数网络等级和开发者配置的 THREAD 额度；只计入活动中心，并与合格计算机及外部参与者供给相加。等级和供给属于当前机器定义，在该加载代内保持不变；所有权沿既有`MachineOwnership`保存，网络登记在加载时建立、卸载时撤销。`NetworkCenter.networkResources()` 按只读契约交接资源向量，默认控制器复用构造时建立的表。

Java声明时给机器添加`MachineOwnership.TYPE`和`NetworkCenter.DEVICE`，装配指定`type -> new NetworkCenterController(type, level, threadCapacity, energyPerTick)`并将`NetworkCenter.DEVICE`标为recreated。自定义实现提供`NetworkCenter`的等级、供给和非负FE定义并使用相同role组件；无业务tick的自定义运行时同样覆写`requiresTick()`，无需继承默认控制器。旧构造器省略的线程额度为0，两参数入口也为零费用。

中心只在生效时支付当前服务器 tick 的费用，网络重复刷新不重复扣费；待机冲突中心不收费，缺能中心从本次候选排除后重新选择。费用预留与网络发布共同提交，证明过期或发布失败时释放/补偿；不增加机器业务 tick。中心建立网络，因此不要求自身已有网络许可。

ZS提供完整快捷入口：

```zenscript
MachineType.networkCenter("example:center", "example.center", "example:center_shape", 1, 4, 4)
    .register("network-center-v1");
```

入口使用相同Java契约与装配工厂，返回原`MachineType`声明句柄。网络中心不调用`processing`或声明配方工位。Java可直接用的注册示例为`run/visual-examples/src/main/java/pe/visual/examples/NetworkCenterExamples.java`；ZS示例为`run/scripts/15_pe_run_network_machines.zs`，结构在`30_pe_run_network.zs`。两例均为控制器上方一个铁块、后方能源仓，等级1、4线程额度，生效时4 FE/t。装有run addon及脚本后可取得`pe_run_tests:java_network_center_controller`或`pe_run_tests:net_center_controller`。

## 普通控制器的联网默认值

`MachineInstance.requiresNetwork()` 先取 `MachineType.Builder.requiresNetwork(boolean)` 显式值，其次把显式网络角色视为主动接网，最后使用 `config/prototype_eng/controllers.cfg` 中 `network.defaultRequiresNetwork=false`。ZS 同名链式方法可显式覆盖；所有普通控制器共用基类门限。需要网络且无角色时由装配添加等级0、无供给的普通 DEVICE；默认false不会强迫普通机器入网。显式false仅关闭共同配方门限，不移除额外网络资源需求。专用网络中心、研究站保留固定职责。

真实玩家新放置的物理机器即使当前离线也保存所有权，保证之后更改默认值时可据此入网；自定义非方块宿主继续显式提供身份。此前未保存身份的旧离线控制器需要换用未携带旧机器存档的新控制器，不能在读档时猜测所有者。配置启动时读取；裸 `new MachineInstance()` 独立库执行对象不应用服务器默认值。详细Java/ZS用法见 [API](../api.md#控制器是否需要网络)。

以下为既有网络运行时记录。

本阶段把[网络规划](network-planning.md)接入实际机器生命周期和服务器调度。使用方仍自行声明角色、等级、资源和机器；没有内置正式机器。当前提供共同网络条件、每 tick 预算/lease，以及普通配方的具体研究/数值消费者；研究站和完整正式 planned 仍须继续迁移。

## 声明与恢复

机器声明 `MachineOwnership.TYPE`，再声明 `MachineNetworkDevice.component(id, source)`。source 是标准 Java BiFunction，接收当前 MachineInstance 和准确身份 Registration，返回既有 `NetworkDeviceSnapshot`；因此动态资源、数据中心已有研究数等可以直接读取使用方自己的组件，不需要另一份机器类型或接口镜像。source 只在 owner 线程运行，必须只读；它返回的身份、loadOrder 和 formed 必须与实际宿主一致。

MachineAssembly 为该网络组件使用 `recreated`，身份仍用 MachineOwnership 内置 codec。角色在工厂阶段绑定到共同基类，所需网络许可初始关闭；按组件依赖顺序加载 ownership 后才登记网络角色。恢复不保存或复用旧 epoch、权限或角色 Registration，scanner 也继续 recreated。自定义宿主重载显式提供 ownership descriptor 和 NetworkRuntime，不依赖内置 TileEntity。

组件加载时捕获自己的准确绑定，重复角色或重复绑定拒绝。卸载/部分加载失败只关闭本次句柄；需要网络的控制器继续受基类门限限制。组件被非法移除也不能借旧句柄授权。

## NetworkRuntime 的整批边界

每个 NetworkRuntime 绑定一个 MachineIdentityRegistry、冻结的 NetworkResourceRegistry、OwnerTeamResolver 和 owner 线程检查。资源声明在 post-init 的 NetworkResourceRegistrationEvent 注册并冻结，内置服务器使用全局表；独立宿主可传自己的冻结表。`register(identity, source)` 只接受同一 identity registry 的当前身份；一个运行实例只登记一个网络角色。source 可以返回空表示当前没有可用宿主，也可以返回 Capture（冻结 snapshot 和 owner-only 有效性证明）。内置组件检查生命周期、准确组件绑定、formed，以及实际结构宿主的配置/World/TE 有效性。

`beginTick(tick)` 开始实际服务器 tick 后才能 refresh。refresh 首先撤销旧发布，再捕获唯一设备、解析队伍并规划，最后重验全部证明和 identity revision，单次 volatile 发布。异常直接传播，旧结果失效；证明过期或显式 revoke 返回 false，不发布部分许可。同队伍账本在本 tick 内保留已消费、预留和操作键，成功重捕获只按供给变化调整容量；新增供给立即可用，供给下降再恢复不抹去已消费量。计算机与活动数据库先取得标准并发，研究站推进时享有同样优先级，详见[预算边界](network-budgets.md)。

`refresh(participantSource)` 提供当前捕获 epoch 给参与者来源，供后续完整参与者集成。来源必须返回 ACTIVE、版本经过验证且在此 owner 边界内保持稳定的资源投影；本阶段内置服务器尚未安装参与者来源，未冒充已实现其注册、工作单元、材料来源或版本提交协议。

Registration 的 `allowsNetworkWork()` 与 `state()` 只读取已发布状态、准确身份和修订号，可以在配方 worker 读取。`state()==null` 表示待发现、失效、冲突或卸载。所有 capture、mutation、close 和 revoke 都限定 owner 线程，worker 不读取 World / FTB。

身份 registry 新增单调 revision。每次有效登记（同位置替换算一次）、有效卸载和首次 close，在修改前进行 long 溢出预检，完成索引修改后发布新 revision。重复登记和旧句柄再次 close 不增加修订。这样 A→加载副本 B→卸载 B 后，即使 A 恢复 unique，旧批次也不能恢复；新增更高等级中心等成员变化也使整批旧计划失效。

关闭旧网络句柄只按准确运行对象删除自己。它若不属于当前发布，不撤销新代发布；同位置替换后的迟到卸载和部分 load 失败因此不会误停新实例。NetworkRuntime.close 永久撤销会话并关闭全部句柄，后续 close 幂等，不允许旧会话重新 refresh/register。

## 调度与服务器接线

`TaskScheduler.tick(Runnable prepare)` 沿用原普通调度流程：入场清理 owner FIFO；每次实际 MAIN action 前调用 prepare；所有 MAIN 完成后、采集 CONCURRENT 元数据前再调用 prepare。prepare 处于受限制的 owner 捕获阶段，不能重入 tick/close、登记/注销或请求 owner 边界；可以通过已有线程安全 submit 排入后续批次。默认无参 tick 保留原行为。

内置 prepare 调用 NetworkRuntime.refresh。因此 MAIN 中发生的队伍、角色或身份变化会在下一 MAIN / worker 前重新捕获。普通 CONCURRENT 机器使用共享的阶段批次；带 NetworkRecipeWork 的机器由服务器工厂转入 owner 协调、来源 lane 并行，见[配方消费者](network-recipes.md)。MAIN 较多时会增加捕获次数，这是对任意 MAIN 回调可变更外部状态的明确处理，尚未取得性能压测证据，不能通过复用已失效批次省略这一步。

prepare 失败沿用 scheduler 的永久 fault、真实 join 与完整清理协议。服务器每次 END 事件只 begin 一次网络 tick，在 scheduler 实际 join 后的 finally 调用 endTick，撤销发布并关闭账本，包括异常退出；旧 receipt 仍可原代补偿。当前网络计算在 owner 同步规划/发布，普通 CONCURRENT worker 与亲和 lanes 保持；这不等于正式 planned 的两次 worker join 已完成。

服务启动顺序为 scheduler → identity → network，启动失败沿相反依赖顺序清理；正常停止先等待 scheduler 接受的工作实际退出，再关闭 network 和 identity。ServerNetworkRuntime 绑定准确 MinecraftServer 并默认使用 FtbOwnerTeamResolver。Mod 依赖声明同步加入 PN 原精确 FTBLib 5.4.7.2 / FTB Utilities 5.4.1.131，版本与开发构件一致。

## 配方与普通组件

`MachineInstance.addRecipeWorkGate(BooleanSupplier)` 仅用于未发布装配，支持多个额外条件取 AND；网络门限直接由共同基类根据上述配置和显式设置执行。gate 必须读线程安全运行状态，不能在 worker 查询外部队伍或 World。`canProcessRecipes()` 结合原生命周期/成型门限和这些附加条件，供普通扫描/工位系统使用。

低层 scanner、工位直接 admission/tick 和 RecipeProcess 的阶段边界也检查 `recipeWorkAllowed()`，不能绕过网络条件。原低层宿主自行管理 formation 的契约仍保留；这部分不会额外强迫独立执行入口成型。已经获取并提交的事务照常 complete，再在后续阶段或工位暂停，不退历史消耗、不重 START。

临时失权不调用 MachineLifecycle.retire：普通组件继续运行，保存、卸载和显式取消清理仍可执行；新批次恢复资格后，原进度继续。网络 ACTIVE 状态只满足共同网络条件；NetworkRecipeWorkSystem 另行检查实际选中数据中心、并行数值向量及阶段提交许可。

## 验证与继续范围

新增 12 项逻辑测试，覆盖身份复制 ABA、prepare 的阶段顺序/失败、整批撤销与证明过期、跨线程读取/拒绝修改，以及真实 assembly、FE、scanner、工位、进程暂停恢复和部分 load 失败后新代继续工作。恢复用真实 FE 余额验证不会重复 START。完整计数与逐文件 MCP 证据见[实施进度](implementation-status.md)；没有初始化游戏或运行客户端/服务端。

canonical 注册、每 tick 账本、owner 工作凭据、物理数据中心存储与普通配方研究/数值消费已接入。后续仍需组件单物品供给、研究阶段限额/预算消费、数据中心自动云同步及下载交互、完整参与者，以及完整 planned prepare。capture epoch 标识发布，真实 tick 决定账本，两者已经分开。旧语义见[计算资源迁移边界](network-resources.md)，当前行为见[预算说明](network-budgets.md)。
