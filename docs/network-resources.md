# 计算资源声明与预算迁移边界

计算资源是 PN 的计算预算维度，物料身份继续使用 AEKey/AEKeyType。资源声明/权威校验之后，已接每 tick 数值账本、logical lease 与 owner 凭据，见[预算说明](network-budgets.md)。计算组件供给、普通配方/研究消费与公式执行仍待迁移。

## 已接入的 Java API

`NetworkResourceRegistry.INSTANCE` 在 `NetworkResourceRegistrationEvent` 接受 Java 注册。事件位于 mod post-init，早于配方注册和机器 runtime 装配解析；事件结束后 freeze，一次 volatile 发布完整只读表。注册只允许在 mod 加载线程进行，运行时不允许追加、替换或删除声明。

注册表保留 PN 的三种标准维度：`prototype_eng:thread` 为 logicalConcurrency，per-item 上限为 1；`prototype_eng:compute`、`prototype_eng:qubit` 为 numeric，无类型固有限额。这些是预算元数据，没有注册机器、研究内容或供给数值。扩展通过 `register(NetworkResourceType)` 声明自己的 ResourceLocation、logicalConcurrency 和 maxPerTickValue。

`require(id)` 用于加载时解析注册声明；`canonical(type)` 校验全部元数据并返回注册实例。相同 ID 但逻辑属性或上限不同一律拒绝；相等的 record 可以由加载器重建，不要求 Java 对象身份一致。`types()` 是按注册顺序冻结的声明列表，未暴露可变 map entry。重复 ID 和 freeze 后注册均报错。

`NetworkRuntime` 构造和 `NetworkPlanner.capture(resources, epoch, devices, contributions, resolver)` 要求冻结的注册表。内置服务器使用全局表，独立宿主可以传入自己的冻结表。capture 在查询队伍前校验每个设备供给、中心 flat/ppm 修饰器及参与者供给中的所有键，包括值为零的声明；即使本批没有另一份同名声明，也能拒绝未注册类型和元数据漂移。

已有快照/向量使用 record 的值相等语义；不为换成相同声明对象而逐批重建快照。运行时先撤旧发布再捕获，注册校验失败不会保留旧许可。元数据注册不授予任何研究、预算或 planned 提交权限。

## 原实现核对与修正

参考根目录 `F:/new/PrototypeNetwork/src/main/java/com/circulation/prototype_network`：

| 旧实现 | 保留或修正 |
|---|---|
| `impl/resource/NetworkResourceRegistryImpl` | 保留预置维度、唯一 ID 和加载后封闭；使用已有不可变 record，无需复制接口对象、逐 getter try/catch 或重复记录异常。 |
| `impl/research/ResearchRegistryImpl.validateRegisteredResource` | 旧代码要求注册实例的 `==` 身份，注册器却复制标准常量，可能拒绝合法声明；改为完整元数据一致后取权威声明。 |
| `impl/component/NetworkComputerResourceCalculatorImpl.canonicalProvidedResource` | 旧计算器仅比 logicalConcurrency，没有检查 maxPerTickValue；新捕获对照完整注册元数据，不接受放宽上限的同名类型。 |
| `impl/component/NetworkComputerComponentImpl` | 固定供给上限作用于 resourcesPerItem，随后 exact 乘 stack count，再汇总电脑和全队；不能把上限误放在设备快照或队伍总量。组件供给链仍待迁移。 |
| `impl/research/ResearchResourceFormulaImpl.validateGrantLimit` | 类型上限也约束研究阶段的 segmentGrantLimit；不约束累计需求总量。研究公式仍待迁移。 |

本阶段只在捕获边界验证注册元数据，没有声称单物品供给或研究阶段限额已由执行器强制执行。

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
