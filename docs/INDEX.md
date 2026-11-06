# 开发索引

恢复上下文先读本文件和 `git status --short`，再按表定位章节或源码。完整状态及历史证据在实施进度，需求在开发文档，不整批重读。

## 当前检查点

- 机器宿主生命周期、事件注册、卸载恢复/结构暂停/破坏取消与故障清理已完成本阶段验证；只本地提交、不推送，具体提交以 Git 日志为准。此前取消/归属/NBT V2 为 `23eed7e`，固定 IO/索引为 `033d6a9`。原工作区23份格式/注解修改继续单独保留。
- 最近验证（2026-11-07）：`gradlew.bat spotlessApply build --console=plain` 通过277项逻辑测试；本机IDEA MCP 对198个src Java及Tags共199文件逐个 `errorsOnly:false`，零错误/警告/弱警告且无文件超时；MCP build_project 成功。报告 `build/reports/idea/machine-lifecycle-final.json`。分批落盘；没有游戏或真实TileEntity运行验证。
- 当前取消：NEW/RUNNING 显式 cancel→CANCELLED，业务 Failure→FAILED；保存原进度/started，不 END、不退历史 IO。根需求逆序 cancel、组件依赖逆序 onAborted，全部尝试并聚合错误。faulted process 禁止再 tick/save，正常 Blocked 可重试。host协调结果/解除，cancelAll失败仍清其他槽；普通卸载没有自动取消。
- 当前归属：需求属性按完整path/expected对象跟踪，默认Renamed codec可保存活动属性；END按removeAtEnd，abort只清owned。Selector/锁清理及成功删除回执进入进程NBT V2，恢复不重放；只验证记录到的归属，外部历史仍需validator。overlay直接children的canonical identity/顺序写时验证。
- 当前扫描：按工位 group、目录优先级和运行 recipe 去重，每刻最多新增一进程；候选/probe 计数可续查，默认降序穷举，完整谓词明确单调才二分。factory/seed/tuning 固定，resolved base 避免重复父倍率；真实 START 获取后全 rollback。局部 preparation 可投影首刻状态并撤销，不能直接模拟不可逆组件回调。
- 新增预筛：FixedIoScanConstraint 仅检查稳定 flat 内置资源/FE 的当前 TICK/END 条件，读取有效 overlay，复用整阶段预留/回滚；零时长跳 TICK，跨阶段输入可能释放同 context 输出空间时跳过对应阶段。它不保证完整首 tick。默认 scanner 三参构造安装，显式构造由调用方配置。
- 新增索引：ResourceRecipeIndex 用显式 Footprint 必需 START 下界构建倒排；forFixedInputs 从内置输入提取但仍需稳定性承诺，未知/动态/未声明配方保留。按 context/key 取 alias 最大量，数量饱和作为下界，匹配全部声明键并保持 scope/顺序。内部工作/deadline 预算尚未接入。

- 当前生命周期：四类事件及FRESH/RESTORED，组件/全局统一捕获；未成型仅停扫描/工位，普通卸载保留进程，新实例load+结构确认后续跑。破坏先闭门，再broken/cancelAll/unloaded；加载失败自动LOAD_FAILED清理，全部聚合，不END/退历史IO。VarHandle gate拒重入/重叠，仍需调度join。坏组件图只在异常清理用最后有效组件快照+无排序装配，仍抛原错。入口见 `docs/machine-lifecycle.md`。

## 下一步

1. 接实际机器定义/控制器TileEntity与完整结构宿主，将已验证的生命周期边界用于真实世界接线。结构匹配需先取得安全世界快照，不能照抄旧worker直接读world/TE。宿主必须在join后保存、变更结构、卸载；PN owner/队伍变化和faulted/planned未完成操作补偿仍须集成。
2. 接真实多线程与 planned：保留 PM 两次 join、稳定 affinity lane、PN owner/epoch/向量预算、迟到任务隔离。旧 epoch/lease/ready 不能从存档恢复为运行权限。目录、index、factory、动态扫描/排序/NBT、随机逐次抽样及嵌套生命周期都仍需内部预算。
3. 机器/结构、投影/渲染、研究/队伍/计算、JEI、最后 CraftTweaker 都是保留范围。当前工作槽/进程持久化和扫描不代表整体机器或重开发已完成。

## 必须遵守

- 纯库：`prototype_eng` / `com.circulation.prototype_eng`。不添加正式机器、配方或玩法数值。接口按真实扩展职责放 api 包，不制造 XxxImpl 镜像。先审查 PM/PN 原行为，再复用和修正。
- Java 25，Kotlin 逻辑改写；集合优先 fastutil；必要字段原子操作优先共享 VarHandle；无非必要反射，无异常控制流。仅为无法静态确认的行为写测试，不测常量/哈希/getter。
- AEKey/AEKeyType 负责资源身份；核心存储独立，不用 MEStorage 承载核心。资源与 FE 仓室保留原等级/方向/资产及公开扩展；混合资源仓输入输出独立，各侧多类型。FE 保持独立接口与存储。
- GUI 直接基于 AEBaseGui，无编辑器/第二套基类。本地化枚举实现 AE2S LocalizationEnum，客户端 getLocal、容器标题 text，禁止裸翻译键/GUI 拼键。JEI 每配方池一个默认或自定义接口实例。Applied-Flux 暂缓，未来保留 `v1.0.0:dev` 的 v。
- 每个 Java 文件必须通过本机 IDEA MCP `get_file_problems(errorsOnly:false)`，零错误、警告及弱警告。工具未挂载时连接已启用的 `http://127.0.0.1:64343/sse`，通过 tools/list 查找；不操作桌面、不改 IDE 设置。构建不能替代检查。
- 不启动 Minecraft 客户端/服务端，不加入 Bootstrap/FML 测试初始化。阶段本地提交，不推送；保留原有工作区修改。

## 按需阅读

| 当前任务 | 入口 |
|---|---|
| 需求、功能保留范围 | [development-spec.md](development-spec.md) 的 R 编号/对应章节 |
| 实际完成度和历史验证 | [implementation-status.md](implementation-status.md) |
| 宿主生命周期、事件、卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md)，api/machine/lifecycle、machine/ControllerLifecycleHost |
| 组件/需求/事务、持久化、工位、扫描 | [recipes.md](recipes.md) 对应章节；源码 api/recipe、api/machine/workslot |
| PM/PN 实际源码与旧缺陷 | [migration-audit.md](migration-audit.md) 对应映射表 |
| 存储/预留/同步 | [storage-redesign.md](storage-redesign.md)，api/storage、api/energy |
| 仓室、GUI及保留效果 | [hatches.md](hatches.md)；资源来源见 [assets.md](assets.md) |

## 本地源码

- 新项目：`F:/new/PrototypeEngineering`。
- PM：`F:/clone/PrototypeMachinery`；存储在 `src/main/kotlin/{api,impl}/storage`，planned 在 `impl/scheduler/planned`。
- PN：`F:/new/PrototypeNetwork`。AE2S：`F:/clone/Applied-Energistics-2`。MMCE：`F:/clone/ModularMachinery-Community-Edition`。

索引不替代源码审查；阶段提交时同步本文件与实施进度，只记录实际证据。
