# 开发索引

恢复上下文先读本文件和 `git status --short`，再按入口定位源码/章节。需求见开发文档，完整状态和历史证据见实施进度；不整批加载文档。

## 当前检查点

- 已完成本阶段：真实 server tick 的预算生命周期、同 tick 固定容量/余额、每机器 lease、向量预留和原代补偿，以及准确 Publication/身份/全部宿主证明约束的 owner WorkPermit。计算资源注册上一阶段为 `be0852d`。普通配方、研究与正式 planned 的自动消费仍未接入，不能把共同网络 gate 当作已经消费预算。阶段本地提交、不推送；最新提交号以 Git 日志为准，原 23 份格式/注解修改保留未暂存。
- 最近验证（2026-11-07）：`gradlew.bat spotlessApply build --console=plain` 通过 467 项测试；IDEA MCP 对 310 个 src Java 和 Tags 共 311 文件逐个 `errorsOnly:false`，最终零错误/警告/弱警告且无超时；MCP build_project 成功。证据 `build/reports/idea/network-budgets-final.json`。实际 Forge/GUI/模型交互未运行验证。
- 预算：begin/end 每 server END 事件一次，capture epoch 不充额度；同 tick 首次成功队伍供给固定，revoke/失败/队伍消失返回不清余额。lease 按实际请求，每机器每 logical 维度一次，中心免标准 THREAD。研究初始numeric与普通余额独立。receipt过期不能commit，rollback仅退原book；去重键含machine/source/sequence，不保留receipt反向引用。owner许可重验全部Capture，worker只读current；重验成本须在正式planned边界整合。
- 计算声明：NetworkResourceRegistrationEvent 早于配方/机器runtime解析，冻结表含标准 thread/compute/qubit；同名完整metadata须一致，相等record无需对象身份相同。捕获检查设备/flat/ppm/参与者全部键，失败撤旧许可。maxPerTickValue限制组件单物品供给和研究segment grant，不限制设备/全队。旧预算与lease审计见network-resources.md。
- 网络运行时：MachineNetworkDevice 显式声明且 recreated，ownership 先load；capture owner-only，精确identity/loadOrder/formed及宿主证明校验后整批发布。每次MAIN action前与CON捕获前refresh，tick退出revoke；失败无旧许可回退。registry revision拒副本来去后的旧批次；旧卸载不撤新代。只暂停配方，普通组件仍运行，恢复不重START。
- 网络规划：OwnerTeamResolver 是扩展接口，FTB 使用真实 server/Universe 持久玩家，离线允许。capture 每 owner 一次查询、拒重复/过期/成员矛盾与同 ID 不同资源元数据；worker plan 只读。中心按等级/加载顺序，数据中心再比已有研究；满库可选。中心+合格电脑+同队参与者合计后一次 exact flat/ppm，不把 maxPerTickValue 当全队上限。
- 身份：FRESH 只接受真实放置玩家，RESTORED 保留 machine/owner；不自动安装到普通机器。位置按 World 引用 + pos，同位置新代撤旧登记，旧 close 不删新代；同 UUID 多个活副本全不唯一，剩一个恢复。登记绑定准确 MachineInstance，不等于 team/epoch/recipe-work 许可。
- 自动配方：显式声明 MachineWorkSlots 后再声明 RecipeScanning，工位走既有 codec、scanner recreated；系统 runBefore 保证同 tick 先扫描后处理。注册表整批发布共享 catalog。空 groups 在 catalog/index/scanner/admission/restore 一致表示全局，包含无组配方；显式组仍过滤。候选/probe 有限额，内部工作预算仍待接。
- GUI：具体类直接继承 AEBaseGui/AEBaseContainer，公开两侧 Java factory。join 后重查窗口/TE 身份，仅同步最多七行不可变快照，保留进度和真实背包；PM 临时 4×4 库存、固定 Idle 和空扩展页是占位。方向工具保留两模式、PM 24 向数学、武器/采掘/耐久/附魔、配置/模型/成就。
- 存储：独立 ResourceStorage/FE 契约和事务不变；AEKey 数量用 AEKey2LongMap 和 addTo，保留必要的溢出预检/零清理/顺序/别名去重，快照通过专用不可变包装器保护。
- 控制器：类型/结构/assembly 整批发布；TE 构造/read 仅留副本，下一 update 过 Forge 放置取消窗口后装配；结构 PM 20/5 节奏 capture→worker→join 后发布。完整朝向与配置变更走 owner FIFO，旧代请求失效。显示不含 runtime/ForgeCaps，不授 PN 权限。
- 运行时与持久化：附 host 后 factory，完整恢复/validate 后 load；retire 先停止新工作，accepted worker 全部实际 join 后清理，卸载保进度、破坏取消。普通调度的 MAIN/CONCURRENT/亲和 lanes 已接；故障不复活。正常 Cleanroom dormant 从 NBT 重建 TE。
- 已有配方能力：组件多系统/Pre-Normal-Post、需求事务、概率/检查点/模糊动态输入/选择器/overlay、完整进程/工位 NBT、取消/失败清理。结构已有 AE 24 向、模板/贪心切片、事实快照与失稳重试；这些不代表剩余整合全部完成。

## 下一步

1. 继续计算组件单物品供给/研究阶段限额、普通配方及研究预算消费、研究/数据中心真实存储、完整参与者（ACTIVE、epoch/version/来源/工位/提交校验）。canonical注册、真实tick账本和owner凭据已接，详见network-budgets.md；消费者不能只检查网络gate或worker current()就跳过预算/提交校验。研究和普通numeric独立、lease共享；参与者补偿失败不能伪装成功。异常loader同TE复用且旧卸载晚到仍需准确卸载代证明。
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
| 计算资源注册/单物品限额/旧预算与lease审计 | [network-resources.md](network-resources.md)，api/network/resource |
| 实际tick预算/lease/当前代工作凭据/原代补偿 | [network-budgets.md](network-budgets.md)，NetworkTeamBudget/NetworkRuntime.WorkPermit |
| 网络角色/服务器发布/共同配方门限 | [network-runtime.md](network-runtime.md)，MachineNetworkDevice/NetworkRuntime/ServerNetworkRuntime |
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
