# 事务确认恢复与 revision 清理额度

本阶段沿用现有需求事务、RecipeProcess、资源/FE reservation，补齐跨lane批次的业务确认与版本额度。正式planned coordinator、阶段handle、tick session和工位批次驱动仍未接入；这不是另一套固定配方引擎。

## 业务事务与准确确认

公开 ReversibleTransaction 位于 api.transaction，定义真实业务职责：commit保留undo，complete释放undo，rollback撤销获取和提交的效果。RequirementTransaction直接扩展它，继续提供需求result与resource reservations。ResourceBatch接受逐事务列表，冻结每个准确实例，按顺序commit、complete，逆序rollback；原三个Runnable重载是默认UNKNOWN的便利入口。

每个终态回调有独立的成功/待确认记录。回调抛错后先尝试其余清理，再聚合抛出错误；后续 resolveBusiness 只查询失败项，成功项不重放。resolution不执行新的业务效果，确认可以释放框架的acquisition计数：

| 结果 | 含义 |
|---|---|
| UNKNOWN | 没有足够证明，保留pending，不盲重试，也不伪装成仍在执行。 |
| REVERSIBLE | 准确原事务仍能安全执行已经决定的终态动作，包括先前部分清理的情况；不允许重放commit。 |
| COMPLETED / ROLLED_BACK | 对应终态已经发生，确认后不再调用该动作。 |

complete决策一旦记录，任何后续错误都不能转成rollback。与既定方向矛盾的状态是故障，不释放隔离。query本身失败同样保留准确事务与pending状态。旧扩展不实现query时仍可正常运行；其终态回调若违约抛错，不能凭猜测重放或假完成。

## 需求作用域与复合事务

RecipeProcess.ScopedRequirement保留原RequirementPath和获取时的冻结Tick，commit/complete/rollback/query均恢复这两份作用域，退出后还原调用方上下文。即使外部正处于另一个Tick或子需求嵌套，恢复查询也使用原获取语义。commit只可尝试一次；终态回调和query禁止同一事务重入，避免listener重复执行业务。

终态回调抛错不会再于finally无条件closed并减少acquiredTransactions。只有成功返回或准确query确认目标终态才关闭一次；未确认时process继续拒绝quiescent/save/detach。确认后可以证明物理上已收束，但原persistenceFailure仍保留，恢复不会把业务故障伪装成健康进程或自动恢复执行。

检查点生命周期与选择器使用内部TransactionGroup追踪各子事务；透明ChildTransaction/CapturedTransaction转发准确query。已完成子不重放；查询阶段不产生新业务效果，重入也拒绝。直接绕过UNKNOWN确认调用未就绪的终态动作属于契约错误，正常pending流程先query再决定是否重试。

选择器的child、modifier/overlay清理、selection completeStart/finish、丢弃选择分别纳入其实际顺序。父query只有全部子事务和自身动作都确认后才能报告终态，不能仅凭child已完成就隐藏自身故障。自身清理没有公开精确query时保持UNKNOWN；没有用整份snapshot覆盖其他writer的属性或overlay。

## 为已预留资源保留 revision 额度

StorageContext以普通long reservedChanges记录已承诺的版本递增，沿共享锁保护。资源/FE reservation只在现有token中增加byte余量：成功reserve为apply与最终complete/rollback保留2次；apply消费1次；终态消费1次，pending直接rollback时返还没用的apply额度。

普通transfer、restore、限额更新及新reserve在修改状态前统一调用context.requireChange，不能花掉已有token额度。valid期间还始终保留独立的1次invalidate额度；常规额度用尽后仍可撤销有效性，并用原token余额完成退款。失效不夺走补偿权，终态可以消耗最后的reserved版本额度。

扩展后端使用reserveChanges/requireReservedChange/reservedChanged并在自己的token保留剩余额度；只用普通changed的外部实现不能被自动视为具有这项保证。所有算术、余额和额度检查必须位于实际mutation前，不能先写状态再因版本不足失败。

无反射边界测试通过包内initializeRevision初始化全新context，再对真实buffer运行操作；入口要求持锁、valid、revision为0、无dirty、无credit、无fence/scope。它不是公开配置或持久恢复API，普通构造始终从0开始。

## 验证与后续

测试覆盖真实资源/FE预留与普通修改交错、失效后退款、pending归还额度、多token共享context和业务失败的整批补偿；业务侧覆盖UNKNOWN后恢复、after-mutation异常、query异常、嵌套路径与冻结Tick、重入、真实checkpoint/selector聚合以及其他writer效果保留。没有Bootstrap/FML或游戏初始化、反射注入、常量/getter/hash专项测试。最终数量与IDEA MCP证据见[实施进度](implementation-status.md)。

下一步在现有RecipeStageExecutor抽出真实acquired-stage handle，补stage lease/version和一次scheduled tick session。每process每round只获取一个当前阶段，整批完成后再进入同tick后继阶段，保留START对selection/overlay的影响；pre/tick/post各一次且不声称可逆。再接正式两次结果屏障、真实退休join、网络owner/team/epoch/预算与工位发布。
