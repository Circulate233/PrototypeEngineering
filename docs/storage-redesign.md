# 独立存储与 AE2 类型边界

用户已明确：不以 `MEStorage` 为本库的核心存储接口，必须根据配方和并发需求独立设计；禁止重造的是资源类型系统。AEKey、AEKeyType 和 GenericStack 继续表示资源及数量，FE 仍保持独立。

## 共享仓室的并发与并行边界（2026-11-12）

多个控制器可引用同一个真实仓室，正确性以物理余额为边界。内置ResourceHatchBlockEntity缓存一个BufferedResourceHatch，结构发现只绑定此引用；同一余额的所有视图必须共用StorageContext、锁、revision和预留量，不能按控制器或临时adapter另建账本。混合仓的独立输入/输出余额各有自己的context。Simple/Default库存捕获按context合并同key视图的最大值、跨独立context累加，实际资源/FE获取按context去重，重复绑定同一仓室不增加可用量或容量。

两种手段当前均有接线：MachineInstance.affinityKeys收集物理存储身份；普通调度及MachineRecipeExecution对共享key传递分组，异步机器回调/工作在组内串行，独立组仍并行。该分组按本轮捕获关系产生，不要求控制器终身绑定一个Java线程；只读异步扫描仍可并发。容器层的锁、revision和原生输入/输出预留是最终保证，玩家/自动化/其它执行入口也必须遵守，不能依赖控制器分组代替容器隔离。每阶段的准确token、source/fence及失败补偿保留。

需要区分库存建议和真实分配：候选发现与START probe只观察/试探，probe结束会补偿并释放预留；MachineWorkSlots.Admission登记的是本机进程/并行占用，没有扣物料或持有共享库存。因此A/B可能都从库存6选到2并行（每并行合计消耗3），这不是已分配的4并行。实际START/TICK/END再次按现存余额/容量预留：A先成功，B不足时保持原阶段、不付款、不推进，补料后可继续。串行到同一线程也不会自动消除这类先选中后等待，更不承诺跨控制器公平分摊或全局最优并行。

设计建议以容器预留/事务保证正确性，用共享存储分组减少竞争。若后续明确要求“准入时即保证共享库存不会超额选中”，需要另行规定全局准入分配与公平性，并把真实START预留交接到START，失败/取消/保存恢复保留准确清理责任；不能把当前库存API结果当作库存配额，也不能在扫描阶段提前执行配方事件。TICK资源和输出仍需按各自实际阶段结算，不因准入分配而凭空保证未来库存/容量。本轮只核实当前行为，未引入这种运行语义变更。

验证使用真实TaskScheduler、两类控制器、同一资源/FE仓室及重复别名，在四种执行策略下确认两台均选2并行时只有一台支付/完成、另一台START保持未开始，数量和FE守恒；第一台失去结构不会使共享仓室失效，补料后第二台继续。既有并发预留、输出容量、旧快照、相反锁序和批次补偿检查一并通过，详见[实施进度](implementation-status.md)。无Minecraft/World初始化，不能代替实际结构成型/端口绑定复验。

## 已落地的边界

- `api.storage.ResourceHatch` 的两侧返回独立 `ResourceStorage`；内置实现返回支持槽位操作的 `SlottedResourceStorage`。
- `SlottedResourceBuffer` 用 AEKey 数组、long 数组、AEKey2LongMap 汇总和 BitSet 管理真实余额、预留量及变更槽位，已移除 `Buffer extends GenericStackInv`。
- 资源操作、存储身份、版本、预留、提交、回滚/补偿和失效由本库契约负责。外部自定义仓室实现同一契约，不能绕过一致性要求。
- MEStorage、GenericInternalInventory、Forge 能力和 AE2 GUI 库存均只能作为适配视图，不能成为真实状态的另一份所有者。适配器必须受核心预留和失效规则约束。
- GUI 继续直接继承 AEBaseGui；这不要求核心存储继承 GenericStackInv。

当前已替换资源核心与适配层，并接入独立 FE 预留、完整配方进程与普通多线程调度；正式 planned 调度器仍待迁移。验证证据以 [implementation-status.md](implementation-status.md) 为准，原 P1 测试不能替代预留、补偿和并发验证。

数量表复用 AE2 `AEKey2LongMap`：有序 totals、快照和配方合并使用其 `LinkdOpenHashMap`，无序预留使用 `OpenHashMap`；增减调用 `addTo`。快照先复制 totals，再遍历实际 extractHolds 用负增量扣除，并删除恰好归零的条目，不为每个总量 key 查询预留表。快照对外使用 AE2 的 `UnmodifiableMap`，包括 `addTo` 在内的写入口均拒绝。

普通 `addTo` 不检查溢出，也不自动删除零项：真实存储先完整预检余额和槽位，再更新各表并清零，保持原预留 apply/rollback/退款容量转换。配方数量合并溢出仍失败；索引的跨来源可用量使用 `SaturatingOpenHashMap.addTo` 保留饱和语义，别名视图仍按 context 取最大量而不相加。预检、精确最大值选择与稳定顺序确实需要的读取不能省略；不以饱和数值代替真实余额。

## 已确认的旧源码依据

| 来源 | 目的与行为 | 重构约束 |
|---|---|---|
| PM `api/storage/ResourceStorage.kt`、`SlottedResourceStorage.kt` | 大数量、按资源查询、多槽同 key、槽位变更追踪。 | 保留能力，资源身份改为 AEKey。 |
| PM `impl/storage/ResourceStorageImpl.kt` | 数量修改维护 revision，通知变更；快照意图是安全迭代。 | 检查快照内容是否共享可变对象；一个 volatile 标志不能使普通映射并发安全。 |
| PM `impl/storage/ItemResourceStorage.kt` | totals/keySlots、稳定槽位顺序、BitSet 空槽分配与去重变更槽位。这里才是实际物品槽后端，没有 `SlottedResourceStorageImpl`。 | 复用汇总、同 key 优先和空槽按索引分配；替换共享可变 PMKey 数量，审查泛化到 long 后的容量与取整溢出。 |
| PM `impl/scheduler/planned/StorageReservationLedgerImpl.kt` | 预留按身份/资源排他，检查数量、owner、revision、lane；区分提交、释放与补偿。 | 保留真实来源身份和执行权验证，重审串行热点和重复状态。 |
| PM `ItemRequirementSystem` / `EnergyRequirementSystem` | 模拟后执行、记录来源增减量，整阶段失败时恢复。 | 模拟不是预留；当前物品系统的 tick 是 no-op，而 FE 有按 tick 输入/输出。不得根据概览推断每类实现相同。 |
| PM `PlannedParallelCoordinator` | prepare/resolve 两次 join、服务端有序决策与提交、取消和迟到任务处理。 | 存储生命周期与调度屏障共同防止失效后继续写入；单仓室 synchronized 不代表跨仓室原子性。 |
| PN `NetworkMachineRecipePlannedOperationFactory` | 将配方执行绑定到网络 owner/epoch 和工作资源预算。 | 合并可去除跨模组转接，但保留预算、归属及 epoch 失效效果；完整实现仍需继续逐段审查。 |

## 新契约必须保证的行为

1. 输入和输出是真实独立缓存，每侧容纳 AE2 多种资源；三种仓室方向不变。
2. 输入数量和输出容量必须可以预留。同一来源的多个视图共享身份与预留状态，不得重复预算；普通自动化和玩家交互也不能消费已预留部分。
3. 需求获取阶段产生可撤销状态；阻塞/失败能够恢复整个阶段。不能把已经对外可见且可能被提走的产物当作随时可以抽回的回滚资源。
4. 资源不足、预留冲突、失效等预期结果显式返回。协议破坏、非法令牌、重复结算和不可能的数值立即失败。
5. 对多仓室阶段和 planned 分布式提交区分准备、应用、完成与补偿；明确何时释放用于回滚的数量/容量，以及何时对外发布变更通知。
6. 存储快照、owner/epoch、revision 与执行 lane 一起参与验证；卸载/换队/失效需取消并等待相应工作边界，禁止迟到结果污染替换后的实例。
7. 跨线程发布和锁/VarHandle 操作须有具体依据。保留并行执行能力，不能把所有访问迁回服务端单线程以规避设计。
8. 持久化定义已提交余额和待恢复状态；禁止丢弃已扣材料或未完成补偿。跨 TileEntity 保存不自动具备磁盘事务保证。

这些约束中，资源/FE 余额、预留和同步整阶段结算已实现；owner/epoch、执行 lane、机器卸载屏障及持久化恢复仍须在调度器与需求集成中落实。不能把局部核心测试当作迟到任务或完整机器生命周期的验证。

## 当前 API 与结算规则

- 每个真实缓存只拥有一个 `StorageContext`，其所有适配视图共享身份、生命周期、revision 和可重入锁。混合仓两侧各自独立。机器/世界发现仍在服务端进行，已取得的核心引用支持并发操作；锁不替代调度器的 owner/epoch 与实际执行线程验证。
- `StorageSnapshot` 复制可用于规划的未预留数量；快照不可变，不共享可变数量对象。`reserveExtract` 预留确切输入量，`reserveInsert` 预留确切输出槽位和容量。旧 revision、失效及不足返回 `ReservationResult`，非法参数/重复结算立即失败。
- 输出预留会占用空槽的 key 身份，防止其他资源抢占；同 key 可继续使用剩余容量。输入预留只扣减可用量，普通自动化仍可使用未预留部分。每 key 的总量与待插入/待退款容量合计受 long 上界约束。
- `ResourceReservation` 经 PENDING → APPLIED → COMMITTED，或从 PENDING/APPLIED 回滚。apply 后保留补偿权：输入转换为退款容量占用，输出转换为不可被提走的数量占用；complete 才释放这些占用。没有跨批缓存的终态账本，也不会成功后遗留同 key 冲突。
- `ResourceTransactions` 复制参与者列表，去重并按真实 context 的稳定顺序加锁，完成全部校验、应用及完成后统一解锁，再发布变更通知。应用异常反向补偿全部参与者；通知/补偿异常保留为原异常的 suppressed，继续处理剩余参与者。外部实现必须保证 apply 在失败时没有部分写入，complete 不拒绝已验证、已应用的令牌。
- 调用方不能在持有任意存储锁时再进入跨库结算。整个 apply/complete 或补偿过程必须持续持有全部参与者锁，不允许解锁后将中间态当作已提交余额读取或保存。当前批结算不是磁盘事务，也不是未来跨执行 lane 的分布式提交协议。
- 普通按槽和按 key 转移不创建预留分配列表/数组；按 key 查询使用汇总，可用量通过 key 级 extractHolds 在 O(1) 时间得到。按槽的写入与预检在同一把锁内，长期预留才保留槽位分配。

`ResourceReservation` 的生命周期契约没有 AEKey 字段，独立 EnergyBuffer 也通过它参加同批协调；共用的是补偿协议，而非资源类型。FE 使用自己的 EnergySnapshot，包含未预留余额/空间与单次接收/提取限额。限额下降可能让尚未 apply 的令牌被拒绝；退款使用保留的数量/容量，不能再次被新速率裁成部分退款。restore/限额变更/失效也推进 revision 并发出合并脏信号。

ResourceTransactions 的业务回调重载在全部存储 apply 后执行业务 commit，全部成功才调用 complete。拒绝/异常先撤销业务再撤销存储；complete 与通知阶段的异常不会再次回滚终态令牌。RecipeStageExecutor 汇总各需求上交的令牌，统一调用这个边界；详细扩展契约见 [recipes.md](recipes.md)。

## 适配、保存与失效

MEStorage 仅存在于 `BufferedResourceHatch.automation()` 适配视图，GenericInternalInventory 与 Forge 适配也只委托独立核心。GUI 直接基于 AEBaseGui；AE2 的 ConfigGuiInventory 构造器绑定 GenericStackInv，因此使用薄的 `HatchGuiInventory` 提供服务端显示视图与客户端同步缓存，不再创建第二份服务器余额。点击、Shift 回退、资源容器装卸在同一侧核心锁内完成，显示副本不参与真实转移。

混合仓保存分别编码 Input/Output，保留槽位，不合并同 key 的两侧余额。加载同时锁住两侧，先准备/验证两侧完整数据，再应用；任一侧存在活动预留、容量超限、同 key 合计溢出或无效数据时，不修改任何一侧。旧格式不在兼容承诺中。失效不可逆，旧引用停止转移；仍可读取槽位余额用于保存，替换宿主必须创建新 context。

变更通知在解锁后执行，可能来自工作线程。内置 TileEntity 的监听只通过共享 VarHandle 标记需要保存，服务端 tick、失效/卸载回调负责执行 markDirty，避免最后一次变更尚未到下个 tick 就卸载而遗漏脏标志；不会从工作线程直接访问世界。未来调度集成还必须覆盖卸载前等待/补偿与保存边界。

## 待修正的源码风险

本次只确认控制流风险，未运行旧工程复现，也未声称实际世界中已经触发。

- PM [ItemResourceStoragePlannedConsumableSourceImpl.kt:147](F:/clone/PrototypeMachinery/src/main/kotlin/impl/scheduler/planned/ItemResourceStoragePlannedConsumableSourceImpl.kt:147) 先执行 `storage.extract`，不足时立即 fail，直到第 151 行才加入 consumed。若实际只提取部分数量，catch 的 `restore(consumed)` 不包含本次已提取量。新实现必须在任何失败检查前记录实际增减，或让核心预留保证原子操作不发生部分写入。
- 同一方法逐个执行 ledger.commit；后续令牌失败时，catch 只恢复物理 consumed 并将 source 状态改回 RESERVED。前面已提交的账本令牌是否全部得到补偿没有在此路径落实。新实现不得将物理余额恢复视为所有参与者状态都已恢复。
- source 的 stateLock 只包住 planned 适配器；独立存储的自动化、GUI、直接 API 是否遵循同一所有权/预留边界仍需继续核对。不能仅凭适配器有 synchronized 就推断真实后端安全。
- PM `StorageReservationLedgerImpl.commit` 把令牌改为 COMMITTED，却保留 activeByIdentityResource；release 只接受 ACTIVE。此底层账本没有成功 finalize 入口，而 coordinator 的 completeBatch 处理的是另一层 ExclusiveReservationLedger。工厂复用账本时，成功操作可能让后续同一资源持续 CONFLICT；新核心的 complete 明确释放所有持有量。
- 旧输出路径的模拟、插入及失败后抽回没有真正占用容量，自动化可能在这些步骤间改变存储。仅修输入 consumed 记录不能修复输出发布与补偿竞争。
- 旧来源身份包含机器/成型/端口信息时，同一物理余额可能生成不同账本。新核心以实际 StorageContext 为身份，外部别名必须返回同一 context，不能用适配器对象或机器 ID 分裂真实余额。
- PN `NetworkMachineRecipePlannedOperationFactory.Behavior.commit` 将数值预留改为 COMMITTED，但 RuntimeLifecycleProbe 仍调用 `validateCurrentRuntime("lifecycle", true)` 要求 RESERVED。PM coordinator 在业务 commit 之后再次验证生命周期，存在误判成功操作为失效的控制流风险；新迁移必须按阶段验证，不能原样复制。
- PM `StructureEnergyContainerComponents.kt` 在大额 unchecked 操作中反复调用 Forge int 模拟，余额不变却累计减少 remaining，可能将同一余额/空间重复计入并绕开一次请求的限额。当前 long FE 核心与单来源分配不复刻该循环；回滚也直接使用精确预留补偿权，不再经过 Forge 方向和速率裁剪。

## 下一阶段的线程边界

继续沿用 PM 的服务端发现/快照/预留与有序业务提交、工作线程冻结数据 prepare、真实来源稳定 affinity 的单线程执行 lane，以及 prepare/resolve 两次 join。取消、超时后结果不确定与迟到结果必须有恢复路径；lane 必须绑定实际 Java 线程。PN 的 owner/epoch、机器/世界身份和工作资源预算也要保留。当前 ResourceTransactions 只覆盖一次可同时持锁的资源批次，尚未接入这些调度边界，不能以它替代原有多线程能力。
