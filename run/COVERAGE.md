# run 覆盖与证据清单

2026-11-11 阶段收尾。统一操作入口：[README.md](README.md)。本次处理示例与脚本，总体 Goal 按用户要求保持暂停。

C = 实际 SDK718 编译；H = 独立 JVM 中执行真实核心 API；N = 用户原生游戏运行；V = 用户实际客户端画面/点击。C/H 通过不能升级为 N/V。准备数据、给物品、注册内容与 PRECONDITION 均不算行为 PASS。

## 最终验证

| 门 | 当前证据 |
|---|---|
| 当前主输出与类路径 | prepare-tests.ps1 成功；Gradle classes/真实命名 compile+runtime 导出成功13s，无游戏启动。 |
| addon | 18 Java 全部 javac/jar 成功；build-result.json 为 installed=true，目标 run/mods/pe_visual_examples-1.0.0-dev.jar。 |
| C | 全部9个实际脚本 compiler=passed；typed bracket 延后真实 Forge lookup，未解析原生注册物品。 |
| H | 实际执行10/20脚本，经 DefinitionRegistrations dispatch/freeze、准确池/codec注册和运行时resolve；97个PASS，0个FAIL，报告completed=true。 |
| IDEA | 18 addon Java及独立验证器共19文件逐个本机MCP errorsOnly:false；全部因outside content roots/excluded拒绝分析，错误/警告/弱警告均unknown。没有修改IDE设置。 |
| N/V | 全部待用户实机；没有自行启动Minecraft、服务端、FML或Bootstrap。 |

最终原始证据：C:/Users/a1896/.codex/work-recovery/PrototypeEngineering-20261111/run-coverage/ 下 closing-prepare.log、final-verification/script-verifier-result.json、同目录script-verifier.log和closing-idea.json。此前失败记录保留作诊断，不能混为最终结果。正式库此前1921 tests/1007Java是历史门，本次没有重跑完整主测试。

九脚本依次为 preinit 10、11、15、16，普通 loader 20、21、30、40、95。它们使用同一 Bindings.scope；95统一registerPools和历史定义，再发布完整v1及准确private v2目录。H只执行10/20及对应核心注册，不冒充其余脚本原生执行。

## 功能矩阵

| 范围 | 实际场景/断言与预期 | 证据状态/入口 |
|---|---|---|
| SDK普通data | member/INDEX/IData、typed长整数/浮点/布尔/字符串、merge/remove/clear、独立snapshot；当前7/false、旧2/true/default11。 | C/H通过；实际Mixin注入N待。scripts/README-core.md |
| scope与冻结闭包 | 跨loader同scope；旧matcher仍匹配，新同名matcher拒绝。 | C/H通过 |
| START/TICK/END | tick1物料88/产品3/FE88,2；tick2物料86/产品10/FE81,15。 | C/H通过 |
| weighted/chance/attribute | 固定END与200%权重累计208；零权0、零概率0；真实attribute将50%变100%产3；三operation产6、END移除。 | C/H通过；分布统计未声称覆盖 |
| selective/partial overlay | 准确字段清空/保留、选择第二候选、进程普通data；partial余额98、selective已付START后88，产品3/FE95,13。 | C/H通过 |
| CUSTOM/single/fuzzy/dynamic/combine | 9+9按7+3付款剩2+6；重复source预验拒绝；single不能凑9+9，补10后重试；组合支付25，余额75/产2。 | C/H通过 |
| void/容量/checkpoint/long | void只存容量1/2，非void拒绝；checkpoint只执行一次；2147483649付2147483648剩1，输出2147483650。 | C/H通过 |
| Java扩展/准确codec | 实际requirement写10、overlay写15；后续同名句柄不能污染旧codec。 | C/H通过 |
| 默认/cross/addon factory及pool | 每类型两独立owner/scanner；预算1跨多次推进，实际执行纯计算和source apply/resolve；custom lookup/beginCreation进入。 | C/H通过 |
| 最终并行和付款 | SAM最终固定5，fixed选择2；scan只探测、余额100/100不变；首tick真实扣5物料/10FE或2物料；仅4物料无法付5，完全拒绝。原/恢复进程剩余9tick准确END输出。 | C/H通过；不把admission当START提交 |
| 冻结facts/JSON | 真实fixture JSON，旋转nested base/controller/local/绝对坐标；17→18使current失效，新snapshot失配，旧snapshot仍匹配；worker回调及freeze拒绝。 | C/H通过 |
| 真实World Query | 独立core_world机器、光照开关、旋转、capture与worker验证。 | C通过，N待；scripts/README-world-query.md |
| 十阶三方向/资源FE | 60真实仓；Forge模拟不改余额；reserve→apply→rollback和token状态；独立两侧多类型与FE transfer100/200、容量1000/2000。 | 配置H通过；真实仓N待，NATIVE-CASES.md |
| autoIO/设置/重配置/掉落 | 实际邻居搬运后共同核账；FE0禁止/-1保留profile上限；拒绝缩容、成功改profile、退出重进；铁65/水1000实际掉落，重放库存0。 | N/V待；NATIVE-CASES.md |
| 外部独立过滤仓 | 独立AEBaseTile与物理capability发现；禁物/模拟/执行/预留/回滚；旧引用拆除或替换后INVALID_STORAGE，新context独立。 | Java编译通过，N待；NATIVE-CASES.md末节 |
| 四工位/cross/取消/恢复 | 400成功tick：煤1600、水8000、nugget1600、diamond10、FE6400；cross两轮；取消保持已提交费用和END0。 | N待；NATIVE-CASES.md |
| 普通data/末tick/保存/full-sync | native_data_fixed/cross安装MACHINE/PROCESS和codec；post消费pending nonce；checkpoint对照真实UUID/seed/recipe/计数，退出重进/卸载/失配补回；客户端准确generation同nonce。 | N/V待；不拿局部SDK对象当保存证据 |
| 五网络角色/FTB/预算 | 真实物理计算物品供给/拒绝、同机THREAD lease1、四工位各compute6付款与补偿、队伍切换与权限；device3并行完整FE1920、四工位7680。 | C通过，N待；NETWORK-RENDER-TESTS.md |
| 研究/手动/站点/权限/奖励 | prerequisite/mutex/去重，手动先付iron1恢复原session/entry，再补1；站60/120周期checkpoint、原site/codec、gold1/redstone120→nugget120/diamond1/FE256；非OP拒绝；奖励回执与真实物品。 | C通过，N待；NETWORK-RENDER-TESTS.md |
| 数据库/下载/真实编码 | paper→book、源研究扣级一次、PREPARED退出恢复原nonce；ResearchDiskEncodingEvent正式注册、实际requestItemDownload、tier/容量/源等级拒绝、准确native盘NBT与产物总量。 | C通过，N待；fixture写盘不冒充正式编码 |
| 渲染/锚点/动画 | 四pass、真实geo/texture/animation、component及whole/per-slice；准确origin/kind/slice/path；CSV/单名/typed优先、旋转offset和Gecko真实frame。 | C通过，N/V待；CPU采样不代替GPU/Bloom |
| 私有目录/JEI/旧配方跨版本 | 完整manifest和准确page/options/native row；已有minecraft.crafting只催化剂；运行中v1=2400t跨v2=40t保留旧codec/seed/data/END emerald1；通知失败仍已published。 | C通过，N/V待；NETWORK-RENDER-TESTS.md |
| 原13/106、GUI/两工具/169结构 | 长数量、分页、候选、真实终端样板；完整169材料不随层截断，玻璃109、单格108+1；三种绑定、候选滚动、30秒投影、Shift滚轮与朝向。 | 原声明保留、Java编译通过；N/V待，原visual-examples/README.md |

## 保留的验证边界

本阶段覆盖功能入口和固定预期，没有宣称所有分支与所有原生效果已通过。复杂IData的每一种嵌套形状尚无独立世界保存断言；多玩家手动研究接管/在线拒绝/奖励领取须另用真实身份操作；旧attachment的draw/hide最终接受、全部front/top与Bloom/GPU仍须画面证据。已有命令不代替这些实际观察。

每项记录本次时间、命令/坐标/actor、预期与实际、相关日志和截图。异步请求未收到回调、前提不足或未执行时维持待验证；失败保留原始日志。没有新的用户原生结果前，总体Goal保持暂停。
