# 计算资源声明与预算迁移边界

计算资源是 PN 的计算预算维度，物料身份继续使用 AEKey/AEKeyType。资源声明/权威校验之后，已接每 tick 数值账本、logical lease、owner 凭据、真实计算组件物品供给与普通配方消费者，见[预算说明](network-budgets.md)和[配方消费者](network-recipes.md)。研究站完整阶段消费与公式执行边界按对应专题继续核对。

## 已接入的 Java API

`NetworkResourceRegistry.INSTANCE` 在 `NetworkResourceRegistrationEvent` 接受 Java 注册。事件位于 mod post-init，早于配方注册和机器 runtime 装配解析；事件结束后 freeze，一次 volatile 发布完整只读表。注册只允许在 mod 加载线程进行，运行时不允许追加、替换或删除声明。

注册表保留三种标准维度：`prototype_eng:thread` 为 logicalConcurrency；`prototype_eng:compute`、`prototype_eng:qubit` 为 numeric。所有类型都没有固有供给上限，单件物品、整台计算机及研究站速率由开发者定义。扩展通过 `register(new NetworkResourceType(id, logicalConcurrency))` 声明类型；2026-11-13 按用户要求删除 maxPerTickValue 字段及旧 THREAD 每件最多1的限制。

`require(id)` 用于加载时解析注册声明；`canonical(type)` 校验全部元数据并返回注册实例。相同 ID 但逻辑属性或内部/外部作用域不同一律拒绝；相等的 record 可以由加载器重建，不要求 Java 对象身份一致。`types()` 是按注册顺序冻结的声明列表，未暴露可变 map entry。重复 ID 和 freeze 后注册均报错。三参数构造的 internal=true 仅供计算机本地预算，网络捕获拒绝内部类型。

`NetworkRuntime` 构造和 `NetworkPlanner.capture(resources, epoch, devices, contributions, resolver)` 要求冻结的注册表。内置服务器使用全局表，独立宿主可以传入自己的冻结表。capture 在查询队伍前校验每个设备供给、中心 flat/ppm 修饰器及参与者供给中的所有键，包括值为零的声明；即使本批没有另一份同名声明，也能拒绝未注册类型和元数据漂移。

已有快照/向量使用 record 的值相等语义；不为换成相同声明对象而逐批重建快照。运行时先撤旧发布再捕获，注册校验失败不会保留旧许可。元数据注册不授予任何研究、预算或 planned 提交权限。

## 真实物品供给

`NetworkComputerComponentRegistry.INSTANCE` 在 `NetworkComputerComponentRegistrationEvent` 接受 Java 注册，事件位于计算资源 freeze 之后、研究/配方及机器定义解析之前；结束后调用 `freeze(NetworkResourceRegistry.INSTANCE)`。独立宿主可使用自己的组件注册表和冻结资源表。组件表不注册正式物品、机器或供给数值。

`register(ItemStack, NetworkComputerComponent)` 的物品必须非空且 count 为 1；内部直接用 `AEItemKey` 的精确物品、metadata 和 NBT 身份，count 不参与身份。注册私有拷贝、禁止重复项及 freeze 后写入，不暴露内部可变 ItemStack。未知普通物品和非物品 AEKey 不贡献计算资源。

外部 provider 的 `capture(ItemStack, MachineInstance)` 在 owner 捕获边界接收私有 count-one 副本，返回 `NetworkComputerComponent.Capture(resourcesPerItem, current)`。返回向量按只读契约使用，全部声明（包括零值）经注册类型及 logicalConcurrency 校验，供给量非负。`fixed(resources)` 是固定供给便利入口，不制造组件 ID、纯栈、context 或 Impl 镜像层。

`NetworkComputerComponentInventory.capture` 初次按公开 `SlottedResourceStorage` 的 committed `getStack` 捕获槽内容，并在各 `StorageContext` 锁下截取修订。默认 `MachineComputerComponents` 只读取 `ComputationHatch`，结构绑定和物品组成变化事件触发重建，稳定时复用缓存；不定时遍历普通输入/输出仓。同物理 context 的多个别名只算一次，各 context 按已有 lock order 排序后 `lockAll`。已预留提取但尚未 apply 的物品仍物理安装，待插入输出还未进入 committed 槽，因此不能用 `StorageSnapshot.available` 替代真实内容。

安装数量由 `AEKey2LongMap` 按 long 汇总；开发者定义的每物品供给经溢出预检乘安装数量，随后用 fastutil `addTo` 累加，最后合并机器 declared vector。单件物品可以提供多份 THREAD、任意算力及自定义资源量，例如64 THREAD和2048 COMPUTE；自定义计算机也没有额外供给上限。整机消费仍按既有规则申请 lease，与 Java worker 数量无关。网络中心也可直接配置线程额度，仅当前活动中心的供给计入队伍。

`MachineComputerComponents` 在装配中声明为 recreated。默认控制器组合它与原资源需求、费用事务，通过网络发布先准入再支付，见[计算机与运算仓](computers.md)。默认证明保留组件组成版本及仓有效性，水等非物品输入/预留变化不触发组件扫描。外部显式 Sources 适配器仍可枚举自己的公开槽并提供准确来源证明，沿原 context 修订捕获；使用方自行负责其工作策略。

供给、外部 Sources 的 `current` 都必须只读并且不获取额外 storage/domain 锁。配方的 `WorkPermit.authorized` 会在 FE/材料事务持锁及 planned `ResourceBatch` fence 内调用这些证明：初次捕获完整锁下读取，后续只用 `StorageContext.current(expectedRevision)` 的 release/acquire 版本证明与 provider 自有版本回执，不重新读取槽或重算 provider。证明回调后再次核对 context 修订，防止回调期间来源变化通过旧证明。普通仓室本身负责 NBT 保存恢复；reader 不保存内容、epoch 或许可，新装配读恢复后的真实仓槽，旧实例证明不复活。

验证使用项目已有 `TestResources` 的 AEKey/AEKeyType，经公开物品注册实际委托的同一内部 `registerKey` 边界进入供给捕获；该包内桥仅在 test 源码。覆盖 long 数量/供给汇总/多源去重、提取预留与实际 apply、provider 初次捕获/版本回执、外部锁下重验、组件恢复及同步/planned 配方 FE 事务。恢复验证用正常 `ResourceKeyNbt` 显式 resolver 和已有 buffer restore。原生 `ItemStack` 夹具曾在进入业务前触发 `Accessed Items before Bootstrap!`，禁止初始化游戏的约束下已移除；物品转换直接调用 `AEItemKey.of` 的复制、count/NBT 身份路径仅静态核对，没有声称动态验证原生 ItemStack 或原生物品 NBT 解码。阶段完整构建通过 803 项测试、113 suites，零失败/错误/跳过；11 个 src Java 与 Tags 共 12 文件全级 IDEA 零诊断，MCP build_project 成功，证据见 `build/reports/idea/computer-supply-final.json`。

## 原实现核对与修正

参考根目录 `F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network`：

| 旧实现 | 保留或修正 |
|---|---|
| `impl/resource/NetworkResourceRegistryImpl` | 保留预置维度、唯一 ID 和加载后封闭；使用已有不可变 record，无需复制接口对象、逐 getter try/catch 或重复记录异常。 |
| `impl/research/ResearchRegistryImpl.validateRegisteredResource` | 旧代码要求注册实例的 `==` 身份，注册器却复制标准常量，可能拒绝合法声明；改为完整元数据一致后取权威声明。 |
| `impl/component/NetworkComputerResourceCalculatorImpl.canonicalProvidedResource` | 对照已注册ID和logicalConcurrency；供给数量由开发者定义，不保留旧类型上限。 |
| `impl/component/NetworkComputerComponentImpl` | 由开发者定义 resourcesPerItem，再乘安装数量、汇总电脑和全队；真实供给改为 AEItemKey 注册、公开 committed 槽与版本回执。 |
| `impl/component/NetworkComputerComponentInventoryImpl` / `NetworkComputerResourceCalculatorImpl` | 保留跨仓供给、未知普通物品忽略及溢出拒绝；去掉 PMKey/字符串 ID/纯栈转换、supports 全表扫描、逐 getter/callback catch-log 与 int 数量限制。 |
| `impl/research/ResearchResourceFormulaImpl.validateGrantLimit` | 2026-11-13 按用户新要求移除研究阶段单站限额及资源类型上限，研究站自己定义速率，研究只定义总需求。 |

旧单物品供给上限已取消；研究推进按各参与站自己定义的速率求和，数量仍保留非负及真实运算溢出检查。默认倍率不再先乘百万后除百万，未缩放的合法long供给可直接通过。见[研究站控制器](research-stations.md#研究站控制器与速率)。

## 后续预算与 lease 的准确边界

以下保留旧源码审计作为迁移依据；每 tick 账本、准确代补偿和操作序号现已接入，研究消费与完整参与者仍未完成，具体范围见[预算说明](network-budgets.md)：

- `NetworkMachineLeaseBookImpl` 每 tick 以机器 UUID 幂等申请一份 logical thread。同机器的其他工位是 ALREADY_GRANTED；没有工位完成即 release 的语义。中心免租，其他机器和研究贡献者共享本 tick 机器集合；它不控制 JVM worker 数。
- `NetworkTeamTickBudgetImpl` 的研究 numeric 向量与普通工位 reservation 余额是独立两份，lease 共享。这是旧 API 明确约定，不能在迁移中悄然合并消费而改变效果。研究站先申请贡献机器的 lease，随后普通工作使用剩余许可。
- `NetworkWorkUnitResourceBookImpl` 先检查完整需求向量，再整组扣减；commit 改状态，rollback 可撤 RESERVED 或补偿 COMMITTED。迁入后仍使用 fastutil addTo 与必要的溢出/余额预检。
- 旧集成只在 server tick START 发布一次，以 plan.epoch 创建新账本；新运行时同 tick 可多次 refresh。账本必须以实际 server session、游戏 tick、team 标识，不能按 capture epoch 重建。同 tick 撤权、队伍短暂消失再出现、重捕获均不能恢复已消费额度。旧容量在 tick 内固定；如果未来允许同 tick 更新容量，必须保留已消耗和预留量。
- 旧账本只有 final tick 标签，旧引用还能 reserve/commit，并未真正失效。迁入后必须区分当前提交权与原代补偿权：过期拒绝新预留和提交，但已接受操作可完成原代清理；禁止向新 tick 退款。
- 旧 work unit 以 machine/operation ID 保留本 tick 终态；planned factory 的 recipe/slot/participant 键不足以区分同 tick 新一轮工作。后续需稳定工作单元身份及提交序号，明确幂等、重试与下一轮执行。
- 旧 TeamTickBudget 只支持内置 THREAD，拒绝其他 logical 类型，容量强制 int；公开 metadata 的 logicalConcurrency 不代表任意 logical 维度已经受支持。后续实现必须明确 lease 维度，不把声明能力写成已有执行能力。

参与者提交还须修正旧 `NetworkParticipantRegistryImpl` / `ParticipantTransactionImpl`：只比较 command 与保留的 plan 不足以证明当前授权。必须重查准确注册代、ACTIVE、owner/team、当前 epoch/version、来源与工位。补偿回调失败或 reservation.rollback 失败不能仅记日志后标成成功 ROLLED_BACK；沿用本项目真实失败与清理协议。

正式 planned 保留 PM prepare/resolve 两次 join 与稳定 source lane，PN factory 的当前许可重查和 issuing reservation 补偿思路可复用。阶段入口及完成范围见[索引](INDEX.md)、[网络运行时](network-runtime.md)和[实施进度](implementation-status.md)。
