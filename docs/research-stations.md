# 研究站材料与推进

2026-11-07。当前完成显式 Java 消费者、真实站点捕获和材料进程恢复入口；尚未安装到 `ResearchWorld` / `ServerResearch` 的自动执行与世界会话存档。下述执行可以直接调用，但不表示服务器研究站功能已经完整。

## 对齐旧实现与 R29

复核 PN `ResearchStationRuntimeImpl` / `ResearchStationTickImpl` 和 PM 的真实需求系统。保留同队、成型、加载、等级、活动物理数据中心门限，维度轮换与维度内 UUID 顺序，各站共享整机 THREAD 租约。多个站合摊一份材料，每种 numeric 的推进量仍为 `min(研究供给, 每站限额 × 站数, 剩余需求)`；研究读取独立供给，不扣普通配方余额。

R29 确认每次有效推进完整执行材料 START、TICK、END。旧 PN 投影丢掉属性/输出并混合部分阶段，不沿用该缺口。新材料保留完整 `RecipeRequirement`，不新增投影 DTO；每段只在 END 成功后授予研究进度。

## 实际入口

- `ResearchStations.capture(teamId, tier)` 枚举现有唯一机器登记，使用 `MachineNetworkDevice.workPermit()` 和该 publication 的 `deviceSnapshot()`。选同维度、同一 world 实例的合格站，逐站申请 lease，并冻结真实主站及一份合摊 `MachineHatches`。没有第二份站点注册表。
- `ResearchStations.renew(capture)` 在新 tick 重新获取许可和 lease，保持原主站、原站集合、原仓室绑定及同一个合摊对象。站被替换、卸载或仓室绑定变化时正常返回 null，不偷偷把半途材料换到另一批站上。来源证明只读身份/绑定及许可，在资源 fence 内不重新读库存或获取额外锁。
- `ResearchStationWork` 持有一个真实队列和玩家/队伍研究来源。`advance` 从队头规划一段、建立或续办原材料进程，`resolve` 保留并恢复原批，`awaitExit` 只证明来源任务实际退出，`cancel` 在结算后取消未完成进程。每个持久时间值最多成功推进一段，不重复推进同刻的新队头。
- `PlannedRecipeCoordinator.forProcess` 接受独立材料进程，共用原协调器的 source lanes、截止时间、取消、退出屏障与 UNKNOWN 恢复。额外事务只在成功获取阶段后捕获一次；返回 null 为正常拒绝。END 的 queue grant 和 completion 与该阶段物料/FE、进程状态进入同一个 `ResourceBatch`。

调用方必须在独占 owner 边界执行，并持有消费者直到旧来源任务退出且所有事务终态确认。保存、prune、替换站点、重新发布网络或运行其他可能访问相同仓室的工作前，必须处理原 pending。异常仍原样传播；不能因调用抛错就丢弃消费者。

## 阶段顺序与材料数量

`RecipeProcess.materialCycle` 使用真实机器 owner 与冻结仓室，只包含所给材料图，不附加普通 `MachineRecipeRequirements`，避免重复普通配方算力需求。资源和 FE 系统通过 `process.hatches()` 取这份来源；普通配方继续读取当前机器绑定。

START 完成 source apply → business commit → 全部终态确认后，才获取 TICK；TICK 完成后才获取 END。因此 START 选择修改器与产物可供后续阶段使用。后续阶段缺料/输出满或授权失效时，保留先前成功阶段，只重试未成功阶段，不重付 START。取消也不退款历史成功阶段，沿用正常配方规则。

材料周期以一次成功 TICK 作为 END 门限，速度 0、0.5、2 都不会多执行或跳过 TICK；TICK 的 scaled/unscaled 上下文仍保留实际速度时间线，供属性和检查点使用。材料本地并行基数为 1，阻断主站普通配方并行对一份材料的放大；材料自身随后添加的 selector/属性修改器仍可生效。站数只影响研究 numeric 限额，不直接复制材料。

结束业务被拒时，只补偿该次 END 的物料/FE、grant 和 completion，历史 START/TICK 不回滚。结束已经决定 COMPLETE 后，即使外部 complete 结果未知，也保留原 grant/完成结果与原 token，仅查询或执行有明确可重试证明的终态确认，不重放完整材料或重新发放研究。

## 存档范围及后续接线

`RecipeProcessNbt.encode` 对材料周期增加显式 `MaterialCycle` 标志；`decodeMaterialCycle` 复用完整属性、selector、overlay、输入锁和组件 codec，并调用 `restoredMaterialCycle`。普通/材料恢复入口互相拒绝错误模式，速度为 0 的“已成功 TICK、进度仍为 0”合法。恢复仍由新宿主提供仓室与授权，不恢复旧 token/epoch/lease。扩展组件继续需要显式 codec 与定义 revision，不静默丢弃未知状态。

尚未完成：消费者所属世界会话、队头/原站身份/进程存档的共同恢复、真实 tiered start 入口、调度器对研究 pending 的所有权及服务器自动执行。不能只在当前 `runAtBoundary(Runnable)` 回调里调用 `advance` 然后放任普通工作继续：pending fence 或未退出的 source task 必须阻止后续捕获与访问。现有单一世界 writer 只应在全部事务终态确认后保存，写失败保留原 pendingSave。

当前仓室 token 没有可持久结果回执，因此不宣称 vanilla chunk 库存与研究文件跨文件原子。另有既存的嵌套检查点顺序问题：`CheckpointRequirementSystem` 内部仍先获取子 START/TICK/END 再统一提交；子 selector 在 START commit 才生效，不能据本阶段顶层周期通过验证，就声称该嵌套组合也已正确。

## 验证

本阶段新增 34 项行为测试：真实站点资格/维度轮换/租约、完整阶段产物与 selector 顺序、速度/并行、同一 source lanes 的拒绝/取消/UNKNOWN、实际 queue grant/completion 与新宿主完整进程恢复。完整 Gradle 构建通过 837 项测试、118 suites，零失败/错误/跳过；14 个 src Java 与 Tags 共 15 文件 IDEA MCP `errorsOnly:false` 零错误/警告/弱警告，MCP `build_project` 成功。证据 `build/reports/idea/research-stations-core-final.json`。未启动游戏；自动服务器链与跨世界文件恢复仍待实现。
