# 检查点子阶段执行审计

2026-11-07。对照 PM 实际源码，检查点与研究站材料周期的提交边界不同。普通检查点、嵌套检查点、检查点内的 selector 以及候选自身带 continuation 的选择均已接入原批次分段执行和准确回退。

## 必须保留的行为

PM CheckpointRequirementSystem.kt:93、109、128 依次获取子 START/TICK/END，:148–174 统一提交。FactoryRecipeProcessorSystem.kt:300–309 在 Blocked/Failure 时逆序撤销本次外层阶段，:112–115 不推进父时钟。因此当前检查点阻塞后应重试整个触发，但净扣料为零；历史已成功的外层 START/TICK/更早检查点不回退。

继续保留 scaled/unscaled 阈值跨越、冻结 Tick、time=0 支持、候选声明顺序、固定选择/禁用、完整祖先路径、异常原样传播和准确 UNKNOWN 恢复。不能把研究站“成功子阶段不退款”的持久规则套进检查点。

## 已确认的差异和缺口

| 情况 | PM / 当前实现的证据 | 必须达到的结果 |
|---|---|---|
| 前序输入释放后序输出空间 | PM EnergyRequirementSystem.kt:43、FluidRequirementSystem.kt:273 在获取时实际修改库存；PE 现由外层按子阶段获取、source apply/expose，再可逆提交业务 | 如满 FE 仓子 START 抽取 5、子 END 回填 5，应能利用同次触发释放的容量 |
| 前序输出供后序输入使用 | PE 已通过准确 fence 内的 exposeApplied 释放本 token 补偿 hold | 后序可使用本次触发产生的资源，外层失败仍能完整补偿 |
| 选择器 modifier/overlay | PM 与 PE 都在选择器 START commit 才执行 modifier；provisional choice 仅保存派发对象 | 已按 START staged modifier/overlay 获取子 TICK/END；外层失败统一撤销 |
| 外层后继失败 | 当前 TransactionGroup、ResourceBatch 和阶段 owner 已持有准确回滚/完成义务 | 子 END 成功也不能提前放弃外层补偿权 |
| 扩展读写其他来源 | ResourceBatch 已可在原 fence 中追加准确 tokens；第三方额外域通过 RecipeRequirementSystem.storageContexts 预声明 | 不能在持有旧域时盲取陌生域、阻塞另一 lane 或用异常探测并重放回调 |

PM 的 time=0 不触发、拒绝候选被 commit、选择回滚未删状态、END 回滚清历史 modifier、局部 ID 冲突、绕过 overlay 及吞清理异常均是旧缺口，已修部分不应退回旧行为。

## 已完成的存储前提

ResourceReservation 增加可选 supportsSequentialUse/exposeApplied。内置 SlottedResourceBuffer 与 EnergyBuffer 在 apply 后可让同一 StorageFence 的后续步骤使用已产生资源或释放的容量，同时保留原 token 的完整 undo、active reservation 和终态义务。空槽可以切换 AEKeyType；别的 pending/补偿 holds 不被释放。

暴露前 pin 准确 fence，未全部结清时禁止 release；complete/rollback 必须回到原 fence scope。逆序回滚先预检全部余额、槽位与 long 上界，再写入。外部 reader/writer 及通知等到整个 fence 释放，不会看到中间值。每 token 预留 apply/expose/terminal 三次 revision；未使用的额度随终态归还，invalidate 独立保留额度。

新增 17 项行为测试（8 资源、8 FE、1 版本上界），完整构建 903 tests / 126 suites 全通过；8 个阶段 src Java 加 Tags 共 9 文件 IDEA 全级零诊断，MCP build_project 成功。证据 build/reports/idea/sequential-storage-final.json。

CheckpointRequirementSystem 现返回 RequirementContinuation，由 RecipeStageExecutor 持有嵌套游标与准确获取清单；RecipeProcess.Stage 与 PlannedRecipeCoordinator 已使用同一 ResourceBatch 分段 source apply/expose、业务 stage，最后统一决定终态。独立域需预声明，外部 token 必须主动支持顺序使用，不能以异常探测/replay。

## 已接执行路径

- 每段仅获取一次。START 可逆提交后才获取子 TICK，随后 END；嵌套游标按祖先路径恢复，子 Tick 与原外层 Tick 各自冻结。外层后继 Blocked 统一逆补偿，父时钟不动；历史已成功阶段保留。
- ResourceBatch.stage 只 prepare/commit 新业务，仍为 OPEN；validateCommit 在最后统一执行一次。append 复用原 Source/affinity，不重放旧 token；source 仍在原 lanes 上 apply/expose/complete/rollback。
- 同步执行也持有持续 fence。探测 continued START 时，为正确观察后续材料，需要可逆 stage 后整批 rollback，不调用 complete；普通平铺需求仍保留仅获取的探测。完整 pure prepare 仍是后续工作。
- 未登记的 acquireNext 窗口禁止 finish/release；获取故障可登记完整 partial 清单后原路清理。缺失 inventory 保留现场。新 token 缺顺序能力时先 appendForRollback 登记准确清理责任，再报告契约错误。
- 顺序回滚遇到后继 UNKNOWN 时保留前序补偿，不能越过依赖继续退款；查询证明终态后才继续。完成方向保持原逐项确认规则。一次获取/应用故障与清理异常均保留。
- 扩展定义须在 storageContexts 声明额外物理域；机器当前资源/FE 仓与结构 children 自动纳入。声明不获取锁、不修改状态，声明外域访问直接失败，不捕获后重跑回调。

## 当前验证

候选局部回退阶段新增 24 项行为测试；完整构建 951 tests / 130 suites，零失败/错误/跳过。12 个阶段 src Java 加 Tags 共 13 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/checkpoint-alternatives-final.json。原 23 份修改保留，未启动游戏或服务端。

此前分段执行阶段的 8 项集成测试覆盖内置资源/FE 闭环、同来源前后检查点、历史消费/父时钟、真实 source lanes、别名去重和扫描后完整退款；该阶段另 16 项为 ResourceBatch 分段责任、UNKNOWN 和实际退出测试。旧异常/选择器测试随真实顺序更新，数量变化来自 START overlay 提前对后继获取生效。

## 候选局部回退

候选可能返回 continuation 时，SelectiveRequirementSystem 使用 RequirementAlternatives 游标。执行器在每个候选开始前保存同一 ResourceBatch 的业务和每个 Source token 前缀。候选完整嵌套 tail 成功后才安装选择及 modifier；候选后续 Blocked/Failure 只撤销保存点之后的参与者，确认后推进同一游标。全部拒绝仍按旧语义禁用 selector，并允许外层继续。

未确定候选占据连续后缀，只有所属工位可继续获取；其他工位已经成功的前缀不会退款，也不能提前使用该候选尚未确定的产物。没有未确定候选时，普通 continuation 可一起获取并在独立 source lanes 上并行应用。保存点不创建另一 fence 或子批次，不提前 complete，最终外层失败仍能撤销成功候选。

rollbackTo 登记局部方向，业务先按逆序撤销，原 Source.resolve 只补偿各自后缀；finishRollbackTo 等全部终态及真实退出后才裁剪清单。UNKNOWN 保留原保存点、Stage 和 fence，不重放回调；超时、显式取消或清理故障可扩大为整批回退，已确认后缀不会重复退款。缺完整获取清单时仍保留未登记窗口，不能猜测清理对象后释放。

外部系统须在 continuedAcquisition 声明可能的 continuation，结构 children 会递归参与判断；这包含可能由动态 payload/overlay 引入的续段。startsAlternative 只报告下一步是否开始候选，rejectAlternative 只在准确补偿确认后推进游标。声明及这两个回调不能获取资源或产生其他业务效果。

候选集成验证覆盖子 TICK/END 的 Blocked/Failure、全部禁用、modifier 仅一次、外层失败、嵌套同定义路径与产物链、共享来源的其他工位前缀、UNKNOWN 补偿和取消，以及 source 尚未退出时的超时与取消。另有两个真实来源线程会合测试，防止普通 continuation 被退化为串行；不验证或复刻线程映射 hash。完整 pure prepare 与运行期工作预算仍待后续迁移。

源码入口：PM F:/clone/PrototypeMachinery/src/main/kotlin/impl/recipe/requirement/component/system/CheckpointRequirementSystem.kt、SelectiveRequirementSystem.kt、AttributeModifierRequirementSystem.kt；PE recipe/requirement/CheckpointRequirementSystem.java、RecipeStageExecutor、ResourceBatch、StorageFence、SlottedResourceBuffer。
