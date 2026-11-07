# 服务器研究与世界存档

服务器已接入手动研究和正 tier 研究站的世界所有者、同步存档、FTB 成员核对、玩家登录退出和既有调度边界。Java 使用方可调用 `ServerResearch.startManual` 直接开始 tier 0 研究，或 `takeoverManual` 接管已有会话；所有等级也可通过 `ServerResearch.requestStart` 排队开始，输入必须是真实在线服务端玩家。库不注册研究内容、正式机器或玩法数值。

## 所有者与生命周期

`ResearchWorld` 在创建线程持有全部玩家研究来源、队伍直属研究、黑名单、队列、手动会话和研究站材料会话。`ResearchWorldSnapshot` 是不可变快照，`ResearchWorldNbt` 复用现有玩家、队伍、队列和手动会话 codec，严格校验身份、等级、队头、时间及恢复所需玩家引用。

主类把 FML 生命周期委托给 `CommonProxy`。`serverStarting` 在主世界加载后建立准确的 server/overworld 会话；`serverStopping` 保存并关闭；`serverStopped` 在调度器关闭后提供研究关闭重试，再关闭网络和身份登记。主世界 Save 同步检查点，Unload 提供关闭兜底；其他维度不建立第二份研究存档。

研究 tick 由 `ServerTaskSchedulers` 的既有 END 流程在普通工作之前安装为 `PlannedExecution`，调度器先持有后执行。旧 pending 和 worker 先结清，之后研究申请共享 THREAD，普通工作才捕获新网络并执行。未完成研究保留原队伍游标、来源和生命周期句柄，不积压新的投料调用。登录/退出继续排入同一个 boundary，执行时重验 server/session/player 代际。未初始化或关闭中的会话正常跳过。时间取持久研究时间与主世界时间的单调推进，世界时间存盘落后于研究检查点时也不倒退。

## 玩家开始研究的 Java 入口

`ServerResearch.requestStart(player, id, targetLevel, resultConsumer)` 使用现有 `TaskScheduler.submit(..., MAIN_THREAD)`。普通任务执行前已有网络 refresh，旧研究/source pending 未结清时任务留在原提交队列。执行时重新捕获实际在线玩家对象、FTB 成员及角色；离线、换 server/world 或同 UUID 换玩家实例的旧请求返回 STALE_CONTEXT。关闭前尚未执行的请求跟随 scheduler 生命周期丢弃，不保存为已入队研究。

正 tier 从 `ResearchStations.admission(teamId)` 读取当前活动网络中心、选中数据中心和同队各维度活动研究站的最高等级，及当前可用 numeric 向量。它不申请 THREAD、不轮换实际工作站、不消耗算力或材料。开始条件执行前后重验原 publication、设备许可与捕获的可用余额，不能把客户端自报等级/资源作为事实，也不能用条件回调后另捕获的新许可替换原证明。

成功入队沿用 `ResearchWorld.start` 的真实请求者条件和单一世界保存；resultConsumer 只在正常结果返回后执行。正 tier 材料会在后续研究 tick 推进。tier 0 沿原手动协议，直接开始/接管仍保留。此处已提供实际服务器 Java 入口，packet/GUI 已通过 AE2 容器接入，见[研究界面](research-gui.md)。奖励使用 ServerResearch.requestReward 经相同 MAIN 边界领取，复核实际玩家会话，详细持有者与物品证据见[服务器奖励](research-rewards.md)。

## 同步存档与失败恢复

`ResearchStateFile` 独占主世界 `data/prototype_eng_research.dat`，不注册进 MapStorage。沿用 PN 的压缩 `{data: …}`、同目录临时文件、文件描述符 sync、原子替换和最终 force；不支持原子替换就失败，损坏或不可读存档不当作空世界。

世界检查点失败保留准确快照。研究站 pending 时 Save 只登记延后检查点，全部来源与业务终态确认后才保存；UNKNOWN 保留原 execution，关闭必须等待真实结算。已结清的研究站材料事件异常保留原错误和材料现场，世界进入显式 faulted 状态，释放调度所有权但禁止新工作与保存。下一次业务开始前先重写该快照，再恢复各手动执行器的私有检查点，之后才允许改变时间、成员和队列。写入前后校验世界仍对应同一快照；外部回调和 IO 异常保留原原因，不转为正常业务结果。关闭失败保留 closing 会话与数据，后续 Save/Unload/Stopped 可重试。手动研究的玩家回执/适配器失败仍沿原意图和回执恢复，不被研究站的致命状态分支拦截。

手动研究入队后若尚未安装会话，失败可移除准确的新队列项；若已经安装会话或待保存意图，则保留队列与会话，避免保存失败后单方面删队头。玩家回执与世界记录的详细顺序见[手动研究](manual-research.md)；两个文件之间没有文件系统原子提交，恢复依赖已有回执证据。

## 成员变化与无效研究

捕获种子覆盖在线玩家和所有活跃队列/会话涉及的玩家，并递归查询成员，包含离线玩家。FTB owner/moderator 与服务器 OP 由服务端读取；每个成员的反向队伍归属必须一致，操作前后重验当前会话、成员和在线玩家对象。Universe 不可用时暂停，不当作队伍解散。

登录只登记玩家，不自动恢复手动研究；退出暂停其所有 ACTIVE 会话，包括非队头。世界重载将 ACTIVE 转为等待接管，保留其他恢复状态。恢复后的手动执行器与后加入的成员共享同一个全局玩家来源表。

已有队列只核对结构资格，不重新调用开始条件。研究失效或队伍消失时，先处理手动会话持有的扣料/完成义务，再 prune 队列：未消费且有证据的准备可取消；已提交回执先结算；已付清的完成按冻结成员传播；未知回执或原玩家离线保留待恢复状态。`RESEARCH_INVALIDATED` 保留取消原因。个人来源和奖励证据不会因换队或解散被删除。

正 tier 材料会话与队列/玩家来源共用 Schema 5 世界存档。正常恢复要求原站真实身份、位置和同一 world，不恢复旧权限。换队后的失效会话使用只允许取消的站点证明；真实永久破坏由 loaded/未装配控制器的生命周期通知，沿原进程清理，同服卸载后的仅取消现场仍保留原 owner。cold raw 没有运行中的事务或局部效果，精确销毁可以直接终结；重启时只读已加载的原物理位置恢复遗漏的销毁，不把 registry 缺少、未加载或外部未知宿主当作破坏。旧/终止 checkpoint 写失败各自保留准确快照与事实，取消故障拒绝再保存旧 tag。详见[研究站执行](research-stations.md)。

## 范围与验证

玩家研究入队阶段新增 7 项行为测试；完整 Gradle 构建通过 886 项测试、124 suites，零失败/错误/跳过。3 个本阶段 src Java 与 Tags 共 4 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-admission-final.json。原 23 份修改保留，未启动游戏或服务端。 入队逻辑测试使用真实机器装配、网络许可/账本及 TeamResearchStart；玩家对象/FTB/MAIN 提交的平台适配按静态代码与 MCP 检查，不以纯 JVM 测试冒充游戏调用。

研究站服务器/存档阶段新增 42 项行为测试；完整 Gradle 构建通过 879 项测试、123 suites，零失败/错误/跳过。本阶段 18 个 src Java 与 Tags 共 19 文件 IDEA MCP errorsOnly:false 零错误/警告/弱警告，MCP build_project 成功。证据 build/reports/idea/research-stations-server-final.json。原 23 份用户修改保留，未启动游戏或服务端。

已接服务器 Java 入口包括所有等级的排队开始、tier 0 直接开始/接管，以及正 tier 队头的设备选择、材料/计算提交和自动推进；普通配方研究/计算消费者见[配方消费者](network-recipes.md)。服务器奖励领取与世界检查点已接，见[服务器奖励](research-rewards.md)；研究界面/packet、普通数据库下载和自动同步已接入，物品化下载和特殊磁盘消费恢复仍未完成；未知奖励交付继续等待证据。`ResearchWorld` 可供外部服务器宿主使用，但其可调用 API 不等于上述玩法流程已接通。

最初手动服务器阶段新增 50 项逻辑测试：世界 owner/成员/失败重试/恢复，结构资格，手动无效取消，以及严格世界 codec 与临时目录真实文件 IO。完整构建通过 741 项测试、104 suites，无失败/错误/跳过。本机 IDEA MCP 对 16 个本阶段 Java 与生成 Tags 共 17 文件逐个 `errorsOnly:false`，零错误/警告/弱警告；MCP 项目构建成功。证据：`build/reports/idea/server-research-final.json`。

没有启动 Minecraft、Bootstrap 或 FML。真实游戏中的 FTB 事件顺序和背包操作尚无实机验收；这不由纯 JVM 文件测试替代。
