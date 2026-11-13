# 计算机与运算仓

2026-11-13。本轮提供默认计算机控制器、运算仓及其 GUI。计算机 GUI 仍延缓；未新增数据库控制器或研究界面。运算仓纹理按用户授权直接沿用现有风格绘制，无 HTML 阶段。

## 计算机接口与装配

`api.network.NetworkComputer` 只有 `providedConcurrency()` 和 `providedResources()` 两个方法。前者为独立整数并发；后者为 `Reference2DoubleMap<NetworkResourceType>` 外部资源表，内部资源不进入网络。

`ComputerController` 直接继承 `MachineInstance`，组合已有组件读取、资源需求事务和网络发布服务；不创建普通配方工位、不注册独立业务 tick，不收取控制器自身 FE。它与 `NetworkCenterController`、`ResearchStationController` 均可继承。

Java 类型声明 `MachineOwnership.TYPE`、`ComputerController.COMPONENTS`、`ComputerController.DEVICE`；装配使用：

```java
MachineAssembly.builder(type, "computer-v1")
    .instanceFactory(value -> new ComputerController(value, maximumThreads, resourceLimits, recipes))
    .recreated(ComputerController.COMPONENTS, ComputerController.DEVICE)
    .build();
```

线程上限为非负 long，资源上限为 `Reference2DoubleMap<NetworkResourceType>`，没有列出的资源不增加控制器限额。`new ComputerController(type)` 使用 `Long.MAX_VALUE` 线程上限、无额外资源上限、无供给配方。上限属于具体控制器，不恢复已删除的资源类型固有限额。`installedComponents()` 直接提供运算仓中各组件的物品身份、数量及捕获定义。

## 运算仓

| 方块 | 槽位 | 每槽物品 | 每槽非物品原生容量 |
| --- | ---: | ---: | ---: |
| `computation_hatch_1` | 1 | 1 | 1000 |
| `computation_hatch_2` | 2 | 1 | 4000 |
| `computation_hatch_3` | 3 | 1 | 16000 |
| `computation_hatch_4` | 4 | 1 | 64000 |

注册命名空间为 `prototype_eng`。物品的 `Item` 必须实现 `NetworkComputerComponent`；非物品接受所有已注册 AEKey 类型。物品栏、流体、AE MEStorage 和其他原生 AE 适配器共用同一物理库存，允许外部插入、抽取；同一槽不会因输入/输出视图重复出现。运算仓没有自动输入、自动输出开关，也不主动搬运。GUI 复用普通资源仓交互和同步，仅改为一行紧凑布局与独立底图。

`ComputationHatch` 是公开仓室契约，默认存储为 `BufferedComputationHatch`。组成变化通过监听器通知；`MachineComputerComponents` 只从这种仓中识别组件，结构重新绑定时更新订阅，库存组成未变时复用捕获结果。物品替换、数量或 NBT 改变才通知组件组合重建，水等非物品输入及预留变化不触发组件重新发现。不存在按间隔遍历普通输入、输出仓的默认扫描。独立动态组件定义改变时可在拥有者边界调用 `invalidateSupply()`；读取与监听生命周期契约见相应 Javadoc。

等级与内容由运算仓 TE 保存和恢复，不在 onLoad 反查方块补配置。破坏使用现有来源预留和延迟掉落机制，不复制仍有补偿义务的库存。

2026-11-14 运算仓名称、GUI标题与MEStorage描述统一使用带色等级方块名称 `[Computation-LvN]-运算仓`。共享资源仓标题居左，运算仓资源槽从标准x26/y24开始，紧凑195×132尺寸保持。

## 组件、配方与内部资源

组件物品实现 `NetworkComputerComponent.capture(ItemStack, MachineInstance)`，返回：

```java
new NetworkComputerComponent.Capture(concurrencyPerItem, provided, requiredResources, energyPerTick, currentProof);
```

provided为每件的double资源供给，concurrencyPerItem为独立整数并发；requiredResources是每件每tick占用的本机资源，energyPerTick是FE。定义与证明按只读契约交接，组件全部消耗必须满足；任一种资源或FE不足，全部组件供给停摆，不扣组件FE，能运行的配方独立继续。

内部资源使用 `NetworkResourceType.computer(id, name, pattern)` 或自定义资源接口，不需要资源注册。只在本机产生和占用，未用额度不跨tick累积。固定展示包、客户端格式化与增量协议见[网络资源](network-resources.md)。

`ComputerRecipe(id, inputsPerTick, provided)` 注册一个每 tick 的资源供给配方，inputsPerTick 是 `List<GenericStack>`。它调用现有资源需求的 TICK 事务，一次成功提交支付一份输入；不使用普通加工配方的 START/END 或持久进度。带 `Predicate<MachineInstance>` 的构造器增加只读资格条件。缺料或条件不满足只令该配方不提供资源，不关闭其他已能运行的配方。

每 tick 先准备各配方输入，再合并配方和组件供给并应用本机上限，检查全部组件所需的本机资源与 FE。组件成功则扣去其占用；失败则保留配方供给。最终只输出外部资源。材料、组件 FE 和提供结果共同提交；没有取得并发准入时释放预留，不先付费。同一加载代、同一服务器 tick 的重复发布不重复支付。组成变化会更新供给，已支付配方保留当 tick 结果，新增组件从下一 tick 开始支付与提供。

## 并发优先级

网络中心不占用并发。计算机启动前必须由中心、外部来源或已经获准运行的其他计算机提供至少1份并发，不能用自己的待生效供给启动。获准后才把自身供给加入可分配额度。活动数据库固定占用1份；研究站仅在研究推进时申请。

计算机、研究站、活动数据库的标准 并发 租约优先于普通设备。普通设备先加载、先取得额度时，高优先级申请可收回其租约，尚未提交的普通工作凭据随之失效。历史已提交结果不重放。中心按等级、数据中心按等级/研究量优先；条件相同用持久机器 UUID 排序，计算机准备与同条件准入也不依赖本次加载顺序。新增供给立即进入当前 tick 可分配额度；供给降低会收紧上限，下降再恢复仍保留已消费量，补偿按原账本结算。

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
    [Resource.fluid(<liquid:water>, 1000)], ComputingResources.create().add(cooling, 1000));
MachineType.computer("example:computer", "example.computer", "example:computer_shape", 8,
    ComputingResources.create().add(compute, 1000), [coolingRecipe])
    .register("computer-v1");
```

机器声明放在preinit；资源无需注册，同一资源在所有定义中复用同一对象。跨loader通过Bindings或Java包装器共享单例。组件Item实现来自Java。

完整 Java 示例为 `run/visual-examples/src/main/java/pe/visual/examples/ComputerExamples.java`：`java_computer` 左侧一个四级运算仓、后方能源输入仓；`computer_card` 提供8 并发/1000 COMPUTE，需要1000内部冷却和32 FE。每 tick 1000 mB 水产生1000冷却；控制器上限8 并发/1000 COMPUTE。ZS 的 `net_computer` 使用相同结构及 `net_computer_card`，提供 run 的 net_compute 资源。放入组件后经外部管道或手动填水、向能源仓供能；中心必须先提供启动并发。计算机 GUI 未实现。
