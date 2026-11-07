# 持久机器身份与运行实例登记

本阶段迁入 PN 的 machine UUID、真实 owner 和重复身份拒绝语义，接到现有 MachineAssembly / MachineLifecycle。身份组件由使用方显式声明；普通机器不自动获得 owner，也没有新增正式机器或玩法内容。队伍解析、网络中心选择、研究、预算和配方工作授权仍待接入。

## 公开入口

| 入口 | 职责 |
|---|---|
| `api.machine.identity.PersistentMachineIdentity` | 保存 machineId / ownerId，严格编解码身份 NBT。 |
| `api.machine.identity.MachineOwnership` | 组件的创建、恢复和加载/卸载登记，使用已有生命周期接口。 |
| `api.machine.identity.MachineIdentityRegistry` | 一个服务器会话内按世界对象、位置和准确运行实例登记；处理替换及重复 UUID。 |
| `MachineIdentityRegistry.Registration` | 本次加载的可撤销句柄；close 只撤销自身。 |
| `machine.ServerMachineIdentities` | 内置 Forge 宿主的服务器会话适配。 |

MachineType builder 显式调用 `.component(MachineOwnership.TYPE)` 即可使用内置身份组件。MachineAssembly 已绑定其 codec，不需要重复绑定，也不会因此自动安装组件。外部宿主可以声明自己的 `ComponentType<MachineInstance, MachineOwnership>`，通过公开构造函数提供 registry，再用 `MachineOwnership.CODEC` 绑定持久化。

`MachineLifecycleHost.worldIdentity()` 默认返回实际 World。非 Forge 宿主须提供生命周期内稳定的世界对象令牌；它不能只是维度编号。`placementPlayer()` 只表示可信的真实玩家放置，内置控制器排除 FakePlayer，不通过区块加载或旧显示状态推测 owner。

## 创建、恢复和搬运

FRESH 加载必须有真实放置者，创建随机 machine UUID 并保存其 owner UUID；缺少放置者直接使本次装配失败。RESTORED 只使用 codec 恢复的身份，即使带数据物品由另一位玩家放下，也不改写 machine UUID 或 owner。普通不声明身份组件的机器不受这个放置条件限制。

身份 NBT version 为 1，且只能包含 `version` int 与 `machineMost`、`machineLeast`、`ownerMost`、`ownerLeast` 四个 long。缺字段、错类型、额外字段和未知版本均拒绝，不用默认零 UUID 或放置者替代损坏身份。team、epoch、lease、ready 与登记句柄不保存。

恢复完成并通过整机校验后，onLoaded 才向 registry 登记。后续监听器加载失败仍由既有 LOAD_FAILED 路径关闭本次句柄；普通卸载、破坏和失败清理也使用该句柄。MachineOwnership 不能在同一实例上二次 load。

## 替换与复制冲突

登记的位置键是 World 对象引用加 BlockPos 编码，不含 machineType。同位置的新运行实例会撤销旧句柄；旧卸载回调再次 close 时，不会删除新实例。runtime token 使用准确 MachineInstance 对象，同一活 runtime 重复登记必须保持身份、世界和位置完全一致。

同 machine UUID 同时出现多个活登记时，所有副本都失去唯一性，不能任选一个继续授权。副本卸载到仅剩一个时，剩余登记恢复唯一性。同位置替换旧代与不同位置复制是不同情况；世界与 runtime 都按引用区分，不调用业务 equals 判断代。

`uniqueRegistrations()` 在 owner 线程返回按加载顺序排列的不可变成员列表。列表中的句柄仍可撤销，因此这不是永久有效的权限快照。`current()` / `unique()` 可以跨线程读取；registry 的所有变更和枚举只在所属服务器线程执行。未来 worker 的提交仍须重验当前登记和独立权限代，唯一身份本身不授予配方工作许可。

内置会话在 scheduler 建立后启动，启动失败清理 scheduler；停止先等待 scheduler 接受的工作真实退出，再关闭 registry。registry close 永久撤销本会话全部登记，后续服务器必须创建新 registry。

## 验证与后续边界

14 项逻辑测试覆盖重复副本及恢复唯一性、同位置不同类型替换、旧卸载隔离、相等对象的引用区分、跨线程约束、永久关闭、严格 NBT，以及真实 assembly/lifecycle 的搬运和部分加载失败清理。完整构建和逐文件 IDEA MCP 证据见[实施进度](implementation-status.md)。没有初始化游戏环境；真实 Forge 放置、搬运和区块卸载仍未运行验证。

后续队伍 provider 与 FTB 适配必须查询当前 owner 的持久队伍关系，离线玩家并不自动失效。工作权限应同时约束新扫描和已有工位推进；临时失去资格不能退休整个机器或阻止普通组件恢复。网络中心、预算、研究和 planned 两次 join 按[PN 审计](pn-identity-migration-audit.md)继续迁移。

原生 TileEntity 的迟到 onChunkUnload 只有 this，无法证明异常 loader 复用同一 TE 时通知属于哪一代；当前句柄解决准确旧代清理，不臆造无法获得的卸载代证明，也不宽泛复活已退休实例。
