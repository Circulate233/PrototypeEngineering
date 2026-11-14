# 网络设备事件

所有回调在服务器所属线程执行。Java用 `NetworkDeviceEvents.register(callback)` 注册全局监听，用 `MachineType.Builder.networkEvent(callback)` 注册某类控制器监听。默认网络中心、计算机、研究站、数据库及接网普通控制器均经过此入口。继承控制器时可重写 `MachineInstance.onNetworkEvent(event)`；需要保留类型声明的监听器时必须调用 `super.onNetworkEvent(event)`。

`NetworkDeviceEvent` 包含 `machine`、`network`、`values`。网络发布先捕获同队全部已加载、身份有效的设备，再运行全局监听和控制器监听，之后选择中心、检查并发并结算费用。计算机回调在准备组件供给时执行，可以修改真实组件基础值。一次tick内可能重复发布，回调必须只调整当前候选值，禁止直接扣材料、修改其他设备或累加外部持久状态；否则重算会重复执行这些副作用。

`NetworkData.devices()` 包含位置、维度、机器类型、所有者、设备角色、等级、成型状态及捕获的供给数据，也包括本次未成型或尚未获准的同队设备。`hasDevice(typeId)` 查找其中成型的指定类型。快照表示本次事件和费用结算之前的数据，计算机供给可能是上次提交值，不表示本次最终准入结果。`contributions()` 提供同队外部参与者的捕获数据。列表及快照只读，禁止跨发布周期保存或修改。

`NetworkResourceState` 的字段：

| 字段 | 用途 |
| --- | --- |
| `resources` | 本设备候选资源供给，计算机以安装组件为基础；内部类型只在本机使用 |
| `required` | 计算机组件本次所需资源，可调整冷却等需求 |
| `concurrency` | 本设备候选并发额度，计算机仍受自身上限限制 |
| `researchRates` | 研究站各资源推进速率 |
| `energyMultiplier` | FE输入费用倍率，默认1，折算后向上取整 |

资源表按对象身份处理，必须复用资源单例，累加必须使用R2D Map的 `addTo`。事件结束后数量和倍率必须非负、有限；禁止保留可写数据并在回调结束后继续修改。组件资源不足时按计算机原规则停止供给，事件调整不会跳过并发准入和组件费用检查。活动数据库、研究站及普通设备的资源和并发也计入网络总量；内部资源不会公开到网络。

FE倍率应用于中心生效费用、研究站实际推进费用、计算机组件费用以及普通配方的FE输入，覆盖同步与计划执行。FE输出不乘此倍率。默认数据库没有FE费用，需要自定义费用时由扩展实现。

```java
var efficiencyModule = new ResourceLocation("example", "efficiency_module");
NetworkDeviceEvents.register(event -> {
    if (event.network().hasDevice(efficiencyModule)) {
        event.values().energyMultiplier *= 0.9;
    }
});
```

注册时机为机器定义阶段。默认实现没有提供固定的节能设备或默认资源。

纯输入配方的 `ComputerRecipeEvent` 另含 `computer`、`process`、`stage`，同样提供 `network` 和 `values`。它直接修改计算机候选资源，输入仍由普通配方事务支付。每个工作tick重建基础值，START、TICK、END成功阶段依次修改；失败只撤回本阶段修改。结果供下一服务器tick结算使用，不保存为永久加成，不提供默认产出方法。需要连续效果时在TICK回调修改，开始或完成效果在对应阶段修改。

```java
var input = RecipeRequirements.RESOURCES.requirement(id("water"),
    new ResourceRequirement(List.of(new GenericStack(water, 10)),
        List.of(new GenericStack(water, 1)), List.of(), List.of(), false));
var finish = ComputerInputRecipe.endInput(RecipeRequirements.RESOURCES.requirement(id("finish"),
    new ResourceRequirement(List.of(new GenericStack(water, 5)), List.of())));
var recipe = new ComputerInputRecipe(id("maintenance"), "v1", 20, List.of(input, finish), event -> {
    if (event.stage() == RecipeStage.TICK) {
        var demand = event.values().required;
        demand.put(cooling, demand.getDouble(cooling) * 0.9);
    }
});
var type = MachineType.builder(id("computer"), title, shape)
    .component(ComputerController.COMPONENTS).component(ComputerController.DEVICE)
    .computerInputRecipe(recipe).build();
```

`MachineAssembly` 自动绑定纯输入进程的保存组件，进度和阶段状态使用 `RecipeProcessNbt`。增删或重排定义、修改输入或回调时必须更新装配和配方revision。Java自定义属性或进程组件通过 `ComputerInputRecipe` 最后一个codec配置回调声明；未绑定的自定义保存数据会按原保存规则报错。

ZS入口：

```zenscript
import mods.prototype_eng.network.Events;
import mods.prototype_eng.network.DeviceEvent;
import mods.prototype_eng.network.ComputerRecipeEvent;
import mods.prototype_eng.resource.ComputerInputRecipe;

Events.onDevice(function(event as DeviceEvent) {
    if (event.network.hasDevice("example:efficiency_module"))
        event.values.energyMultiplier = event.values.energyMultiplier * 0.9;
});
```

类型监听使用 `.networkEvent(function(event as DeviceEvent) { ... })`，纯输入定义使用 `ComputerInputRecipe.create(id, revision, duration, Requirement[], callback)`，结束输入用静态 `ComputerInputRecipe.endInput(requirement)`。`event.stage` 为START/TICK/END字符串，`progress`和`duration`来自普通配方进程。资源表支持 `get/set/add/remove`，其中 `add` 调用底层 `addTo`；并发、FE倍率可直接赋值。网络数据提供 `teamId`、`tick`、`devices`与 `hasDevice(typeId)`；每个设备可读 `type/id/owner/role/level/formed/dimension`。

完整可编译示例见 `run/visual-examples/src/main/java/pe/visual/examples/ComputerExamples.java` 和 `run/scripts/16_pe_run_network_demo_machines.zs`。
