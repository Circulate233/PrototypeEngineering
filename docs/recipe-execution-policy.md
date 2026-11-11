# 配方发现、执行策略与组合池

## 选择执行策略

`ControllerExecutionPolicy` 把候选发现和机器工作分开选择；按用户明确选择，新建 `MultithreadController`/`CrossRecipeController` 默认 `FULL_ASYNC`。

| 策略 | 候选发现 | 普通机器回调与配方工作 |
|---|---|---|
| `FULL_ASYNC`（默认） | worker | 尽可能在 worker |
| `ASYNC_SCAN`（半异步1） | worker | 主线程 |
| `ASYNC_WORK`（半异步2） | 主线程 | 尽可能在 worker |
| `SYNCHRONOUS` | 主线程 | 主线程 |

Java 在机器创建声明中调用 `MachineType.Builder.executionPolicy(ControllerExecutionPolicy.ASYNC_SCAN)`；CT 对应 `.executionPolicy("ASYNC_SCAN")`，其余三个枚举名相同。GUI显示实际策略，scheduling组件保存Policy；旧存档只有Mode时按原ExecutionMode恢复，不擅自改变已有机器的执行位置。非空policy由原正式planner承接，`ExecutionMode`仍用于普通任务及旧声明，不新增另一套线程池或机器调度器。

异步允许作者把自己的事件放到worker，不能使原生World/TE API自动获得线程安全。已知只能由owner处理的需求通过 `RecipeRequirementSystem.requiresOwnerThread()` 声明；默认false，扩展作者可显式覆盖。声明须稳定并覆盖系统可引入的全部结构child。首次执行时按进程实际冻结需求（包含机器提供者和嵌套child）缓存该事实；同工位宿主存在owner-only需求时，其整批配方结算保留owner，避免跨需求/进程事务被拆开。PN的`NetworkRecipeWorkSystem`明确声明true，许可、身份/来源证明和团队预算账本的owner检查保留；FULL_ASYNC下这类结算的纯prepare和来源动作仍走原workers/lanes。用户任意事件的可异步性不由框架猜测。

选择同步工作时，原coordinator使用`PlannedTaskExecutor.synchronous()`，prepare和source都在caller执行；异步工作时，coordinator在稳定source lane上创建并原线程续办，`synchronousSources()`保留纯prepare worker并发、source在当前lane执行，避免同lane嵌套等待。取消、deadline、UNKNOWN补偿、post和实际退出证明仍由原coordinator持有。普通组件和生命周期使用一次性独占worker claim，主线程在真实退出后收回，不能在等待期间保存/卸载或重入dispatch；共享affinity key的控制器保持传递分组、组内稳定顺序串行，独立组可并发。

## 异步发现与主线程准入

主线程捕获本次canonical池选择、工位范围及目录generation；多池选择只复制provider引用，不在此处枚举全量配方。`beginScan(limits, true)`交付`discoveryWork()`给一个独占worker，推进原`CandidatePass`和池索引。worker读取输入存储的独立数量捕获、计算候选及数量上界；池/index扩展仍须遵守纯发现合同，不能运行START、扣料或写机器/世界。库不因选择异步而允许纯发现回调发布副作用。

同一轮机器先各自在owner捕获发现任务，再批量派发原worker池，避免逐台提交并等待而把空载扫描串行化。批次实际退出后，每台只消费自己的准确结果，再由主线程调用`finishDiscovery`并预算化复核目录、绑定、有效性和quantity revision；过期意见重取。随后factory、最终数量selector、FE/条件/权限、START probe以及工位准入都在owner进行。没有把“库存命中”当作实际资源预留，也不会在worker发布Process或工位状态。发现批次仍在本次正式scheduler周期内join，不宣称扫描任务可独立跨tick后台运行。

固定输入的空库存/无候选发现不会创建`RecipeProcess`、START事务或逐候选`RecipeInventoryMatch`对象。池索引、canonical顺序、共享组合视图和上次配方单项索引复用缓存；单输入池直接读所需key，不枚举无关库存。通用/自定义/动态输入无法证明必要下界时保留候选，之后仍可能需要主线程构造临时进程做真实探测，不宣称所有扩展的空载均零分配。

## 完成后的继续搜索

多线程每个声明工位仅缓存上次真实完成的配方ID。新扫描先按当前目录重新解析canonical、验证本次选池和工位组，再只探该配方；成功可绕过整池发现，失败沿剩余预算回到正常候选发现，同pass不重复探该失败项。取消、失败和手工detach不改成功历史；工位恢复清空这份瞬态提示，不把它当作持久化授权。已知stock线性路径的单项重试也复用固定输入数量上界，父并行100万、库存仅够2时只需一次真实probe；未知factory/selector/modifier保持保守搜索。

跨配方控制器以“上次准入成功配方之后”为下一完整pass的轮转起点；当前pass的offset保持不变，每个实际候选仍检查一次，成功不刷新预算。不复制或旋转配方列表，按当前generation既有priority二分起点；上次配方暂不在候选中仍按其目录位置继续，定义已删除则回到起点。真实START/共享并行/每配方线程上限继续决定准入，轮转不预留共享库存。

## 多个提供者的组合配方池

`CompositeRecipePool(id, titleKey, children)`组合Simple、Default、自定义及其它组合池。child顺序决定组合自身首次出现的canonical顺序；相同canonical对象只出现一次，同ID不同对象拒绝。同配方被多个child接受时数量上界取最宽松值；任一child的null意见仅回退该child，empty意见不回退。

`match(inventory)`、`match(inventory, parallelism)`与预算化`beginMatch(inventory, parallelism, groups)`均保留这套顺序和去重；只读库存结果给出各配方独立上界，不能当作候选组已分摊材料。`beginCandidates(machine, groups)`保留每个child的策略、预算和freshness proof。冻结/替换把准确canonical和机器反查递交原child策略，不把它们改成平铺Default池。

实际`RecipeCatalog.Selection`对混合多池使用这一个组合入口，之后仍按全目录priority排序并与外部index取交集。最多16种provider身份序列缓存组合；首次编译发生在真正推进discovery的线程，因此异步策略不在主线程冷构建组合目录。工位上次配方索引另有最多64项引用缓存；这些缓存都不持有资源或执行许可。

CT可直接组合已完整声明的池，child无需另行register：

```zenscript
var mixed = Pool.combine("addon:mixed", "addon.pool.mixed", [simplePool, generalPool]);
mixed.register();
```

此接口保留准确ZenRecipe、codec和revision，同一配方的不同codec/revision绑定拒绝。显式组合应在child定义完整后创建；后续给另一个已注册child追加条目不会修改旧不可变组合的成员。机器动态选择多个已经冻结的池时，框架自动使用当前选择构建/复用组合，不受此声明时点限制。

## 验证入口

`ControllerExecutionPolicyTest`使用真实scheduler/controller，覆盖四策略×两控制器的发现、factory/selector/probe、普通回调、START/TICK/END线程及材料/FE支付；双入口latch证明多机发现确实重叠，单机发现失败仍等待其它已接收任务退出并正确归还全部生命周期。`AsynchronousRecipeDiscoveryTest`覆盖空库存0factory/selector、补料后准入、worker结果收据前库存变化拒旧，以及单项重试上界；`CrossRecipeRotationTest`覆盖真实库存变化的连续9轮公平准入。组合测试覆盖混合provider、canonical/组/交集/宽松bound、失效、缓存及CT编译；ownership回归覆盖中途capture异常、中断和准确claim归还。最终数量及IDE证据见[实施进度](implementation-status.md)。本轮不启动游戏，实际World回调和画面仍由用户复验。
