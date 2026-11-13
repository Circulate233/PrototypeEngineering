# 网络设备与中心显示演示

2026-11-14。内容只注册在run环境：16脚本定义设备及供给配方，31脚本定义结构与普通负载配方；Java的ComputerExamples提供三种组件Item，ExampleResources/RunResources共享资源单例。

| 设备ID（pe_run_tests命名空间） | 默认控制器与参数 |
| --- | --- |
| net_demo_center | 一级中心，4并发，生效时4 FE/t |
| net_demo_computer | 计算机，本体无FE费用，上限8并发 |
| net_demo_station | 一级研究站，算力100/t、量子位10/t，推进时8 FE/t |
| net_demo_database | 一级数据库，16条研究，活动时占1并发 |
| net_demo_load | 普通联网控制器，1工位/1并行，不要求研究即可运行两条负载配方 |

| 组件ID（pe_run_tests命名空间） | 每件并发 | 每件资源供给 | 每tick冷却 | 每tick FE |
| --- | ---: | --- | ---: | ---: |
| net_demo_cpu_card | 4 | 算力500 | 500 | 16 |
| net_demo_quantum_card | 4 | 量子位64 | 250 | 24 |
| net_demo_io_card | 2 | 内存128.5、带宽256.25、存储吞吐64、信号100 | 250 | 8 |

计算机资源限额：算力800、量子位64、内存128.5、带宽256.25、存储吞吐64、信号256。每个运算仓槽只放1件组件，普通输入仓物品不贡献组件供给。

| 计算机每tick配方 | 消耗 | 提供 |
| --- | --- | --- |
| net_demo_water_cooling | 10 mB水 | 内部冷却1000 |
| net_demo_lava_cooling | 5 mB岩浆 | 内部冷却1000 |
| net_demo_signal | 红石1 | 外部信号200 |

配方独立运行；一件CPU、量子、IO组件共消耗1000冷却和48 FE/t，只供水即可工作。组件费用不足时全部组件供给停摆，能运行的红石配方继续提供信号200。冷却属于内部资源，不显示到网络。六种外部资源供给后，中心超过5行启用滑块。

| 普通负载配方 | 材料 | 时长 | FE/t | 每tick网络占用 |
| --- | --- | ---: | ---: | --- |
| net_demo_compute_load | 圆石1→玻璃1 | 100 tick | 8 | 算力25、内存8、带宽16.5 |
| net_demo_quantum_load | 石英1→铁粒2 | 60 tick | 4 | 量子位2、存储吞吐8、信号20 |

进入有操作权限的测试世界后，在空地执行以下命令。搭建走既有真实玩家放置链，按放置者当前FTB队伍入网。同队其他中心沿原等级/UUID规则选择生效者。

```text
/pe_examples kit network
/pe_examples build net_demo_center 0 80 0
/pe_examples build net_demo_computer 6 80 0
/pe_examples build net_demo_database 12 80 0
/pe_examples build net_demo_station 18 80 0
/pe_examples build net_demo_load 24 80 0
/pe_examples charge 0 80 1 1000000
/pe_examples charge 6 80 1 1000000
/pe_examples charge 18 80 1 1000000
/pe_examples charge 24 80 1 1000000
/pe_run_tests net-stock 5 80 0 pe_run_tests:net_demo_cpu_card 1
/pe_run_tests net-stock 5 80 0 pe_run_tests:net_demo_quantum_card 1
/pe_run_tests net-stock 5 80 0 pe_run_tests:net_demo_io_card 1
/pe_run_tests net-stock 7 80 0 water 10000
/pe_run_tests net-stock 7 80 0 minecraft:redstone 512
/pe_run_tests net-stock 25 80 0 minecraft:cobblestone 16
```

计算机左侧(5,80,0)为四级运算仓，右侧(7,80,0)为普通输入仓，后方为能源仓。中心上方铁块、后方能源仓；数据库仅上方铁块。研究站和负载机右侧输入、左侧输出、后方能源仓；默认NORTH_UP分别对应+x/-x/+z。

右键中心观察六行资源、分数数量、滑块及算力/内存/带宽占用。供水约可运行50秒，可继续补液或外部输入。取出或耗完圆石，向负载机投入石英后观察另三种资源占用。该套总供给12并发，计算机和活动数据库各占1；负载机工作时占1，研究站仅实际推进时占1。

```text
/pe_run_tests net-stock 25 80 0 minecraft:quartz 16
/pe_run_tests net-stock 5 80 0 pe_run_tests:net_demo_cpu_card 1
/pe_run_tests net-stock 7 80 0 lava 10000
```

第四槽再装CPU并供岩浆时，1500冷却需求可满足，算力原始1000被限为800，并发仍限8。移除岩浆、只供水后，组件全部停摆，仅红石配方提供信号200；资源行缩减。补岩浆或取出额外CPU后恢复，关闭重开GUI可检查完整基线。

研究站、数据库沿已有后台工作，需要推进研究时按[原网络研究步骤](../visual-examples/NETWORK-RENDER-TESTS.md)发起请求。本套不新增研究GUI。当前addon已编译安装；后续改动执行 `run/prepare-tests.ps1` 和 `run/verification/verify.ps1`，用户完整启动游戏后复验。Java和15份真实SDK脚本编译通过；World注册、费用、放置及GUI仍待实机。
