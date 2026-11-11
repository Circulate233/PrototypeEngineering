# 原生运行用例与真实断言

本文件记录原生 addon 的操作路径。所有命令由玩家手动执行，需权限 2；不会在世界加载时搭建、投料、充电或改写世界存档。`PASS` 只指该命令读取到的实际条件，`FAIL` 是实测不符，`PRECONDITION` 是条件不足而未执行。日志使用 `pe_run_tests`，可在 `run/logs/latest.log` 保存实机证据。整个任务的当前源码/脚本检查不代表这些原生用例已经运行。

原 13 台机器与 106 条配方全部保留，仍使用原 `visual-v1` 局部 codec，并已向真实配方目录声明该精确版本。另加 `pe_run_tests:native_data_fixed` 和 `pe_run_tests:native_data_cross` 两个真实 owner；它们共享 `native_pool` 的 6 条 `native-v1` 配方，显式安装 `DataComponents.MACHINE/PROCESS` 及官方 codec。机器 post、进程 post 在真实调度路径记录普通数据，不读 World，不直接写 tile NBT。

## 准备与由用户启动

```powershell
# 只准备当前主类、命名 compile+runtime 类路径、编译/打包/安装 addon，不启动游戏。
.\run\prepare-tests.ps1

# 由用户明确执行这一条才启动开发客户端；后续更新须完整退出再重启。
.\run\start-tests.ps1

# 用户选择原生 Bloom 视觉复验时启用已存在的 Lumenized 依赖。
.\run\start-tests.ps1 -WithBloom
```

`run/test-runtime.gradle` 仅在这些入口被显式应用时给开发运行接入 CT2 Main/API/ZenScript 4.1.20.718。没有向 mods 复制 PE 或 CT 阴影副本，没有改正式依赖配置。默认启动未启用原生 Lumenized；此时只能确认 Bloom 回退行为，不能记录原生 Bloom 渲染通过。

`run/export-classpath.gradle` 导出当前命名主输出及 compile/runtime 并集至 `run/visual-examples/runtime-classpath.txt`；编译不再依赖已清理的 `build/staging`。`build.ps1` 自动收集全部 addon Java 源，只打包自己的类和资源，保留完整编译参数及 `build-result.json`。客户端占用目标 Jar 时保留新 Jar并报告安装失败，准备入口拒绝继续启动旧 addon。

## 十阶、三方向、物品/流体/FE 与事务

在创造模式新测试世界选已加载空地：

```text
/pe_run_tests gallery 0 80 0
/pe_run_tests gallery-check 0 80 0
```

这是 60 个真实 PE 仓室：十阶 × 输入/输出/混合 × 材料/FE。所有位置先预检空气、边界、加载及玩家权限。tier `n` 的 x 为 `3×(n-1)`；材料输入/输出/混合的 z 为 0/3/6，FE 的 z 为 10/13/16，y=80。不会自动投料、改变容量或启动自动 IO。

`gallery-check` 逐个报告真实槽位/每槽容量/FE容量和限制，检查物理方向、混合两侧独立 context、空仓 Forge 物品/流体模拟是否按方向接收且不改变余额，再逐侧通过真实 `reserveInsert/reserveReceiveEnergy → canApply → apply → rollback` 验证余额原样和准确 token 的 `ROLLED_BACK`。60 个位置访问完成并非聚合 PASS；任何容量不足、已有工作预留、缺失 capability 都明确留作条件不足或失败。检查不读 NBT，也不使用伪库存。

默认配置的对照值（用户若已有配置覆盖，以命令打印的有效 profile 为准）：

| 阶 | 单向槽/混合每侧槽 | 物品每槽 | 流体每槽 mB | FE 每侧容量 | 默认每操作 FE 上限 |
|---:|---:|---:|---:|---:|---:|
|1|1/1|64|16,000|128,000|32|
|2|2/1|128|32,000|512,000|128|
|3|4/2|128|64,000|2,000,000|512|
|4|9/4|256|128,000|8,000,000|2,048|
|5|16/8|512|256,000|32,000,000|8,192|
|6|25/12|1,024|512,000|128,000,000|32,768|
|7|36/18|2,048|1,024,000|512,000,000|131,072|
|8|49/24|4,096|2,048,000|2,000,000,000|524,288|
|9|64/32|8,192|4,096,000|8,000,000,000|2,097,152|
|10|81/40|16,384|8,192,000|32,000,000,000|8,388,608|

右键逐阶仓室，核对原生物品图标、long 数量、容量、十阶滚动及禁用控件。LV1 混合两侧各只有一槽，每侧可支持多种 AEKey 类型，但一个槽同一时刻只放一种；验证第二类型前先取出该槽材料。材料输入仓手持水桶/空桶的 AE2S 左右键 tooltip 应分两行，有内嵌资源图标，Shift 原批量交互保留；这属于视觉观察，不能由 `gallery-check` 代替。

FE 混合四阶位置 `(9,80,16)` 可精确核对双侧：

```text
/pe_examples charge 9 80 16 100
/pe_run_tests assert-energy 9 80 16 input 100
/pe_run_tests assert-energy 9 80 16 output 0
/pe_run_tests fe-rate 9 80 16 0 0
/pe_examples charge 9 80 16 100
/pe_run_tests assert-energy 9 80 16 input 100
/pe_run_tests fe-rate 9 80 16 -1 -1
/pe_examples charge 9 80 16 100
/pe_run_tests assert-energy 9 80 16 input 200
/pe_run_tests assert-energy 9 80 16 output 0
```

`fe-rate 0 0` 同时断言真实 Forge 模拟端口返回零；`-1` 恢复额外限制默认，仍服从该仓有效 profile 上限。用 GUI 设置 0/-1/大正值并重复命令核对，退出重进后 `inspect` 应保留设置。输出仓 `charge`、输入仓 `drain` 应被真实端口拒绝。需要核对生成/回收的 END 总量时使用原 `boundary_energy`：`energy_consume` 输入 3072 FE，`energy_generate` 输出 6144 FE，`energy_recycle` 输入 896、输出 448 FE；此前先给输入精确充10000并确保输出空，最终断言分别为 6928/0、10000/6144、9104/448。

自动 IO 使用真实相邻箱子/流体容器/FE 方块，不用 helper 冒充邻居。对已放仓室执行 `auto x y z true true`（GUI 也可设置），在邻居投放已知数量，之后 `assert-resource`/`assert-energy` 核对输入实际增量、邻居实际减量；输入仓输出开关不改变物理方向。输出侧先跑真实配方产生产品，再开启自动输出，核对邻居产品总量与仓室余额。混合两侧各自独立。关闭开关、同一 TE 中补料/腾出容量、保持 TE 更换邻居内容，再开开关，应能再次搬运，且不重复第一次搬运量；默认新仓关闭自动 IO。日志和邻居 GUI 的实测数量共同构成证据。

有效 profile 的原生重配置使用 gallery 四阶混合材料仓 `(9,80,6)`。先通过 GUI 投入铁锭65+水1000，输出侧空，自动 IO 关闭；不改 `hatches.cfg`：

```text
/pe_run_tests profile-resource 9 80 6 input 1 32 128000
/pe_run_tests profile-status 9 80 6 BLOCKED_CAPACITY
/pe_run_tests assert-profile 9 80 6 input 4 256 128000
/pe_run_tests assert-resource 9 80 6 input minecraft:iron_ingot 65
/pe_run_tests assert-resource 9 80 6 input water 1000
/pe_run_tests profile-resource 9 80 6 input 4 32 64000
/pe_run_tests profile-status 9 80 6 APPLIED
/pe_run_tests assert-profile 9 80 6 input 4 32 64000
/pe_run_tests assert-profile 9 80 6 output 4 256 128000
/pe_run_tests assert-resource 9 80 6 input minecraft:iron_ingot 65
/pe_run_tests assert-resource 9 80 6 input water 1000
```

每次 request 后等待真实 owner 边界再读 status。第一次一槽容量32容不下65，整侧旧 profile/余额必须保留；第二次铁锭拆三槽、水占一槽，4槽可容纳，正确应用且另一侧保持原样。命令只调整指定侧的槽数/物品与流体容量，保留该侧其他已注册类型的容量。当前配置覆盖默认值时先按 `inspect` 实测旧 profile 改对照值。退出重进后再次读取同侧 profile/余额，确认原生保存。活动事务期间 request 返回 `PENDING_BUSY` 时应等待真实提交/补偿退出后应用，不能直接用声明对象当作“已应用”。

破坏掉落用独立新空仓测试，先关闭自动 IO，不共享正在执行的机器。投入铁锭65与水1000，在生存模式破坏并收集掉落，实际应得到铁锭65（原生分堆或资源包）与水1000的 AE 资源包，仓室物品只保留配置；重新放置后 `assert-resource ... input minecraft:iron_ingot 0` 与 `... water 0` 应通过。活动预留时破坏要等准确补偿/提交后收取最终余额，再与破坏前最后完整 tick 的实际消耗/产出核对；不能把初始投料数当作最终掉落数。该真实地面/拾取/重新放置过程由用户操作，命令没有假造掉落实体。

## 四工位、跨配方、共享物品流体与 FE

```text
/pe_examples build pe_run_tests:native_data_fixed 0 80 40
/pe_run_tests pause 0 80 40
/pe_examples charge 0 80 41 100000
/pe_examples stock -1 80 40 pe_run_tests:native_cycle_1
/pe_examples stock -1 80 40 pe_run_tests:native_cycle_2
/pe_examples stock -1 80 40 pe_run_tests:native_cycle_3
/pe_examples stock -1 80 40 pe_run_tests:native_cycle_4
/pe_run_tests resume 0 80 40
/pe_run_tests assert-active 0 80 40 4 4
/pe_run_tests inspect 0 80 40
```

机器是原生 `MultithreadController`，固定四工位有四种 group；新配方都把每进程数量固定为1，整机允许4。每条400个成功 tick：START 消耗铁锭/金锭/红石/绿宝石之一×1，TICK 消耗煤×1+水5mB+4FE并产铁粒×1，END 分别产钻石1/2/3/4。`stock` 每条投入一次完整 START/TICK，合计煤1600、水8000mB。`inspect` 输出实际执行模式、工作线程名称、精确 owner/host、种子、进度和普通进程数据；线程名称是实际调度观察，源码没运行不能称四线程实机通过。

等 GUI 记录显示四条全部完成后执行：

```text
/pe_run_tests assert-active 0 80 40 0 0
/pe_run_tests assert-completed 0 80 40 pe_run_tests:native_cycle_1 1
/pe_run_tests assert-completed 0 80 40 pe_run_tests:native_cycle_2 1
/pe_run_tests assert-completed 0 80 40 pe_run_tests:native_cycle_3 1
/pe_run_tests assert-completed 0 80 40 pe_run_tests:native_cycle_4 1
/pe_run_tests assert-resource -1 80 40 input minecraft:coal 0
/pe_run_tests assert-resource -1 80 40 input water 0
/pe_run_tests assert-resource 1 80 40 output minecraft:iron_nugget 1600
/pe_run_tests assert-resource 1 80 40 output minecraft:diamond 10
/pe_run_tests assert-energy 0 80 41 input 93600
```

另选新空位置搭 `native_data_cross`，同样投四种输入；使用原生 `CrossRecipeController`，应真实形成四种不同配方的动态工作记录。只投入 `native_cycle_1 4` 时，单配方线程上限2，应在第一轮 `assert-active ... 2 2 pe_run_tests:native_cycle_1`，其余两次等第二轮；最终完成计数4、铁粒1600、钻石4，FE总耗6400。固定工位与 Cross 不共用假工位状态。

不同 owner 用第二位真实玩家亲自执行 `build`，不能替换 tile NBT；各自在不同位置运行。`inspect` 显示原生保存身份，`assert-owner x y z <玩家UUID>` 核对 ownerId，不以队伍授权代替 owner。两台不同位置同 tick 运行时分别核对各自数据/余额/进程 owner，不能把其中一台的完成记录当作两台通过。

## 普通数据、准确保存、最后 tick、同步与重附

使用刚进入运行且仍未完成的一轮，先暂停：

```text
/pe_run_tests pause 0 80 40
/pe_run_tests assert-active 0 80 40 4 4
/pe_run_tests data-arm 0 80 40 last_tick_A
/pe_run_tests data-verify 0 80 40 last_tick_A
/pe_run_tests_client data 0 80 40 last_tick_A
/pe_run_tests checkpoint 0 80 40 native_round
```

`data-arm` 只写普通数据里的 pending；`last_tick_nonce` 在下一个真实机器 post 回调写入。`data-verify` 太早会明确 FAIL，等待一个 tick 再执行。暂停只关闭配方 gate，普通机器组件仍会执行，因此可测试最后 tick 的普通数据 dirty。客户端命令读准确 generation 的原生 `dataSnapshot` 和实际客户端普通数据，另打印进程快照；服务端成功不能替代这一条真实 full-sync 证据。

`checkpoint` 在 `readGuiAtBoundary` 加入工作线程后读取真实 owner/identity/seed/进度/普通数据和目录中该准确进程的行为 revision，写 `run/pe_run_tests/evidence/native_round.properties` 的独立证据侧车；不会替用户强制保存或改写 world NBT。保持配方暂停，用户从菜单退出世界，再原世界重进：

```text
/pe_run_tests restore 0 80 40 native_round
/pe_run_tests data-verify 0 80 40 last_tick_A
/pe_run_tests_client data 0 80 40 last_tick_A
/pe_run_tests assert-active 0 80 40 4 4
/pe_run_tests resume 0 80 40
```

`restore` 比较维度/位置/真实机器 UUID/owner UUID/type/nonce相等，普通机器计数不能倒退，每个原进程 recipe和seed相同、成功tick与普通observed计数不能倒退。配方暂停后这些进程应仍存在；完成之后再 restore 会正确失败，不用“消失即完成”掩盖丢失。没有反射读取 tile 存档字段，没有把脚本局部 IData 当作保存证据。

卸载/重附用另一轮和另一 label：保持暂停，关闭客户端强制区块加载（若用户主动使用了加载器），玩家离开至目标区块实际卸载后返回；确认发生卸载，再 `restore`。进入世界/正常 unload后都有相同身份与原进程。拿掉上方铁块应失配，普通机器数据计数仍执行、配方工作停；补回后重新成型并继续原进度。这三种边界需分别记录，不把仅关闭 GUI 当作区块卸载。

## 取消与已提交 tick 的收据

重新搭一台四周期空机器，完整投四条，运行若干成功tick但尚未完成，然后：

```text
/pe_run_tests pause <控制器x> <y> <z>
/pe_run_tests inspect <控制器x> <y> <z>
/pe_run_tests cancel-check <控制器x> <y> <z>
/pe_run_tests assert-active <控制器x> <y> <z> 0 0
```

`cancel-check` 要求真实暂停的、未完成的 `native_cycle` round。它从四个实际进程的 successfulTicks累计 N，先核对实际输出铁粒=N、钻石=0，再取消准确 owner下的真实工位，确认煤/水/已产铁粒/FE的提交余额不变、END钻石仍0，取消数等于原进程数。START和已成功tick已经提交，取消不会返还这些资源；未成功tick与END不可无故收费或发奖。机器仍暂停防止下一tick重新自动接纳剩余START输入。条件不满足不执行取消，也不打印PASS。

## 加权、概率、恢复种子

在一台新的 `native_data_cross` 上仅投 `pe_run_tests:native_weighted`；真实配方耗沙砾1，400tick后从燧石/铁粒/金粒按80/15/5选择一个，数量1，无FE。输出仓须原先空：

```text
/pe_examples stock <输入仓x> <y> <z> pe_run_tests:native_weighted
# 完成后：
/pe_run_tests assert-weighted <输出仓x> <y> <z>
/pe_run_tests assert-completed <控制器x> <y> <z> pe_run_tests:native_weighted 1
```

该断言读取真实输出仓，要求唯一一种合法key、合计1，不把任何一个固定产物硬定为PASS。统计权重不是单次结果断言。

`native_chance` 消耗燧石1+水1000，400tick后25%产钻石1，无FE。先清空输出，运行中暂停/checkpoint/退出重进/restore，确认原种子和原进程普通数据后再resume，完成后：

```text
/pe_run_tests assert-completed <控制器x> <y> <z> pe_run_tests:native_chance 1
/pe_run_tests assert-resource-range <输出仓x> <y> <z> output minecraft:diamond 0 1
```

合法值0或1，单次没钻石是合法结果。该用例证明合法实际产物及保存后的原种子/owner；没有伪造概率命中，也不以一次合法值声称25%分布已经统计验证。

## 原生 GUI、工具与169结构导入

保留原 `boundary_structure` 的170实方块、169非控制器材料和玻璃候选。按原 README 的层图在空地搭建，对控制器按R进入JEI结构页，实际导入加工样板：全层/底层/中层/顶层都应完整169，输出为本地化“原名-结构”控制器1。默认铁块57+玻璃109+材料仓2+FE仓1；切染色玻璃候选后仍169，玻璃109整种变化。终端真正编码后再取样板核对，JEI轮廓本身不是导入证据。

机械构造器：首次普通右键不同控制器只绑定；再次同控制器打开；潜行右键绑定并打开。预览选一格玻璃替换后BOM为原玻璃108+染色玻璃1，右侧双击玻璃材料再替换为109染色玻璃；右侧单击只显示线框。材料滚动/候选网格/原生 tooltip和最近射线命中必须实际观察。GUI投影30秒到期；手持对应工具Shift+滚轮从当前层逐底层→顶层→完整循环，替换材料保留片层。

方向工具分别试6面及每面的4个朝上方向，预览应跟真实朝向；控制器变向不搬动世界，重新按该朝向实际搭壳后应成型。范围结构选择器在实际方块区域选择，零层控制器、片层原生纹理/透明玻璃、线框与候选应正确。以上视觉/点击/滚轮/真实搭建都是手动观察；已有170结构声明与当前普通断言不能冒充工具GUI操作通过。

## 独立外部 AE 方块实体的过滤、容量、发现与失效

新增 `pe_run_tests:external_filtered_hatch` 是独立 `AEBaseTile` 和独立 AE 方块，没有继承 PE 的资源仓方块实体。它只复用真实有界存储引擎及公共物品/流体适配器；输入和输出各2槽，每槽物品64、水1000mB。输入只接受铁锭和水，输出只接受金粒和水。所有普通写入、槽位替换、输出预留都经过同一过滤，外部 Forge 接口仍只能向输入存入、从输出取出。右键打开现有 PE 原生仓室 GUI。

```text
/pe_examples build pe_run_tests:external_filter 60 80 40
/pe_run_tests external-check 59 80 40
/pe_run_tests external-discovered 60 80 40 59 80 40
/pe_examples stock 59 80 40 pe_run_tests:external_exchange
/pe_run_tests assert-active 60 80 40 1 1
# 等80个成功tick完成：
/pe_run_tests assert-completed 60 80 40 pe_run_tests:external_exchange 1
/pe_run_tests assert-resource 59 80 40 input minecraft:iron_ingot 0
/pe_run_tests assert-resource 59 80 40 input water 0
/pe_run_tests assert-resource 59 80 40 output minecraft:gold_nugget 2
/pe_run_tests assert-resource 59 80 40 output water 50
```

`external-check` 要求两侧空，实际尝试钻石/熔岩输入、铁锭/熔岩输出，均应拒绝且不收费；禁物预留与原子槽替换应拒绝。允许物品129/水2001的真实空仓模拟分别只接受128/2000，Forge 模拟能接铁锭/水并拒钻石/熔岩，随后四种合法入出端口的真实预留/应用/回滚保持双方空余额。`external-discovered` 在控制器 joined 边界核对成型，并要求真实 `MachineHatches` 中的对象就是该外部 tile 暴露的 capability；不能靠声明中写了该方块就算发现成功。80tick配方通过该对象的真实存储消费铁锭1+水250，产金粒2+水50，无FE。

真正替换失效在该配方完成后执行（先取出产品可单独观察掉落）：

```text
/pe_run_tests external-capture 59 80 40 external_A
# 玩家真实拆除此方块并重新放置同一 external_filtered_hatch：
/pe_run_tests external-invalid external_A REPLACED
/pe_run_tests external-discovered 60 80 40 59 80 40
```

如果仅拆除而不补回，使用 `external-invalid external_A REMOVED`。检查持有的旧 tile/capability/context，不重新查询新端口冒充旧引用：旧两侧 `isValid=false`，实际 insert/extract返回0，旧快照的真实预留结果为 `INVALID_STORAGE`，规划快照空；替换时另核对新 tile和两侧新 context不同且有效。旧捕获在验证后释放，不跨服务器重启假装仍有原对象。破坏掉落复用真实附加掉落与准确事务补偿，仓室物品不携带库存。外部仓保存读取真实后台 slot codec，两侧内容先校验容量与过滤才发布，不写进度或伪库存。

## 真 World 查询与24朝向搭建

搭建命令现在接受可选的完整朝向，并对同一声明表旋转全部 offset；控制器最后放置并通过真实 host 朝向入口设置。旧13台、native数据机、外部过滤机、网络与渲染机的搭建都使用同一空气/加载/权限/边界预检。

```text
/pe_examples build pe_run_tests:core_world 80 80 40 NORTH_UP
/pe_run_tests world-query 80 80 40 MATCHED NORTH_UP
/pe_examples stock 79 80 40 pe_run_tests:core_world_recipe
# 等40成功tick完成：
/pe_run_tests assert-resource 79 80 40 input minecraft:iron_ingot 0
/pe_run_tests assert-resource 81 80 40 output minecraft:gold_ingot 1
```

`core_world` 的实际表由 CoreCases 的 current blueprint提供：输入在(-1,0,0)，输出在(1,0,0)，灯(6,0,0)，石头封住灯五面，供电红石块(6,-1,0)。所有坐标以控制器为原点。玩家将供电红石块替换为石头，等灯熄灭并完成原生光照更新，再执行 `world-query ... MISMATCH NORTH_UP`；灯方块仍存在、结构其他物料仍齐全，查询的实际亮度导致匹配失败。重新供电应重新匹配并运行真实配方。

可在新位置指定其它23个 `BlockOrientation`（Tab补全）搭建同表，`world-query` 可选参数仅核对当前真实 host朝向，再用真实朝向捕获查询。它调用 CoreCases 的真实 World source、owner捕获/worker判定/旧 proof新鲜度核对，并输出实际坐标、亮度、线程、query访问及判断记录。不会在 detached fixture中制造一个假 World，也不以简单getter读值声称完整 source路径已通过。

## 当前验证边界

上述代码有真实运行入口与准确预期，但本任务没有启动Minecraft、服务端、FML或Bootstrap，也没有改 `run/world` 或用户现有配置。逐Java本机MCP使用 `errorsOnly:false`；IDE对run目录返回排除/不在内容根，错误/警告/弱警告数量不可得，不能记作0。MCP build选择这些文件返回成功也不证明IDE实际分析了被排除文件。真实命名类路径javac/jar、脚本SDK编译与安装记录由本轮统一验证填入总 README/覆盖矩阵；客户端尚未执行的用例维持“待用户原生验收”。
