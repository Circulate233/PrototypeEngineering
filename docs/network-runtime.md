# 网络角色、整批发布与配方门限

本阶段把[网络规划](network-planning.md)接入实际机器生命周期和服务器调度。使用方仍自行声明角色、等级、资源和机器；没有内置正式机器。当前提供共同网络条件及 owner 侧每 tick 预算/lease 凭据；普通配方、研究和正式 planned 的自动消费链仍须继续迁移。

## 声明与恢复

机器声明 `MachineOwnership.TYPE`，再声明 `MachineNetworkDevice.component(id, source)`。source 是标准 Java BiFunction，接收当前 MachineInstance 和准确身份 Registration，返回既有 `NetworkDeviceSnapshot`；因此动态资源、数据中心已有研究数等可以直接读取使用方自己的组件，不需要另一份机器类型或接口镜像。source 只在 owner 线程运行，必须只读；它返回的身份、loadOrder 和 formed 必须与实际宿主一致。

MachineAssembly 为该网络组件使用 `recreated`，身份仍用 MachineOwnership 内置 codec。新实例在工厂阶段安装永久的配方条件，初始关闭；按组件依赖顺序加载 ownership 后才登记网络角色。恢复不保存或复用旧 epoch、权限或角色 Registration，scanner 也继续 recreated。自定义宿主重载显式提供 ownership descriptor 和 NetworkRuntime，不依赖内置 TileEntity。

组件加载时捕获自己的准确绑定，重复角色或重复绑定拒绝。卸载/部分加载失败只关闭本次句柄；永久 gate 保持关闭，不通过删除 gate 变成无条件允许。组件被非法移除也不能借旧句柄授权。

## NetworkRuntime 的整批边界

每个 NetworkRuntime 绑定一个 MachineIdentityRegistry、冻结的 NetworkResourceRegistry、OwnerTeamResolver 和 owner 线程检查。资源声明在 post-init 的 NetworkResourceRegistrationEvent 注册并冻结，内置服务器使用全局表；独立宿主可传自己的冻结表。`register(identity, source)` 只接受同一 identity registry 的当前身份；一个运行实例只登记一个网络角色。source 可以返回空表示当前没有可用宿主，也可以返回 Capture（冻结 snapshot 和 owner-only 有效性证明）。内置组件检查生命周期、准确组件绑定、formed，以及实际结构宿主的配置/World/TE 有效性。

`beginTick(tick)` 开始实际服务器 tick 后才能 refresh。refresh 首先撤销旧发布，再捕获唯一设备、解析队伍并规划，最后重验全部证明和 identity revision，单次 volatile 发布。异常直接传播，旧结果失效；证明过期或显式 revoke 返回 false，不发布部分许可。同队伍账本在本 tick 首次成功发布后保留，revoke 和重捕获都不补充额度，详见[预算边界](network-budgets.md)。

`refresh(participantSource)` 提供当前捕获 epoch 给参与者来源，供后续完整参与者集成。来源必须返回 ACTIVE、版本经过验证且在此 owner 边界内保持稳定的资源投影；本阶段内置服务器尚未安装参与者来源，未冒充已实现其注册、工作单元、材料来源或版本提交协议。

Registration 的 `allowsNetworkWork()` 与 `state()` 只读取已发布状态、准确身份和修订号，可以在配方 worker 读取。`state()==null` 表示待发现、失效、冲突或卸载。所有 capture、mutation、close 和 revoke 都限定 owner 线程，worker 不读取 World / FTB。

身份 registry 新增单调 revision。每次有效登记（同位置替换算一次）、有效卸载和首次 close，在修改前进行 long 溢出预检，完成索引修改后发布新 revision。重复登记和旧句柄再次 close 不增加修订。这样 A→加载副本 B→卸载 B 后，即使 A 恢复 unique，旧批次也不能恢复；新增更高等级中心等成员变化也使整批旧计划失效。

关闭旧网络句柄只按准确运行对象删除自己。它若不属于当前发布，不撤销新代发布；同位置替换后的迟到卸载和部分 load 失败因此不会误停新实例。NetworkRuntime.close 永久撤销会话并关闭全部句柄，后续 close 幂等，不允许旧会话重新 refresh/register。

## 调度与服务器接线

`TaskScheduler.tick(Runnable prepare)` 沿用原普通调度流程：入场清理 owner FIFO；每次实际 MAIN action 前调用 prepare；所有 MAIN 完成后、采集 CONCURRENT 元数据前再调用 prepare。prepare 处于受限制的 owner 捕获阶段，不能重入 tick/close、登记/注销或请求 owner 边界；可以通过已有线程安全 submit 排入后续批次。默认无参 tick 保留原行为。

内置 prepare 调用 NetworkRuntime.refresh。因此 MAIN 中发生的队伍、角色或身份变化会在下一 MAIN / worker 前重新捕获。默认 CONCURRENT 机器使用共享的阶段批次；MAIN 较多时会增加捕获次数，这是对任意 MAIN 回调可变更外部状态的明确处理，尚未取得性能压测证据，不能通过复用已失效批次省略这一步。

prepare 失败沿用 scheduler 的永久 fault、真实 join 与完整清理协议。服务器每次 END 事件只 begin 一次网络 tick，在 scheduler 实际 join 后的 finally 调用 endTick，撤销发布并关闭账本，包括异常退出；旧 receipt 仍可原代补偿。当前网络计算在 owner 同步规划/发布，普通 CONCURRENT worker 与亲和 lanes 保持；这不等于正式 planned 的两次 worker join 已完成。

服务启动顺序为 scheduler → identity → network，启动失败沿相反依赖顺序清理；正常停止先等待 scheduler 接受的工作实际退出，再关闭 network 和 identity。ServerNetworkRuntime 绑定准确 MinecraftServer 并默认使用 FtbOwnerTeamResolver。Mod 依赖声明同步加入 PN 原精确 FTBLib 5.4.7.2 / FTB Utilities 5.4.1.131，版本与开发构件一致。

## 配方与普通组件

`MachineInstance.addRecipeWorkGate(BooleanSupplier)` 仅用于未发布装配，支持多个独立条件取 AND；无网络角色的普通机器不自动增加网络限制。gate 必须读线程安全运行状态，不能在 worker 查询外部队伍或 World。`canProcessRecipes()` 结合原生命周期/成型门限和这些附加条件，供普通扫描/工位系统使用。

低层 scanner、工位直接 admission/tick 和 RecipeProcess 的阶段边界也检查 `recipeWorkAllowed()`，不能绕过网络条件。原低层宿主自行管理 formation 的契约仍保留；这部分不会额外强迫独立执行入口成型。已经获取并提交的事务照常 complete，再在后续阶段或工位暂停，不退历史消耗、不重 START。

临时失权不调用 MachineLifecycle.retire：普通组件继续运行，保存、卸载和显式取消清理仍可执行；新批次恢复资格后，原进度继续。网络 ACTIVE 状态只满足网络条件，不能替代尚未实现的研究/预算条件或正式 worker 提交许可。

## 验证与继续范围

新增 12 项逻辑测试，覆盖身份复制 ABA、prepare 的阶段顺序/失败、整批撤销与证明过期、跨线程读取/拒绝修改，以及真实 assembly、FE、scanner、工位、进程暂停恢复和部分 load 失败后新代继续工作。恢复用真实 FE 余额验证不会重复 START。完整计数与逐文件 MCP 证据见[实施进度](implementation-status.md)；没有初始化游戏或运行客户端/服务端。

canonical 注册、每 tick 账本及 owner 工作凭据已接入。后续仍需组件单物品供给/研究阶段限额、普通配方/研究预算消费、数据中心存储、完整参与者，以及 planned 两次 join 与提交补偿。capture epoch 标识发布，真实 tick 决定账本，两者已经分开。旧语义见[计算资源迁移边界](network-resources.md)，当前行为见[预算说明](network-budgets.md)。
