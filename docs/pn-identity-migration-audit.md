# PN 身份、队伍与工作授权迁移审计

参考仓库 `F:/new/PrototypeNetwork`，源码根 `src/main/java/com/circulation/prototype_network`。保留行为目的，修改依赖附属项目的接线与准确代管理；没有将旧 XxxImpl 包层级直接迁入新项目。

## 已迁入身份阶段

| 旧实现 | 原行为与问题 | 本阶段处理 |
|---|---|---|
| `impl/device/NetworkDeviceRuntimeComponentImpl` | 持久 machine/owner；恢复后工作状态及 epoch 等待重新发现。 | MachineOwnership 复用整机 codec，身份独立保存，登记与权限不从 NBT 恢复。 |
| `impl/device/NetworkDeviceRuntimeRegistryImpl.register/remove` | 替换使旧组件卸载，但 remove 按位置删除；旧回调可能误删新实例。LocationKey 含 type，同位置换类型可残留。 | World 引用 + position 定位；Registration 准确撤销自身，runtime 引用索引拒绝带着同实例改身份。 |
| 同类 `snapshots/resolve` | 重复 machine UUID 全部拒绝，保留加载顺序；不能任选副本。 | 增量维护 UUID 冲突组，最后一份副本退出后恢复唯一性；稳定登记顺序。 |
| 同类 `validateLoaded` | identity-ready 和 TE 非 invalid 不足以证明准确 World、TE 与当前代。 | 实际 controller 的宿主校验继续负责世界有效性；registry 只记录准确实例，身份不代替世界或权限证明。 |

## 后续接线必须修正

`impl/device/NetworkDeviceRuntimeIntegrationImpl` 的 pending、planned context、station 缓存也存在按位置或 UUID 无条件清理的问题。迁移时它们必须带准确登记/权限代；旧卸载不能删除新代的数据。旧 snapshots 调用位于 try 外，错误可能绕过后续撤权；新的整批发布不能在捕获失败后继续使用上次许可。旧 onWorldUnload 首次清理抛错可跳过其他清理，必须沿用本项目已有异常聚合协议。

`impl/device/NetworkDeviceTickPlannerImpl` 在 accepted owner 分支延迟填入 workStates，用该 map 去重会漏掉重复 machine UUID。新规划以本 tick 捕获的唯一登记为输入，仍须在发布/提交重验当前代；不能靠规划结果表充当输入去重表。

旧工作授权主要接 planned factory 和研究站，未找到所有普通 PM 配方都统一门控的证据。合并后新扫描与已有工位推进要使用同一当前代工作许可，不能只给 scanner 增加 ready 谓词。临时缺少队伍资格暂停配方工作，保留普通组件和后续重新获权路径，不调用整机 retire。

## FTB 队伍语义

旧 `api/team/OwnerTeamResolverImpl` 直接调用 FTBLib，无反射：

- 先检查 Universe.loaded，通过 Universe.get().getPlayer(ownerUUID) 查询持久玩家；没有在线检查，离线玩家可以正常解析。
- 接受有效 PLAYER / SERVER 队伍，SERVER 允许没有队长；owner 必须属于当前成员集合。
- Universe 未加载、未知 owner、无队伍、无效队伍或不支持的类型返回拒绝；损坏成员/队长 UUID 是错误，不作为正常拒绝吞掉。
- 返回当前队伍 UID、类型、成员及可选队长的独立快照；保存的 team 或新的放置者不能替代这次查询。

旧依赖为 `curse.maven:ftb-library-237167:2985811` 与 `curse.maven:ftb-utilities-237102:3157548`。队伍/规划阶段已配置两依赖，新增公开 OwnerTeamResolver 与直接 FtbOwnerTeamResolver；没有无条件允许的队伍替身或名称镜像接口。capture 每批每 owner 一次解析并检查成员一致性，worker plan 只读快照。服务器发布和配方许可仍未接，详见[网络规划](network-planning.md)。

## planned 边界

PM `impl/scheduler/planned/PlannedParallelCoordinator.kt` 的 prepare / resolve 两次 join、稳定 source lane 仍为保留要求。owner 线程捕获当前 team、登记与权限 epoch；worker 不读 World / FTBLib；commit 再检查当前代，迟到工作按真实完成与补偿协议处理。PN 的中心选择、向量预算、研究和计算资源须在这些边界接线，身份唯一性不能代替它们。

本审计的旧缺陷来自源码审查，未启动旧项目复现。当前已完成范围和逻辑测试证据见[身份说明](machine-identity.md)及[实施进度](implementation-status.md)；本文件的后续项不代表已经实现。
