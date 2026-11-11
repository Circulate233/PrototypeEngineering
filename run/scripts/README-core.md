# 核心 CT 场景

本目录是实际加载的脚本，依赖当前 PE 与 `pe_visual_examples` 开发 addon 中的 `mods.pe_run_tests.CoreCases` 和 `NativeChecks`。`10_pe_run_core_preinit.zs` 在 preinit 注册四种机器；`20_pe_run_core_checks.zs` 在普通 loader 注册配方、结构 predicate 与配置值。两者共用 `Bindings.scope("pe_run_tests:bindings")`，不使用跨 loader 全局变量。最后的 `95` 脚本统一执行一次 `CoreCases.registerAll()`，将所有场景池和历史定义交给同一 resolver。

完整重启由用户自行进行。仅 `/ct syntax` 不执行断言，也不能加载新的机器注册。启动日志中的 `PASS core_sdk_*` 只证明本次 SDK member、INDEX、IData、typed 数据与独立 snapshot；局部 `ComponentData` 每次启动重新创建，不证明机器保存。
SDK718把 `has` 作为保留字；删除/clear断言通过 run-only `CoreCases.dataContains(data, key)` 薄别名调用真实 `ComponentData.has`，没有改 PE 正式 API。

进入测试世界后执行 `/pe_run_tests registered`。该命令读取已注册的准确机器 assembly，运行 detached owner、真实扫描器、真实配方事务及 frozen structure API 检查。`NativeChecks.check` 按实际条件输出 `PASS <id>`；失败输出 `FAIL <id>` 并抛出异常。断言运行不修改世界方块、既有仓室、配置文件或存档。JSON 检查读取实际的 `scripts/fixtures/core_custom.json`，不会将它复制进用户结构配置目录。

| 检查 | 实际预期 |
|---|---|
| `core_sdk_member_index_snapshot` | 当前 count=7/flag=false；旧 snapshot count=2/flag=true/initial=1；默认值11 |
| `core_cross_loader_scope`、`core_frozen_matcher` | 同一 scope；preinit 捕获的 matcher 保持匹配，新同名 matcher 拒绝 |
| `core_stage_first_tick` | 100物料剩88、产物3；100FE输入剩88，输出FE=2 |
| `core_stage_end` | 第二 tick 完成，物料剩86、产物10，输入FE=81、输出FE=15 |
| `core_weighted_output`、`core_weighted_zero_weight`、`core_chance_zero_output` | 固定END99与随机产物累加：200%下99×2＋(2＋3)×2＝208；零权的第二资源类型99条目产出0；0%产物0 |
| `core_chance_attribute_output` | 50%概率乘真实 START attribute的2，变成100%并确定产出3 |
| `core_attributes_output`、`core_attribute_end_removal` | ADDITION、MULTIPLY_BASE、MULTIPLY_TOTAL真实产物6；END移除后有效并行1 |
| `core_partial_preserve_clear` | 外部 overlay 于 START 前清空初始输入/最终输出；保留每 tick字段：物料98、产物3、FE95/13 |
| `core_selective_preserve_clear` | selection 于 START commit 写入 patch，已支付10初始物料保持：物料88、产物3、FE95/13 |
| `core_custom_allocation` | 资源9+9按7+3支付，剩2+6；FE6+6从第二源付3，剩6+3；返回数组改写不污染 source facts |
| `core_custom_duplicate_preflight` | 同源重复份额被预验拒绝，9+9余额和可用量全部保持 |
| `core_single_source_reject_split`、`core_single_source_retry` | 9+9不能凑10；第一源补到10后原 process重试成功，余额0+9 |
| `core_fuzzy_payment`、`core_dynamic_payment` | 20物料支付10；dynamic 保留旧 matcher 闭包 |
| `core_combined_payment` | 固定5、fuzzy10和dynamic10共付25，余额75，产物2 |
| `core_void_output_capacity`、`core_excess_reject_output_capacity` | 目标产物10/FE10，容量仅1/2；void分别存1/2；非void拒绝且余额都0 |
| `core_checkpoint_once` | 第1 tick跨过检查点，完整子生命周期只产生2；第2 tick不重复 |
| `core_long_resource_payment` | 初始2147483649支付2147483648剩1；产物2147483650无int截断 |
| `core_external_java_requirement`、`core_external_exact_codec` | 真实 Java requirement写入10；精确 typed overlay写入15，改变其它同名句柄后原codec恢复仍15 |
| `core_two_owners_*`、`core_owner_scan_*` | 四种机器分别创建两个独立 owner/scanner；真实预算1的扫描跨多次推进后接纳准确 process |
| `core_parallelism_final_core_thread/cross`、`core_parallelism_payment_*` | 建议4经SAM选择5，实际支付5物料/10FE；固定选择机器选2并支付2物料 |
| `core_parallelism_end_output_*`、`core_restored_end_output_*` | 原与编码恢复后的准确 process运行完整10 tick，END分别产出5或固定2 |
| `core_parallelism_reject_unpaid` | 仅4物料不能支付最终5，拒绝接纳并保持4物料/100FE |
| `core_addon_factory_custom_pool` | 实际自定义 pool lookup和 addon owner 的 cooperative beginCreation被执行 |
| `core_detached_codec_restore_*` | 编码/恢复创建独立实例，原 factory类、准确配方及有效并行保持；此项不是世界退出/重进 |
| `core_json_resolution_no_callback`、`core_json_predicate_freeze` | JSON解析不执行 matcher；freeze后的新增注册被拒绝 |
| `core_custom_match/stale/changed/frozen_*` | EAST_UP旋转的nested base/controller、local offset与绝对坐标都按冻结facts匹配；source17→18撤销freshness，新snapshot不匹配，原snapshot仍匹配 |
| `core_hatch_independent_sides`、`core_hatch_multiple_types` | 混合材料仓两侧独立接受150/350；输出侧剩余槽按第二类型容量接受400，第一类型350保持，输入侧第二类型仍0 |
| `core_hatch_fe_transfer`、`core_hatch_fe_capacities` | FE两侧单次传输分别限100/200；累计容量分别限1000/2000 |

核心四种机器为 `pe_run_tests:core_thread`、`core_cross`、`core_addon`、`core_fixed`，21个准确配方在 `core_pool`，simple/fixed/custom池共享准确对象。它们的 detached 付款测试使用 run addon 注册的独立 AEKey 类型，避免静态引导 Minecraft。用户世界的可视原版物料、保存、同步、卸载、退出重进、网络及渲染场景由统一 run README 和原生命令说明提供，必须另行观察；本表通过不代替这些实机结果。

本次制作未启动 Minecraft。最终全部18 addon Java编译成功，9脚本经实际SDK718编译，97核心断言通过。本机MCP对18 addon Java及独立验证器共19文件逐个使用 `errorsOnly:false`，均返回排除目录/不在内容根，错误/警告/弱警告未知。扫描驱动须推进真实computation与source apply/resolve；准入只探测不付款，首tick才检查START扣款，随后编码恢复并完成剩余9tick。SAM在任何回退建议下都最终选择5，四物料负例不能回退到可付款4。详见 [最终覆盖记录](../COVERAGE.md)。

真实World自定义Query已有独立可执行机器与光照开关步骤，见 [README-world-query.md](README-world-query.md)。它没有复用受控 Source 的17来冒充世界采样，必须由用户实际执行。
