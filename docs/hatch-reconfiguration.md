# 仓室安全重配置

本阶段保留 PM `modify* → loaded bridge → applyConfig` 的热更新目的。Java 入口是 `HatchConfigurationUpdates.configureDefaults`（启动期脚本目标）和 `request(server, targets)`（准确服务器的运行期请求）；CT 参数转换仍属后续阶段。资源与 FE profile 均为不可变值，物理 tier/mode 不由热配置改变。

## 目标、有效值与实例待办

`HatchConfig` 一次发布不可变 generation：文件默认值与显式 profile override 分离，先设置的脚本目标不会被后续文件 load 覆盖。资源 profile 每侧有独立 slots / AEKeyType 容量，FE 每侧有独立 capacity / transfer 默认上限。81 是原生默认布局值，不是配置上限；内置合并 GenericInternalInventory / IItemHandler 的单 int 索引要求双侧槽数合计可用 int 表示，使用 checked arithmetic 预检。窗口传输另见大槽数方案和有限可见窗口实现，不使用 32731 限制 profile。

运行期入口复用 `ServerTaskSchedulers.forServer(server).runAtBoundary`。每次请求只在该边界复制一次各已加载世界的 TE 列表，过滤非 remote、准确 server/world、当前已加载坐标上的准确实例，再通过 `ReconfigurableHatch` 职责提交匹配目标；不强转内置 TE，也不加载 chunk。扫描报告记录当次提交结果，宿主的 `configurationStatus()` 反映其之后的完成/受阻状态。

各 TE 保留自己的 effective profile 与 `HatchConfigurationRequest`。公开请求先取得本次宿主 Boundary，并由真实 TaskScheduler 校验 owner，再检查准确宿主仍有效；这些检查先于任何 generation、target、observed revision 写入，也先于相同/空目标、已有排队或容量受阻的返回。扩展宿主可以提供自己的短生命周期 scheduler/当前宿主观察，公共请求始终保留统一 admission 校验。Boundary 只存在于本次请求和排队闭包，不写入存档，不提供跨会话授权。容量不足时有效值、GUI 查询、存档与余额都保持旧值，pending target 留在该实例。BLOCKED_CAPACITY 的原 context/revision 未改变时不重复完整预检；PENDING_BUSY 只在该实例后续 tick 请求同一 scheduler 边界。已排队标记合并重复请求，旧 generation / 已移动或失效实例 / closing 或 closed scheduler 不会应用；新目标覆盖旧待办。没有失败后的全世界重扫、配方取消、补做 END 或历史退款。

## 两侧共同守恒替换

资源 `BufferedResourceHatch.replace` 和 FE `EnergyHatchStorage.replace` 都遵守如下顺序：

1. 获取原双侧 StorageFence；不可获取作为 PENDING_BUSY 返回。
2. 在原 access scope 读取 committed 余额与版本，检查 native activeReservations 及 context 的 deferred mutations / reserved revision credits。任一侧有未决责任便保留完整旧实例。
3. 退出原 scope，但继续持有本次 fence。在 scope 外建立候选新 buffers、恢复全部余额、绑定同一物理位置的来源标签，并准备 ports。资源按 AEKey2LongMap / addTo 合计，先计算每类型最少槽数是否真实可容纳。优先保留合法原槽和同 key 多槽分布，仅将越界/超容量余量稳定填入同 key 空余容量及空槽；只有此方案因碎片不能容纳全部类型时，才按原 key 首次出现顺序压实。缺类型、槽数不足、FE 超容量都正常拒绝。
4. 重入原 scope，复核准确宿主/目标/旧 context revision，先预检全部退休 revision，再一起退休旧 contexts，并以已经准备好的普通字段赋值发布新存储和 effective profile。新 context 的身份与锁序不会从旧实例复制。
5. finally 完整释放原 fence 后通知 world。候选准备期间的通知被关断；成功后先记真实 dirty/待通知责任，再发 block update。通知失败不恢复旧 context，也不重新消费已迁移内容。

旧资源/FE capability、自动化 port、快照和旧 GUI 引用始终绑定原 contexts。退休后旧能力不能转移新余额。结构发现沿既有周期重新取得新能力，不承诺所有机器同 tick 换绑。

## 存档与重新挂载

新 NBT `EffectiveProfile` 保存每侧真实已生效布局/容量/默认速率，同时保存完整 Input/Output 或 InputEnergy/OutputEnergy；auto 和用户速率独立保留。`loadTag` 先完整验证 profile、两侧内容和参数，构造候选，再更换旧实例。出现 `EffectiveProfile` 的现代档必须具有精确类型的 Mode、Tier、auto、用户速率和对应侧数据；不允许另一方向字段，资源空列表也不例外。只有 legacy 分支保留原有缺字段兼容。坏后侧或身份/设置字段不会先发布好前侧，也不会取消原待办。

资源旧档 `Slots` / `Capacities` 仍按其原布局恢复。新数量条目使用项目已有 `ResourceKeyNbt` 的 Key/Quantity 严格形状；旧 GenericStack 的 `#t` / `#` 仍支持。解码不使用 AE 的 missing-content fallback，不将坏 key 替换成另一个资源身份。未知类型、重复/越界槽、超容量、负数和错误字段类型明确拒绝。

旧 FE 档没有 effective capacity 时，每侧临时有效容量至少为 `max(当前合法基础容量, 该侧保存余额)`，随后进入 world 时再请求当前目标。旧 mixed 单 `Energy` 仅放入输入侧，输出保持零。`clearRemoved()` 与 NBT 共用完整恢复路径，因此 invalidate→validate 不会因全局目标缩容而 clamp 丢失 FE；每次恢复都生成新 contexts。

`HatchInventoryPort` 的无槽类型/容量元数据覆盖两侧并以最大单槽容量作为估计；有槽查询明确按所属侧取值。原生 `HatchItemHandler` 复用 AE 物品转移行为，但覆盖 slot limit，以支持输入/输出不同容量。流体保持原有分侧适配。

## 容量事实、物品提示与服务器目标

资源profile保留明确的`CapacityBasis(itemEquivalent, ratios)`，工厂默认AE类型单位比率（物品1、流体1000），公开API允许覆写；构造校验`base×ratio`与每类原生容量完全一致。NBT及服务器显示快照传输真实basis。旧native-only二参profile不倒推base/ratio，继续按原生容量保存余额，未声明基准时物品提示也显示未声明。

资源物品每有效侧只显示“槽位数量：xxx”“槽位容量：xxx”，交互仓用输入/输出标题区分。保存有效profile优先；裸物品以真实服务器配置快照为准，连接后等待完整快照，不读取另一份客户端配置冒充服务器值。快照复用网络与连接生命周期，以准确连接、session及revision拒绝旧包，完整分片验证通过后一次安装，断线清理。损坏保存槽数相加或basis乘法溢出在解码边界转为可显示的无效数据提示；未知ratio类型明确拒绝。

能源用户限额默认`-1`，表示不追加用户限制，`0`禁止对应操作，正值与effective profile的transfer配置上限取较小值。legacy无保存限额时取-1；现代NBT仍要求明确long，低于-1拒绝且不部分更换原余额。能源物品显示实际生效上限。两类仓室auto开关新实例与legacy缺字段默认false，保存的true/false继续保留。相关细节见[容量与提示](hatch-capacity-tooltip.md)。

## 自动IO真实执行与部分结算

两类仓室的真实`AEBaseTile.update()`现各自调用实例持有的`HatchAutoTransfer`；只在服务端、准确已加载宿主仍有效且对应方向开关打开时执行。六面邻居先检查已加载，再按反面能力查询，不加载chunk。临时游标随TE实例轮转并限制每tick槽探测，真实输入和输出缓存保持分离；同一类型不会通过重复能力入口搬运两次。普通访问用有序非阻塞锁，已有配方fence/source scope继续排除自动IO。

外部实际转移前先本地模拟裁出正数R，再取得准确原生receipt。`NativeTransferReservation`仅提供`amount/complete(actual)`，不扩张通用配方reservation：输出先预扣R并保留退款空间，输入只预留R容量，回调期间不会出现尚未从邻居取到的虚假资源。实际返回A必须满足0≤A≤R；在原锁下按原槽和hold直接结算，输入加入A、输出退回R-A，一次释放整R预留和全部revision credit。资源使用AEKey2LongMap/addTo及定槽allocation；FE回调即使把当前用户限额改0，仍完成已取得权利的准确结算。

真实TE的FE setter支持helper已持有双侧锁的同线程回调：`EnergyHatchStorage`先按序尝试取得全部普通锁，不完整取得时释放本次锁再保留原有lockAll规则；两侧新限额先共同预检，成功更新后才发布用户字段。因此坏后侧不会留下只改了前侧的设置。

正常partial返回是普通成功路径。第三方actual抛出或返回非法数值时，helper保留原receipt责任并隔离准确本地context，不猜测complete(0)或再次执行。未决external receipt计数阻止同一TE通过loadTag/clearRemoved变成健康库存，正常重配也不能越过未决预留。这个未知外部结果不承诺自动恢复，没有新增持久恢复框架。

## 验证范围

新增必要逻辑测试：`HatchReconfigurationTest`、`EnergyHatchReconfigurationTest`、`ResourceHatchPersistenceTest`、`HatchConfigurationRequestTest`。新增公共请求测试使用真实 TaskScheduler 与实际 TE request/queue/buffer，只替换无游戏环境的宿主观察；覆盖无待办、已排队、同目标、相等 effective、不同目标、null、容量阻塞时的外线程拒绝，随后证明原 action/重试仍应用原目标。现代损坏档测试从实际 saveAdditional 输出删改字段。覆盖真实旧 fence 的新 buffer 初始化/标签绑定/失败释放、两侧守恒、容量拒绝版本、ACTIVE 预留和 deferred mutation 阻塞、旧能力失效、不对称类型与容量、96/128 末槽、现代与旧格式 NBT、坏后侧不部分发布、FE 超旧默认量和 invalidate→validate 恢复。没有反射、Bootstrap/FML 初始化或游戏启动。

首次 before、absence/owned 清单与逐文件本机 MCP 证据位于 `build/staging/hatch-reconfiguration/`。本阶段代理没有运行 Gradle/MCP build 或提交。主代理第一次联合专项为 169 tests / 23 suites 全零（`build/staging/continued-java-plan/focused-first.log`、`focused-first-results/`）；该结果先于独立复核的三项 P2 修复，不能代替新增 7 项回归。修复输入快照保留在 `review-repair/before/`，最早 before 未覆盖。修复后的新专项及完整构建由主代理统一运行。真实服务器 loaded 扫描、GUI 网络传播和游戏交互仍需用户实机验收，逻辑测试不代替这些证据。

2026-11-10恢复收尾检查点见`build/staging/hatch-reconfiguration/resume-rate-semantics/`及`native-transfer-first/`、`native-transfer-second/`：材料文案/明确basis/服务器快照/新FE -1/0语义与自动IO合并，必要既有回归补充禁转、保存恢复及坏basis；新增4项原生partial/重入/定槽/未决责任恢复边界测试和10项helper测试。helper独立复核后也覆盖邻居实例替换、同TE内容定期刷新及大量不可抽取候选的轮转公平。

首次恢复构建在MachineHatches背景成员重排的非法前向引用处停止；下一轮主源码编译通过，但两份背景测试MachineDataCenterStorageTest/MachineNetworkDeviceTest出现同类前向引用，均为0测试执行，不能引用旧XML。三个文件分别只用类名/this限定引用修复，保持原字段顺序，构造器只保存相应回调、不在字段初始化执行；写前完整before已纳core，精确单处替换证据在native-transfer-second。后续专项与完整构建由主代理统一记录。

恢复后第三轮专项的真实汇总为203 tests / 37 suites，1 failure、0 errors/skipped，staleXml为空；唯一失败在helper邻居替换回归，不能记为全零。保存结果为`build/staging/chat-goal-recovery-2026-11-10/focused-resumed-repair2-results/`。原生partial、FE限额、profile/NBT及配置快照相关测试已执行通过，helper失败修复后还需统一重跑；本轮没有启动Minecraft或MCP build。

唯一失败经复核为有限槽窗口夹具的时序断言：替换后的首次检查只探测前4个已由旧异key占用的本地槽，下一tick才到空槽4。仅将该测试改为明确两tick断言，生产代码不变；helper随后单独重跑10 tests / 1 suite全零、无格式变化，证据为`build/staging/chat-goal-recovery-2026-11-10/auto-io-final-results/`。

最终完整构建为1814 tests / 237 suites，failures/errors/skipped均0，staleXml为空、formatChanged为空，保存结果为`build/staging/chat-goal-recovery-2026-11-10/full-resumed-results/`。当前core清单53文件（20已有、33新增）/49Java；49文件本机MCP逐文件errorsOnly:false错误、警告、弱警告全零，完整IDE读回与冻结源码一致。最终manifest、原始MCP、比对哈希及交接入口位于`build/staging/hatch-reconfiguration/`和`final-core-handoff.md`。这证明自动化行为与编译通过，真实服务器及GUI交互仍由用户启动游戏复验。
