# 开发索引

恢复上下文先读本文件并检查 `git status --short`。按入口读取所需章节和源码；需求以用户当前指示及 [development-spec.md](development-spec.md) 为准，实施状态、历史检查点和验证证据集中在 [implementation-status.md](implementation-status.md)。

## 当前检查点

- 2026-11-09 PM 缺失工具/旋转光照/能量 JEI：范围结构选择器和机械构建器及原 GUI/模型/124 张 PNG 已接入；控制器按实际顶点格式旋转法线与剔除方向；CleanMix 关闭运行依赖传递，实际测试 Gson 已从 2.2.4 恢复为 2.14.0。JEI 补回红石/荧石能量列表条目、透明提示/检索槽及方向/时机索引。完整1693 tests/221 suites全零，末次两项专项/build通过；33 Java、两语言、依赖脚本本机全级零，MCP build成功。证据 `build/staging/pm-tools-and-lighting/`，入口 [结构工具](structure-tools.md)、[控制器资源](controller-assets.md)、[JEI](jei.md)。真实游戏验收待用户，其余代理/总goal暂停。


- 2026-11-09 默认JEI布局反馈：取消说明分页，固定两侧各4×4完整PM槽背景；实际字体换行/池级最大高度向Y展开，所有材料页显示完整说明，页脚与输入边界同步。文案改为加工时间、能量消耗/产出等玩家表达，移除默认技术介绍。专项33项、完整1682 tests/217 suites全零；12 Java及两语言本机全级零，MCP build成功。完整验证记录从`build/staging/jei-default-layout/`和[JEI说明](jei.md)进入。真实视觉待用户复验；其余迁移/代理/总goal保持暂停。

- 2026-11-09 仓室再次反馈：擦除42张仓室纹理的升级槽装饰、修正主体宽度与材料交互仓Lv3/4输出区偏移；能源单向仓电池右移23px并拓宽左侧，交互仓两根电池/两行数量分别同步和提示。用户明确能源交互仓为双独立容器，已分开真实余额、机器/Forge方向、预留、NBT与生命周期；PM原实现单storage/单电池动态接线仅作调查事实。专项28项、完整1680 tests/216 suites全零，10 Java本机全级零。证据 `build/staging/hatch-io-layout/`；实机仍待用户复验，其他迁移/代理/总goal暂停。
- 2026-11-09 实机 GUI 反馈已修正：标题/输入框/机器及结构标题按区域对齐，材料仓名称与104个纹理/模型文件改名，非物品及容量/按钮 tooltip、超64中键接线补全；关闭按钮改用 AE 并移除 MUI 三图/说明，控制器临时占位区禁收物品，整机状态包含页外进程。专项40项、最终完整1676 tests/216 suites全零，14 Java与两语言本机全级零；一次结构节拍测试失败及通过复查均保留于 `build/staging/gui-feedback/`。入口 [实机反馈修复](gui-parity.md)。用户另授权的6.1-sol/xhigh示例已交付：13台机器/13池/106配方，含能源输入/输出/混合仓与最大170实际方块结构，已安装run/mods；入口 `run/visual-examples/README.md`。其余代理及总goal保持暂停；实机复验待用户。
- 2026-11-09 GUI/JEI 修复：恢复 PM 仓室/控制器原 PNG、布局、文本与交互，结构预览 native renderBlock、控制器拾取/24 朝向及仓室资产链；通用仓 slot 保留 AE 多类型操作，能源直接调用 AE MathExpressionParser，无 EvalEx 新依赖。JEI 保留按池自动排布/分页并用 PM 纹理，物品原生、非物品统一 GenericStack；多可选材料同槽轮播，样板导入复用 AE2S 优先级，逐 tick 数量乘配方时长，不附加概率/候选拒绝。最终完整重跑 1675 tests/216 suites 全零，49 Java 本机全级零、MCP build 成功；精确本地提交/背景保护从 `build/staging/gui-original-parity/` 进入。实机视觉及终端网络导入未验收，总 goal 与所有子代理仍暂停。入口 [GUI 原效果修复](gui-parity.md)、[JEI 显示与导入](jei.md)。
- 2026-11-09 Modern有效算法/线程策略已直接并入：默认CPU平台池与显式virtual per-task、有限single-worker lanes保留原safe reload/pending/source/structure/finished/FIFO义务；Gecko SoA/cube packed及generic primary scratch接入当前管线，可选Vector保持scalar默认。正常完整build1658 tests/212 suites全零、15任务全执行，11 Java本机全级零/完整读回一致、MCP build成功；module缺席/强制scalar/实际Vector三种真实输出一致，9pilot排除阈值、六关键场景长样本与窄修/3最终scalar fork raw保留。入口 [普通调度](scheduling.md)、`build/staging/modern-inline-integration/`、`build/staging/gecko-modern-math/`；未推送/启动游戏。
- 2026-11-09 控制器与配方池阶段已实现并验证：可继承的 MultithreadController/CrossRecipeController、正常装配默认 PLANNED_PARALLEL、整机并行余额/配方线程上限、事件最终数量覆盖、通用/简单池接口及预算化 live freshness。focused70 tests/9 suites、实际完整重跑1620 tests/206 suites均零失败/错误/跳过；42 Java 本机 IDEA 全级零、完整读回一致，最终 MCP build 成功。最早 before、真实失败/最终日志、独立审查和精确本地提交见 `build/staging/controller-shared-parallel/`、`build/staging/recipe-pool-strategies/`、`build/staging/controller-pool-plan/review.md`。
- 结构异步/预算/首真实tick、JEI原生装饰与显式ResourceRecipeIndex freshness 本轮完成并验证，保留原scheduler/默认PLANNED_PARALLEL及旧virtual扩展。正常完整重跑1636 tests/207 suites全零、15任务全执行；结构39/JEI13/index2 Java本机MCP全级零与完整读回一致，MCP build成功。JEI/Resource已本地提交5ac7908/2d3237e，精确提交及背景保护记录见对应stage；入口 [结构说明](structures.md)、[JEI说明](jei.md) 和 `build/staging/structure-async-budget/continued-full-result.json`，earliest-before、四repair与背景均保留。整体 PM/PN 迁移继续。
- 调度统计/配置阶段已实现并验证：原 Java scheduler 的 effective/pending、安全 tick 重载与 typed wall/count report，实际 permission-2 pe_scheduler command；focused120 tests/17 suites、正常完整重跑1648 tests/210 suites均全零且15任务全执行，11 Java本机全级零/完整读回一致、MCP build成功。原before、纯行尾repair/首次真实失败、最终证据与精确本地候选/提交保护从 `build/staging/scheduler-statistics-config/` 恢复；入口 [普通调度](scheduling.md)。
- 已完成阶段及证据从实施进度/专题恢复：研究下载、接口用户盘、材料破坏会话、个人遗忘/管理员绝对等级、结构 A/B/C/D1/D2/D3 与 AE GUI/ModularUI 撤除。历史暂停/停止均不替代当前恢复后的目标；不在本索引重复其测试记录。

## 下一步

- 当前新增复验范围：用户自行运行 client，核对 CleanMix/Gson 启动、两件结构工具原 GUI 和实际建造/拆卸、控制器各朝向光照，以及红石/荧石能量 JEI 条目的提示/检索。具体操作从 [结构工具](structure-tools.md) 进入；构建和逻辑测试不替代游戏验收。


- 当前优先级覆盖下方历史队列：按 [GUI 原效果修复](gui-parity.md) 等待用户实机复验：仓室/能源/控制器、结构预览、JEI 多可选材料轮播及普通/PEA 样板终端导入，其余迁移及总 goal 暂停；run 边界示例已交付并安装，按 `run/visual-examples/README.md` 的 build/stock/charge 命令测试实际成型与运行。

1. 继续 [配方功能核对](recipe-feature-parity.md)：工位 RESCAN/isCore 策略待契约，入口 `build/staging/workslot-restart-plan/facts-and-contract.md`。
2. 统计/配置已实现并验证：入口 [普通调度](scheduling.md)、`build/staging/scheduler-statistics-config/`。D04 的直接整合本轮已实现并验证，入口 `build/staging/modern-inline-integration/` 与 `build/staging/gecko-modern-math/`；默认CPU平台池与标量数学、显式virtual/可选Vector的证据和成本边界均保留；历史 `build/staging/scheduler-backend-plan/handoff.md` 的独立组织待定不再适用。结构预算公开合同见 `build/staging/structure-budget-plan/policies.md`。
3. Java 完整后最后接 CraftTweaker；Applied-Flux 暂缓。精确本地提交与原背景保护记录按实施进度进入对应stage。
4. 实机 World/网络包/GUI/GL/Bloom/HEI验收待用户自行启动；构建和纯逻辑测试不代替这些证据。

## 必须遵守

- 纯库 `prototype_eng` / `com.circulation.prototype_eng`；不添加正式机器、配方或玩法数值。先审查 PM/PN 的实际目的和实现，再复用/修正；保留未明确取消的效果及多线程能力。
- Java 25；API按真实外部职责划分，不制造 XxxImpl 镜像。集合优先fastutil，AEKey数量用AEKey2LongMap，累加/扣减优先addTo，保留溢出及事务预检；必要原子字段优先共享VarHandle，不用非必要反射或异常控制流。
- AEKey/AEKeyType负责身份，核心不用MEStorage；资源/FE仓保留十阶、三方向及公开扩展，混合仓两侧独立且各侧支持多类型，FE独立。
- GUI直接AEBaseGui，无编辑器/第二套基类/ModularUI依赖或资源命名空间。LocalizationEnum：客户端getLocal、服务器text，GUI不拼翻译键或裸调用翻译。JEI每池一个默认或自定义接口实例。
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
| 配方池 JEI 默认/独立自定义页面 | [jei.md](jei.md) |
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
