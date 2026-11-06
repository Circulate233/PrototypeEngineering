# 普通调度、所有权与服务器接线

本阶段迁移 PM JavaTaskSchedulerBackend 的普通 MAIN_THREAD / CONCURRENT 路径，保留真实线程池、共享资源亲和分组和 join。正式 planned 的 discover/plan/reserve/prepare/resolve/commit、两次 join、PN owner/team/epoch 与预算仍须继续迁移；`PLANNED_PARALLEL` 当前明确拒绝，不降级为普通并发。源码入口为 `api/scheduler`、`scheduler/TaskScheduler` 和 `scheduler/ServerTaskSchedulers`。

## 可复用行为与已修正问题

PM [JavaTaskSchedulerBackend.kt](F:/clone/PrototypeMachinery/src/main/kotlin/impl/scheduler/backend/JavaTaskSchedulerBackend.kt:253) 先处理主线程任务，再对普通 worker 做并查集分组和 Future join。共享至少一个 affinity key 的任务传递合并，在固定单线程 lane 中执行；无亲和键任务使用固定工作池。新实现复用并查集、全部 key 的有序 hash 选择固定 lane 的算法；同组成员改为稳定登记顺序，避免原 identityHashCode 排序碰撞及不可控次序。

旧 `joinAll` 捕获所有异常后继续下一 Future，遇到 InterruptedException 时可能把仍运行的任务留在后台；旧 `SafeRunnable` 也会将任务失败只记日志。新实现把异常保留到收束完成后抛出，已经接受的所有普通任务必须完成。一个 affinity 组首错停止该组后续成员，其余已派发组仍全部 join。提交被拒、主线程或元数据回调出错，同样先等待此前已接受的任务退出，再报告错误。

普通任务使用不对外暴露的 FutureTask，不调用 cancel。正常完成/ExecutionException 表示业务动作已经退出；InterruptedException 则清除并记住中断，继续等待同一任务以及后续任务，最后恢复 owner 的中断标志并抛错。不能使用 Future.cancel 的返回值作为退出证明，也不能拿 planned 的提前 terminal outcome 代替物理退出。普通路径没有用超时放弃运行任务；非合作的卡死任务仍会阻止所有权交还，不能宣称已经安全卸载。

一旦 tick 发生执行、元数据、派发或 join 故障，调度器保持 faulted，后续 tick/登记/提交均拒绝，避免重放任意业务副作用；注销和关闭仍可用于清理。线程池由实例懒创建并持有，部分创建失败会清理已创建资源。关闭等待所有 owned executor 真正结束，失败保留资源引用并关闭 admission，只允许重试清理，不自动重建旧调度器。

## 主阶段中的世界副作用

`ScheduledTask` 是外部任务的真实扩展契约，元数据由 owner 读取，worker 只调用其业务动作。任务按对象身份登记；每次注销后重新登记使用新 Registration 身份，旧捕获候选不得复活。

tick 开始只冻结本次主任务候选和一次性任务批次。主阶段尚无本 tick 普通 worker，owner 的 MAIN_THREAD 回调可以同步登记、注销其他控制器。新主任务留到下 tick，避免放置触发无限递归主任务；主阶段结束后才捕获当前并发任务及其 active/mode/keys，因此新并发机器、卸载机器和修改后的资源亲和键都在此边界生效。当前 tick 已执行的 task 对象不会因换模式或注销重登而再次执行。

元数据回调、并发捕获/派发/join 期间和外部线程不能修改登记；tick/close 始终不能递归进入。`submit` 可以从任意线程加入受锁保护的下一批队列，但本 tick 回调中新提交的 Runnable 只在下 tick 执行。注销只撤销周期性登记，不会取消另行提交的任意 Runnable；异步结构结果仍由 StructureRefresh 的 request/configuration 证明拒绝。

主任务可以同步清理另一台尚未开始执行的机器。自卸载/自破坏仍需未来控制器在当前机器回调结束、MachineLifecycle gate 释放后完成清理，不能递归取消正在执行事务的自身工位。真实控制器必须按这个边界设计，不能以禁止主线程放置/破坏方块的方式规避原功能。

## 机器与服务器

`MachineInstance` 直接实现 ScheduledTask。缺少显式 MachineScheduling 组件时沿用 PM 的 CONCURRENT 默认值；组件以不可变 ExecutionMode 值表达选择，变更须发生在合法 owner 边界。生命周期 active 门保留未成型机器的普通组件执行，只停止配方扫描/推进；卸载、破坏和故障机器不再进入新一轮业务。

机器 affinity 从 MachineHatches 的公开资源 input/output storageIdentity 和能源 input/output storageIdentity 收集。混合仓的独立侧缓存保持独立身份；同一真实存储的别名汇为同一个共享 key。发现与元数据采集在 owner 上完成，不在 worker 查 TileEntity 或 World。缺省行为不能替代 PN 后续计算资源/来源 lane 的精确权限模型。

`ServerTaskSchedulers` 在 FMLServerAboutToStart 创建一个确切 MinecraftServer 身份的 session，在 ServerTick.END 调用真实普通调度，并在 FMLServerStopped（世界卸载之后）关闭。没有静态全局线程池，也不允许旧 server 引用取得新 session。外部控制器通过 forServer 取得本会话实例；实际控制器 TileEntity 的登记调用仍待实现。

`SchedulerConfig` 单独保存到 `prototype_eng/scheduler.cfg`，普通 worker 默认 `max(2, availableProcessors)`，lane 默认 `clamp(availableProcessors, 2, 8)`，来自 PM 原配置，服务器启动时生效。planned 超时、后端切换、性能统计窗口/命令和 Modern Backend 能力仍需后续迁移，当前没有用配置占位冒充已实现功能。

## 验证与后续

逻辑测试使用真实 ExecutorService、latch 和公开 MachineInstance/存储接口，验证 worker 确实重叠、亲和组串行、受拒派发及中断后的 join、故障不复活和卸载后的门。没有初始化 Minecraft/Forge 注册环境；FML 事件时序与实际世界交互目前只有源码接线和编译检查，没有运行游戏证明。

当前仍需实现机器类型/附 World 后的完整恢复、控制器注册/自退出清理、正式 planned 调度及 PN 权限/预算、工作量限制、性能统计与后端能力。整体目标和其余结构/研究/渲染/JEI/CraftTweaker 范围保持不变。最终测试数量、逐文件本机 MCP 结果与提交记录见[实施进度](implementation-status.md)。
