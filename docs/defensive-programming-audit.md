# 防御性编程审查

2026-11-15 清理存档与内部同步的格式版本限制：删除 Version/Schema 的写入、读取、常量和拒绝分支，结构展示协议直接使用当前字段布局。渲染分片移除可由总字节数推导的 Count；普通属性、研究数量与 Set 数据不再重复检查键，机器数据同步不再为唯一性检查创建临时集合。真实交易回执继续核对原操作、付款状态及交付目标，避免重复结算。对应的旧格式分支和仅用于验证格式限制的测试一起移除。

按用户指示检查整个生产源码，先追查状态和机制是否有实际职责，再清理重复条件。第一轮只简化 onLoad 条件并引入 loadedLocation 的做法已撤销；本记录以最终机制为准。审查期间已有大量未提交修改，审计基线和逐模块清单位于 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261113/defensive-audit/`。

## 机制变更

| 范围 | 原问题与处理 |
|---|---|
| TE 配置 | 方块工厂已将模式、等级、槽数或机器类型传给 TE，TE 存档已有这些数据。删除 pendingConfiguration、initializeConfiguration 和 onLoad 反查方块补配；创建使用构造参数，恢复使用 TE 自身 NBT。 |
| 来源诊断标签 | 只有自定义分配回调消费位置文本，却派生出绑定标记、位置缓存、revision 变动和移动失效逻辑。删除 StorageContext.sourceIdentity/bindSourceIdentity、TE allocationLocation/loadedLocation 和替换流程的 location 参数；Java/CT 自定义分配使用本次来源下标及冻结资源数据。 |
| 样板分区 | 分区 ID 实际用于同槽隔离和催化剂关联，改由存储自身持有 UUID 并随 NBT 保存恢复，取消世界位置绑定。新的样板存储格式要求 PartitionId；未增加旧坐标格式迁移。 |
| 网络组件 | 三类组件加载时反扫 owner 组件表找 this，并保存第二份加载状态。工厂直接传入类型，删除反扫和 loadAttempted；只提供数据的组件取消生命周期监听。 |
| 数据库及库存适配 | Capture 复用唯一 PhysicalCapture；删除重复玩家 UUID、线程和不可变注册事实。磁盘导入取消两遍相同模拟，物品复制取消前后同内容的重复校验。 |
| 投影 | 删除每 tick 的 round 令牌和每格七份状态复验；只读观测直接使用本 tick 捕获的模型。跨帧模型变化、断连和异步资源代际继续由原有状态承担。 |
| 窗口 | 删除资源窗口的全 JVM 同步身份分配器，页码版本局限于当前容器；原生包的 windowId 负责选中窗口。 |
| JEI | wrapper 构造不再为每个配方遍历整个池验证归属，撤掉额外 O(n²) 初始化扫描。实际工厂返回与注册边界仍验证一次。 |
| 定义发布 | 结构 Publication 直接保有对应 catalog，删除服务器会话的 candidateCatalog/publishedCatalog/publishedView 镜像。切换发生在安装回调结束后、广播前。配方请求只在入队检查固定会话 ID，后续使用原 Publication 的活性与版本。 |
| 自定义分配 | 总可用量只需达到本次需求。删除对无须表示的全仓总和做溢出拒绝的行为；两仓各有 Long.MAX_VALUE 时仍可正常从指定来源扣 1。 |

此外清理了原生生命周期已保证的自位置查询、重复能力/注册表查询、已知泛型载荷的二次类型试探、复制后相同校验，以及捕获内部异常后回退为空值或伪造正常结果的分支。

## 保留依据

- 事务的实际来源以 StorageContext 对象、快照版本、预留和锁为准；诊断标签从未承担这项职责。
- 跨线程、跨 tick、owner FIFO 和资源重载的迟到结果确实可能过期；在实际回到目标对象的边界核对，不在同一同步调用栈反复自验。
- 外部 NBT、网络载荷、可变 capability、用户自定义事务及库存写入具有真实边界；必要的格式检查、权限、溢出、共同提交和补偿保留。
- 游戏原生创建、加载、tick 分派以及只读回调的约定由正常调用链承担；删除只为模拟违约重入而制造的协议和对应测试。

## 审查与验证进度

上一轮记录是中间检查点；以下为用户再次修改代码后要求的完整复审，最终验证以本节与实施进度为准。未启动 Minecraft。

## 2026-11-13 再次复审与统一格式化

本轮从 `57efff1` 的当前工作树重新审查；基线、四分片报告及最终原始证据在 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261113/clean-spoless-review/`。用户明确授权统一提交当前未提交类，提交日志为 `Clean and Spoless code`。

| 位置 | 确认的问题及最终处理 |
|---|---|
| BlockPos2ObjectMap | 普通 Map 构造器漏复制，实际返回空表；补齐 putAll。共享空表、原生 fastIterator 替代包装与逐条 entry 分配。只读发布使用契约。 |
| 结构匹配、构建、预览 | 直接从 packed 坐标计算最终位置，旋转/平移合并中间对象；预览边界使用六个整数累积，结束时才创建端点。结构图的完成列表/显示表直接移交。 |
| 两套预算归并排序 | 固定复用两个缓冲区，删除每轮新建或逐元素清空缓冲的额外遍历；保留稳定顺序、预算续办及算术溢出检查。 |
| 输入隔离组与 ME 样板 | 隔离组从每组重扫所有分区改为准确索引/共享前缀区间分发；样板详情身份与定义索引随解码结果一次发布。普通模式不再计算无用比例签名。 |
| 构建器 GUI | 删除每帧三个 NBT 子树的 toString 与切片映射重建；使用完成同步状态的只读子树和选择状态比较。路径验证改为单遍解析，避免重复路径对象及中间计数表。 |
| 研究、网络与组件 | 删除十个私有数量 Map 实现、两个 FrozenDependencies 和重复快照；工作量守恒直接按键检查，不构造 expected 整表。活动黑名单继续在实际变化处捕获一次版本快照。 |
| 普通组件 NBT | 相等赋值不复制；merge 只复制变化的新值并一次发布，旧私有节点复用。活动存储的快照捕获一次，下游只读借用。 |
| 渲染缓冲 | 已完成 pass Map 直接转交；VBO 缓存命中先返回，避免 ByteBuffer 视图分配；透明单项直接绘制，不先构造两个临时列表。实际 reader/lease 与资源回收边界保留。 |

测试不以“不许修改借用集合”为目标。删除剩余防写断言与违约输入；Map 原测试改为真实漏复制回归，输入分组增加一项共享前缀/次序/实际扣料回归。其余沿现有结算、恢复、并发和交互测试。

IDEA MCP 已对全部 1105 份 Java 执行 `reformat_file`，包括 801 main、280 test、21 addon、2 文档示例和 1 独立验证源码；五批均返回成功。完整逐文件 `errorsOnly:false`、构建和最终计数见 [实施进度](implementation-status.md)。格式化后仓库 Spotless 的 12 文件换行符差异已统一，未改 IDE 设置。

## 2026-11-14 防御检查、复制与循环清理

本轮复查 b72090aacf2896c25c82c1b15135dcf0f95df30e 之后新增及修改的 Java，并扩展检查当前生产源码的数组 clone 和 List 循环。删除 StrictNbt、DownloadValidation、字段白名单、重复 NBT 类型检查、构造器中的数据复验、保存回调前后装配图比较，以及对应的防写和违约输入测试。

默认 ResourceKeyNbt、AttributeModifierNbt 和 MachineAttributesNbt 复用单例。RecipeOverlayNbt 改为静态编解码，绑定表在构建时生成一次；固定需求图通过 RecipeProcess.definitionIndex() 按需建立并复用，删除重复遍历、临时编码器和重复路径解析。研究与网络数据、下载输出、输入锁和控制器待加载 NBT 按只读约定直接交付。库内 Optional 改为 nullable 对象或原始类型无效值，奖励时间用 long 的 -1 表示尚未发生；依赖库要求的类型保留。

生产源码中 969 个 List 增强 for 已改为索引访问。API 明确要求 List 参数及返回值，包括嵌套列表，支持常数时间索引访问；调用方提供 ArrayList、ObjectArrayList、List.of 或符合要求的自定义实现。计算组件在一次遍历中汇总供给、消耗、并发和 FE，删除单独的回调列表；研究站参与者判断按既有排序线性匹配，数据库并发拒绝只扫描一次设备列表。手动会话列表在实际变化后重建，ZS 事件包装对象和资源表包装对象按事件复用。

Snapshot、手动材料凭据、发布数据、网络分片及缓存 getter 中的数组 clone 已移除。生产源码仅保留 ControllerRotatedModel 和 PreviewBlockVertices 的两处 clone，用于生成会修改坐标、法线、颜色或光照的模型顶点。网络分片、重组、流体属性数组合并和统计排序仍保留实际数据转换需要的复制；分片构造器与 getter 不再二次复制。ResourceTransferRun.Snapshot 直接借用数组，恢复交接后原运行实例必须停止执行。

最终完整 Gradle build、Spotless 和 IDEA MCP 构建通过；1735 项测试、269 suites，零失败、错误和跳过。451 份改动 Java 已通过本机 IDEA 格式化及 errorsOnly:false 检查，449 份全级零诊断；HatchCasingModels 保留既有 CTM 依赖解析造成的 9 条 ERROR，1 份 run 文件被 IDE 排除。未将这些文件记录为全级零。27 份 Java addon 编译和 15 份真实 ZS 脚本编译、核心 API 行为检查通过。run addon 生成成功，本轮未安装。

最近两个中文提交信息已重写为英文，文件树未改变；新增代码注释已扫描，未发现中文注释。证据目录为 C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261114/，最终文件包括 audit-verified-build.log、audit-idea-verified-full.json、audit-idea-verified-summary.json、audit-run-verified.log、audit-zs-verified/、audit-verified-test-counts.json、audit-added-comments-verified.json 与 list-loop-final.log。阶段本地提交，保留用户 AGENTS.md 修改。

## 2026-11-15 全项目复审

从 `6300da1` 的当前工作树复审851份生产 Java，逐模块检查正常调用规则、真实状态变化与对象使用范围。当前生产源码844份，完整结果及文件哈希见 `C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261115/review-summary.json` 和 `review-current-source-manifest.json`。

| 范围 | 最终处理 |
|---|---|
| 参数与加载 | 删除由签名、工厂、原生生命周期和已知载荷保证的重复非空断言，以及 TE 自位置和自身身份复验。真实缺失、权限、版本、溢出和事务预检继续由对应边界处理。 |
| 普通数据与组件 | ComponentData 借用不可再修改的子标签，快照只建立独立顶层索引并按 revision 缓存。数据组件替换、机器代际或 roster变化才重建显示；排序、参与者、装配与定义索引复用既有结果。 |
| 配方与仓室 | 删除重复 codec/索引、DTO 防复制、同一阶段的重复检查、无用参数与空循环。工作槽恢复直接读取受控内部槽，删除临时快照和验证用 WorkSlot；资源键直接从已有 NBT 解码。 |
| 网络与研究 | 合并设备、奖励、成员和资源汇总的重复遍历；资源/奖励/队列快照只读交付。网络来源仅保留实际后端对象和版本，后端替换使旧发布失效。 |
| 渲染与结构 | 预览按原数组及 offset写最终缓冲，删除 clone和逐quad数组拆分；结构路径原地反转，每个结构只生成一次并供切片共用。VBO清理先保存 fastutil entry的值再移除，避免读取已经失效的entry。 |
| Mixin与测试 | 删除5个可替换Mixin、对应无用接口和辅助类；公开API及2条AT替代方案见[mixins.md](mixins.md)。移除只要求防写、违约输入或重复验证的测试，保留实际付款、恢复、来源替换、渲染和资源释放行为。 |

生产源码仅剩 `ControllerRotatedModel` 在烘焙时复制共享基础模型顶点的一处 clone，24种朝向直接读烘焙缓存。网络分片、重组和原生数组合并仍需生成其实际数据表示；异步任务、资源重载、事务预留和来源替换仍有真实版本边界。

完整Gradle build、Spotless、1728项测试/269 suites、IDEA MCP构建、27份Java addon和15份真实ZS验证通过。最后删除无消费者AT后重新编译和打包通过，未重复执行已通过的行为测试。520份Java格式化及全级检查中，503份零诊断，CTM文件保留5条IDE依赖ERROR及1条弱警告，16份run被排除。详细限制、原始证据和复验入口见[实施进度](implementation-status.md)。

## 2026-11-15 API 与调用点复核

预算三态、FE patch、属性基础值和联网覆盖移除可空原始类型包装，分别使用共享 BudgetResult、long/int 的 -1 和 double 的 NaN。ResourceBatch 删除逐次 Step 分配，资源拒绝结果共享常量，ReservationResult 不再复验调用方状态配对。属性查找只遍历一次父链，单层直接读取已有 Map，父子 modifier 覆盖才建立工作表；固定 operation 顺序仍按原规则执行。

模型缓存命中不再建立资源列表、空 owner Map 或源捕获 Map，sameBase 直接比较字段。动画层不再构造 Snapshot 后立即取回原列表，二维层和三维模式复用已有配置。研究注册验证与前置图收集合并，奖励资格避免整表 Map→List 转换，无变化状态复用原 Snapshot。世界保存前后整表比较及对应违约 writer 测试已删除，保存失败仍先重试原快照。

保留的研究副本用于注册顺序与 ID 排序、可逆事务 before/after 和历史保存版本，不能原地覆盖这些正在使用的数据。生产数组 clone 仍只有烘焙时的一处，用于旋转共享基础模型顶点并建立24种朝向缓存；运行时直接读取缓存。借用动画层、NBT和列表的期限已同步到 Javadoc 与 API 文档。

最终完整构建、1727项现有测试和实际 Java/ZS SDK 示例通过，没有新增测试。59份改动Java经本机IDEA格式化和全级检查，58份零诊断，1份文档示例被IDE排除并单独编译。证据与文档入口见[实施进度](implementation-status.md)和[API](../api.md)。
