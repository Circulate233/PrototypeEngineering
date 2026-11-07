# 普通物品化研究下载与恢复

普通数据库下载与物品转换是两条真实服务器通路。数据库下载只把当前云研究写入活动物理数据库；普通物品转换调用材料注册的 `createOutput`，消费准确槽位的一件材料和冻结来源的等级费用，再交付完整原生产物。两者均保留永久奖励资格，不把数据库内容作为可扣减的研究来源。

## 旧 PN 语义与本次接线

已对照旧 `ResearchItemDownloadExecutionImpl` 的真实请求、来源冻结、玩家材料凭据和恢复顺序。`DATABASE_AND_ITEM` 的目标完成等级只需不高于活动数据库已存等级，同时满足数据库研究 tier 门槛；冻结全体当前成员，每人原始个人等级均须不少于 `itemDownloadLevelCost`，各扣同一费用。不能要求每人的等级达到下载目标，也不能额外要求当前云等级达到数据库已存目标。`ITEM_ONLY` 要求目标不高于队伍直属等级，费用仅从直属来源扣除。费用默认 1，可配置为 0，与输出研究完成等级独立。

普通转换拒绝 `specialOnly`，在扣料前核对黑名单和目标阶段互斥规则。当前活动物理数据库的 owner 必须属于捕获成员；FTB 成员、角色权限、实际 actor/window、原数据库实例及 publication 证明由服务器重新捕获。全体冻结成员的真实空来源在费用为 0 时也会先创建，不能用缺失来源代替预约。

## 公开入口与真实调用

- `ResearchItemDownloadRegistry` 按完整材料 AEItemKey 注册回调。`CommonProxy` 在研究注册表 freeze 后发布 `ResearchItemDownloadRegistrationEvent` 并冻结下载注册；不注册正式配方或游戏数值。
- `ResearchItemDownloadContext` 提供真实 actor/server/world、准确活动数据库 UUID、队伍、当前 actor world tick、目标等级、冻结单件材料及 operation UUID。回调只运行于主线程真实 actor 能力上下文。
- `ServerResearch.requestItemDownload` 通过既有 MAIN 队列重捕获请求授权，默认 `ResearchContainer`/`ResearchGui` 的物品下载动作绑定最后绘制的研究、原队伍/数据库、当前选中快捷栏槽和 nonce。Java 平台入口可使用准确的 0..40 槽。
- `ResearchWorld.requestItemDownload` 持有唯一世界 writer；新 actor+nonce 才运行事件，成功后冻结完整 native Count=1 模板与独立 long 数量，先持久化操作再允许材料效果。相同 nonce 和相同 intent 重试同一操作；不同 intent 返回冲突，不能再运行回调或另建操作。
- `ResearchWorld.recoverItemDownloads`、`pendingDownloadOperations` 和 `downloadReceipts` 供服务器边界、登录及后续物品/实体加载恢复。`abortItemDownload` 仅在完整 before 库存证明材料未扣时允许明确终止。

授权 `current` 在材料提交前重新核对 FTB、权限、物理数据库及 actor/window。World 自己核对原来源 before、预约和黑名单/互斥规则；不能把读取阶段的 cloud snapshot 证明用于 World 已初始化来源或持有短 writer 的阶段。材料提交后使用独立 `actorCurrent` 控制交付能力，换队或原数据库变化不抹去已承担的原来源义务。

## 唯一存档与提交顺序

Schema 3 的 `ResearchWorldSnapshot` 同时包含所有玩家/队伍、手动/站点会话、pending 下载及 terminal receipt。`ResearchWorldNbt` 严格读写这一明确 schema，没有第二份下载世界存档，也不回退旧 schema。

1. `PREPARED` 保存原 intent、成员/个人或直属来源 before/after、材料完整 native tag/ForgeCaps 与库存 before/after、输出模板和数量。按 researchId 预约，其他研究仍可推进。
2. 保存 `materialArmed` 后才写玩家 PREPARED 凭据；消费前必须完整 before 相等，只扣准确槽一件。真实库存写入观察到完整 after 后，玩家 COMMITTED 凭据同步保存；World 再保存 `MATERIAL_COMMITTED`。
3. 按冻结 operation UUID 借用原预约、提交原来源费用，用 `Prepared.owns/snapshot` 精确放行自己短 writer 的完整世界快照，保存 `SOURCES_COMMITTED`。保存失败保留唯一 `pendingSave` 和 after 值；先 complete 短 writer，预约直到准确 pending flush 成功才 release，不能回滚已经尝试持久化的来源结果。
4. `SOURCES_COMMITTED` 已可靠保存后才清理玩家材料凭据。恢复此阶段及其后不重新预约、不再要求当前来源等级恰好等于旧 after，正常新研究或遗忘不能导致再次扣费。
5. 从 frozen native 模板通过共享 `ItemDeliveryAdapter` 冻结完整计划，先保存 `DELIVERING` 操作，再逐片段 prepare、保存开始状态、全量或零效果交付、真实 effect commit 和 World CONFIRMED。片段只能按 `requireTransitionTo` 改状态，不能改 parent、完整 payload、数量或目标。
6. 材料凭据可靠清理且全部片段已 durable CONFIRMED 后，转为 terminal nonce receipt，丢弃重型库存镜像。保留完整 CONFIRMED 片段，供卸载后再加载的掉落和登录后重复清标签；missing/released 从不补发。

`PREPARED`/`MATERIAL_COMMITTED` 重载时从持久操作重建原 before 预约；离线原成员继续在世界来源表和 trackedPlayers 中保留。等级下降导致的旧队列失效仍交由 World 原有 pending 结算及 prune 机制。PREPARED 凭据加 after 库存仅表示未知，不能据内容相等推定该操作已扣；缺失、损坏、冲突或不确定材料/交付证据保留恢复错误或 pending。

## 分级盘当前边界

`ResearchDiskData`、严格 native codec 和 `ResearchDiskEncodingEvent` 已存在，可由 addon 把自己的空白材料和输出物品注册到上述真实物品转换流程。编码要求 `researchTierForLevel(目标完成等级) ≤ diskTier`，导入规则要求 `diskTier ≤ databaseTier`；完成等级独立保存。

当前没有实际默认研究盘物品、正式盘配方或真实盘导入的扣盘/数据库共同持久事务。旧 `SpecialDiskInteractionImpl` 先写库再 shrink 的失败窗口仍需重新设计并接通，不能把核心编码/导入规则或可调用 storage 步骤称为完整导入链。R31 控制器/仓室继承及流体工作属于另一个阶段。

## 验证

下载世界新增 18 项行为测试覆盖原离线成员费用、零费用空来源、黑名单拒绝、armed/save 失败、材料提交后授权变化、来源 pending 保存失败、已提交来源变化、未知材料/物品证据、完整计划先保存、真实观察恢复、nonce 冲突、terminal 清理和明确 abort。材料 41 槽测试实际发现并修正移除最后槽后空 NBT list 原生存盘类型变化造成的 after 不匹配。

本轮全 `gradlew.bat test --console=plain`：1164 tests / 150 suites，零失败、错误或跳过。World 核心 8 Java、材料修复 2 Java、旧世界 schema 契约测试 1 Java 均取得 IDEA MCP `errorsOnly:false` 完整零错误/警告/弱警告。证据：`build/reports/idea/download-world-final.json`、`download-world-verification-final.json`；平台及来源检查另见 `download-platform-final.json`。共享 ItemDeliveryTemplates 进一步只递归规范原生存盘会变型的空列表，不反复 gzip 或整栈往返；新增一项真实 native `.dat` 写入/回读后恢复已观察片段且不重复交付的测试，相关下载/奖励 suite 已在合并定向运行中全部通过，证据 `build/reports/tests/ae-physical-extension/combined-run-output.txt`。该合并运行中唯一失败属于独立物理层新 fixture，修复后的该 fixture 定向重跑已通过；不将合并 141 项称为全通过。新增共享修复 2 Java IDEA MCP 全级零诊断，证据 `build/reports/idea/download-native-template-final.json`。没有启动 Minecraft 客户端或服务端，实机交互未列为通过。
