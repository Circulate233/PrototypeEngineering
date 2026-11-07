# 检查点子阶段执行审计

2026-11-07。对照 PM 实际源码，检查点与研究站材料周期的提交边界不同。此项尚未修复，不以现有顶层材料周期测试证明嵌套行为正确。

## 必须保留的行为

PM CheckpointRequirementSystem.kt:93、109、128 依次获取子 START/TICK/END，:148–174 统一提交。FactoryRecipeProcessorSystem.kt:300–309 在 Blocked/Failure 时逆序撤销本次外层阶段，:112–115 不推进父时钟。因此当前检查点阻塞后应重试整个触发，但净扣料为零；历史已成功的外层 START/TICK/更早检查点不回退。

继续保留 scaled/unscaled 阈值跨越、冻结 Tick、time=0 支持、候选声明顺序、固定选择/禁用、完整祖先路径、异常原样传播和准确 UNKNOWN 恢复。不能把研究站“成功子阶段不退款”的持久规则套进检查点。

## 已确认的差异和缺口

| 情况 | PM / 当前实现的证据 | 必须达到的结果 |
|---|---|---|
| 前序输入释放后序输出空间 | PM EnergyRequirementSystem.kt:43、FluidRequirementSystem.kt:273 在获取时实际修改库存；PE 先获取全部子阶段，内置资源/FE 此时仅 reserve | 如满 FE 仓子 START 抽取 5、子 END 回填 5，应能利用同次触发释放的容量 |
| 前序输出供后序输入使用 | PE SlottedResourceBuffer 的已 apply 输出仍保留补偿 hold，简单先 apply 也不能让后续普通读取消费它 | 后序可使用本次触发产生的资源，外层失败仍能完整补偿 |
| 选择器 modifier/overlay | PM 与 PE 都在选择器 START commit 才执行 modifier；provisional choice 仅保存派发对象 | 子 TICK/END 获取必须使用 START 成功安装的效果，拒绝或异常仍局部撤销 |
| 外层后继失败 | 当前 TransactionGroup、ResourceBatch 和阶段 owner 已持有准确回滚/完成义务 | 子 END 成功也不能提前放弃外层补偿权 |
| 扩展读写其他来源 | RequirementTransaction 返回的 tokens 和 StorageFence 当前都是一次冻结；第三方可使用独立 StorageContext | 不能在持有旧域时盲取陌生域、阻塞另一 lane 或用异常探测并重放回调 |

PM 的 time=0 不触发、拒绝候选被 commit、选择回滚未删状态、END 回滚清历史 modifier、局部 ID 冲突、绕过 overlay 及吞清理异常均是旧缺口，已修部分不应退回旧行为。

## 后续实现边界

需要由现有外层阶段/协调器持有可暂停的子阶段游标；同批分段执行 source apply 与可逆业务修改，最后统一决定 COMPLETE 或逆补偿。存储还需区分同事务可用的临时资源与补偿额度，使前序输出/空间能供后序使用。新增来源必须在安全边界明确登记，不能以提前 complete、同步嵌套事务或重复 callback 换取表面通过。

复用 RecipeProcess.Stage、PlannedRecipeCoordinator、原来源 lanes 与 UNKNOWN 证明。正式实现时同时验证嵌套 selector/overlay、FE/资源闭环、父阶段后继失败、同 source 别名、取消/超时和真实退出。

源码入口：PM F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/CheckpointRequirementSystem.kt、SelectiveRequirementSystem.kt、AttributeModifierRequirementSystem.kt；PE recipe/requirement/CheckpointRequirementSystem.java、RecipeStageExecutor、ResourceBatch、StorageFence、SlottedResourceBuffer。
