# 集合复制与流调用审查

## 2026-11-13 契约优先

用户明确要求：热路径不为调用方可能违反不可变/生命周期契约而复制集合。Javadoc 声明输入和返回值的只读期限，生产者交接后停止修改；实际从并发变化状态采集快照、排序或维护真实交易账本时才创建所需数据。此前为“安全复用”自造的 FrozenReservations、CanonicalList、FrozenChoices、EntryView 和 builder 关闭协议已删除，不能将这些历史实现继续作为必须保留项。结构读计划/匹配/仓室集合不再额外遍历复制，StructureQuery 在捕获时复制一次，谓词读取和等价比较直接只读消费。具体变更及测试清理见 [本轮审查](defensive-programming-audit.md)。下列较早记录中的副本数量、只读包装和不可变 singleton 描述仅表示当时实现。

## 2026-11-12 规则与范围

回调的字段、参数和调用点必须直接使用原始类型签名；对象加int的消费者使用fastutil的`ObjectIntBiConsumer<T>`，禁止声明为`BiConsumer<T, Integer>`再在执行时拆箱，也不保留装箱兼容重载。JDK已有的`BooleanSupplier`、`ToIntFunction`等原始类型接口可以直接使用。没有对应接口的双参数返回值回调应声明具名函数接口，例如文本换行的`TextWrapper.apply(Text, int)`，禁止为此建立临时Pair或捕获另一个函数；否则会引入装箱、拆箱或额外对象分配。

本次编译器AST检查覆盖1078个main/test Java文件，材料单格/整类选择和构建树分段回调已改为`ObjectIntBiConsumer`，JEI换行宽度改为原始int，结构快照测试夹具改为`BooleanSupplier`。结构任务的`Supplier<Boolean>`保留：其同一action对象交给`PlannedTaskExecutor.Job<Boolean>`，结果按`Result<Boolean>`区分成功、失败及取消；布尔值使用单例。禁止仅为改变字段类型再按任务创建适配lambda，这会增加实际分配。剩余嵌套`Result<Boolean>`属于任务结果对象，纯对象回调继续使用JDK接口。

必须直接比较 ResourceLocation、UUID 等已有自然顺序的标识，禁止为了排序在比较器里反复调用 toString()；依赖排序结果的清单校验与预算排序必须使用相同规则。带完整路径编码要求的 RequirementPath 和规范化文件路径单独保留其顺序。

集合初始化必须优先使用已知的来源数量、解析后长度、固定槽数或实际参与者数量。逐项累计的配方输入、输出和资源数量表通常以6作为起始容量，超过后正常扩容；禁止把6作为材料数量限制。可选列表可能为空时保留延迟分配，禁止为了估算容量提前读取不存在的JSON字段、未接受的队伍信息，或重复构建来源列表。无可靠数量依据的长期注册表、缓存和特殊语义集合不机械套用同一容量。

正式库以高性能为目标：生产代码避免顺序Stream和由此产生的临时管线、捕获lambda及中间集合；并行流若承担实际并发调度，不按此规则机械改为同步。集合复制必须说明所有权、触发点和失效条件，稳定数据不能在常驻tick、扫描、查询或渲染中重复整表复制。非特殊语义仍采用fastutil，AEKey数量继续使用AEKey2LongMap及必要的addTo/溢出预检。

本轮扫描全部746个正式Java文件，使用JDK编译器AST定位集合构造、copyOf/copy/clone、addAll/putAll、toArray/arraycopy及流入口，初始1000项候选涉及271个文件。候选包含容量预分配、只读包装和NBT/ItemStack复制，不等于1000次集合复制。完整前后台账、调用宿主和表达式保存在证据目录的`ast-before.tsv`、`ast-after.tsv`、`copy-ledger.tsv`。测试断言和忽略目录的示例命令补全不作为发布库热路径；示例的命令补全仍有一处冷路径Arrays.stream，未改动示例包。

正式源码64个stream入口和21个toList调用已全部移除，Files.walk的串行管线改为文件访问器并保留稳定排序；原正式源码没有parallelStream。本轮保留原调度器、source lanes、owner/worker交接、取消和实际退出协议，没有把并发工作改成同步。最终显式copyOf候选由112降为87；这些是静态调用点数量，不能换算为实测分配量或耗时改善。

## 已消除或移出稳定热路径的复制

| 路径 | 当前处理与失效点 |
|---|---|
| ComponentMap装配/有序绑定 | 按底层图revision缓存不可变结果，直接迭代原不可变节点，不再每次查询复制节点列表和依赖集合；图变更仍重新校验/发布，缓存有序绑定仍逐项扣访问预算，预算不足不发布部分结果。 |
| MachineInstance宿主/扫描器 | 宿主在注册时发布新的不可变顺序快照，扫描器在登记变化后重建一次。PolicyWork直接保留宿主快照，去掉每tick复制；旧快照不随随后登记变化。 |
| SlottedResourceBuffer库存 | 在共享context锁下按有效revision复用全量或最近单key快照；变更后由ImmutableResourceAmounts.Builder直接移交所有权，不再先复制到临时map再复制到只读map。单key快照使用不可变singleton，不建哈希表。未关闭native journal和失效context不能命中旧缓存。 |
| NetworkResourceModifier | 仅复用库私有最终类型的已校验非负资源向量；任意外部map仍复制、检查身份/数量并封闭entry.setValue。modifier的负flat值不能被误当作合法供应向量；apply不重复复制已冻结的base。 |
| 网络组件库存/研究站来源 | 私有局部builder交给其私有捕获对象，不二次复制context、provider或站点表；来源current/revision/permit仍逐项复验。映射站点hatches直接汇聚，去掉stream临时列表。 |
| TickGroup与内置资源/FE事务 | 私有batch builder和本次获取完成的token列表按原所有权合同移交，使用已有frozen入口；不缩减准确账本、终态或补偿责任。 |
| ResourceHatchWindow | side快照按存储revision及firstSlot复用；分页或余额/容量变化后才重建。公共Side构造仍冻结任意外部输入。 |
| ResearchRegistry与GUI | 定义ID排序在注册freeze完成，研究/数据库GUI复用该顺序，保留all()原登记顺序；页切片直接交最终构造器冻结一次，不先复制切片再复制一次。 |
| ResearchWorld | 团队成员快照按团队表变更失效；blacklist按真实增删失效；已相等的knownMembers不再先复制再比较。每tick新事实仍按原边界捕获。 |
| 结构重载/客户端渲染注册表 | 缓存已分离的LoadedIdentity、Owner、Listener、tile和hide lease成员快照，只在增删/替换时失效。保留回调重入期间旧遍历的身份隔离，不持有fastutil会随表位移动的活entry。 |
| 模型绘制/Bloom/延迟打开 | 队列通过所有权交换处理，绘制队列复用备用列表，清理后才归还；Bloom retained只包私有已完成列表。失败回调仍捕获准确state/view，只在成员或view身份变化后重建。 |
| 模型/预览查询 | frustum只检查是否存在绑定，不构造完整锚点图；Gecko估算缓存命中不复制两个骨骼集合，只有缓存未命中才冻结key。投影先筛可见样本再排序，不复制整份样本后再建筛选表；不透明绘制直接分桶，只有透明通道保留独立排序列表。 |
| 嵌入式预览输入 | 全部仍有效的client tick不复制active表；实际过期/关闭前才冻结未遍历后缀，保留关闭回调的重入隔离。 |
| 私有展示结果 | 材料行、说明段落、锚点、规范坐标和正则结果的已完成局部builder用只读所有权视图返回，去掉不必要的第二次列表复制。 |

## 必须保留的复制与运行期边界

不能把“所有剩余复制都不在热路径”当作已经成立的结论。以下复制有明确隔离或计算职责，其中部分会在实际工作、变化的快照或渲染排序中发生；它们不是对稳定表的无条件重复复制。

| 保留项 | 必要性及触发条件 |
|---|---|
| 公共构造器、外部集合/回调结果 | 调用方可继续修改原列表/map/数组。尤其InputAllocationPlan需跨预算yield保留自定义allocate结果；RecipeOverlayPipeline/同步probe需在外部回调前固定指令顺序。不能直接替换成原集合的unmodifiable活视图。 |
| 准入/事务/continuation清单 | 必须保留原token、业务和阶段的准确顺序及所有权，直到实际退出/补偿确认。ResourceBatch续段输入需要同一稳定身份跨yield；不为少一次复制而借用随后继续append的账本切片。 |
| 新一tick网络/研究工作事实 | 新许可、online/membership、资源量与来源revision不是静态注册表。跨阶段或worker消费前仍需捕获这一轮的独立事实；旧代状态不能替代新的授权。 |
| 有不同顺序/内容的结果 | 透明绘制距离排序、当前奖励排序、材料合计/筛选和有界GUI行是新结果。保留原输入顺序的同时生成实际需要的结果；已去掉固定定义反复排序和可见筛选前的整表副本。 |
| 原生mutable payload/可写数组 | ItemStack/NBT/顶点、网络分片、存档及其对外访问器仍需分离可变数据；不能把原世界对象、客户端数组或正在回收的GPU/buffer视图直接交给另一所有者。很多触发于声明、导入/导出、保存/恢复或资源epoch变化。 |
| 关闭/故障/失效通知 | ReusableModelVboUploader.retire、资源关闭和Bloom失败回调可能移除或替换被遍历的表；必要快照保留旧身份，防重入误撤销新对象。正常渲染/tick中的稳定成员表已缓存，异常路径仍优先准确清理。 |

后续新增复制须按同样方式审查：先确定是否为不同内容/排序的新结果，或必要的调用方/异步隔离；能把构造放到注册、图/存储revision或资源epoch变更处的，不放在稳定读取中。真实改变的数据只冻结一次，库内可信最终不可变类型可复用，任意外部只读包装不视为所有权证明。

## 验证与证据

沿用现有真实行为测试，没有新增源码镜像、公式或getter测试。完整Spotless/build通过，1963tests/270suites失败、错误、跳过均为0；66个修改Java文件本机IDEA MCP逐文件errorsOnly:false最终错误、警告和弱警告为0，MCP build通过。缓存失效、别名数量、native journal、原生槽分页、图更新、事务/取消/多线程与GUI顺序由既有相应流程覆盖；未启动Minecraft或FML/Bootstrap。

证据目录：`C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261112/collection-allocation-audit/`。原依赖、BuilderStructureTree的Row背景、双语rate背景、原暂存JEI测试和INDEX.md~保持。工作字节、候选提交、原暂存/背景patch及本地提交见同目录记录。
