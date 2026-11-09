# 普通调度、所有权与服务器接线

TaskScheduler 持有完整 PlannedExecution，保留 pending、取消与实际退出证明，并支持先清理后停原 lane 的 tryClose。2A 已将公共三参 scheduler 的正式机器入口接到原组件游标、生命周期、工位 coordinator 与纯 prepare；服务器只追加普通 CONCURRENT 网络 admission/refresh。具体所有权边界见[planned 所有权](scheduler-planned-ownership.md)与[正式执行](planned-execution.md)。

PM JavaTaskSchedulerBackend 的普通 MAIN_THREAD / CONCURRENT 路径已迁移，保留真实线程池、共享资源亲和分组和 join。planned runner、真实配方阶段协调、取消与 deadline 及调度器持有的清理基础现已完成，见[执行屏障](planned-execution.md)和[原实现审计](planned-migration-audit.md)；真实配方资源预留/提交/补偿、纯 prepare、PN 消费与 2A 正式机器接线已完成。`PLANNED_PARALLEL` 只接受交付真实 lifecycle-bound MachineInstance 的 PlannedMachineTask，Runnable 正式提交仍拒绝，正式执行不会落入普通并发。源码入口为 `api/scheduler`、`scheduler/TaskScheduler` 和 `scheduler/ServerTaskSchedulers`。

## 可复用行为与已修正问题

PM [JavaTaskSchedulerBackend.kt](F:/clone/PrototypeMachinery/src/main/kotlin/impl/scheduler/backend/JavaTaskSchedulerBackend.kt:253) 先处理主线程任务，再对普通 worker 做并查集分组和 Future join。共享至少一个 affinity key 的任务传递合并，在固定单线程 lane 中执行；无亲和键任务使用固定工作池。新实现复用并查集、全部 key 的有序 hash 选择固定 lane 的算法；同组成员改为稳定登记顺序，避免原 identityHashCode 排序碰撞及不可控次序。

旧 `joinAll` 捕获所有异常后继续下一 Future，遇到 InterruptedException 时可能把仍运行的任务留在后台；旧 `SafeRunnable` 也会将任务失败只记日志。新实现把异常保留到收束完成后抛出，已经接受的所有普通任务必须完成。一个 affinity 组首错停止该组后续成员，其余已派发组仍全部 join。提交被拒、主线程或元数据回调出错，同样先等待此前已接受的任务退出，再报告错误。

普通任务使用不对外暴露的 FutureTask，不调用 cancel。正常完成/ExecutionException 表示业务动作已经退出；InterruptedException 则清除并记住中断，继续等待同一任务以及后续任务，最后恢复 owner 的中断标志并抛错。不能使用 Future.cancel 的返回值作为退出证明，也不能拿 planned 的提前 terminal outcome 代替物理退出。普通路径没有用超时放弃运行任务；非合作的卡死任务仍会阻止所有权交还，不能宣称已经安全卸载。

一旦 tick 发生执行、元数据、派发或 join 故障，调度器保持 faulted，后续普通执行/登记/提交均拒绝，避免重放任意业务副作用；注销、边界动作和关闭仍可用于清理。线程池由实例懒创建并持有，部分创建失败会清理已创建资源。关闭等待所有 owned executor 真正结束；无法证明终止时保留资源和边界队列，只允许重试清理，不自动重建旧调度器。若已经证明全部终止，即使关闭或清理报告错误也可完成 CLOSED，错误本身不表示仍有活 worker。

## 主阶段中的世界副作用

`ScheduledTask` 是外部任务的真实扩展契约，元数据由 owner 读取，worker 只调用其业务动作。任务按对象身份登记；每次注销后重新登记使用新 Registration 身份，旧捕获候选不得复活。

tick 开始只冻结本次主任务候选和一次性任务批次。主阶段尚无本 tick 普通 worker，owner 的 MAIN_THREAD 回调可以同步登记、注销其他控制器。新主任务留到下 tick，避免放置触发无限递归主任务；主阶段结束后才捕获当前并发任务及其 active/mode/keys，因此新并发机器、卸载机器和修改后的资源亲和键都在此边界生效。当前 tick 已执行的 task 对象不会因换模式或注销重登而再次执行。

元数据回调、并发捕获/派发/join 期间和外部线程不能修改登记；tick/close 始终不能递归进入。`submit` 可以从任意线程加入受锁保护的下一批队列，但本 tick 回调中新提交的 Runnable 只在下 tick 执行。注销只撤销周期性登记，不会取消另行提交的任意 Runnable；异步结构结果仍由 StructureRefresh 的 request/configuration 证明拒绝。

主任务可以同步清理另一台尚未开始执行的机器。`runAtBoundary` 供控制器请求自卸载/自破坏：MAIN 回调返回、MachineLifecycle gate 释放后才清理，在后续 CONCURRENT 捕获前生效；worker 请求等全部已接受 Future 物理退出后才执行。它使用受 submissionLock 保护的独立 fastutil FIFO，外线程仅入队，IDLE owner 立即排空，边界内新增请求继续排队而不递归。元数据回调拒绝请求；边界内允许健康登记和清理注销，仍禁止递归 tick/close。

普通 fault 不清空边界队列；已接受动作尝试一次，错误聚合并继续其余动作。faulted tick 入口也先处理边界清理再拒绝普通执行。close 在证明 executor 全部终止后排空队列，并在队列锁内关闭边界 admission，拒绝关闭后请求；终止未证实时暂存动作，IDLE 也不能提前执行。`MachineController` 先接受停止请求再退休实例，使被拒元数据请求不改变机器生命期，详见[控制器运行时](machine-controller.md)。

## 机器与服务器

`MachineInstance` 与 `MachineController` 实现 PlannedMachineTask 并继承 ScheduledTask；外部包装交付同一真实机器即可使用公共正式入口。缺少显式 MachineScheduling 组件时沿用 PM 的 CONCURRENT 默认值；组件以不可变 ExecutionMode 值表达选择，变更须发生在合法 owner 边界。生命周期 active 门保留未成型机器的普通组件执行，只停止配方扫描/推进；卸载、破坏和故障机器不再进入新一轮业务。

机器 affinity 从 MachineHatches 的公开资源 input/output storageIdentity 和能源 input/output storageIdentity 收集。混合仓的独立侧缓存保持独立身份；同一真实存储的别名汇为同一个共享 key。发现与元数据采集在 owner 上完成，不在 worker 查 TileEntity 或 World。缺省行为不能替代 PN 后续计算资源/来源 lane 的精确权限模型。

`ServerTaskSchedulers` 在 FMLServerAboutToStart 创建一个确切 MinecraftServer 身份的 session，在 ServerTick.END 调用真实普通调度，并在 FMLServerStopped（世界卸载之后）关闭。没有静态全局线程池，也不允许旧 server 引用取得新 session。外部控制器通过 forServer 取得本会话实例；实际 MachineControllerBlockEntity 现已经 MachineController 登记，世界接线见[控制器集成](controller-integration.md)。

网络角色现使用 `tick(prepare)`：每次实际 MAIN action 前、CONCURRENT 元数据捕获前及 formal owner 组件边界，owner 同步捕获/规划/发布当前网络条件；正常及异常 tick 退出都撤销。prepare 不能改 scheduler 登记或请求 owner 边界，失败沿用既有 fault/join/清理。默认无参 tick 保留原流程，网络拒绝仅暂停配方，不停普通组件。具体身份修订、临时失权和多次 MAIN 捕获的成本边界见[网络运行时](network-runtime.md)。

`SchedulerConfig` 单独保存到 `prototype_eng/scheduler.cfg`，普通 worker 默认 `max(2, availableProcessors)`，lane 默认 `clamp(availableProcessors, 2, 8)`。planned 预算默认 5000ms，日志默认关闭、间隔 200 tick、采样窗口 400 tick。文件读取沿 PM sane 范围限制 workers 到处理器默认上限的两倍、lane 到 32、planned 毫秒到 1–60000、日志间隔到 1–1200、窗口到 20–6000。公共不可变 Settings 保留 Duration 精度；运行时请求在接受前验证正值及纳秒可表示性。

## 统计与配置的实际边界

TaskScheduler 的 effectiveSettings 与 pendingSettings 明确区分已生效与待应用设置。requestSettings 由 owner 只保存请求；即使 IDLE 请求也不调用立即排空的 runAtBoundary。每次真正 tick 入场冻结本次有资格安装的请求，先恢复旧 pending，确认所有接受的异步任务/source 实际退出及 finished，并排空旧 owner FIFO，再在新研究、ordinary、prepared capture 前安装。旧边界回调新请求覆盖已冻结请求时，新请求继续等待下一 tick；旧 pending 未结清、boundary 未证 quiescence、closing 或 faulted 都不能安装。

调整 workers/lane 只在此边界退休原 owned executors，等待真实 termination 后才撤销旧 PlannedTaskExecutor 并懒建新池；同一 TaskScheduler/Session、Registration 身份、待处理一次性队列、owner FIFO、factories、tick generations 与网络/研究源合同保留。不调用 close/tryClose 兑现 reload，因为关闭有清理登记和队列的不同语义；退休失败保留原 executor/planned 引用并沿既有 fault/可重试清理路径报告。仅修改 timeout/metrics 不换池。旧 captured forward deadline 不因 reload 续期；未来 capture 使用新预算。

每次已进入的实际 TaskScheduler.tick（包括报告故障及旧 cleanup 暂留的 tick）在 finally 只记一条 bounded sample。采样始终开启；metricsEnabled 仅控制定期日志。变更窗口重置 ring，但 completedTicks 和 lastLoggedTick 不重置，同一 tick 不重复日志。typed immutable SchedulerReport 返回 total/main/concurrentStage/join/planned/cleanup/structureWorker 的纳秒 avg/p95/max，以及 mainTasks/concurrentTasks/customMainTasks/customConcurrentTasks/affinityGroups 五项计数；快照按需复制排序，记录不构造 report。平均数不通过可能溢出的直接总和计算；p95 沿 PM 的 floor(n×95/100) 索引。

wall 耗时表示 owner 观测的真实耗时：concurrentStage 包含 metadata/group/dispatch 和 join 等待；join 是实际接受 Future 的等待。planned 是 capture 与 retained drive，cleanup 是 cancel/awaitExit/结构 batch awaitQuiescence，结构项仅本 tick runStructureWorker 的实际 worker 段和退出等待。这些嵌套项会重叠，不能相加，也不表示 worker CPU；普通 main 内 owner boundary 的结构耗时同时在 main 内。计数主阶段按已调用 action，并发按实际成功派发的任务/组，不把拒绝派发算作已接受。tick 外同步边界执行不冒充某个 tick 的成本。

实际 FMLServerStarting 注册 `/pe_scheduler status|report|reload|metrics <on|off>`，权限等级 2。status 展示实际 policy、登记数量、pending execution/boundaryReady/closing/faulted/closed 和 effective/pending，而非尚未生效的磁盘值；report 在日志关闭时仍可读取窗口。reload 重新读取原配置路径并完整替换待应用请求；命令操作忽略大小写，metrics 保留 PM 的 on/true/1/enable/enabled 与 off/false/0/disable/disabled 别名，基于 pending 优先、否则 effective 只改日志 flag，不覆盖已排队的 workers/lane/timeout，属于运行时请求。当前策略标签 JAVA 描述现有单一 Java 管线，不提供伪 COROUTINES 或另一个后端。

D04 已由用户确认直接完全并入：后续 Modern 的有效算法与线程策略继续整合到 Java 25 主工程当前调度/渲染管线，独立模组、双 Jar 或另一套独立后端不保留。

## 验证与后续

持续机器 gate 与逐 normal 组件 cursor 已接入真实正式执行，见[机器 tick 边界](machine-tick-boundaries.md)。先恢复旧 pending 并证明 source 实际退出，再派新业务；close 在 source lanes 存活时完成清理，UNKNOWN/卸载仍保留准确义务。公共三参入口的 tickGeneration/tickOpen 撤销原前向权，旧 pending 不换 callback/deadline；MAIN → CONCURRENT join → planned 顺序保持。

逻辑测试使用真实 ExecutorService、latch 和公开 MachineInstance/存储接口，验证 worker 确实重叠、亲和组串行、受拒派发及中断后的 join、故障不复活和卸载后的门。没有初始化 Minecraft/Forge 注册环境；FML 事件时序与实际世界交互目前只有源码接线和编译检查，没有运行游戏证明。

机器类型、附宿主装配/恢复和控制器运行时登记/自退出清理已接入。实际 TileEntity/Forge 调用、共同网络条件、真实 tick 账本与 owner WorkPermit 已接入；planned 执行基础已经具备有序结果和实际退出的独立证明。真实网络机器组件游标、控制器登记和配方/研究预算消费者已接通。研究作为普通工作之前的 PlannedExecution，先持有再执行，旧 pending 未结清不放行普通捕获；见[服务器研究](server-research.md)。块一纯 prepare/需求工作预算与 2A 正式模式已接；结构预算已完成；统计/配置阶段已实现并验证，Modern 有效算法与线程策略的直接整合继续。整体目标和其余结构/研究/渲染/JEI/CraftTweaker 范围保持不变。最终测试数量、逐文件本机 MCP 结果与提交记录见[实施进度](implementation-status.md)。


2026-11-09 本阶段统一 focused `test --rerun-tasks --tests com.circulation.prototype_eng.scheduler.*` 为 120 tests/17 suites，全零、10任务全执行；最终正常 `build --rerun-tasks` 为 1648 tests/210 suites，全零、15任务全执行。首次完整构建在新生产文件 LF 的 Spotless 检查停止（编译通过、尚未跑全 test），只将四个新文件改为 CRLF，normalized 文本完全不变；最终重跑包含此格式修复。11 Java 逐文件本机 get_file_problems(errorsOnly:false) 全级零，完整 numbered readback 与 disk 逐行一致，MCP build isSuccess:true/problems:[]；post-MCP raw source hashes 不变。证据为 `build/staging/scheduler-statistics-config/{focused-result.json,full-first-result.json,line-endings-repair-result.json,full-v2-result.json,final-idea-checks.json,post-repair-idea-checks.json,final-readback-result.json,final-mcp-build.json,post-mcp-source-result.json}`。逻辑回归联合既有真实 pending/source/退出测试；可控 Retained 重载行为测试不冒充 Minecraft/实际 World 网络/FML 实机验收。阶段未推送或启动游戏/服务端。
