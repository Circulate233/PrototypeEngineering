# 计算机与运算仓

2026-11-14。默认计算机控制器、运算仓及计算机GUI已实现。计算机GUI保留概览与组件两页，配方展示统一使用JEI；运算仓纹理沿现有风格绘制。

## 计算机接口与装配

`api.network.NetworkComputer` 只有 `providedConcurrency()` 和 `providedResources()` 两个方法。前者为独立整数并发；后者为 `Reference2DoubleMap<NetworkResourceType>` 外部资源表，内部资源不进入网络。

`ComputerController` 直接继承 `MachineInstance`，组合已有组件读取、资源需求事务和网络发布服务；不创建普通配方工位、不注册独立业务 tick，不收取控制器自身 FE。它与 `NetworkCenterController`、`ResearchStationController` 均可继承。

Java 类型声明 `MachineOwnership.TYPE`、`ComputerController.COMPONENTS`、`ComputerController.DEVICE`；装配使用：

```java
MachineAssembly.builder(type, "computer-v1")
    .instanceFactory(value -> new ComputerController(value, maximumThreads, resourceLimits, recipes))
    .computerRecipes(recipes)
    .recreated(ComputerController.COMPONENTS, ComputerController.DEVICE)
    .build();
```

线程上限为非负 long，资源上限为 `Reference2DoubleMap<NetworkResourceType>`，没有列出的资源不增加控制器限额。`new ComputerController(type)` 使用 `Long.MAX_VALUE` 线程上限、无额外资源上限、无供给配方。上限属于具体控制器，不恢复已删除的资源类型固有限额。`installedComponents()` 直接提供运算仓中各组件的物品身份、数量及捕获定义。

2026-11-14 计算机供给配方接入 JEI。必须将控制器使用的同一份完整列表传给装配的 `computerRecipes(recipes)`，并在装配整个生命周期内保持列表不变；ZS 的 `MachineType.computer(...)` 自动保存声明。每台有供给配方的计算机获得独立分类与控制器入口，复用原材料布局，转换材料标注“每份”，每种资源仅显示“每份资源名：数量”，不绘制箭头；纯输入配方显示普通时长及阶段输入。名称与 double 数量由资源的 `displayName()`、`format(double)` 生成。JEI 直接读取装配声明，不创建机器实例或执行资格条件。计算资源仅作为说明文字，不作为 AE 材料。

## 运算仓

| 方块 | 槽位 | 每槽物品 | 每槽非物品原生容量 |
| --- | ---: | ---: | ---: |
| `computation_hatch_1` | 1 | 1 | 1000 |
| `computation_hatch_2` | 2 | 1 | 4000 |
| `computation_hatch_3` | 3 | 1 | 16000 |
| `computation_hatch_4` | 4 | 1 | 64000 |

注册命名空间为 `prototype_eng`。物品的 `Item` 必须实现 `NetworkComputerComponent`；非物品接受所有已注册 AEKey 类型。物品栏、流体、AE MEStorage 和其他原生 AE 适配器共用同一物理库存，允许外部插入、抽取；同一槽不会因输入/输出视图重复出现。运算仓没有自动输入、自动输出开关，也不主动搬运。GUI 复用普通资源仓交互和同步，仅改为一行紧凑布局与独立底图。

`ComputationHatch` 是公开仓室调用规则，默认存储为 `BufferedComputationHatch`。组成变化通过监听器通知；`MachineComputerComponents` 只从这种仓中识别组件，结构重新绑定时更新订阅，库存组成未变时复用捕获结果。物品替换、数量或 NBT 改变才通知组件组合重建，水等非物品输入及预留变化不触发组件重新发现。不存在按间隔遍历普通输入、输出仓的默认扫描。独立动态组件定义改变时可在拥有者边界调用 `invalidateSupply()`；读取与监听生命周期调用规则见相应 Javadoc。

等级与内容由运算仓 TE 保存和恢复，不在 onLoad 反查方块补配置。破坏使用现有来源预留和延迟掉落机制，不复制仍有补偿义务的库存。

2026-11-14 运算仓名称、GUI标题与MEStorage描述统一使用带色等级方块名称 `[Computation-LvN]-运算仓`。共享资源仓标题居左，运算仓资源槽从标准x26/y24开始，紧凑195×132尺寸保持。

## 组件、配方与内部资源

组件物品实现 `NetworkComputerComponent.capture(ItemStack, MachineInstance)`，返回：

```java
new NetworkComputerComponent.Capture(concurrencyPerItem, provided, requiredResources, energyPerTick, currentProof);
```

provided为每件的double资源供给，concurrencyPerItem为独立整数并发；requiredResources是每件每tick占用的本机资源，energyPerTick是FE。定义与证明按只读调用规则交接，组件全部消耗必须满足；任一种资源或FE不足，全部组件供给停摆，不扣组件FE，能运行的配方独立继续。

内部资源使用 `NetworkResourceType.computer(id, name, pattern)` 或自定义资源接口，不需要资源注册。只在本机产生和占用，未用额度不跨tick累积。固定展示包、客户端格式化与增量协议见[网络资源](network-resources.md)。

`ComputerRecipe(id, unitInputs, unitResources)` 定义一份材料的换算。组件每次结算时按资源缺口取 `ceil(缺口 / 单位产出)`，只执行所需份数。例如1 mB水换1冷却，2500冷却需要2500 mB；3 mB水换3冷却则需要2502 mB。多条转换按声明顺序补足剩余缺口，无缺口时不取料。多种产出取各缺口所需份数的最大值。

材料不足时尽量消耗已有的完整份数，即使最终组件仍不能工作，也提交这些材料。多输入的一份必须全部凑齐；不足一份的余料视为不存在，不消耗、不按比例产出。转换产出仅供本机组件消耗，剩余量不进入网络、不跨tick保存；资源类型即使声明为外部资源，也不能通过转换直接对外供给。资格条件不满足时不执行该转换。

每tick先取得组件及事件调整后的资源、并发和需求，再应用控制器上限并按需转换材料。全部组件需求和FE满足时输出组件的剩余外部资源；不足时全部组件供给归零，组件FE不扣。材料预留、组件FE和结果共同提交；没有取得并发准入时全部释放。同一服务器tick的重复网络发布不重复支付。组件组成变化通过既有监听器重建。

`ComputerInputRecipe(id, revision, duration, inputs, callback)` 声明另一种纯输入配方，使用普通 `RecipeProcess` 的时长、START/TICK/END阶段、等待和保存机制。通过 `MachineType.Builder.computerInputRecipe(...)` 添加，ZS同名方法。START和TICK使用普通资源或FE需求，END一次输入用 `ComputerInputRecipe.endInput(requirement)`，传入只声明START支付的普通需求。普通模糊材料、来源分配及自定义输入系统沿原API使用；自定义需求必须只产生输入效果。

每个定义在本机运行一个进程，共用计算机已取得的1份并发。有纯输入配方时控制器才执行所属线程tick。事件携带计算机、进程、阶段、同队网络快照和可写资源值，没有固定输出字段。阶段失败时撤回本阶段的资源修改；此前成功阶段和已支付START不会重付。工作tick先从安装组件恢复基础值，阶段回调的资源修改用于下一服务器tick的供给结算，未继续工作时不重复沿用旧效果。组件停供不会取消纯输入进程。

事件、能耗调整与Java/ZS示例见[网络设备事件](network-events.md)。

## 并发优先级

网络中心不占用并发。计算机启动前必须由中心、外部来源或已经获准运行的其他计算机提供至少1份并发，不能用自己的待生效供给启动。获准后才把自身供给加入可分配额度。活动数据库固定占用1份；研究站仅在研究推进时申请。

计算机和活动数据库的标准并发租约优先于普通设备，研究站在普通设备结束后使用剩余并发。普通设备先加载、先取得额度时，高优先级申请可收回其租约，尚未提交的普通工作凭据随之失效。历史已提交结果不重放。中心按等级、数据中心按等级/研究量优先；条件相同用持久机器 UUID 排序，计算机准备与同条件准入也不依赖本次加载顺序。新增供给立即进入当前 tick 可分配额度；供给降低会收紧上限，下降再恢复仍保留已消费量，补偿按原账本结算。

## ZS 与示例

```zenscript
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.resource.ComputerRecipe;
import mods.prototype_eng.resource.ComputingResource;
import mods.prototype_eng.resource.ComputingResources;

var compute = ComputingResource.network("example:compute", "example.resource.compute", "%.2f FLOP/t");
var cooling = ComputingResource.computer("example:cooling", "example.resource.cooling", "%.1f CU/t");
var coolingRecipe = ComputerRecipe.create("example:cooling_recipe",
    [Resource.fluid(<liquid:water>, 1)], ComputingResources.create().add(cooling, 1));
MachineType.computer("example:computer", "example.computer", "example:computer_shape", 8,
    ComputingResources.create().add(compute, 1000), [coolingRecipe])
    .register("computer-v1");
```

机器声明放在preinit；资源无需注册，同一资源在所有定义中复用同一对象。跨loader通过Bindings或Java包装器共享单例。组件Item实现来自Java。

完整 Java 示例为 `run/visual-examples/src/main/java/pe/visual/examples/ComputerExamples.java`：`java_computer` 左侧一个四级运算仓、后方能源输入仓；`computer_card` 提供8 并发/1000 COMPUTE，需要1000内部冷却和32 FE。每1 mB水换1冷却，按缺口消耗；控制器上限8 并发/1000 COMPUTE。ZS 的 `net_computer` 使用相同结构及 `net_computer_card`，提供 run 的 net_compute 资源。放入组件后经外部管道或手动填水、向能源仓供能；中心必须先提供启动并发。

## 计算机界面

2026-11-15 界面改版：预览已覆盖为新设计，正式 Java 界面待按 [设备界面改版](device-gui-redesign.md) 实现；本节以下描述为当前已实现的旧布局，与改版说明冲突时以改版说明为准。

默认ComputerController.DEVICE自动使用384×242的无背包界面。AE2Button切换概览与组件，两枚按钮等宽。概览展示已结算的并发、资源需求、FE实耗及外部供给；组件页选择查看每件需求和供给。配方列表、输入和转换产出统一由JEI展示。概览两栏等宽，资源名居左、数值居右，FE行紧跟需求资源。超出可见行使用AE滚动条，悬停查看完整内容。

2026-11-15 修正文字位置：状态及结构成型提示使用同一行高和纵向位置；未成型原因只显示一次。组件数字及对应列标题右对齐，空组件提示在表格内居中，不绘制选中行。并发只显示当前实际供给，不再使用实际供给/配置上限的格式；组件缺料、缺电或未取得网络许可时显示 0。控制器的并发上限继续参与后台计算，界面不再同步该配置值。

服务端在网络工作完成后的所属线程边界采集。组件固定定义随组成变化发送，资源数量沿原double增量包；不再收集或同步GUI配方列表、状态与进度。界面只读实际事务结果，禁止为显示重新执行配方条件、预留材料或消耗FE。

Java自定义容器与screen继续使用MachineGuiRegistry和MachineScreenRegistry。NetworkComputer接口仍只暴露并发和外部资源。默认控制器显示读取方法必须在所属线程边界调用；返回的声明列表与资源Map必须按只读方式使用。
