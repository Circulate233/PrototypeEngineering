# 结构定义、快照与匹配

已迁入 PM 模板/切片的 Java 定义、24 向旋转、声明读取、脱离世界的事实快照和独立匹配结果，并接入宿主发布复核及公开仓室发现。源码依据和未迁完内容见[结构审计](structure-migration-audit.md)。没有注册正式机器、配方或控制器；实际控制器 TileEntity 和真实调度器仍待接线。

## 朝向与定义

直接复用 AE2S `BlockOrientation` 的 24 种朝向。`StructureTransform` 只缓存 24 个整数有符号置换，初始化时从 AE2 得到基向量，热路径不做矩阵/浮点旋转；支持基准变换、任意 from→to、坐标/方向与逆变换。坐标运算检查溢出，不允许绕回到另一世界位置。

PM 默认 NORTH/UP 绕 UP 到 EAST 的约定已验证。AE2S `rotateClockwiseAround(UP)` 实际把 NORTH 变为 WEST，因此没有拿这个方法直接替代 PM 旋转；目标朝向通过 AE2 的前/上方向表示。

`MachineStructure` 保留外部形状的真实扩展职责：转换、声明潜在读取、产生完整匹配结果。`TemplateStructure`、`SliceStructure` 是实际实现类，没有接口/Impl 镜像；定义保持对象身份，不引入递归结构相等。`StructurePattern` 冻结相对位置和谓词，模板/切片冻结偏移、验证器与子结构；名字/JSON/预览注册仍待专门接入。

`StructurePlan` 将定义从它的基础朝向转换到请求朝向，保留一个固定控制器坐标并收集读取。每次 `match` 返回独立 `StructureMatch` 树或 null，前一次成功不会污染下一次失败。结果保存每个 occurrence 的重复数、实际本层方块位置及子树；位置收集按公开结果进行，不按具体模板/切片类判断，也不按 ID 扁平覆盖计数。

## 模板、切片与修正

| 情形 | 当前行为 |
|---|---|
| 模板 | `anchor + offset` 后按 pattern、validators、children 顺序匹配；全部成功才发布树。 |
| 切片 | 贪心匹配连续切片，首次普通不匹配停止；实际试探的切片未加载则整体失败，即使已满足 min。到 max 后不多读一片。 |
| 切片验证器/子结构 | 验证器只在基础 origin 执行一次，子结构只在最后匹配切片执行一次；子结构失败不回退到更短切片。 |
| 零片 | 保留旧 matcher/preview 的规则：children anchor 为 `base - sliceOffset`。规划、匹配和结果收集统一此规则；max=0 不检查 pattern，仍执行验证器及子结构。 |
| 控制器排除 | 所有层只排除最初固定的实际 controller；子结构自己的 anchor 不再误当控制器。匹配与结果位置收集一致排除。 |
| 旋转 | orientation、offset、pattern、验证器相对读取及 children、sliceOffset 一同转换；修复旧验证器未旋转的问题。 |
| 包围盒 | 保留稀疏 pattern 整个 min/max 区域的加载检查，不能仅检查有方块的位置。空 pattern 的区域是本层 origin。 |

`minCount/maxCount` 严格校验非负与顺序，计数循环避免 int 末尾溢出。当前规划枚举潜在最大范围，尚未接入调度工作量/时间预算；这不是允许把巨大结构准备工作直接塞进每 tick 的承诺。

## 读取与线程

`StructureQuery<T>` 是显式事实描述符。reader 只能在服务端捕获，copier 必须在捕获和交付时隔离可变值；不可变值可 identity copy。第四个构造参数为显式等价比较器，不能默认外部值的 equals 足够。比较器收到非 null 的隔离副本，true 必须保证该描述符的所有消费者无法区分两值，false 可以保守触发重新匹配；null/缺失由框架单独比较。计划通过 descriptor 对象身份保持类型，并拒绝同 ID 的不同 descriptor。查询不能把 World、TileEntity、活动 handler 或执行许可当作事实返回。

`StructureReadPlan` 冻结全部潜在 area/query-position 读取，按声明顺序去重。模板与切片提前声明潜在分支，验证器也必须声明 pattern 之外的读取。潜在范围不是额外的成型条件：某条未使用远端分支未加载，不会提前否决较短的有效结构。

`WorldStructureSource` 复用现有 `MachineLifecycleHost` 校验服务端线程，以 `isAreaLoaded(..., false)` 检查区域；不强加载。`StructureSnapshot.capture` 同步完成读取后不再保留 source。工作线程只消费快照，不能临时回到 world 补未知读取。NBT 在捕获及每次交付时深拷贝。

快照区分三种情况：已捕获的缺失值（如无 TE 的 null）、未加载区域、捕获异常。普通未加载的所需谓词为不匹配；直接读取未声明/不可用位置是调用错误。潜在分支上的 RuntimeException 保留为诊断事实，若实际访问该事实则抛出，绝不转换为空气/null/false；未访问的分支不因此改变结果。JVM Error 立即中断捕获。该错误处理不表示调用者可忽略 `failures()` 诊断或继续使用已失败的真实运行宿主。

快照只解决读取线程和对象隔离，不授予后续发布权限。以下发布入口复核世界事实、配置代与实际仓室；真实调度 join、PN owner/epoch 到配置代的接线仍待完成。外部 reader 和能力发现必须遵守观察性读取契约，不得修改世界、配置或递归调用刷新入口。

## 谓词与内置事实

`StructurePredicate` 同时承担单格需求或相对位置验证器的读取声明与纯匹配。方向无关谓词可复用默认 transform；方向相关的外部实现必须同时转换声明与测试。`StructurePredicates` 提供查询条件、AND/OR、相对 offset、非空气、声明 TE、精确 state、预先展开的方块集合以及浅层 NBT。

- AND/OR 保留声明顺序与短路；alternatives 可以分别组合 state/NBT，不再把含 NBT 的候选退化成首项。
- 精确 state 仍按 canonical IBlockState 身份比较；方向/Axis 变换复用同一个实现，state+NBT 与普通 state 一致。仍保留旧代码对 property 不接受目标值时维持该 property 的行为，完整块状态旋转策略以后续实机核对为准。
- NBT 保留 PM root 浅层规则：String 比实际字符串值，其他 tag 比 SNBT；缺字段/无 TE 不匹配。`stateWithNbt` 的空约束不额外要求 TE，独立 NBT 谓词要求存在 TE。
- 正则由未来加载器在定义阶段展开，匹配阶段使用冻结方块集合；当前未接 JSON 正则加载。

`StructureQueries` 提供 block state、PM 双条件非空气、block-state 声明 TE、TE NBT、dimension、day-cycle worldTime、rain/thunder、biome ID，以及显式 Forge capability 的存在性查询。通用 capability 查询只检查 hasCapability；资源/能源输入/能源输出的专用查询使用 HatchCapabilities.find，与实际仓室发现共用 getCapability/isValid 规则。全部只捕获 Boolean，不把 handler 交给 worker。静态初始化只建立描述符，注册表/世界访问留在实际 reader 内。

## 结果复核与仓室发布

`StructurePlan.evaluate` 在共享不可变事实之上创建本次独立读取追踪，返回 `StructureEvaluation`。证明包含真正访问的 area/query-position、贪心切片的首个失败探测、验证器额外位置，以及短路前确实访问过的分支；不只检查成功的 allBlocks，也不把全部潜在最大范围当作必要条件。外部结构必须只根据输入快照进行确定性匹配；无读取声明的外部可变状态不属于证明。

`StructureEvaluation.refresh` 在服务端先捕获实际使用的事实。证明仍成立时直接使用 worker 结果；有变化时重新捕获完整计划并重新匹配，以新结果为准。WORLD_TIME 仍精确比较，不能使用“都满足某个时间条件”冒充通用等价；时间改变但条件仍真可以重算后正常发布。旧访问位置现在故障，也先允许新分支短路跳过它；只有新匹配仍访问的故障才抛出。重算回退发生在主线程，未来调度预算必须覆盖该成本，不能声称所有匹配成本都已移到 worker。

`StructureRefresh` 属于一台机器的生命周期，流程如下：

1. 服务端 `prepare` 获取稳定的 `StructureConfiguration` 身份，建立计划并捕获快照；每次新请求使旧请求永久失去发布权。`Request.evaluate` 只保存配置、计划和快照，可在 worker 运行，不持有 host、World、TileEntity 或活动仓室。
2. 调度器 join 后，服务端 `publish` 在现有 `MachineLifecycle` TRANSITION gate 内核对当前请求、配置身份和加载状态。配置对象每次方向/定义/owner 权限变化都重新创建，包括 A→B→A；不能把旧保存身份或旧请求恢复成执行许可。
3. 复核实际事实，按最终树的 allBlocks 排序发现仓室；三个公开职责分别收集、同一契约对象去重，不依赖内置 TileEntity 类。复核事实后再次查当前 provider/能力身份和 isValid，缺失能力也参与比对。
4. 在同一 gate 内再次确认请求适用，再一起替换匹配树和 MachineHatches，最后才触发实际 formed 真值变化。每次成功都重建绑定；同朝向、仍成型但长度或 provider 变化，也更新绑定而不重复成型事件。
5. fresh match 确认失配才接受 `StructureAssembly.UNFORMED`，清绑定并暂停配方，保留工位。发现期间候选失稳则返回 false，不发送误导的失效事件，当前请求可重试；调度器必须在该发布边界完成重试，或显式 invalidate 后暂停，不能释放执行权继续使用未复核候选。旧结果返回 false，不改新请求和当前绑定。

刷新入口拒绝服务端回调重入，避免外层捕获覆盖内层新请求/撤销。当前读取或发现异常会故障机器且原异常抛出；若异常发生时该配置已撤销，原异常仍报告，但不能故障替代配置。worker 异常由任务所有者报告并通过 `fail(request, cause)` 返回服务端，仅当前请求能故障生命周期；过期异常不能变成普通失配。`Request.failures()` 与协调器 `diagnostics()` 提供初次及重算快照中未访问分支的诊断，调用者须报告这些信息。

`ControllerLifecycleHost` 同时实现公开的 `MachineStructureHost`，固定绑定 World，在结构入口检查确切 controller 的 World、位置、invalid、区域已加载及当前位置 TE 身份。清理的线程校验与活控制器检查分开，控制器已移除也能执行 broken/unload。configure/revoke 的调用方必须先 join 并 `StructureRefresh.invalidate` 旧绑定。默认发现使用 unsided 能力；专用外部宿主可明确定义侧面，不扫描六面后任意挑代理。

仓室本身仍是活动存储契约。替换/卸载 provider 必须使旧 StorageContext 失效；结构证明不能代替余额锁、预留或配方提交时的有效性检查。主线程串行和观察性回调是上述复核的前提，不能支持任意第三方在最后一次检查中继续修改世界。真实控制器配置变更、PN 权限轮换和调度停止/重试仍须落实到后续实现。

## 验证与后续

测试涵盖 24 向及任意朝向间变换、整数边界、快照隔离与潜在分支、重复读取去重、实际模板/切片树、旋转验证器、未加载探测、零片及同 ID 多 occurrence。验证不创建世界或启动 Bootstrap/FML/游戏；真实 WorldStructureSource、方块状态与 Forge capability 行为仍缺实机证据。当前完整测试和逐类 MCP 结果记录在[实施进度](implementation-status.md)。

新增测试覆盖真实 worker 消费快照、读取证明/时间刷新、切片增长、同方向换仓、过期成功/失败/失配、配置 ABA、卸载、发现失稳重试、故障隔离、跨线程/执行重叠及重入。逻辑 fixture 实现公开宿主接口，没有伪造 Forge 注册环境。

下一步仍须接机器类型/控制器 TileEntity 的实际生命周期调用，再接严格两阶段 JSON 加载/注册发布、预览与 BOM/display override、模型隐藏/渲染、实际并发调度及工作预算。当前宿主发布契约不代表这些能力已完成，也没有缩减其迁移范围。
