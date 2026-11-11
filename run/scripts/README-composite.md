# 子结构与可拓展结构：实际加载的示例

定义位于 [12_pe_run_composite_preinit.zs](12_pe_run_composite_preinit.zs) 和 [23_pe_run_composite_structures.zs](23_pe_run_composite_structures.zs)，不是伪代码。前者注册两台控制器和真实processing装配，后者声明结构树、1–4段slice、配方池；95统一安装准确codec/目录。Java助手只从服务器当前canonical结构图生成搭建位置，不另写一份镜像结构。

准备命令仍为项目根目录 `run/prepare-tests.ps1`；用户完整退出后用 `run/start-tests.ps1` 启动。新增控制器必须完整重启，不能只 `/ct reload`。以下操作均待用户实机；编译、SDK Compiler和核心97断言没有执行这些世界场景。

## 共享、嵌套子结构

实际树：根机器 → 左/右空锚点分支 → **同一个**shared_pod对象 → marker孙结构。左右两个pod和两个marker都是不同的实际occurrence，不能按相同结构ID去重。pod为3×3竖直金框玻璃墙，后方marker由青金石块和绿宝石块组成。

```text
/pe_examples kit composite
/pe_examples build pe_run_tests:nested_structure 0 80 40
/pe_examples structure-check 0 80 40 1 true
/pe_examples charge 0 80 41 10000
/pe_examples stock -1 80 40 pe_run_tests:nested_glass
```

等待80个成功tick，输入沙子1→玻璃2，FE总消耗320。输出仓在 `(1,80,40)`。搭建27方块，排除控制器的完整BOM为26：金块8、玻璃8、石英块2、青金石块2、绿宝石块2、铁块1，以及四阶输入/输出/FE输入仓各1。

`structure-check`读取真实World并冻结后由worker匹配，检查当前证据、实际26材料位置、两个pod和两个marker，不以“已给物品/已放方块”冒充PASS。右树应显示左右分支和各自marker。只改一格玻璃时固定用料与右树总计7＋1；整种替换后8，同一共享定义的两次使用仍分别显示其位置。

移除一个真正的孙结构标记，再补回：

```text
/setblock -3 81 45 minecraft:air
/pe_examples structure-check 0 80 40 1 false
/setblock -3 81 45 minecraft:lapis_block
/pe_examples structure-check 0 80 40 1 true
```

应失配/停机后恢复成型，另一分支不能代替缺失的marker。另可在新空地用 `EAST_UP` 或其它朝向搭建，断言读取控制器实际orientation，不固定世界坐标轴。

## 一至四段可拓展塔及移动末端盖

根机器 → slice body（`min=1,max=4,step=(0,1,0)`）→ gold cap。每段为3×3水平截面：4个铁角、4块边缘玻璃、中心红石块。末端金盖为3×3共9块，只在**最后匹配段之后**出现一次，并非每段都有一份。

```text
/pe_examples build pe_run_tests:expandable_structure 16 80 40 NORTH_UP 1
/pe_examples build pe_run_tests:expandable_structure 24 80 40 NORTH_UP 2
/pe_examples build pe_run_tests:expandable_structure 32 80 40 NORTH_UP 3
/pe_examples build pe_run_tests:expandable_structure 40 80 40 NORTH_UP 4
/pe_examples structure-check 16 80 40 1 true
/pe_examples structure-check 24 80 40 2 true
/pe_examples structure-check 32 80 40 3 true
/pe_examples structure-check 40 80 40 4 true
```

| 段数 | 排除控制器的材料总数 | 铁块 | 玻璃 | 红石块 | 金块 | 四阶仓室 | NORTH_UP时金盖Y |
|---|---:|---:|---:|---:|---:|---|---:|
| 1 | 22 | 5 | 4 | 1 | 9 | 输入/输出/FE输入各1 | 82 |
| 2 | 31 | 9 | 8 | 2 | 9 | 同上 | 83 |
| 3 | 40 | 13 | 12 | 3 | 9 | 同上 | 84 |
| 4 | 49 | 17 | 16 | 4 | 9 | 同上 | 85 |

搭建命令额外加控制器1。断言核对真实slice repetitions、材料位置总数、唯一cap及其实际origin；朝向改变时用真实orientation旋转期望位置。非扩展样例不接受repeat参数；0/5段搭建命令在放置前拒绝。

对1段样例实际运行：

```text
/pe_examples charge 16 80 41 10000
/pe_examples stock 15 80 40 pe_run_tests:expandable_glass
```

100个成功tick后沙子1→玻璃4，FE消耗400，输出仓 `(17,80,40)`。其它段数保持同一测试配方数值，用来核对结构扩展没有偷偷改变配方。

负例：拆掉1段塔金盖的中心 `(16,82,43)`，`structure-check 16 80 40 1 false` 应MISMATCH，补回金块后 `... 1 true`；把4段样例的金盖改为第5段并把金盖移到Y86，`structure-check 40 80 40 5 false` 应MISMATCH，不能截成4段成功。把body全部去掉、盖移到第1段位置后，`... 0 false`验证min=1；这些破坏/移动由玩家实际操作，断言命令不改世界。

## 预览、构造器与保存

普通JEI结构页默认采用声明最小段数1，这是预览参数，不是采样附近塔的实际段数。独立客户端命令可给同一个生产预览panel明确段数和朝向：

```text
/pe_structure_example nested_structure
/pe_structure_example expandable_structure 1 NORTH_UP
/pe_structure_example expandable_structure 4 NORTH_UP
/pe_structure_example expandable_structure 3 EAST_UP
```

命令在聊天关闭后的下一tick打开，仍绑定原player/world/connection/container/window；不改方块、配方目录或全局默认值。固定面板应分别22、49、40材料，金盖随段数移动；分层浏览不改变完整BOM。它使用普通独立结构GUI，不伪称已替JEI选择了4段。

构造器绑定任意该塔后，右树展开body，拖动“重复次数”滑块1→4。右树、左侧实际预览、用料22→49、金盖位置及投影应一起变化；变化通过准确工具窗口发送到服务器，按当前occurrence和min/max验证后写入工具NBT。关闭重开和真实退出重进仍应为4，换绑另一控制器时清除此实例的段数选择。开新空地手工放该控制器，设为3段后启动构建，应实际构建40材料而不是默认为1段的22；自动构建按既有规则消耗实际背包材料，创造/生存行为也按既有规则。

保存本次命令、坐标、段数、日志中的PASS/FAIL和截图。源码验证不替代World匹配、运行、构造器库存支付或退出重进的原生验收。
