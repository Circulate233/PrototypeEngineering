# 网络、研究、渲染及客户端实机脚本

本入口随 preinit 的 `15_pe_run_network_machines.zs`/`16_pe_run_render_machines.zs`、普通 loader 的 `30_pe_run_network.zs`/`40_pe_run_render.zs`、末尾 `95_pe_run_manifest.zs` 和 run addon 一起安装。机器类型必须在 preinit，资源/研究/配方/结构/渲染声明在普通 loader。脚本使用同一个 `Bindings.scope("pe_run_tests:bindings")`；末文件通过 `CoreCases.registerAll()` 调用真实 `registerPools`，再声明完整 v1 目录和准确 v2 客户端变体。旧示例保留。启动只声明内容，不建世界、不投料、不改存档。`prepare-tests.ps1` 准备；由用户执行 `start-tests.ps1` 启动。四通道视觉复验时按启动入口显式启用 Bloom；未启用时记录回退现象，不能报原生 Bloom 通过。

以下是**待用户执行的预期**，不是已经执行的游戏结果。服务器命令每项先比较真实返回值/状态/余额，成功才输出 `PE_RUN_TESTS PASS`；不满足会输出真实断言失败。异步请求须等待相应回调。客户端命令在客户端线程检查实际已接受的同步和页面；服务器读值不代替客户端证据。

## 1. 启动及原生目录

先完整重启，确认 CT 日志没有 30/40/95 的编译/执行错误，再进入创造测试世界执行：

```text
/pe_run_tests registered
/pe_run_tests net-registered
/pe_run_tests_client bindings
/pe_run_tests_client manifest v1
/pe_run_tests client-server-manifest 0
```

`net-registered` 检查实际 frozen registry 的1200/1300计算需求、先决/互斥与准确net_recipe的纯数值compute×2、研究一级门。研究站自身配置速率10，研究不定义单站限额。THREAD是每机器logical lease，不可添加到数值需求vector。客户端 `bindings` 核对 renderer 使用的同一个 registry、四 pass、machine/component/whole/per-slice 声明和资源包中的 geo/texture/animation；`manifest` 要求完整目录与私有客户端 snapshot 匹配、准确独立页面实例、全局 alternatives/显示概率和该页 expanded/隐藏概率设置。

## 2. 五种真实网络角色和物理计算物品

在已加载、可编辑的空地逐个建造（原点为控制器，面朝北，上朝上）：

```text
/pe_examples build pe_run_tests:net_center 0 80 0
/pe_examples build pe_run_tests:net_computer 6 80 0
/pe_examples build pe_run_tests:net_station 12 80 0
/pe_examples build pe_run_tests:net_database 18 80 0
/pe_examples build pe_run_tests:net_device 24 80 0
/give @p pe_run_tests:net_computer_card 1
/pe_examples charge 0 80 1 100000
/pe_examples charge 6 80 1 100000
/pe_examples charge 12 80 1 10000
/pe_examples charge 24 80 1 10000
```

中心使用上方铁块和后方FE仓；计算机的四级运算仓在X−1，即(5,80,0)，FE仓在Z+1。把一张net_computer_card手动装入运算仓，并通过外部流体输入持续供应至少1000mB水/t；每tick冷却配方耗水1000mB，卡需1000冷却与32FE才提供供给。其他控制器的输入仓在X+1，输出仓在X−1，FE输入在Z+1。全部由实际玩家放置/认领，FTB team使用实际已加入的队伍；没有自动创建队伍或伪造权限。成型并持续供水后分别执行：

`net_database`已改用默认 `DataCenterController`，等级1/容量16及原结构不变，存储和磁盘回执沿原codec，角色在重载后重新取得许可。Java示例 `pe_run_tests:java_data_center` 同为一级/16条，结构为控制器上方铁块；不要与本队原库同时放置来判断单库内容，因为同条件按持久UUID选中一个。两个默认数据库均无GUI，自动升级和以下研究下载/磁盘导入仍使用既有后台入口。

```text
/pe_run_tests net-status 0 80 0 NETWORK_CENTER ACTIVE_CENTER
/pe_run_tests net-status 6 80 0 COMPUTER ACTIVE_DEVICE
/pe_run_tests net-status 12 80 0 RESEARCH_STATION ACTIVE_DEVICE
/pe_run_tests net-status 18 80 0 DATA_CENTER ACTIVE_DATA_CENTER
/pe_run_tests net-status 24 80 0 DEVICE ACTIVE_DEVICE
/pe_run_tests net-supply 6 80 0 1000 8
```

输出须有实际当前permit、角色、state、供给。一张卡在费用全部满足时提供1000 net_compute/8 THREAD，内部冷却不进入队伍供给。取出卡或停水并耗尽仓内水后执行 `net-supply 6 80 0 0 0`，恢复卡/供水后检查1000/8。中心提供4并发，计算机和活动数据库各占1，研究站推进时占1；先加载普通机器后再形成计算机/数据库，基础设备应优先取得额度。退出重进应保持同条件设备的UUID选择，不随加载顺序改变。卸下中心、恢复结构、改变真实FTB队伍后再运行原生角色检查。负面情况使用 `net-denied <controller坐标> <UNFORMED/NO_ACTIVE_CENTER/OWNER_TEAM_REJECTED/CENTER_LEVEL_INSUFFICIENT/SAME_TYPE_CONFLICT/INSUFFICIENT_THREADS>`，真实state必须吻合且网络work与permit均拒绝；`net-status`仅用于当前实际ACTIVE角色。

## 3. 手动、站点、队列、先决和互斥

先给测试材料，把kit所有iron存入测试箱，保持玩家库存没有iron，以观察入队/去重。之后仅从箱中取一枚iron，观察部分提交；退出重进后再取一枚完成，不能把kit四枚全部留在玩家库存。

```text
/pe_run_tests research-kit
/pe_run_tests research-start research_station 1 NOT_STARTABLE
/pe_run_tests research-start research_manual 1 STARTED
/pe_run_tests research-start research_manual 1 DUPLICATE_TARGET
/pe_run_tests research-queue 1
```

手动入口自动按实际玩家库存逐项扣料。先只供 iron×1 时执行 `research-manual-paid 1`，再 `research-manual-checkpoint` 保存实际session UUID、queue entry和paid数量到已读回的原生player文件。退出世界再进执行 `research-manual-resume` 和 `research-manual-paid 1`，必须准确原session/entry且仍已支付1；补另一枚后应完成。完成后：

```text
/pe_run_tests research-level research_manual 1 0
/pe_run_tests research-queue 0
/pe_run_tests research-start research_mutex 1 NOT_STARTABLE
/pe_run_tests research-start research_station 1 STARTED
/pe_run_tests research-start research_station 1 DUPLICATE_TARGET
```

研究站需真实中心、一级数据库、一级站及供给，缺少任何一项记录相应 `NO_ACTIVE_CENTER`、`DATA_CENTER_LEVEL_INSUFFICIENT`、`STATION_LEVEL_INSUFFICIENT` 或资格拒绝；数值不足不伪造 environment。站输入先放 gold×1、redstone×60，输出仓清空，FE 充满：

```text
/pe_run_tests net-stock 13 80 0 minecraft:gold_ingot 1
/pe_run_tests net-stock 13 80 0 minecraft:redstone 60
/pe_run_tests research-queue 1
```

实际阶段 START 消耗 gold×1；每个成功计算周期消耗 redstone×1、输出 iron_nugget×1、FE×2，START FE×16。60 个周期后等待缺料，END diamond 不应出现。执行 `assert-resource` 比较真实输出仓 iron_nugget×60、diamond×0，`research-station-progress 600`准确检查数值，再 `research-station-checkpoint` 保存完整真实processTag（codec/seed/state/components）、queueentry和原站点身份/坐标。退出重进等待缺料状态稳定后 `research-station-resume` 要求准确checkpoint相等，再检查余额/progress；然后补 redstone×60。一级完成应 gold×0、redstone×0、iron_nugget×120、diamond×1，总 FE 消耗256；队列清空，个人/共享一级：

```text
/pe_run_tests assert-resource 11 80 0 output minecraft:iron_nugget 120
/pe_run_tests assert-resource 11 80 0 output minecraft:diamond 1
/pe_run_tests research-level research_station 1 1
/pe_run_tests research-queue 0
```

循环研究 `research_loop` 声明 maxLevel=2147483647，可逐级 `research-start research_loop 1 STARTED`、确认后一级2；其需求10、分段10，无材料。数据库的循环同步应随真实队伍云更新。`research_special` 不能普通入队，只由实际原生研究盘供给，后续盘用例覆盖这一职责。

配方门先把 cobblestone×6 放入 device 输入仓并充电：在研究站一级未完成/未下载进数据库前不能产 glass；成功 `research-download research_station 1 COMPLETED` 后再观察真实进程 net_recipe。machine suggested parallelism=1，实际selection fixed3/max4；运行中 `net-work-proof 24 80 0 3`须是准确owner/host的唯一进程且真实effective3。它同时在真实当前permit/issuing book上取得同机lease，同一实际process再次请求必须ALREADY_GRANTED，再以当前最终向量实际reserve compute6、检查余额−6、commit并rollback核对完全返还；不通过打印vector猜付款。THREAD同一机器只占1，与并行数量及工位数无关。该probe补偿了自己的数值测试，配方实际付款仍以真实进度/输出和FE合计核验：cobblestone×6→glass×12，80 tick FE×24（总1920），PN数值compute6。先仅供2枚cobblestone不得偷偷按suggested1执行；少FE或少计算供给应阻塞/拒绝完整3份准入。移走物理计算资源观察网络阻塞/恢复。使用 `assert-active`、`assert-resource`、`assert-energy` 精确比较；不要用“有配方”代表已经支付网络费用。

同机实际四工位另用 `net_slots`，不是把四次手动lease请求称为四个正在运行的工位：

```text
/pe_examples build pe_run_tests:net_slots 30 80 0
/pe_run_tests net-stock 31 80 0 minecraft:cobblestone 24
/pe_examples charge 30 80 1 10000
/pe_run_tests assert-active 30 80 0 4 4 pe_run_tests:net_recipe
/pe_run_tests net-work-proof 30 80 0 3 4
```

须在四个进程尚在工作时执行proof；否则明确precondition失败，不计此项PASS。`net_slots`真实a/b/c/d四工位、maxConcurrent4，各进程实际effective3，共享同机THREAD lease1；proof逐个真实active process在同一个准确permit/book上reserve compute6，累计余额−24，commit后全部补偿恢复。完成后实际cobblestone24→glass48、FE总7680。不得把四个机器分别取得四条lease当作同机四工位通过。

## 4. 真实 actor/team 权限与持久奖励

同一个实际非 OP 玩家可执行 `/pe_run_tests_research`，该入口仅进入正式 ServerResearch 权限 API；不开放世界建造。以下将真实 UUID 替换成该玩家 UUID，这是唯一需实际身份的参数：

```text
/pe_run_tests_research research-blacklist research_loop true NOT_TEAM_LEADER
/pe_run_tests_research research-admin-player <实际玩家UUID> research_loop 1 PERMISSION_DENIED
```

由实际 FTB leader/moderator 或 OP 对同一 blacklist 操作应 `ALLOWED`，解除 blacklist 后才继续 loop。对自己 team 的普通 start/view 允许；通过 FTB GUI 真正离队/换队后再请求，保存实际拒绝状态。默认 policy 来自脚本 `context.standardDecision(action)`，不是固定 allow。OP 用实际 admin API仅显式执行时改变目标研究。

手动一级完成已创建原生奖励 eligibility。执行：

```text
/pe_run_tests research-reward-mode wait
/pe_run_tests research-reward research_manual 1 research_reward RETRY_LATER
/pe_run_tests research-claim research_manual 1 CLAIMING
```

emerald×2 的物品子项先交付，事件子项保持待重试；退出重进不能再给 emerald。然后：

```text
/pe_run_tests research-reward-mode ready
/pe_run_tests research-reward research_manual 1 research_reward CLAIMED
/pe_run_tests research-event-count 1
/pe_run_tests research-reward research_manual 1 research_reward ALREADY_CLAIMED
/pe_run_tests research-claim research_manual 1 CLAIMED
```

事件 child key 写入真实原生 player 文件，并经 PlayerDataPersistence 读回才 APPLIED。再完整重启/重进，事件effects仍1，物品未复制，claimed/choice身份保持；此处不是仅内存计数。

## 5. 数据库、普通物品、原生盘及 PREPARED 跨会话

站一级完成后 `research-download research_station 1 COMPLETED` 再 `research-database research_station 1`，退出重进验证。paper 一个放在原生库存槽0后 `research-item research_station 1 0 COMPLETED` 应消耗 paper×1、个人研究一级扣成0、book×1；数据库级不因物品下载下降。槽号是原生0–35，必须先通过库存 GUI确定材料槽，不能默认kit占槽0。对未注册转换材料应 `NO_EVENT`、特有研究 `SOURCE_REJECTED`；奖励 eligibility 不随消费消失。

需要正常的 personal station 一级作为item来源才能继续。可完成站二级（需求1300、130周期，用新 gold×1/redstone×130/FE），或在独立新测试世界按前面重做。以下明确是**用户选择后退出连接**的测试命令：

```text
/pe_run_tests research-prepare-exit item research_station 1 0
```

命令在真实 MAIN 边界、FTB owner和实际数据库下，经正式 ResearchWorld 保存 PREPARED；在材料新效应前暂停请求，再断开当前玩家。不会编辑存档/NBT去伪造操作。单人世界退出后重新进入即为新服务器会话；多人要由用户退出并重启服务器后重连。重进后等恢复：

```text
/pe_run_tests research-recovered item
/pe_run_tests research-pending item 0
```

`research-recovered`要求原来实际保存的 nonce 对应 COMPLETED 永久回执及原生材料证据已释放，而不只检查空队列。book总量和源研究扣级各一次。恢复前改变真实权限/队伍/数据库时 PREPARED 应保留/拒绝新授权；已支付部分仍按原代清偿。

### 真实编码成盘（R30，独立于fixture写盘）

脚本的 `NetworkRender.registerDiskEncoding()` 在正式download注册phase调用 `ResearchDiskEncodingEvent.register`，实际blank盘转完整native研究盘。`research-encode`调用同一个 `ServerResearch.requestItemDownload`：真实玩家材料槽、nonce、当前数据库、源研究扣级和产物交付；不会直接设置盘payload来宣称编码成功。下面各正向编码子例以站一级已完成且数据库stored1、个人source1开始，先 `research-level research_station 1 1` 和 `research-database research_station 1`。如果已做paper→book耗掉一级，须先正常完成站二级补充真实来源，或在独立新测试世界按前面重做；after预期始终是实际before−1。

先把kit的真实tier0 blank盘放原生slot0（留至少一格空位），执行：

```text
/pe_run_tests research-encode research_station 1 0 EVENT_REJECTED
/pe_run_tests research-encoding-rejected 1
```

拒绝检查要求原nonce没有world operation/receipt、原槽完整native材料未变、blank数量不变且实际源级仍1。再用真实 `research_disk` blank换到slot0，`research-encoding-blank 0 2`只配置Carrier的物理per-stack tier2，必须仍无研究payload；此步仅是使用方空盘布局setup。执行真正编码：

```text
/pe_run_tests research-encoding-blank 0 2
/pe_run_tests research-encode research_station 1 0 COMPLETED
/pe_run_tests research-encoded 0 0
/pe_run_tests research-database research_station 1
```

产物实际位于slot0时使用上述核对；若native交付选择了其它空位，先由库存GUI确定其原生slot，再传入该slot。`research-encoded`必须匹配刚保存的准确nonce/原材料槽、COMPLETED永久回执、盘tier2/完成等级1、完整native产物NBT与回执output完全一致、模板标记`native_encoding_preserved`、blank总量−1和同内容研究盘总量+1、实际源1→0及回执冻结的before/after、材料凭据已释放。tier2盘在tier1编码数据库合法，因为门是research tier1≤disk tier2；随即 `research-disk-import 0 DISK_REJECTED`证明导入目标才要求disk tier≤database tier，再 `research-encoded 0 0`确认拒绝没有消费产物。plain tier1 blank同样可通过真实encoding事件生成tier1输出，不需要调用fixture写盘。

PREPARED编码恢复子例重新具备真实个人source1/数据库stored1，将plain tier1 blank放slot0：

```text
/pe_run_tests research-prepare-exit encode research_station 1 0
```

正式world writer已经保存完整encoded output候选与原材料/源计划，材料和源尚未付款，命令断开当前用户。退出世界/重启服务器后重进执行 `research-recovered encode`、`research-pending encode 0` 和 `research-encoded 0 0`；必须是原nonce的一次扣料、一次源级消费、完整产物和正式回执。多人会话需用户实际停止/重启服务端才计跨server-session，不只重连。

另一个独立source1子例验证**材料/源已经支付、产物尚未交付**的恢复：

```text
/pe_run_tests research-encode-output-exit research_station 1 0
```

它沿真实download协议到SOURCE已提交并由world writer保存DELIVERING和原生盘产物，再暂停该次actor的新交付并断开。不是预制Nbt操作。重启后 `research-recovered encode_output`、`research-encoded 0 0` 必须确认原完整盘只交付一次、材料与源不重扣；再退出重进重复核对，数量仍+1。上述预期after0以before1为例；如果使用正常二级source2，则传入实际after1。

### 预制特殊盘仅用于真实导入setup

`research-disk-write`不是编码协议，也不计R30编码PASS。原生研究盘来自 `research-kit` 的 `pe_run_tests:research_disk`（默认tier1、实现真实ResearchDiskCarrier），用GUI放槽0，显式写入特殊研究一级并导入：

```text
/pe_run_tests research-disk-write 0 research_special 1
/pe_run_tests research-disk-import 0 COMPLETED
/pe_run_tests research-database research_special 1
```

实际消耗盘一枚，数据库持久条目一级。盘 tier限制研究tier而非 completedLevel。把低tier盘放槽0，执行 `research-disk-mismatch 0 research_special 1` 显式提供tier1 payload但保留真实tier0载体，再 `research-disk-import 0 MISSING_DISK`，应拒绝且不扣盘/写数据库；普通空盘同样 `MISSING_DISK`。PREPARED盘需新的、数据库尚未存入的准确条目（例如第二个新测试世界中先写 research_special 的盘）：

```text
/pe_run_tests research-prepare-exit disk research_special 1 0
```

同样是正式world writer保存、真实载体/数据库barrier，保存后当前玩家断开；退出/重启后重进执行 `research-recovered disk`、`research-pending disk 0`、`research-database research_special 1`。准确 nonce 必须是 RELEASED，数据库效应和盘扣减各一次。真实右键数据库也走同一 ResearchDiskInteractions，可另记录本机GUI/物品交互证据。

## 6. 四 pass、多个锚点、动画与数据连接

四pass各一台，另有只走machine/component绑定的一台：

```text
/pe_examples build pe_run_tests:render_default 0 80 12
/pe_examples build pe_run_tests:render_transparent 6 80 12
/pe_examples build pe_run_tests:render_bloom 12 80 12
/pe_examples build pe_run_tests:render_bloom_transparent 18 80 12
/pe_examples build pe_run_tests:render_component 24 80 12
/pe_run_tests render-data 0 80 12 active idle,overlay 11
/pe_run_tests_client anchors 0 80 12 3 11 idle,overlay
/pe_run_tests_client offset 0 80 12 0.25 0.625 0.75
/pe_run_tests_client components 24 80 12
/pe_run_tests_client frames 0 80 12 3
```

前三个锚点是whole原点一次与两次per_slice的worldY+1/+2。`anchors`对count3还精确比较原点与按当前orientation的UP旋转后+1/+2坐标、STRUCTURE kind、whole/slice ID、occurrence path []/[0]和slice index −1/0/1；不是仅打印origin。`components`要求实际已装配marker、准确machine/component两个ID和共同controller origin。`frames` 从当前准确客户端 view/data冻结真实请求，在worker以真实GeckoGeoModelInstances加载模型/动画并采样10/19 tick，断言正顶点数不变且bone pose变化；回到client还须准确view/data仍有效。四个模型在真实相机frame中观察默认实体/透明/泛光/透明泛光，idle旋转、overlay脉冲以及active反向快转/上下运动；截图/帧观察须由用户记录，CPU动画采样不冒充可视成功。

普通data优先序：`render-data ... active empty 12`→`anchors ... 3 12 active`（单名称）；`render-data ... empty empty 13`→`anchors ... 3 13 idle,overlay`（声明fallback）；`render-data ... active idle,overlay 14`→CSV覆盖单名。机器实际安装持久化typed MachineAnimationState；`render-typed ... active` 后 `anchors ... 3 14 active`须覆盖CSV；`render-typed ... empty` 回到 `idle,overlay`。分别对其它pass同样执行写值、typed和anchors/frames命令。`render-orient ... EAST_UP` 后offset应 `-0.75 0.625 0.25`；还应以方向工具改变front和top，核对旋转并观察 yOffset始终沿worldY。全局renderer须离开近裁剪边界时仍按实际global渲染。

```text
/pe_run_tests_client capture 0 80 12
```

退出世界/重连（可完整重启前先完成同进程的退出重进）后 `render-data-check ... 14` 和 `anchors ... 3 14 idle,overlay`要求真实服务器保存和当前客户端同步；同进程重连执行 `pe_run_tests_client reject-old ...`，旧world/connection/attachment的view不可接受且旧data对象已撤销，当前代重新就绪。原生普通data机器另使用 `/pe_run_tests_client data <x> <y> <z> <data-arm的nonce>`，检查服务器末tick nonce已进入准确generation的客户端full snapshot，输出实际进程hosts/slots/data。

## 7. v1→v2 私有显示、旧handle拒绝及通知失败

先按步骤1确认v1页面：准确独立page expanded候选两种，输入iron/gold任选×1，输出emerald×1、25%、120t；manifest命令自动核该准确private recipe的native display row、数量、chance和duration。另有deterministic `client_migration` v1：quartz START1、redstone TICK1、2400t→emerald1；v2则quartz1、40t→diamond2，便于观察真实旧进程完成。

在新的空地建一台专用真实控制器，保持其它材料为空：

```text
/pe_examples build pe_run_tests:render_default 36 80 12
/pe_run_tests net-stock 37 80 12 minecraft:redstone 2400
/pe_run_tests net-stock 37 80 12 minecraft:quartz 1
/pe_run_tests client-capture-old 36 80 12 old-v1
```

等实际old process已START并在工作再capture；没有active/started v1会明确失败。2400t（约两分钟）留出更新、退出重进的操作窗口。capture写实际PROCESS普通data nonce/seed，并经原生player文件读回保存machineUUID/seed/codec graph；不拿detached process冒充世界进程。随后：

```text
/pe_run_tests client-update
/pe_run_tests client-old-handle REJECTED_STALE
/pe_run_tests client-server-manifest 1
/pe_run_tests_client manifest v2
/pe_run_tests client-old-process 36 80 12 old-v1
/pe_run_tests_client migration 36 80 12 old-v1
```

独立页应保持同一实例/UID，准确prepareUpdate更换canonical private pool，显示diamond×2、75%、40t；默认页仍显示全局alternatives和概率overlay。客户端manifest命令比较完整服务器声明/private目录并自动核准确native row，不用server singleton冒充本地页；实际layout/draw仍由用户观察。`client-old-process`要求真实在运行的原machine/owner/host、v1 duration2400/codec graph/seed/ordinarydata；当前canonical却已经v2 duration40且是另一个对象，历史v1仍准确绑定。客户端migration命令检查该nonce确实进入准确generation的实际process full snapshot。

旧进程未完成时退出世界/服务器重进，先 `client-old-handle REJECTED_SESSION`（同进程旧handle）再 `client-old-process ... old-v1` 和客户端migration核对，不能用之前内存对象代替重建owner。若完整重启客户端会重新从初始v1声明开始，需要再次 `client-update` 后检查v2/原seed/普通data。最后在它实际完成后：

```text
/pe_run_tests client-old-finished 36 80 12 old-v1
```

须同一原生machineUUID、原nonce、没有未完成old进程，实际input quartz/redstone为0、output emerald1/diamond0，证明原2400TICK材料与旧END输出，不能按新40t/v2输出完成。结束后再退出重进重复核余额。没有真正完成不能计该PASS。

`client_external_pool`通过实际有效`minecraft.crafting`绑定既有HEI类别，`render_component`同时是其机器催化剂。manifest命令核native registry已解析该UID、有真实controller catalyst且没有为该pool新建own page/wrapper；由用户打开原Minecraft crafting页检查入口。它不会把该外部池配方塞进伪造独立页。

通知失败需**独立的一次完整客户端重启/初始v1**执行 `client-notify-failure`（不要在已经v2的目录上把同一个v2键绑定第二个snapshot）：实际Forge post-publication observer会故意抛错。预期返回 `NOTIFICATION_FAILED` 同时published=true，真实目录已经v2；再执行 `client-old-handle REJECTED_STALE`、manifest v2、页面输出核对，失败通知不能回滚已发布事实。日志中的这一条 deliberate异常是用例预期，其他异常仍是失败。

请每项保存本次日志、命令/坐标/actor、实际值和截图；最后标记启动通过、服务器原生通过、客户端同步通过和视觉通过各自证据。没有实机结果时维持待用户验证。
