# 开发索引

恢复上下文先读本文件和 `git status --short`，再按入口定位源码/章节。需求见开发文档，完整状态和历史证据见实施进度；不整批加载文档。

## 当前检查点

- 已完成本阶段：PN 当前 owner 队伍查询、直接 FTB 适配、冻结捕获与独立网络规划、中心/数据中心选择及计算资源汇总修饰。服务器发布、统一工作授权和预算账本仍未接入。阶段本地提交，不推送；提交号以 Git 日志为准。原 23 份格式/注解修改仍保留未暂存。
- 最近验证（2026-11-07）：`gradlew.bat spotlessApply build --console=plain` 通过 446 项测试；IDEA MCP 对 299 个 src Java 和 Tags 共 300 文件逐个 `errorsOnly:false`，最终零错误/警告/弱警告且无超时；MCP build_project 成功。证据 `build/reports/idea/network-planning-final.json`。实际 Forge/GUI/模型交互未运行验证。
- 网络规划：OwnerTeamResolver 是扩展接口，FTB 使用真实 server/Universe 持久玩家，离线允许。capture 每 owner 一次查询、拒重复/过期/成员矛盾与同 ID 不同资源元数据；worker plan 只读。中心按等级/加载顺序，数据中心再比已有研究；满库可选。中心+合格电脑+同队参与者合计后一次 exact flat/ppm，不把 maxPerTickValue 当全队上限。
- 身份：FRESH 只接受真实放置玩家，RESTORED 保留 machine/owner；不自动安装到普通机器。位置按 World 引用 + pos，同位置新代撤旧登记，旧 close 不删新代；同 UUID 多个活副本全不唯一，剩一个恢复。登记绑定准确 MachineInstance，不等于 team/epoch/recipe-work 许可。
- 自动配方：显式声明 MachineWorkSlots 后再声明 RecipeScanning，工位走既有 codec、scanner recreated；系统 runBefore 保证同 tick 先扫描后处理。注册表整批发布共享 catalog。空 groups 在 catalog/index/scanner/admission/restore 一致表示全局，包含无组配方；显式组仍过滤。候选/probe 有限额，内部工作预算仍待接。
- GUI：具体类直接继承 AEBaseGui/AEBaseContainer，公开两侧 Java factory。join 后重查窗口/TE 身份，仅同步最多七行不可变快照，保留进度和真实背包；PM 临时 4×4 库存、固定 Idle 和空扩展页是占位。方向工具保留两模式、PM 24 向数学、武器/采掘/耐久/附魔、配置/模型/成就。
- 存储：独立 ResourceStorage/FE 契约和事务不变；AEKey 数量用 AEKey2LongMap 和 addTo，保留必要的溢出预检/零清理/顺序/别名去重，快照通过专用不可变包装器保护。
- 控制器：类型/结构/assembly 整批发布；TE 构造/read 仅留副本，下一 update 过 Forge 放置取消窗口后装配；结构 PM 20/5 节奏 capture→worker→join 后发布。完整朝向与配置变更走 owner FIFO，旧代请求失效。显示不含 runtime/ForgeCaps，不授 PN 权限。
- 运行时与持久化：附 host 后 factory，完整恢复/validate 后 load；retire 先停止新工作，accepted worker 全部实际 join 后清理，卸载保进度、破坏取消。普通调度的 MAIN/CONCURRENT/亲和 lanes 已接；故障不复活。正常 Cleanroom dormant 从 NBT 重建 TE。
- 已有配方能力：组件多系统/Pre-Normal-Post、需求事务、概率/检查点/模糊动态输入/选择器/overlay、完整进程/工位 NBT、取消/失败清理。结构已有 AE 24 向、模板/贪心切片、事实快照与失稳重试；这些不代表剩余整合全部完成。

## 下一步

1. 将当前身份/队伍查询/网络规划接入真实角色组件和服务器捕获/发布边界。整批结果必须重验准确登记/配置/参与者版本，失败撤旧许可；共用门限约束新扫描和已有工位，临时失权不退休整机。当前 NetworkWorkState 仅拓扑决策，不能代替研究/预算/worker 许可。资源 canonical 注册及单来源上限、完整参与者仍待接。异常 loader 复用同一 TE 且旧卸载晚到的问题需准确卸载代证明，不添加宽泛复活。
2. 正式 planned：复用 PM prepare/resolve 两次 join、稳定 source lane，接入 PN owner/team/epoch/向量预算、迟到隔离和补偿。旧 epoch/lease/ready 不从存档恢复为权限。结构/目录/index/factory/动态排序/NBT/随机逐次抽样等内部工作预算、性能统计/命令/后端继续迁移。
3. 严格两阶段 JSON/注册、结构预览/投影/切片/BOM/display override、复杂渲染、研究/队伍/计算、每池默认/自定义 JEI，最后 CraftTweaker。Applied-Flux 按用户要求暂缓，后续保留 `v1.0.0:dev` 的 v。

## 必须遵守

- 纯库：`prototype_eng` / `com.circulation.prototype_eng`。不添加正式机器、配方和玩法数值。先确认 PM/PN 实现及目的，再复用和修正；保留未明确取消的效果及多线程性能能力。
- API 独立包，接口按真实外部扩展职责划分，不制造 XxxImpl 镜像。Java 25，Kotlin 逻辑改写；集合优先 fastutil，AEKey 数量使用 AEKey2LongMap，累加/扣减优先 addTo；必要原子字段优先共享 VarHandle。无非必要反射或异常控制流，仅为静态审查不足的逻辑行为写测试。
- AEKey/AEKeyType 负责身份，核心不用 MEStorage。资源/FE 仓室保留十阶/三方向/资产/公开接口，混合资源仓两侧独立且每侧多类型；FE 独立。
- GUI 直接 AEBaseGui，无编辑器/第二套基类。本地化枚举实现 LocalizationEnum：客户端 getLocal、服务器 text，禁止 GUI 拼键/裸翻译调用。JEI 每池一个默认或自定义接口实例。
- IDEA 检查必须用本机 MCP：未挂载时连接已启用的 `http://127.0.0.1:64343/sse`，先 tools/list，再逐类 get_file_problems(errorsOnly:false)，消除错误/警告/弱警告。不得操作桌面或改 IDE 设置；构建不替代检查。
- 不启动 Minecraft 客户端/服务端，不加入 Bootstrap/FML 测试初始化。阶段本地提交，不推送，保留已有工作区改动。

## 按需阅读

| 内容 | 入口 |
|---|---|
| 已确认需求/功能范围 | [development-spec.md](development-spec.md)，R 编号 |
| 实际完成度/验证证据 | [implementation-status.md](implementation-status.md) |
| 旧实现及已知问题 | [migration-audit.md](migration-audit.md)、[machine-controller-migration-audit.md](machine-controller-migration-audit.md) |
| 配方/组件/事务/扫描 | [recipes.md](recipes.md)，api/recipe、api/machine/workslot |
| 机器 GUI/自定义入口 | [machine-gui.md](machine-gui.md)，api/gui、api/client/gui、container/MachineContainer |
| 方向工具/原交互数学 | [orientation-tool.md](orientation-tool.md)，item、OrientationToolConfig |
| 机器声明/整批绑定 | [machine-types.md](machine-types.md)，MachineType/Registry |
| 装配/NBT/稳定边界 | [machine-persistence.md](machine-persistence.md)，MachineAssembly |
| PN 持久身份/准确登记/后续授权 | [machine-identity.md](machine-identity.md)、[pn-identity-migration-audit.md](pn-identity-migration-audit.md)，api/machine/identity |
| 队伍/FTB/中心规划/计算贡献 | [network-planning.md](network-planning.md)，api/team、api/network、integration/ftb |
| 生命周期/卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md)，api/machine/lifecycle |
| 控制器运行时/退休 | [machine-controller.md](machine-controller.md)，MachineController |
| 真实 TE/朝向/结构接线 | [controller-integration.md](controller-integration.md)，MachineControllerBlockEntity/ControllerStructureUpdates |
| 普通调度/planned 待迁移 | [scheduling.md](scheduling.md)，api/scheduler、scheduler |
| 结构匹配/快照/发布 | [structures.md](structures.md)、[structure-migration-audit.md](structure-migration-audit.md) |
| 存储/预留/FE | [storage-redesign.md](storage-redesign.md)，api/storage、api/energy |
| 仓室/美术来源 | [hatches.md](hatches.md)、[assets.md](assets.md)、[controller-assets.md](controller-assets.md) |

## 本地源码

新项目 `F:/new/PrototypeEngineering`；PM `F:/clone/PrototypeMachinery`；PN `F:/new/PrototypeNetwork`；AE2S `F:/clone/Applied-Energistics-2`；MMCE `F:/clone/ModularMachinery-Community-Edition`。PM 存储在 `src/main/kotlin/{api,impl}/storage`，planned 在 `impl/scheduler/planned`。

索引不替代源码审查；阶段提交时同步实施进度，只记录实际证据。
