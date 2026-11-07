# 研究定义、资格、完成与奖励执行

已迁移 PN 的研究定义、资格计算、权限与入队、队列推进、手动投料与恢复、完成传播和奖励领取逻辑。它们有实际可调用的 Java 执行入口，尚未形成完整的服务器研究流程；研究站材料/计算提交、下载和存档服务接线仍须继续完成。

## 定义与注册

`CommonProxy.postInit` 在计算资源冻结之后发布 `ResearchRegistrationEvent`，随后冻结 `ResearchRegistry`，再发布配方和机器注册。使用方通过此事件定义研究内容，库不注册正式研究树。

- `ResearchDefinition`：身份、最高等级、specialOnly、下载策略和等级成本、unlock、startCondition。
- `ResearchLevelStage`：连续等级区间、tier、前置、互斥、材料需求、资源公式、稀疏的逐等级奖励选择。
- `ResearchResourceFormula`：沿 PN 的阶段相对 affine 公式，计算需求时精确检查溢出；每段授予上限与总需求分离。
- `ResearchRegistry`：冻结前检查引用、全图前置环、各覆盖阶段的互斥对称性、计算资源元数据。`Integer.MAX_VALUE` 末段不逐等级展开。

材料使用本工程 `RecipeRequirement<?>`，没有复制 PM 旧类型或跨模组快照。tier 0 只接受旧手动消费者实际支持的静态 AEItemKey START 输入；旧代码只取 ITEM inputs 投影、忽略其他效果，新代码明确拒绝这些不支持的完整载荷。普通阶段保存完整需求，其研究站执行仍待接入，不能将保存定义视为已经消耗材料。

来源：PN `ResearchDefinitionImpl`、`ResearchLevelStageImpl`、`ResearchResourceFormulaImpl`、`ResearchRegistryImpl`。合并不必要的接口/Impl 镜像，保留真实扩展职责 `ResearchStartCondition`。

## 资格与来源

`ResearchEligibility.calculate` 实际计算 `TeamResearchView`：

- DATABASE_AND_ITEM 仅聚合当前成员的个人最大等级；换队不清掉个人源。
- ITEM_ONLY 仅读取直属队伍源；拒绝个人/队伍混存、未知研究和普通源中的 specialOnly。
- 黑名单归零，前置按等级阶段级联回退；互斥只阻止新目标，不回退已完成等级。
- `canStart` 校验实际请求者属于捕获成员、目标为当前有效等级加一，再执行真实 `startCondition.canStart`。回调异常传播。
- `structurallyEligible` 供已有队列重建使用，只核对结构资格，不重新执行用户开始条件。

沿 `TeamResearchViewCalculatorImpl` 的算法，把旧逐等级回退改为逐阶段回退。这里消费调用方捕获的队伍和资源事实；尚未自动绑定 FTB 成员变化、中心状态或研究队列。

## 实际入队与研究队列

`ResearchPermissionPolicy` 提供真实扩展接口，默认策略沿 PN：同队成员可开始研究，队长/管理员可维护本队黑名单，服务器操作员可执行管理操作。`ResearchPermissionContext` 由服务器捕获身份与角色，不能接受客户端自报权限。

`TeamResearchStart.start` 先检查当前队伍/定义、权限与结构资格；正tier要求活动中心、足够等级的数据中心及研究站。实际startCondition收到真实请求者与资源，即使匿名预览拒绝，也按这次实际请求重算。回调前后重查current证明与队列epoch，拒绝重入；重复/满队/忙不执行无意义的开始条件，也不消耗材料。tier0的准确入队结果交给`ManualResearch.begin`绑定会话，再把队列和会话一起保存。

`TeamResearchQueue` 保留容量5和稳定entryId，只规划队头。numeric按每种资源独立累计，单段授予为 `min(available,每站limit×参与站数,剩余需求)`，logical lease由真实站点消费者另行申请；plan不授予网络或库存权限。GrantMutation与PruneMutation实现已有ReversibleTransaction，持准确队列写入权，提交/补偿完成前不允许保存或其他写入。回滚恢复业务内容但递增epoch，旧plan不会重新有效。

成员变化的prune只重查next-level、前置、互斥和黑名单；不能根据包含startCondition的预览缓存取消已入队目标。队列、结果与mutation都限创建/restore的owner线程，快照供异步处理。ResearchQueueNbt保存顺序、进度、nextEntryId、epoch和授予时间；恢复不恢复旧plan。时间参数使用统一且可持久恢复的服务器时间线，例如主世界totalWorldTime，不能混用维度时间或重启归零tickCounter。

实际规则和旧材料投影缺陷见[执行审计](pn-research-execution-audit.md)。队列目前尚未与完整材料需求、逻辑lease、站点选择及完成传播接成同一个服务器工作提交。

## 完成传播与持久数据

手动研究已通过`ManualResearch.advance`接入逐项物料计划、玩家PREPARED/COMMITTED回执、九状态世界恢复、暂停接管和冻结完成传播。正常缺料不创建扣料记录；已提交恢复不重扣；检查点异常保留准确快照并原样传播。公开库存和检查点接口供服务器所有者绑定，详细顺序、迁移修正与尚未完成的服务器接线见[手动研究](manual-research.md)。

`TeamResearchCompletion.prepare/prepareFrozen` 复用 PN `TeamResearchCompletionImpl` 的双来源传播与恢复基线规则：

- DATABASE_AND_ITEM 提升冻结成员的个人等级；ITEM_ONLY 仅提升 `TeamOwnedResearch`，但两者都给成员创建逐研究等级奖励资格。
- 完成仅创建 UNCLAIMED 资格，不执行奖励回调。
- 普通重复完成可修复缺失资格；恢复提交使用原成员基线，保留已存在领取证据。
- 按各阶段实际奖励等级遍历，移除旧 `boolean[targetLevel+1]` 和逐等级扫描。
- 准备后的具体玩家/队伍事务持有原状态写入权，支持提交、反向回滚和最终完成；源被下载或领取占用时正常返回 busy。准备失败释放之前已准备的准确实例。

`PlayerResearchStateNbt` 保存个人等级和完整领取状态；`TeamOwnedResearch.CODEC` 保存直属等级。它们不保存运行权限，恢复必须重新通过当前定义/成员/业务资格校验。尚未注册完整 WorldSavedData/服务器研究存档所有者，也尚未把材料、研究进度和完成传播接成同一个研究站工作提交。

## 奖励回调与物品交付

`ResearchRewardClaimer.claim` 已实际执行选择、持久 checkpoint、逐 child/fragment 交付和恢复。固定组选择、事件幂等键和物品片段保存后不可任意替换；事件在领取时调用 `RewardEventAction.deliver`，完成过的 child 不重放。已开始但不确定的物品交付先查询真实观察或缺失证明，不盲目重发。外部 callback/checkpoint 异常在保留恢复状态、释放 session 后原样抛出；只有明确业务暂停才返回 RETRY_LATER。

`MinecraftRewardItemDeliveryAdapter` 提供真实 AEItemKey 物品的背包/掉落交付、观察和同步保存入口。它仍不是自动启用的完整领取功能：

- 尚无领取 packet/UI 与服务器研究存档服务绑定；持久 `RewardClaimCheckpoint` 由业务所有者提供。
- 默认只把实际观察视为已交付；加载中的背包/实体扫描不能证明全局缺失。缺失证明扩展默认返回 false，完整交互冻结和权威存储恢复仍待接入。
- 未运行 Minecraft 或服务端，游戏交互、标签移动及掉落恢复尚未做实机验证。

来源：PN `PlayerResearchStateImpl`、`RewardClaimImpl`、`ResearchRewardClaimImpl` 和物品 delivery adapter。下载事件、特殊磁盘、队列服务器接线与实际配方研究/计算条件仍未完成；CraftTweaker 按用户要求最后实现。

## 验证

累计 98 项研究行为测试覆盖权限/真实入队/队列进度、手动材料/会话/回执/执行恢复，以及：公式/阶段/冻结引用，双来源/前置回退/真实开始条件，奖励 checkpoint 失败恢复和精确片段，双策略完成/补偿/稀疏无限等级/存档校验。手动执行测试隔离了依赖Bootstrap的真实AEItemKey构造，运行真实队列和完成状态机；不替代游戏背包验证。最终构建和 IDEA MCP 结果记入实施进度；不以这些测试代替尚未接入的服务器流程。
