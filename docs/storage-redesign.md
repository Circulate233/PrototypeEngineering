# 独立存储与 AE2 类型边界

用户已明确：不以 `MEStorage` 为本库的核心存储接口，必须根据配方和并发需求独立设计；禁止重造的是资源类型系统。AEKey、AEKeyType 和 GenericStack 继续表示资源及数量，FE 仍保持独立。

## 对现有代码的影响

- `api.storage.ResourceHatch` 返回 MEStorage 的签名需要替换。
- `BufferedResourceHatch.Buffer extends GenericStackInv` 将核心状态交给 AE2 库存的做法需要重构，不能仅新增同名方法包裹它。
- 资源操作、存储身份、版本、预留、提交、回滚/补偿和失效由本库契约负责。外部自定义仓室实现同一契约，不能绕过一致性要求。
- MEStorage、GenericInternalInventory、Forge 能力和 AE2 GUI 库存均只能作为适配视图，不能成为真实状态的另一份所有者。适配器必须受核心预留和失效规则约束。
- GUI 继续直接继承 AEBaseGui；这不要求核心存储继承 GenericStackInv。

当前代码尚未完成此替换；原 P1 的已通过测试只证明旧后端上的方向、转移等行为，不证明新存储契约已经具备。

## 已确认的旧源码依据

| 来源 | 目的与行为 | 重构约束 |
|---|---|---|
| PM `api/storage/ResourceStorage.kt`、`SlottedResourceStorage.kt` | 大数量、按资源查询、多槽同 key、槽位变更追踪。 | 保留能力，资源身份改为 AEKey。 |
| PM `impl/storage/ResourceStorageImpl.kt` | 数量修改维护 revision，通知变更；快照意图是安全迭代。 | 检查快照内容是否共享可变对象；一个 volatile 标志不能使普通映射并发安全。 |
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

这些是实现与验收约束，不表示接口和算法已完成。下一步依据完整存储/预留/补偿调用链确定具体 API，再替换后端及适配器。必要测试围绕预留竞争、回滚守恒、失效与迟到任务、方向隔离和真实并发，不增加字段或类名测试。
