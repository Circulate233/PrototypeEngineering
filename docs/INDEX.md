# 开发索引

恢复上下文先读本文件并检查 `git status --short`。按入口读取所需章节和源码；需求以用户当前指示及 [development-spec.md](development-spec.md) 为准，实施状态、历史检查点和验证证据集中在 [implementation-status.md](implementation-status.md)。

## 当前检查点

- 总体 Goal 保持暂停。本次单独处理用户仓室方块/物品白灰及 Lumenized 物品发暗反馈，源码已本地提交 `16640be`，未推送。44 模型/52 发光元素关闭方向漫反射并改为外壳先绘制；30 标记元数据缺 BLOOM 时仅复制回退 CUTOUT，保留 light=15，有原生层时保留 Bloom；共享仓室 CUTOUT 保透明覆盖。专项4/1、完整1872/248均全零，4 Java 与 Mixin 配置本机全级零、MCP build 成功。入口 [仓室美术](assets.md)，证据 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/hatch-model-parity/`；需重启客户端复验，新 Mixin 不能仅靠资源刷新应用。

- 2026-11-10 局部定义替换 A–E、外部 JEI 页面绑定和 AE 网络包结构已完成本轮实现与验证。专项 67 tests/12 suites、完整构建 1868 tests/247 suites，失败/错误/跳过均为零；67 Java 本机 MCP 全级零、完整读回匹配，MCP build 成功。源码已本地提交 `644e710`，未推送。入口 [定义替换](recipe-definition-replacement.md)，新证据位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261110/unified-final/`。
- 历史仓室/结构批次已本地提交 `e35604e` 结构纯计划缓存、`2c55f70` 仓室配置/自动IO、`812c0f9` 大槽窗口/禁用滑块；未推送。完整1814 tests/237 suites全零，73Java与两语言本机全级零/完整读回，MCP build成功；精确候选编译与独立复核通过，原暂存及工作字节保留。自动IO部分结算/回调重入、邻居和同TE内容变化的公平刷新已修复；材料提示仅槽位数量/槽位容量，AE默认等价与比例API保持，自动IO默认关闭，能源用户限额默认-1、0禁止且保留profile配置上限。证据从 `build/staging/chat-goal-recovery-2026-11-10/handoff.md` 进入。
- 2026-11-10 总 goal 已恢复。本批已实现输入来源策略、跨控制器周期结构检查并发、PN 外部网络参与者；独立复核发现的问题均已修复。完整构建 1744 tests / 224 suites 全零，37 Java 本机全级零及完整读回一致，MCP build 成功。已本地提交 `b49f62c` / `239b52d` / `ad8c584`，未推送；入口 `build/staging/resumed-goal-plan/plan.md`、`unified/third-results/` 及同目录 `exact-commit-preparation/`。
- GUI/两件工具修复已提交 `73233cb` / `7bcb5e5`；用户最新回复“尚未复验”。范围结构选择器、机械构建器、仓室/控制器、JEI 与样板导入的实机结果仍待用户，不能以构建通过替代。入口 [结构工具](structure-tools.md)、[GUI 修复](gui-parity.md)、[JEI](jei.md)。新反馈优先。
- 后继 Java 工作为局部配方/命名matcher及modifier定义替换，再接CraftTweaker；纯结构计划和安全仓室重配置已完成本批。`definition_replacement_audit` 正把既有独立方案收束为实际scanner/codec/JEI和远端本地声明边界，入口 `build/staging/definition-replacement/`。Applied-Flux继续暂缓。

## 下一步

1. 定义替换本轮已验证并本地提交 `644e710`；保留两语言现有文案、`JeiResourceReloadTest` 与 `docs/INDEX.md~` 背景。当前基线为用户的 `b2dee38 spotless`，原 Gradle 导入修复已包含其中。
2. 先复验仓室方块/物品在无 CTM、仅 CTM、CTM+Lumenized 下的颜色、透明覆盖及局部发光；未自行启动游戏。总体 Goal 暂停，恢复后再按实际 PM/PN 调用补核命名 matcher/modifier 与普通 ZSData 桥接等 Java 前提，再接 CraftTweaker。原 CT 计划随清理丢失，按规格和源码恢复最小范围；不恢复 GUI DSL、独立 Modern，Applied-Flux 暂缓。
3. 游戏由用户自行启动，GUI/工具/真实 HEI 页面和 AE 客户端调度仍待实机复验；纯行为测试不冒充原生运行通过。
4. 用户已确认手动清理 build/.idea。原 stage 的 before/XML/MCP 原文件未恢复，旧条目只作历史；后续证据均保存在上述 build 外 recovery 目录。

## 必须遵守

- 纯库 `prototype_eng` / `com.circulation.prototype_eng`；不添加正式机器、配方或玩法数值。先审查 PM/PN 的实际目的和实现，再复用/修正；保留未明确取消的效果及多线程能力。
- Java 25；API按真实外部职责划分，不制造 XxxImpl 镜像。集合优先fastutil，AEKey数量用AEKey2LongMap，累加/扣减优先addTo，保留溢出及事务预检；必要原子字段优先共享VarHandle，不用非必要反射或异常控制流。
- AEKey/AEKeyType负责身份，核心不用MEStorage；资源/FE仓保留十阶、三方向及公开扩展，混合仓两侧独立且各侧支持多类型，FE独立。
- GUI直接AEBaseGui，无编辑器/第二套基类/ModularUI依赖或资源命名空间。LocalizationEnum：客户端getLocal、服务器text，GUI不拼翻译键或裸调用翻译。JEI普通池每池一个默认或自定义实例；有效外部UID仅注册机器入口。
- IDEA只用本机MCP。未挂载时连接已启用的 `http://127.0.0.1:64343/sse`，先tools/list，逐文件get_file_problems(errorsOnly:false)，记录/消除错误、警告与弱警告；不得操作桌面或修改IDE设置，构建不代替检查。
- 阶段完成本地提交、不推送；不自行启动Minecraft客户端/服务端或Bootstrap/FML测试初始化，保留其他工作区修改及 `docs/INDEX.md~`。当前检查点更新本索引，详细状态/证据写实施进度。

## 按需阅读

| 内容 | 入口 |
|---|---|
| 已确认需求/功能范围 | [development-spec.md](development-spec.md) |
| 实际完成度/验证证据 | [implementation-status.md](implementation-status.md) |
| 旧实现及已知问题 | [migration-audit.md](migration-audit.md)、[machine-controller-migration-audit.md](machine-controller-migration-audit.md) |
| 检查点分段执行 | [checkpoint-execution-audit.md](checkpoint-execution-audit.md) |
| 配方阶段/预设事件的真实迁移状态与功能优先顺序 | [recipe-feature-parity.md](recipe-feature-parity.md)、[pm-recipe-events-audit.md](pm-recipe-events-audit.md) |
| 普通数据库下载/自动升级/真实物理授权/数据库 GUI | [data-centers.md](data-centers.md) |
| 普通物品转换/材料和来源费用/nonce与原生输出恢复/分级盘边界 | [research-downloads.md](research-downloads.md) |
| 研究 GUI/分页同步/文案/开始接管黑名单与领取 | [research-gui.md](research-gui.md) |
| 研究定义/资格/入队/队列/完成传播/奖励与服务器缺口 | [research-features.md](research-features.md)、[pn-research-execution-audit.md](pn-research-execution-audit.md) |
| 研究站显式消费者/真实来源/材料周期/END共同提交 | [research-stations.md](research-stations.md) |
| 服务器研究 owner/单一存档/FTB/玩家事件/手动入口 | [server-research.md](server-research.md) |
| 服务器奖励领取/准确会话/物品确认与证据清理 | [research-rewards.md](research-rewards.md) |
| 手动研究逐项投料/玩家回执/九状态恢复/暂停接管 | [manual-research.md](manual-research.md) |
| 配方/组件/事务/扫描 | [recipes.md](recipes.md) |
| 配方输入来源分配策略 | [input-allocation.md](input-allocation.md) |
| 配方池 JEI 默认/独立自定义/外部已有页面 | [jei.md](jei.md) |
| 局部定义替换/历史版本/远端声明 | [recipe-definition-replacement.md](recipe-definition-replacement.md) |
| 机器 GUI/自定义入口 | [machine-gui.md](machine-gui.md) |
| 方向工具/原交互数学 | [orientation-tool.md](orientation-tool.md) |
| 范围结构选择器/机械构建器 | [structure-tools.md](structure-tools.md) |
| 机器声明/整批绑定 | [machine-types.md](machine-types.md) |
| 装配/NBT/稳定边界 | [machine-persistence.md](machine-persistence.md) |
| PN 持久身份/准确登记/后续授权 | [machine-identity.md](machine-identity.md)、[pn-identity-migration-audit.md](pn-identity-migration-audit.md) |
| 队伍/FTB/中心规划/计算贡献 | [network-planning.md](network-planning.md) |
| 计算资源注册/单物品限额/旧预算与lease审计 | [network-resources.md](network-resources.md) |
| 实际tick预算/lease/当前代工作凭据/原代补偿 | [network-budgets.md](network-budgets.md) |
| PN 配方扩展/并行占用/真实数据中心/服务器自动协调 | [network-recipes.md](network-recipes.md) |
| 网络角色/服务器发布/共同配方门限 | [network-runtime.md](network-runtime.md) |
| 外部网络参与者/工作责任 | [network-participants.md](network-participants.md) |
| 生命周期/卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md) |
| 可暂停机器组件/持续执行gate/调度接线风险 | [machine-tick-boundaries.md](machine-tick-boundaries.md) |
| 控制器运行时/退休 | [machine-controller.md](machine-controller.md) |
| 真实 TE/朝向/结构接线 | [controller-integration.md](controller-integration.md) |
| 普通调度/服务器边界 | [scheduling.md](scheduling.md) |
| scheduler持有的planned cycle/取消/实际退出/可重试关闭 | [scheduler-planned-ownership.md](scheduler-planned-ownership.md) |
| planned 屏障/取消/实际退出与后续接线 | [planned-execution.md](planned-execution.md)、[planned-migration-audit.md](planned-migration-audit.md) |
| 真实跨宿主阶段协调/source屏障/pending/实际退出 | [planned-recipe-coordinator.md](planned-recipe-coordinator.md) |
| 工位批次/宿主lease/多进程round/结果发布 | [work-slot-batches.md](work-slot-batches.md) |
| 真实阶段/一次tick会话/延迟post/准确恢复 | [recipe-tick-sessions.md](recipe-tick-sessions.md) |
| 跨lane资源独占/批次结算/准确token恢复 | [deferred-resource-batches.md](deferred-resource-batches.md) |
| 业务query/需求作用域/复合事务/revision额度 | [transaction-recovery.md](transaction-recovery.md) |
| 结构匹配/快照/发布 | [structures.md](structures.md)、[structure-migration-audit.md](structure-migration-audit.md) |
| 存储/预留/FE | [storage-redesign.md](storage-redesign.md) |
| 仓室/美术来源 | [hatches.md](hatches.md)、[assets.md](assets.md)、[controller-assets.md](controller-assets.md) |

## 本地源码

PE `F:/new/PrototypeEngineering`；PM `F:/clone/PrototypeMachinery`；PN `F:/new/PrototypeNetwork`；AE2S `F:/clone/Applied-Energistics-2`；MMCE `F:/clone/ModularMachinery-Community-Edition`。PM存储入口 `src/main/kotlin/{api,impl}/storage`，planned入口 `impl/scheduler/planned`；PE按专题进入 `src/main/java/com/circulation/prototype_eng/{api,machine,recipe,scheduler,structure,research,integration}`。

索引不替代源码审查。精简前完整索引只作恢复审计，保留于 `build/staging/controller-shared-parallel/exact-commit/index-before-compaction.md`；历史事实及证据按实施进度/专题查阅。
