# 队伍查询与网络设备规划

本阶段迁入 PN 的 owner→team 查询、中心/数据中心选择、设备状态和计算资源贡献算法。对外入口为 `api.team`、`api.network` 和 `api.network.resource`，接口只保留实际可替换的 `OwnerTeamResolver`；结果、快照、规划器和修饰器使用具体类或 record。没有声明正式机器、研究内容或平衡数值。

## 当前入口与边界

`NetworkPlanner.capture(resources, epoch, devices, contributions, resolver)` 在服务器 owner 线程调用，resources 必须是冻结的 NetworkResourceRegistry。宿主先取得当前唯一登记的设备和 ACTIVE 参与者，并保留准确登记、配置和参与者版本以供发布/提交复核；快照不含 World、TE 或可变运行状态。capture 会复制输入列表、检查设备 UUID 唯一性、参与者 UUID 唯一性和 epoch，并对照注册表校验设备、修饰器及参与者的全部资源元数据，再为每个不同 owner 调用一次 resolver。

同队伍结果的类型、成员和可选队长必须一致；已查询成员自己的结果也必须指向同一个队伍，不能一边声称它是成员、一边拒绝或归到另一队伍。异常或矛盾使整次 capture 失败，不产生部分输入。成员很多的队伍也只对本批实际 owner 查询一次。

`NetworkPlanner.plan(inputs)` 只读取已冻结输入，可以放在 worker 执行。它不再查询 FTB，不读取世界，不改机器。结果包含 epoch、每设备 `NetworkWorkState`、可解析设备的 teamId 和每个有活动中心的队伍选择。公开结果表使用 fastutil backing 与保护 `Map.Entry.setValue` 的只读包装；数量表的 primitive entries 同样不可变。

NetworkRuntime 现已把整批结果发布到显式 MachineNetworkDevice，并作为扫描、已有工位及进程阶段共同的网络条件；准确登记/修订号和宿主证明重验见[运行时接线](network-runtime.md)。`ACTIVE_DEVICE` 等仍是拓扑决策，不能替代研究条件、预算预留或正式 planned worker 的提交许可。

## FTB 查询

外部宿主使用 `new FtbOwnerTeamResolver(server)`，作为 capture 的 resolver；其他队伍来源实现同一个公开接口。适配器校验准确服务器线程及 Universe.server 身份，通过持久 `Universe.getPlayer(UUID)` 查询 owner，没有在线要求。接受有效 PLAYER / SERVER，SERVER 可以没有队长；owner 与可选队长必须位于捕获成员集合。

Universe 未加载或不属于本服务器、未知 owner、无队伍、无效队伍和不支持的类型返回结构化拒绝。损坏成员 UUID、跨 Universe 的 team 等非法状态抛错，不能变成常规空队伍。拒绝结果不提供有效 team payload；不会每 tick 刷重复告警日志。FTBLib 自身 isValid 已排除真实无队长 PLAYER；公共结果工厂沿用旧 API，对外部 provider 不增加这条额外限制。

开发依赖使用 PN 原坐标 `curse.maven:ftb-library-237167:2985811` 与 `curse.maven:ftb-utilities-237102:3157548`。仓库声明拆入 `gradle/scripts/repositories.gradle`，在 MDG 首次解析之前加载；Curse 坐标仅走 CurseMaven / CleanroomCurseMaven，防止插件仓库错误承接。其余依赖声明保留原执行阶段。公开队伍 API 不引用 FTB 类型；内置 ServerNetworkRuntime 现默认使用准确 server 绑定的 FTB resolver，Mod 声明所需版本，无无条件允许的后备 provider。

## 保留的选择与状态规则

| 情况 | 结果 |
|---|---|
| 多个已成型网络中心 | 最高等级优先，同级加载最早者生效；其他中心为 SAME_TYPE_CONFLICT。 |
| 多个符合中心等级的已成型数据中心 | 最高等级，再已存 distinct research 数最多，再加载最早。满库仍可选；不按空余容量选择，也不合并其他中心数据。 |
| 非中心设备等级高于活动中心 | CENTER_LEVEL_INSUFFICIENT。 |
| 没有活动中心 | 已成型设备 NO_ACTIVE_CENTER，未成型设备 UNFORMED；参与者不能单独创建网络。 |
| owner 当前队伍拒绝 | OWNER_TEAM_REJECTED，优先于结构状态；不加入有效队伍表。 |
| 符合条件的 COMPUTER / RESEARCH_STATION / DEVICE | ACTIVE_DEVICE；只有 COMPUTER 被动贡献其计算资源。 |

NETWORK_CENTER 和 RESEARCH_STATION 保留正等级约束。COMPUTER、DATA_CENTER、通用 DEVICE 允许声明等级 0；它们仍需当前活动中心才能进入本规划的有效网络。旧 DATA_CENTER 类型允许 0，但旧 runtime candidate 拒绝 0，存在自相矛盾；新 snapshot/selector 统一接受声明范围，不声称旧实现已经支持无中心活动数据中心。

`NetworkDeviceSnapshot` 表示已加载设备，因此没有恒真的 loaded 字段；formed 和 loadOrder 是本次宿主捕获值。上一 tick workState 不参与新选择。持久身份复用 `PersistentMachineIdentity`，真实同 UUID 多副本应先由[身份登记](machine-identity.md)全部拒绝，capture 另行拒绝重复输入，修正旧 planner 用延迟填充的 workStates 去重的漏洞。

## 计算资源与参与者

NetworkResourceType 是计算预算的 metadata：ResourceLocation、logicalConcurrency、maxPerTickValue，承接 PN 的 thread/compute/qubit 等维度。物料身份仍使用 AEKey/AEKeyType，FE 仍独立；这些计算预算不作为仓室资源类型注册。

元数据 record 默认按所有字段比较；每个向量、modifier 的多张表按 ID 拒绝不同元数据，完整 capture 另行对照冻结的 canonical 注册表，避免未注册或跨批次元数据漂移。Java 注册事件和标准 thread/compute/qubit 已接入，详见[计算资源](network-resources.md)。`maxPerTickValue` 作用于固定计算组件的单物品供给和研究阶段 grant limit，不能限制堆叠、设备或全队总量；这两处执行约束随组件供给及研究公式继续迁移。

每队基础贡献为：活动中心 + 成型且等级合格的 COMPUTER + 当前队伍的 ACTIVE 参与者贡献。其他中心、研究站、数据中心与通用 DEVICE 的资源不参与合计。所有贡献完成后，只应用一次活动中心 modifier。参与者不按种类或等级过滤；`NetworkContribution` 只是经过宿主验证的参与者资源投影，保留 epoch/version，尚未迁入其工位、材料来源、注册状态机或提交校验。

modifier 沿用 `floor((base + flat) × (1_000_000 + ppm) / 1_000_000)`，负 flat 可以存在，但结果不得为负；因子可为零，不可为负。加法、因子和中间乘法都用 exact long 检查，保留旧明确拒绝中间溢出的语义。汇总使用 fastutil addTo 并保留溢出预检；不通过捕获算术异常继续运行。公开向量默认缺项为 0，完全冻结输入及条目。

## 验证与后续

新增 13 项逻辑测试覆盖选择优先级/满库、等级与角色过滤、参与者与中心修饰顺序、捕获与 worker 隔离、每 owner 一次解析、重复和迟到参与者、跨查询成员矛盾、失败传播、算术边界及快照不可变。仅测参数拒绝或 getter 门限的两项初稿已删除，这些条件由静态检查和完整规划用例覆盖。完整测试数和逐文件 MCP 证据见[实施进度](implementation-status.md)。未启动 Minecraft，也未在测试中初始化 FTB Universe；FTB 原生 API 通过依赖编译、源码/字节码核对及 MCP 检查验证。

准确机器登记、运行角色组件、server tick 的捕获/发布边界和共同网络门限已接入。正式 planned、资源/lease 账本、研究/队伍持久状态、完整参与者、数据中心存储及网络工作 GUI 继续迁移，不能由选择和汇总结果替代。
