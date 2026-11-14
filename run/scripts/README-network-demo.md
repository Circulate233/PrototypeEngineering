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

| 单位转换 | 每份材料 | 每份本机产出 |
| --- | --- | --- |
| net_demo_water_cooling | 3 mB水 | 冷却3 |
| net_demo_lava_cooling | 1 mB岩浆 | 冷却2 |

一件CPU、量子、IO组件共需1000冷却和48 FE/t。水转换每份3，因此单独供水每tick消耗1002 mB；水的完整份数不足时，岩浆继续补缺口。不满一份的余料不消耗。转换结果只在本机使用；信号由IO组件提供。全部组件资源或FE不足时，组件全部停供。

`net_demo_maintenance` 是20tick纯输入配方：START消耗1红石，TICK消耗1 mB水，END消耗5 mB水。成功TICK通过事件把下一tick组件冷却需求乘0.9；等待时不保留旧的维护效果。网络全局事件在同队有成型的 `net_demo_database` 时把每台网络设备的FE输入乘0.9，再按整数FE向上取整。

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

右键中心观察六行资源、分数数量、滑块及算力/内存/带宽占用。请持续补水；数量按组件当前需求和转换单位决定，不再按固定配方每tick扣一份。取出或耗完圆石，向负载机投入石英后观察另三种资源占用。该套总供给12并发，计算机和活动数据库各占1；负载机工作时占1，研究站仅实际推进时占1。

```text
/pe_run_tests net-stock 25 80 0 minecraft:quartz 16
/pe_run_tests net-stock 5 80 0 pe_run_tests:net_demo_cpu_card 1
/pe_run_tests net-stock 7 80 0 lava 10000
```

第四槽再装CPU时，组件基础冷却需求增至1500，算力原始1000被限为800，并发仍限8。水足够时会按增大的需求自动多取材料；水不足则尝试岩浆。水和岩浆都不足时，已有完整份数仍支付，组件供给归零。补充足量材料后恢复，关闭重开GUI可检查完整基线。

研究站已提供正式 GUI，显示队首进度、当前可用/剩余材料、网络占用与累计量，以及可重排的共享队列。数据库界面继续延缓。按[网络研究步骤](../visual-examples/NETWORK-RENDER-TESTS.md)发起请求；更新后编译 addon，用户完整启动游戏并重新放置装配版本已变的演示研究站。验证结果见项目实施进度。