# 开发索引

恢复上下文先读本文件和 `git status --short`，再按入口定位源码/章节。需求见开发文档，完整状态和历史证据见实施进度；不整批加载文档。

## 当前检查点

- 2026-11-08 结构 D1 原生方块模型世界投影本阶段实现与验证已完成：公开 BLOCK_MODEL 实际接 RenderWorldLast，复用 C 当前完整模型/已过滤 Sample；首个有效 display block/meta、原生 atlas 四层、fullbright/0.65 顶点 alpha、相对16³ VBO 与 LRU8 已接。静态构建及逐格 CPU 准备/发射按 cell/layer/getQuads/quad 续办，保留原独立96/128区块访问预算；空/远区块也计访问。稳定多样本低预算跨帧公平推进，最终透明选中集远→近；代/resource epoch/断连/外部close准确撤销与释放。新增18行为，focused18/1、受影响130/12、最终正常完整build1435 tests /179 suites全过；12 Java本机MCP errorsOnly:false全级零、完整读回/磁盘12/12一致、MCP build成功，当前盘raw源码构建前后及最终MCP稳定。准确写前、C最终重接、固定候选/修正delta/原诊断与15路径统一清单入口 `build/staging/structure-preview-block-models/verification/`，见[结构定义](structures.md)。未推送、未启动Minecraft/服务端，真实OpenGL实机尚未验收；下一步D2 AEBaseGui/JEI/代表项选择/TESR-NBT、D3模型/动画/Bloom/隐藏继续，不能把D1算整个预览完成。

- 2026-11-08 结构 C 基础预览模型与世界盒投影本阶段实现与验证已完成：客户端沿权威目录增量展开模板/切片 occurrence，按子边选择切片、根旋转、零片子结构、固定控制器排除和坐标后项覆盖；完整不可变模型交付名字/层/全结构 display BOM。StructurePreviews.client 的 request/advance/project/move/stop 已接实际 tick 与 RenderWorldLast，支持轮廓/半透明盒/组合、距离/层/预算/展示命中隐藏；观察仅代表性视觉比较，不是 matcher/成型权威。精确代/连接/世界及回调重入隔离，未知注册ID与未加载chunk显式处理。focused26/2、受影响126/14、正常完整build1417 tests /178 suites全通过；12 Java本机MCP全级零、完整项目文件读回/磁盘12/12一致、MCP build成功，源码SHA稳定。准确写前、冻结候选/P2与推广修正、原失败及最终证据/15路径统一清单见 `build/staging/structure-preview/verification/` 和[结构定义](structures.md)。未推送、未启动Minecraft或服务端；完整纹理方块模型、模型隐藏/复杂渲染/VBO/Bloom、预览GUI/JEI及实机验收继续后续，Applied-Flux暂缓、CT最后。

- 2026-11-08 R32 机器声明/动态配方池本阶段实现与验证已完成：静态池引用、canonical 池和机器反查同次冻结发布；实际 `getRecipePools(RecipeCatalog)` 子类入口经 create/restore 工厂交付，零声明/空选择无自动候选，任意 canonical 池可动态选择，同一逻辑 pass 在预算 yield 间只取样一次。同步/bounded 扫描复用原工位与资源/FE/研究/计算/来源结算，运行和恢复进程保持原生命周期；JEI 消费静态机器关联注册真实控制器 catalyst，默认页显示本地化数量/名称/ID。新增14行为；focused37、额外受影响92、旧fixture复验12全通过，正常完整 build1391 tests /176 suites全通过；19 Java本机MCP全级零、完整读回/磁盘19/19一致、MCP build成功，21源码资源测试SHA稳定。见[配方](recipes.md)、[JEI](jei.md)、[需求](development-spec.md)，准确写前/固定delta/失败与最终验证/统一清单入口 `build/staging/machine-recipe-pools/verification/`。本阶段实现与验证已完成；未推送、未启动Minecraft或服务端，OpenGL/原生catalyst实机验收仍待继续。

- 2026-11-08 块二 B 扫描内部工作预算本阶段实现与验证已完成：真实 RecipeScanningSystem 经原机器游标暂停到同一 planned owner，目录/索引、factory、属性继承、约束/START probe、需求捕获和存储预留/结算按实际访问续办；ScanLimits 的候选/probe 限额与 RecipeWorkBudget 独立。只允许 captureOnlyPreparation 捕获穿过等待中的纯资源准备，同轮完整捕获后才派发，FE/opaque 回调保持原序；旧 Stage.acquire 同步与后续子段语义保持。原授权/deadline 后重验，UNKNOWN/真实未退出保留原 token、receipt 与清理义务，部分原生预留及退款溢出预检已覆盖。受影响 629 tests / 83 suites、最后授权重验 41 tests / 3 suites、最后正常完整 build 1377 tests / 174 suites 全通过；52 Java 本机 IDEA MCP errorsOnly:false 全级零、完整项目文件读回/磁盘 52/52 一致、MCP build_project 成功，52 Java 构建前后 SHA 稳定。准确写前与最终输入同时冻结，原 41 个受影响失败及修复证据保留于 `build/staging/recipe-scan-budget/verification/`，见[正式执行](planned-execution.md)、[配方](recipes.md)、[网络消费者](network-recipes.md)与[功能核对](recipe-feature-parity.md)。外部合作回调、完整首次 tick 等价投影、结构预览/复杂渲染及实机验收仍有边界；未推送、未启动 Minecraft 或服务端。

- 2026-11-08 结构 B 运行中重载、准确既有/独立宿主配置代撤销与客户端权威目录/缓存同步已实现并验证：实际 `/pe_structures reload` 经原 scheduler join/待确认义务边界，复用 A 完整解析与原子目录替换；整批旧配置先撤销，再暂停/安装新代并重建同朝向绑定，旧 worker 无权发布。实际 Loaded 记录后自动登记独立宿主，显式 owner 保留原 cadence；live session 的 foreign scheduler 构造显式拒绝且无登记残留。服务端会话/序号+16 KiB 分块同步完整几何/name/display DAG，部分数据保留旧 view/cache，完成后一次替换，断连清理。23 项 B 行为、受影响 187 tests / 24 suites、完整 build 1308 tests / 164 suites 全通过；24 Java 本机 IDEA MCP errorsOnly:false 全级零、完整项目文件读回/磁盘一致、MCP build_project 成功，26 源码资源构建前后及 24 Java MCP SHA 稳定，独立 max 审计闭环。见[结构定义](structures.md)，准确 formal-before/冻结候选/修正 delta/最终统一清单与验证证据 `build/staging/structure-reload/`。预览/投影渲染、切片/BOM/display override 消费、模型隐藏/复杂渲染及实机验收仍待继续；本阶段实现与验证已完成，未推送、未启动游戏或服务端。

- 2026-11-08 结构 A 启动期严格两阶段 JSON/共享发布本阶段实现与验证已完成：PreInit 只冻结 DTO，PostInit 冻结内置/外部谓词工厂并解析全部 JSON/Java DAG，完整目录、机器结构引用与装配经一个私有不可变快照原子发布；未被机器引用的定义也保留，translationKey/display 与 matcher 分离，昼夜使用实际 DAYTIME。新增 21 项行为，受影响 112 tests / 14 suites、完整 build 1285 tests / 161 suites 全通过；18 Java IDEA MCP errorsOnly:false 全级零、完整 read_file 项目文件文本/磁盘一致、MCP build_project 成功，18 Java 构建前后与 MCP 检查 SHA 稳定。见[结构定义](structures.md)及 `build/staging/structure-json/` 的准确写前/最终快照与证据。后续 B 的实际结果见上方检查点；投影/切片/BOM/display override 消费与复杂渲染未计完成。A 已本地提交 `63247b8b5c2652c7909835b023a2f94d5e2584f9`，未推送，未启动游戏或服务端。

- 2026-11-08 块二 A 正式 PLANNED_PARALLEL 与独立机器宿主已接通并验证：公开 PlannedMachineTask 交付真实 MachineInstance，Controller/Instance/外部 wrapper 共用原生命周期、组件游标、工位 coordinator 和来源 lanes；公共三参 scheduler 默认接正式机器，服务器仅为 CONCURRENT 网络机器追加 admission/refresh。捕获登记、active、宿主及底层 mode、原机器/lifecycle，owner prepare 返回后重验 current，旧 pending 不续权；MAIN → CONCURRENT join → planned 顺序保持。正常完整 build 1264 tests / 160 suites、受影响 170 tests / 20 suites 全通过，12 Java IDEA MCP errorsOnly:false 全级零、完整 read_file 项目文本/磁盘一致，12 源码构建前后 SHA 稳定。见[正式执行](planned-execution.md)、[调度](scheduling.md)和[网络消费者](network-recipes.md)，准确写前基线/最终快照/证据入口 `build/staging/planned-mode-hosts/`。扫描内部工作预算属于 2B；本阶段实现与验证已完成，未推送、未启动游戏或服务端。

- 2026-11-08 JEI 每池默认/独立自定义页面已实现并验证：客户端以稳定池 ID 绑定一个生效分类实例，默认按 IO 最大槽数生成并分页，自定义直接实现原生页面接口；AEKey/long、独立 FE、条件与原生交互保持。全池及当前全部 registered ingredient 在任何 wrapper/index 发布前按原生 helper UID 统一捕获，已知冲突 key 全部降级并提示，正常查询沿原生 helper 语义；不证明未知后续 NBT 全域精确查询。两 suite 12 行为及完整 build 1259 tests / 159 suites 全通过，17 Java IDEA MCP errorsOnly:false 全级零诊断、完整 MCP 项目文件读回/磁盘一致，19 源码资源构建前后 SHA 稳定，17 Java 在 MCP 验证后无变化，MCP build_project 成功。见[JEI 页面](jei.md)，准确基线/最终快照/证据入口 `build/staging/jei-pool-migration/`。额外转移/书签/结构预览/历史 decorator 资产不计完成，客户端实机未验收；已本地提交 `caf721849b8cc01d96e16a66717407daa0fafcc4`，未推送、未启动游戏或服务端。

- 2026-11-08 块一真实 pure prepare 与内置工作预算已实现、验证并补齐文档：CONCURRENT 网络及研究材料消费者经既有 runPrepare 派发纯 kernel，owner 实际捕获/选择/预检/预留按预算续办；原 deadline/current attempt 内 work yield 不续权，跨 tick 仅复用无授权纯事实，overlay 复用规范化冻结列表，旧同步 acquisition 的后续子段模式保持。完整 build 1247 tests / 157 suites 全通过，33 Java IDEA MCP errorsOnly:false 全级零诊断、MCP项目文件读回与磁盘一致、MCP build_project 成功，34 源码/资源文件构建前后 SHA 一致。外部 snapshot/matcher/comparator/opaque callback 是合作回调边界；正式 PLANNED_PARALLEL、独立宿主自动接线和扫描预算归块二。见[执行边界](planned-execution.md)、[配方](recipes.md)、[网络消费者](network-recipes.md)及[功能核对](recipe-feature-parity.md)，精确基线/统一最终清单与快照入口 `build/staging/recipe-pure-prepare/`。未推送，未启动游戏或服务端。

- 2026-11-07 永久破坏材料会话已接并完整验证：真实 broken/未装配 raw 通知、同服原 owner cancel、cold 局部终结及重启后已加载原位置核验；Schema 5 保存可选原物理方块凭据，外部未知宿主不判毁。新增 10 项 World 行为，完整 build 1224 tests / 156 suites 全通过，18 Java IDEA errorsOnly:false 全级零诊断、23 文件构建前后 SHA 一致；独立审计闭环。精确阶段快照/证据 `build/staging/research-station-destruction/`，见[研究站执行](research-stations.md)。本阶段已精确本地提交，未推送，未启动游戏或服务端。

- 2026-11-07 扩展阶段完成实现与最终验证：AE2S 物理继承/开放扩展、仓室接口和 GUI、流体每次 fill/drain 转移限额与 clear/source fence、组件亲和及任意轻量结构端口；MachineAssembly Schema 2 保存成型次数，同代 refresh 不增、恢复不带形成授权。完整 build 1179 tests / 153 suites，零失败/错误/跳过；79 Java 的 IDEA errorsOnly:false 全级零诊断（40 格式改动复检、39 原 SHA 未变复用），Document/磁盘一致、构建前后 SHA 无漂移，MCP build_project 成功。原 IDE Git 新文件确认对话框阻塞已解除；验证及准确暂存证据见 `build/staging/component-extension/full-build-final-result.json`、`build/reports/idea/component-extension-inspections-79-final.json` 和同目录 `exact-index-manifest.json`。见[组件扩展](component-extension-migration.md)、[仓室](hatches.md)、[控制器集成](controller-integration.md)。下载已提交 `f180bfc`，扩展阶段已本地提交；未推送、未启动游戏或服务端。

- 2026-11-07 R30 接口式分级盘源码已接：使用方注册自己的 Item/tier/布局，Carrier 与现编码事件便捷注册复用精确 AEItemKey 转换，统一 RightClickBlock hook 在 GUI 前接实际服务器导入；库不内置盘/等级表。World Schema 4 复用准确单槽材料凭据，与数据库原子内容+receipt、真实落盘回读共同恢复。编码仅研究门槛 tier ≤ 盘 tier，导入再检查盘 tier ≤ 目标数据库 tier，完成等级独立。databaseArmed 区分安全取消、首次明确拒绝和未知义务；原目标恢复及登录/tick/物品保护已接。本阶段新增 35 项行为，完整 build 1214 tests / 156 suites 全通过，29 Java IDEA errorsOnly:false 全级零诊断、构建前后 SHA 一致；独立审计三项闭环，三种当前 Jar 无内置盘残留。盘阶段已精确本地提交，未推送。见[物品化下载](research-downloads.md)，证据/基线入口 `build/staging/research-disk-workflow/`。

- 2026-11-07 用户确认并要求实施 R31：控制器方块可继承；控制器 Block/ItemBlock 与仓室 Block 等按 PM 真实扩展方式核查并修正，消除公开扩展点的 final 限制，方块、方块实体和物品继承当前 AE2S 对应基类，仓室公开接口便于外部特殊实现接入。实施入口见[控制器迁移审计](machine-controller-migration-audit.md)和[仓室](hatches.md)。

- 研究默认 GUI 与 AE2 分页同步已接：目录/队列/奖励、文案/图标、Java 打开及开始/接管/黑名单/领取。请求绑定原窗口、玩家、队伍和准确队列项，奖励保留本人永久资格；详情见[研究界面](research-gui.md)。

- PN 普通配方研究/计算已接通：有序扩展注册、机器默认与逐配方条件合并、真实选中数据中心、阶段占用/提交/补偿。算力等 numeric 随并行量缩放；THREAD 按整机 UUID 只占 1，不随工位或 Java 线程数重复。START/扫描只检查，工作 TICK 占用，零时长 START 占用一次。见[配方消费者](network-recipes.md)。
- 服务器 CONCURRENT 网络机器已自动转入 owner 协调，原组件游标在工位处理处暂停，材料/FE 来源 lanes 仍并行，结算后恢复后续 normal/post；pending、卸载及旧 tick 保留准确清理义务。公共三参 scheduler 默认接正式机器宿主；普通 CONCURRENT 网络 admission/refresh 由服务器注入，扫描内部预算已由 2B 接入并验证。
- 真实计算组件物品供给已接通：AEItemKey 精确注册、真实 committed 槽、long 数量、单物品上限和整机供给合并；提取预留仍供给，待插入输出不供给。捕获后的授权只读版本证明，可在材料/FE 锁及 source fence 内使用，见[计算资源](network-resources.md)。
- 研究站自动服务器执行与世界会话已接：普通工作之前由 scheduler 持有原 execution，真实站点/共享 lease、合摊材料、numeric 独立限额及 END grant/completion 共用原 lanes。每段 START→一次成功 TICK→END，缺料只重试未成功阶段；当前 Schema 5 保存原站、可选原控制器方块凭据和完整材料，并包含接口式用户盘导入；永久破坏沿原进程 cancel 或精确 cold 局部终结，见[研究站执行](research-stations.md)。
- 检查点与候选局部回退已接：同一外层阶段逐段执行子 START/TICK/END；候选 Blocked/Failure 时，在原 fence 和 source lanes 撤销完整候选后缀，确认后尝试下一项，外层成功前缀保留。支持嵌套选择、产物链、满仓闭环与 modifier/overlay 顺序；普通 continuation 保留跨来源并行，见[检查点审计](checkpoint-execution-audit.md)。
- 服务器奖励领取与原生交互保护已接：准确世界检查点、UUID.dat/单人 level.dat/Anvil 文件核验、明确零效果重试；Mixin 在原执行处覆盖副手/创造/使用、死亡重生、光标关闭/保存、装备损耗和漏斗。清标签前重写 pending，第三方直接改栈使用公开保护查询；效果前 PREPARED/ISSUING 凭据、加载时合并与准确未发出恢复已接，ISSUING 缺失仍未知，见[服务器奖励](research-rewards.md)。
- 最近验证（2026-11-07，物品化下载）：新增 18 项 World 行为测试，全 test 1164 tests / 150 suites 全通过；World 核心 8 Java、材料修复 2 Java、旧 schema 契约测试 1 Java IDEA MCP 全级零诊断。随后一项 native 文件回读恢复及下载/奖励受影响 suite 已通过合并定向运行，共享模板修复 2 Java IDEA MCP 全级零诊断。证据 build/reports/idea/download-world-final.json、download-world-verification-final.json、download-native-template-final.json；平台见 download-platform-final.json。原修改保留，未启动游戏或服务端。
- 数据中心普通下载与自动同步已接到当前队伍权限、真实物理存储和服务器独占边界；每 tick 在研究结算后同步一次，写后刷新不补预算。数据库及普通物品转换 GUI 均绑定原队伍/数据库/研究；普通物品转换已接真实扣料/扣级与输出恢复，接口式特殊磁盘消费和数据库共同事务已接并通过本阶段完整验证，见[数据中心](data-centers.md)和[物品化下载](research-downloads.md)。
- 服务器手动研究已接通世界单一存档、FML 生命周期、FTB 成员核对、tick 边界和 Java 开始/接管；失效队列先结算回执再 prune，旧 pending 检查点先重试，关闭失败保留会话。见[服务器研究](server-research.md)。
- 配方事件已有真实 Java 调用：需求 START/TICK/END、选择提交回调、检查点/属性效果、动态匹配条件、进程钩子及 PN 配方扩展。机器事件不计入；CT 最后，见[事件复审](pm-recipe-events-audit.md)。
- 控制器、结构捕获/匹配、三方向十阶通用资源/独立 FE 仓、普通调度、扫描、默认/自定义 Java GUI 与方向工具已接入。GUI 直接 AEBaseGui，资源数量使用 AEKey2LongMap；实际边界按专题及实施进度核对。

## 下一步

0. 扩展和 R30 接口式用户盘共同导入均已实现、验证并本地提交；盘物品/tier/布局由外部注册，无内置盘/等级表。永久破坏材料会话本阶段已接真实入口，最终证据见 `build/staging/research-station-destruction/`；背景修改及 `docs/INDEX.md~` 保留。

1. 研究视图、领取、普通数据库下载、普通物品转换、接口式用户盘导入和永久破坏材料会话已接；未知结果继续保留原义务。块一真实网络/研究材料 pure prepare 与内置实际预算已完成；2A 正式 PLANNED_PARALLEL 与独立宿主已验证；2B 扫描内部预算与 R32 机器池声明/动态选择及静态 JEI 关联已接并验证；下一步按[配方功能核对](recipe-feature-parity.md)继续剩余 Java 功能与结构/渲染联动。Java 先完整，CT 最后。
2. 保留已接的研究站成员核对、原 pending 所有权、numeric 独立、整机 lease 共享及准确原代补偿；下载的冻结原来源与确认片段持续用于恢复。R31/流体工作按各自专题及真实证据推进，不计为本下载阶段完成项。
3. R32 本阶段实现与验证已完成；2B 已复用纯 kernel、实际预算游标、机器生命周期与 source lanes 接入扫描内部预算；外部合作回调仍遵守原稳定/纯契约。C 基础预览/盒投影已验证；继续完整纹理方块模型/复杂渲染、JEI 额外联动及实机验收，整体重开发仍在进行。
4. 结构 A 启动期严格两阶段 JSON/共享注册及 B 运行中重载、准确宿主撤销/刷新与客户端权威目录/缓存同步已实现验证；C 基础预览/世界盒投影、切片/层/BOM/display override 消费本阶段实现与验证已完成。继续完整纹理方块模型、模型隐藏/复杂渲染/VBO/Bloom、预览 GUI/JEI、结构准备工作预算、JEI 额外联动及其他未取消功能，最后 CraftTweaker。Applied-Flux 继续暂缓，保留 v1.0.0:dev。

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
| 检查点分段执行、嵌套选择与候选局部回退 | [checkpoint-execution-audit.md](checkpoint-execution-audit.md)，CheckpointRequirementSystem、ResourceBatch |
| 配方阶段/预设事件的真实迁移状态与功能优先顺序 | [recipe-feature-parity.md](recipe-feature-parity.md)、[pm-recipe-events-audit.md](pm-recipe-events-audit.md) |
| 普通数据库下载/自动升级/真实物理授权/数据库 GUI | [data-centers.md](data-centers.md)，ResearchDataCenters、DataCenterResearchAccess |
| 普通物品转换/材料和来源费用/nonce与原生输出恢复/分级盘边界 | [research-downloads.md](research-downloads.md)，ResearchWorld、api/research/download、delivery、disk |
| 研究 GUI/分页同步/文案/开始接管黑名单与领取 | [research-gui.md](research-gui.md)，ResearchGuiAccess、ResearchContainer、ResearchGuiSnapshot |
| 研究定义/资格/入队/队列/完成传播/奖励与服务器缺口 | [research-features.md](research-features.md)、[pn-research-execution-audit.md](pn-research-execution-audit.md)，api/research、research |
| 研究站显式消费者/真实来源/材料周期/END共同提交 | [research-stations.md](research-stations.md)，api/research/station、RecipeProcess.materialCycle |
| 服务器研究 owner/单一存档/FTB/玩家事件/手动入口 | [server-research.md](server-research.md)，ResearchWorld、ServerResearch |
| 服务器奖励领取/准确会话/物品确认与证据清理 | [research-rewards.md](research-rewards.md)，ResearchWorld.claimReward、ServerResearch.requestReward |
| 手动研究逐项投料/玩家回执/九状态恢复/暂停接管 | [manual-research.md](manual-research.md)，api/research/manual、research/manual |
| 配方/组件/事务/扫描 | [recipes.md](recipes.md)，api/recipe、api/machine/workslot |
| 配方池 JEI 默认/独立自定义页面、已知查询身份边界 | [jei.md](jei.md)，api/client/jei、integration/jei |
| 机器 GUI/自定义入口 | [machine-gui.md](machine-gui.md)，api/gui、api/client/gui、container/MachineContainer |
| 方向工具/原交互数学 | [orientation-tool.md](orientation-tool.md)，item、OrientationToolConfig |
| 机器声明/整批绑定 | [machine-types.md](machine-types.md)，MachineType/Registry |
| 装配/NBT/稳定边界 | [machine-persistence.md](machine-persistence.md)，MachineAssembly |
| PN 持久身份/准确登记/后续授权 | [machine-identity.md](machine-identity.md)、[pn-identity-migration-audit.md](pn-identity-migration-audit.md)，api/machine/identity |
| 队伍/FTB/中心规划/计算贡献 | [network-planning.md](network-planning.md)，api/team、api/network、integration/ftb |
| 计算资源注册/单物品限额/旧预算与lease审计 | [network-resources.md](network-resources.md)，api/network/resource |
| 实际tick预算/lease/当前代工作凭据/原代补偿 | [network-budgets.md](network-budgets.md)，NetworkTeamBudget/NetworkRuntime.WorkPermit |
| PN 配方扩展/并行占用/真实数据中心/服务器自动协调 | [network-recipes.md](network-recipes.md)，api/network/recipe、NetworkRecipeWorkSystem、MachineRecipeExecution |
| 网络角色/服务器发布/共同配方门限 | [network-runtime.md](network-runtime.md)，MachineNetworkDevice/NetworkRuntime/ServerNetworkRuntime |
| 生命周期/卸载/破坏 | [machine-lifecycle.md](machine-lifecycle.md)，api/machine/lifecycle |
| 可暂停机器组件/持续执行gate/调度接线风险 | [machine-tick-boundaries.md](machine-tick-boundaries.md)，ComponentMap.TickDispatch、MachineLifecycle.ScheduledExecution |
| 控制器运行时/退休 | [machine-controller.md](machine-controller.md)，MachineController |
| 真实 TE/朝向/结构接线 | [controller-integration.md](controller-integration.md)，MachineControllerBlockEntity/ControllerStructureUpdates |
| 普通调度/服务器边界 | [scheduling.md](scheduling.md)，api/scheduler、scheduler |
| scheduler持有的planned cycle/取消/实际退出/可重试关闭 | [scheduler-planned-ownership.md](scheduler-planned-ownership.md)，PlannedExecution、TaskScheduler.tryClose |
| planned 屏障/取消/实际退出与后续接线 | [planned-execution.md](planned-execution.md)、[planned-migration-audit.md](planned-migration-audit.md)，api/scheduler/planned、scheduler/planned |
| 真实跨宿主阶段协调/source屏障/pending/实际退出 | [planned-recipe-coordinator.md](planned-recipe-coordinator.md)，PlannedRecipeCoordinator、MachineWorkSlots.TickGroup |
| 工位批次/宿主lease/多进程round/结果发布 | [work-slot-batches.md](work-slot-batches.md)，MachineWorkSlots.TickBatch |
| 真实阶段/一次tick会话/延迟post/准确恢复 | [recipe-tick-sessions.md](recipe-tick-sessions.md)，RecipeProcess.TickSession/Stage、RecipeStageExecutor.Acquisition |
| 跨lane资源独占/批次结算/准确token恢复 | [deferred-resource-batches.md](deferred-resource-batches.md)，StorageFence、ResourceBatch、ResourceReservation |
| 业务query/需求作用域/复合事务/revision额度 | [transaction-recovery.md](transaction-recovery.md)，ReversibleTransaction、ScopedRequirement、TransactionGroup、StorageContext |
| 结构匹配/快照/发布 | [structures.md](structures.md)、[structure-migration-audit.md](structure-migration-audit.md) |
| 存储/预留/FE | [storage-redesign.md](storage-redesign.md)，api/storage、api/energy |
| 仓室/美术来源 | [hatches.md](hatches.md)、[assets.md](assets.md)、[controller-assets.md](controller-assets.md) |

## 本地源码

新项目 `F:/new/PrototypeEngineering`；PM `F:/clone/PrototypeMachinery`；PN `F:/new/PrototypeNetwork`；AE2S `F:/clone/Applied-Energistics-2`；MMCE `F:/clone/ModularMachinery-Community-Edition`。PM 存储在 `src/main/kotlin/{api,impl}/storage`，planned 在 `impl/scheduler/planned`。

索引不替代源码审查；阶段提交时同步实施进度，只记录实际证据。
