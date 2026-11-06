# 开发索引

恢复上下文先读本文件和 `git status --short`，再按表定位章节或源码。完整状态及历史证据在实施进度，需求在开发文档，不整批重读。

## 当前检查点

- 规范机器声明/整批关闭/后置结构绑定已完成本阶段验证，只本地提交、不推送，提交号以Git日志为准。此前普通调度为 `2c73d9c`，结构发布为 `49bfe01`，匹配为 `9f74f62`，生命周期为 `1505a65`。原23份格式/注解修改单独保留；22份diff逐字相同，MachineInstance的重叠方法按原布局分离暂存，剩余无功能差异。
- 最近验证（2026-11-07）：`gradlew.bat spotlessApply build --console=plain` 通过344项逻辑测试。本机IDEA MCP已逐个检查238个src Java及Tags共239文件，`errorsOnly:false`，零错误/警告/弱警告且无文件超时；MCP build_project成功。最终证据见 `build/reports/idea/machine-types-final.json`。全部通过MCP，不操作桌面；实际World/TileEntity/Forge交互未运行验证。
- 当前机器类型：MachineType保留PM声明字段/默认模式/模型/LocalizationEnum；ComponentMap静态验证复用实际系统图而不调用factory。MachineTypeRegistry批次拒绝无部分加入，freeze确定顺序关闭，resolveStructures整批绑定同一个canonical类型；两次发布均不代表PN/planned ready。typed实例附生命周期前无调度/保存权限；模式组件优先，显式创建工位读取类型布局/组。默认属性仅声明，机器级恢复、Forge控制器及PN配置仍未接。入口 `docs/machine-types.md`。
- 当前普通调度：TaskScheduler复用PM固定池/并查集亲和分组/single lanes，全部accepted任务在异常/中断时也真实join后才归还owner，故障不复活。MAIN中新MAIN下tick、新CON本tick；Registration拒旧候选复活，task identity拒同tick二跑，metadata/worker禁登记。MachineInstance直接接模式与公开storageIdentity；FML aboutToStart→END tick→stopped拥有会话。PLANNED明确拒绝，仍须正式迁移；入口 `docs/scheduling.md`。
- 当前结构：AE 24向、模板/贪心切片与快照基础见 `docs/structures.md`。新增evaluate追踪实际读取，包括失败切片probe；query显式等价比较，变化则fresh capture/match，未访问潜在分支不误拒。StructureRefresh的request/config身份拒迟到与ABA，worker只算快照；发布在生命周期gate内复核事实/provider/能力并一起安装树和新MachineHatches，同方向也重建。真正失配才清绑定，失稳返回false由调度边界重试/显式invalidate，当前异常故障，旧配置异常不故障新配置。ControllerLifecycleHost固定World且分离活TE检查和清理线程检查；真实TE/owner变化调用仍待接。
- 当前生命周期：四类事件+FRESH/RESTORED；未成型仅停扫描/工位，普通卸载保留并由新实例load/结构确认后续跑；破坏broken/cancelAll/unloaded，加载失败LOAD_FAILED，全部清理聚合，不END/退历史IO。VarHandle gate拒重叠但不替代join，坏图异常清理仍抛错。详见 `docs/machine-lifecycle.md`。
- 当前配方：完整需求/组件/局部属性、概率/选择/overlay、工位保存与取消已接；终态自有清理和回执为NBT V2，faulted不可再tick/save，正常Blocked可重试。扫描按group/去重/候选probe预算，每次最多1个NEW，默认非单调下降搜索，START真获取后全rollback；flat IO预筛是独立当前量条件，资源索引需显式稳定下界，内部工作预算仍待接。详见 `docs/recipes.md` 和迁移审计对应章节。

## 下一步

1. 使用已接MachineType/Registry完成实际控制器TileEntity：先接收纯NBT，附World后创建/恢复依赖世界的组件，恢复后按整条属性ID补缺默认，成功load才登记。factory/restore不提前发布世界副作用；明确空配方组的全局候选语义后再接自动scanner。自卸载/自破坏须等自身回调退出gate后清理。详见 `docs/machine-types.md`、`docs/machine-controller-migration-audit.md`。定义/方向/owner变化在调度边界invalidate并轮换配置；结构发布失稳要重试或暂停。
2. 在已接普通调度上实现正式 planned：保留 PM prepare/resolve两次join、稳定source lane、PN owner/team/epoch/向量预算、迟到任务隔离及补偿。旧epoch/lease/ready不能从存档恢复为权限；terminal/取消不是物理退出证明。结构规划/匹配回退、目录/index/factory、动态排序/NBT、随机逐次抽样及嵌套生命周期仍需内部预算；性能统计/命令/后端能力也须迁移。
3. 严格两阶段JSON/注册发布、结构预览/BOM/display override、模型隐藏/渲染、研究/队伍/计算、JEI与最后CraftTweaker继续迁移。全范围仍保留，当前结构匹配与生命周期基础不代表完整机器已完成。

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
| 结构定义、快照、模板/切片匹配 | [structures.md](structures.md)；原实现审计 [structure-migration-audit.md](structure-migration-audit.md) |
| 宿主生命周期、事件、卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md)，api/machine/lifecycle、machine/ControllerLifecycleHost |
| 普通调度、线程退出、MAIN登记边界 | [scheduling.md](scheduling.md)，api/scheduler、scheduler |
| 机器声明、整批关闭、后置结构绑定 | [machine-types.md](machine-types.md)，api/machine/MachineType、MachineTypeRegistry |
| 机器类型/控制器及PN接线审查 | [machine-controller-migration-audit.md](machine-controller-migration-audit.md) |
| 组件/需求/事务、持久化、工位、扫描 | [recipes.md](recipes.md) 对应章节；源码 api/recipe、api/machine/workslot |
| PM/PN 实际源码与旧缺陷 | [migration-audit.md](migration-audit.md) 对应映射表 |
| 存储/预留/同步 | [storage-redesign.md](storage-redesign.md)，api/storage、api/energy |
| 仓室、GUI及保留效果 | [hatches.md](hatches.md)；资源来源见 [assets.md](assets.md) |

## 本地源码

- 新项目：`F:/new/PrototypeEngineering`。
- PM：`F:/clone/PrototypeMachinery`；存储在 `src/main/kotlin/{api,impl}/storage`，planned 在 `impl/scheduler/planned`。
- PN：`F:/new/PrototypeNetwork`。AE2S：`F:/clone/Applied-Energistics-2`。MMCE：`F:/clone/ModularMachinery-Community-Edition`。

索引不替代源码审查；阶段提交时同步本文件与实施进度，只记录实际证据。
