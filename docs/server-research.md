# 服务器研究与世界存档

服务器已接入手动研究和正 tier 研究站的世界所有者、同步存档、FTB 成员核对、玩家登录退出和既有调度边界。Java 使用方可调用 `ServerResearch.startManual` 直接开始 tier 0 研究，或 `takeoverManual` 接管已有会话；所有等级也可通过 `ServerResearch.requestStart` 排队开始，输入必须是真实在线服务端玩家。库不注册研究内容、正式机器或玩法数值。

## 所有者与生命周期

`ResearchWorld` 在创建线程持有玩家研究、队伍研究、黑名单、队列、目标进度和手动材料会话。世界、队列和手动会话保存 long 时间、普通资源剩余量与 double 网络累计量，不保存物理研究站材料进程。编解码直接读取业务字段，不写入或检查固定格式版本号，禁止自动删除研究文件。

主类把 FML 生命周期委托给 `CommonProxy`。`serverStarting` 在主世界加载后建立准确的 server/overworld 会话；`serverStopping` 保存并关闭；`serverStopped` 在调度器关闭后提供研究关闭重试，再关闭网络和身份登记。主世界 Save 检查变更后保存，Unload 提供关闭兜底；其他维度不建立第二份研究存档。普通时间/资源进度不再每 tick 强制落盘，物品支付与交付保留事务检查点，见[增量存档](research-persistence.md)。

研究 tick 由 `ServerTaskSchedulers` 的 END 流程通过 `afterOrdinary` 运行。先处理旧 pending，再执行普通及 planned 机器工作，最后研究取得剩余并发和资源。研究站本次扣料同步提交，不保留跨 tick 的物理材料进程。登录退出仍经同一边界核对实际会话，时间使用持久研究时间与主世界时间的单调推进。

## 玩家开始研究的 Java 入口

`ServerResearch.requestStart(player, id, targetLevel, resultConsumer)` 使用现有 `TaskScheduler.submit(..., MAIN_THREAD)`。普通任务执行前已有网络 refresh，旧研究/source pending 未结清时任务留在原提交队列。执行时重新捕获实际在线玩家对象、FTB 成员及角色；离线、换 server/world 或同 UUID 换玩家实例的旧请求返回 STALE_CONTEXT。关闭前尚未执行的请求跟随 scheduler 生命周期丢弃，不保存为已入队研究。

正 tier 从 `ResearchStations.admission(teamId)` 读取当前活动网络中心、选中数据中心和同队各维度活动研究站的最高等级，及当前可用 numeric 向量。它不申请 THREAD、不轮换实际工作站、不消耗算力或材料。开始条件执行前后重验原 publication、设备许可与捕获的可用余额，不能把客户端自报等级/资源作为事实，也不能用条件回调后另捕获的新许可替换原证明。

成功入队沿用 `ResearchWorld.start` 的真实请求者条件和单一世界保存；resultConsumer 只在正常结果返回后执行。正 tier 材料会在后续研究 tick 推进。tier 0 沿原手动协议，直接开始/接管仍保留。此处已提供实际服务器 Java 入口，packet/GUI 已通过 AE2 容器接入，见[研究界面](research-gui.md)。奖励使用 ServerResearch.requestReward 经相同 MAIN 边界领取，复核实际玩家会话，详细持有者与物品证据见[服务器奖励](research-rewards.md)。

## 个人遗忘与绝对等级管理

`ServerResearch.requestForgetPersonal/requestSetPlayerLevel/requestSetTeamLevel` 复用原MAIN、server/world/player session与原窗口guard。个人仅本人当前队伍，管理员要求实际operator；PLAYER经持久FTB owner解析支持离线目标，并核所有peer成员一致性。TEAM以Universe实际稳定UID/类型/有效性及成员反向解析核对；显式未活跃历史ID可使用空成员来源，Universe不可用则暂停，不能按缺provider当作队伍消失。

`ResearchWorld.forgetPersonal/setPlayerResearchLevel/setTeamResearchLevel` 执行匹配来源kind、普通/非special、范围与准确权限重验。遗忘阈值k仅删个人k及以上后缀，管理为绝对等级，0移除。未变返回UNCHANGED；拒绝或失效不创建新来源表。上调复用原completion receipt、个人rewardLevels为空，下调复用原down-only source mutation；不更改下载的降级限制，不创造领取资格或调用奖励/开始事件，无材料/FE/XP费用。

下载预留、其他reward/completion writer与所有保留旧队伍的同源冻结完成均保护写入。ManualResearch.blocksSourceReduction读取真实completionIntent和冻结recipients，包括最终材料效果前阶段；来源减少只检查相关source。等级变更不立即prune/退款/丢工作，原协调先完成既有扣料/完成义务再prune。全部原领取组、children、fragments、状态与时间保留。

World仍使用单writer/operate/pendingSave：仅自身准确receipt允许原snapshot读取，guard/authority异常在precommit保护内rollback并聚合清理错误；commit后IO失败保留after和原pendingSave并complete自身receipt，下一操作先重试准确快照。新旧MAIN动作统一原窗口finalized清理，入队/异步/反馈失败保留primary与suppressed，不伪装业务拒绝。

本阶段新增11行为；最终扩大受影响497 tests/54 suites、正常完整build1579 tests/200 suites全零，13 Java全级IDEA MCP与完整项目文件读回/磁盘一致、MCP build成功。固定V5独立审计已闭环两项；准确原失败/最终证据和阶段精确提交见 `build/staging/research-actions/`。原FTB/World/packet/GUI实机未验收，未自行启动客户端或服务端。

## 同步存档与失败恢复

`ResearchStateStore` 独占主世界 `data/prototype_eng_research/`，不注册进 MapStorage。玩家、队伍、下载/回执/导入各自分片，只替换变化记录；meta 保留身份与校验索引，pending 协调跨分片提交与恢复。`ResearchStateFile` 保留旧单文件 codec 与读取入口，首次迁移后旧文件不再作为活动 writer。压缩、同目录临时文件、批量写、文件描述符 sync、原子替换和最终 force 保留；不支持原子替换、损坏或不可读存档均报错，不当作空世界。具体文件、迁移和平台保证边界见[增量存档](research-persistence.md)。

世界检查点失败保留准确快照。研究站 pending 时 Save 只登记延后检查点，全部来源与业务终态确认后才保存；UNKNOWN 保留原 execution，关闭必须等待真实结算。已结清的研究站材料事件异常保留原错误和材料现场，世界进入显式 faulted 状态，释放调度所有权但禁止新工作与保存。下一次业务开始前先重写该快照，再恢复各手动执行器的私有检查点，之后才允许改变时间、成员和队列。写入前后校验世界仍对应同一快照；外部回调和 IO 异常保留原原因，不转为正常业务结果。关闭失败保留 closing 会话与数据，后续 Save/Unload/Stopped 可重试。手动研究的玩家回执/适配器失败仍沿原意图和回执恢复，不被研究站的致命状态分支拦截。

手动研究入队后若尚未安装会话，失败可移除准确的新队列项；若已经安装会话或待保存意图，则保留队列与会话，避免保存失败后单方面删队头。玩家回执与世界记录的详细顺序见[手动研究](manual-research.md)；两个文件之间没有文件系统原子提交，恢复依赖已有回执证据。

## 成员变化与无效研究

捕获种子覆盖在线玩家和所有活跃队列/会话涉及的玩家，并递归查询成员，包含离线玩家。FTB owner/moderator 与服务器 OP 由服务端读取；每个成员的反向队伍归属必须一致，操作前后重验当前会话、成员和在线玩家对象。Universe 不可用时暂停，不当作队伍解散。

登录只登记玩家，不自动恢复手动研究；退出暂停其所有 ACTIVE 会话，包括非队头。世界重载将 ACTIVE 转为等待接管，保留其他恢复状态。恢复后的手动执行器与后加入的成员共享同一个全局玩家来源表。

已有队列只核对结构资格，不重新调用开始条件。研究失效或队伍消失时，先处理手动会话持有的扣料/完成义务，再 prune 队列：未消费且有证据的准备可取消；已提交回执先结算；已付清的完成按冻结成员传播；未知回执或原玩家离线保留待恢复状态。`RESEARCH_INVALIDATED` 保留取消原因。个人来源和奖励证据不会因换队或解散被删除。

正 tier 的目标进度保存在世界 V6，切换、移出队列和站点卸载后继续保留。每次推进重新选取当前合格站点，删除了原站点位置恢复、材料配方进程和永久破坏清理。手动研究继续按玩家材料凭据恢复，详见[研究站执行](research-stations.md)与[手动研究](manual-research.md)。

## 范围与验证

玩家研究入队阶段新增 7 项行为测试；完整 Gradle 构建通过 886 项测试、124 suites，零失败/错误/跳过。3 个本阶段 src Java 与 Tags 共 4 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-admission-final.json。原 23 份修改保留，未启动游戏或服务端。 入队逻辑测试使用真实机器装配、网络许可/账本及 TeamResearchStart；玩家对象/FTB/MAIN 提交的平台适配按静态代码与 MCP 检查，不以纯 JVM 测试冒充游戏调用。

研究站服务器/存档阶段新增 42 项行为测试；完整 Gradle 构建通过 879 项测试、123 suites，零失败/错误/跳过。本阶段 18 个 src Java 与 Tags 共 19 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-stations-server-final.json。原 23 份用户修改保留，未启动游戏或服务端。

已接服务器 Java 入口包括所有等级的排队开始、tier 0 直接开始/接管，以及正 tier 队头的设备选择、材料/计算提交和自动推进；普通配方研究/计算消费者见[配方消费者](network-recipes.md)。服务器奖励领取与世界检查点已接，见[服务器奖励](research-rewards.md)；研究界面/packet、普通数据库下载和自动同步已接入，普通物品转换与接口式用户盘共同导入已接，个人遗忘/管理员等级本阶段已接；未知奖励交付继续等待证据。`ResearchWorld` 可供外部服务器宿主使用，但其可调用 API 不等于上述玩法流程已接通。

最初手动服务器阶段新增 50 项逻辑测试：世界 owner/成员/失败重试/恢复，结构资格，手动无效取消，以及严格世界 codec 与临时目录真实文件 IO。完整构建通过 741 项测试、104 suites，无失败/错误/跳过。本机 IDEA MCP 对 16 个本阶段 Java 与生成 Tags 共 17 文件逐个 `errorsOnly:false`，零错误/警告/弱警告；MCP 项目构建成功。证据：`build/reports/idea/server-research-final.json`。

没有启动 Minecraft、Bootstrap 或 FML。真实游戏中的 FTB 事件顺序和背包操作尚无实机验收；这不由纯 JVM 文件测试替代。
