---
title: Java 与 ZenScript API
---

## 源码布局与使用环境

Prototype Engineering 为模组和整合包提供机器、结构、配方、仓室、研究和计算网络。具体机器及其配方由使用者声明。运行环境为 Minecraft 1.12.2、Cleanroom、Java 25 和 AE2 Supergiant；使用 ZenScript 时还需要 CraftTweaker 2。

Java 公开 API 位于 `src/main/java/com/circulation/prototype_eng/api`。ZS 使用 `mods.prototype_eng` 命名空间，入口实现在 `integration/crafttweaker` 中。本文的 Java 和 ZS 部分都先介绍如何调用现有功能，再介绍如何添加自己的行为。[ZS 使用参考](api-zs-reference.md)按功能说明脚本方法及其参数。

机器、配方、结构等对象使用 `模组ID:名称` 形式的 ID。显示名称使用语言文件中的翻译键，不能用中文名称代替 ID。Java 的固定翻译键必须由实现 `LocalizationEnum` 的枚举管理。客户端用 `getLocal()` 取得译文，服务端使用可翻译文本。

物品、流体以及 addon 资源都用 AE2 的 `AEKey` 表示种类，用 `GenericStack` 携带数量。多个资源的数量表使用 `AEKey2LongMap`，累加和扣减优先使用 `addTo`。带原始类型的集合字段、参数和返回值必须使用 fastutil 专用接口，例如 `Reference2LongMap<AEKeyType>` 和 `IntList`；只有纯对象集合可以用标准 JDK 集合接口对外暴露。并发、位集合等特殊语义可以使用相应实现，必须说明实际需要的语义。

## Java：调用已有功能

### 初始化与注册

Java addon 通过 Forge 事件提交定义。库在对应事件结束后检查并冻结注册结果，因此必须在该事件中或事件派发前完成声明。

| 注册事件                                    | 用途                                               |
| ------------------------------------------- | -------------------------------------------------- |
| `MachineTypeRegistrationEvent`              | 声明机器类型，库随后为这些类型创建控制器方块。     |
| `NetworkResourceRegistrationEvent`          | 添加计算网络使用的资源种类。                       |
| `NetworkComputerComponentRegistrationEvent` | 声明哪些物品提供计算资源。                         |
| `ResearchRegistrationEvent`                 | 添加研究及等级要求。                               |
| `ResearchItemDownloadRegistrationEvent`     | 设置研究下载所用材料和生成物品的函数。             |
| `ResearchServerConfigurationEvent`          | 设置研究操作的权限规则。                           |
| `RecipeRegistrationEvent`                   | 注册配方池、配方及保存版本。                       |
| `RecipeDeclarationRegistrationEvent`        | 给整套配方目录声明身份，用于客户端校验和目录替换。 |
| `StructurePredicateRegistrationEvent`       | 添加结构 JSON 可以引用的自定义条件。               |
| `MachineRuntimeRegistrationEvent`           | 提交 Java 结构及机器组件的创建、保存方式。         |

也可以调用 `DefinitionRegistrations.machines(...)`、`recipes(...)` 或 `runtime(...)`，预先登记在对应事件执行的函数。下面的方法把配方注册安排到配方事件中，调用它时不要求配方事件已经开始：

```java
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import net.minecraft.util.ResourceLocation;

public final class ExampleRecipeRegistration {
    public static void declare(ResourceLocation poolId, LocalizationEnum title, MachineRecipe recipe) {
        DefinitionRegistrations.recipes(event -> {
            event.registry().registerPool(poolId, title.getTranslationKey());
            event.registry().registerRecipe(poolId, recipe);
        });
    }
}
```

`dispatch(...)` 由库的加载流程调用。addon 必须通过事件监听器或上述登记方法提交定义，禁止自行调用 `dispatch`；注册阶段重复派发或关闭后再登记会抛出 `IllegalStateException`。

### 创建能运行配方的机器

`MachineType.builder(id, displayName, structureId)` 描述一种机器：它叫什么、匹配哪个结构、能够使用哪些配方池、安装哪些组件。`build()` 产生不可变定义；`event.registry().register(type)` 让库注册该类型。

机器类型常用的设置如下：

| 设置                       | 对机器的作用                                           |
| -------------------------- | ------------------------------------------------------ |
| `recipePool(poolId)`       | 添加扫描范围，机器只从所选池中寻找配方。               |
| `recipeGroup(groupId)`     | 在所选池中进一步按配方组筛选。                         |
| `component(type)`          | 声明每个机器实例都需要的组件。                         |
| `workSlot(definition)`     | 添加一个逻辑工位，多线程控制器可分别给各工位安排配方。 |
| `attribute(id, base)`      | 设置并行数、速度等属性的初始值。                       |
| `executionPolicy(policy)`  | 选择配方扫描和工作回调能否异步执行。                   |
| `requiresNetwork(boolean)` | 显式设置普通控制器是否必须接入逻辑队伍网络才能执行配方。 |
| `controllerModel(modelId)` | 选择控制器使用的模型声明。                             |

默认控制器左下角提供16个物品格。`controllerInventorySlots(int)` 设置开放数量，默认0；`controllerInventorySlots(ToIntFunction<MachineInstance>)` 按当前机器实例计算数量。配置动态函数后优先使用其返回值；没有函数、函数被设为null、机器尚未装配或已注销时使用固定值。返回值必须在0到16之间，越界会抛出 `IllegalArgumentException`，不会自动裁剪。动态函数在服务端工作线程结束后的边界执行，必须只读取状态，禁止在函数内修改机器、库存或世界。

`machine.controllerInventory()` 返回物理控制器的原生 `InternalInventory`，可用 `getStackInSlot(slot)` 按索引读取，用原生迭代器遍历。默认宿主的具体实现为 `ControllerInventory`，`availableSlots()` 返回当前开放数，`forEachAvailable((stack, slot) -> ...)` 遍历开放格，`forEachStored(...)` 只遍历实际有物品的格子，包括临时关闭的格子。`MachineControllerHost.controllerStack(slot)` 和 `forEachControllerStack(...)` 供自定义控制器使用。必须在服务端拥有机器执行权时读取这些物品，禁止从配方工作线程直接访问物品堆栈；需要异步计算时，先在服务端采集所需的只读事实。

格子从左到右、从上到下开放。未开放格子覆盖半透明白色，普通点击、拖动、双击、数字键及Shift移动都必须通过相同的服务端开放状态检查。关闭格子保留其中物品，重新开放后恢复访问；全部物品随控制器保存，拆除控制器时参与正常物品掉落。

`MachineAssembly.builder(type, revision)` 规定怎样创建和恢复这个类型的实际机器。`instanceFactory(MultithreadController::new)` 创建多线程控制器，`instanceFactory(CrossRecipeController::new)` 创建跨配方并行控制器。`component(type, codec)` 为有状态组件绑定保存方式，`recreated(type)` 则在恢复时重新创建不需要保存的组件。codec 是把组件写入 NBT、检查 NBT 并恢复组件的对象。

配方机器需要工作槽、扫描器和工作槽保存配置。先在类型中添加 `MachineWorkSlots.TYPE`，再添加 `RecipeScanning.component(...)` 创建的扫描组件；随后在装配中调用 `workSlots(...)`，让存档能够恢复运行中的配方。`RecipeScanning.component` 的 `ScanLimits` 限制一次扫描处理的候选数和尝试次数，用于把较大的扫描分到多次执行。

下面的两个方法分别在机器类型事件和运行装配事件中调用，向它们传入同一个 scanner 和 type。scanner 由 `RecipeScanning.component(...)` 创建，recipeCodec 由配方注册方法创建。包含字段、事件监听器和结构声明的完整版本见 [ExampleMachines.java](docs/api-examples/java/ExampleMachines.java)。

```java
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.*;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import it.unimi.dsi.fastutil.objects.ObjectList;
import net.minecraft.util.ResourceLocation;

public final class MachineRegistration {
    public static MachineType declare(MachineTypeRegistrationEvent event, ResourceLocation id,
                                      LocalizationEnum name, ResourceLocation shapeId,
                                      ResourceLocation poolId,
                                      ComponentType<MachineInstance, RecipeScanner> scanner) {
        var type = MachineType.builder(id, name, shapeId)
            .recipePool(poolId)
            .component(MachineWorkSlots.TYPE)
            .component(scanner)
            .attribute(StandardMachineAttributes.PROCESS_PARALLELISM, 4)
            .build();
        event.registry().register(type);
        return type;
    }

    public static void runtime(MachineRuntimeRegistrationEvent event, MachineType type,
                               ComponentType<MachineInstance, RecipeScanner> scanner,
                               RecipeProcessNbt recipeCodec) {
        var assembly = MachineAssembly.builder(type, "machine-v1")
            .instanceFactory(MultithreadController::new)
            .workSlots(new MachineWorkSlotsNbt("slots-v1", ObjectList.of(recipeCodec)))
            .recreated(scanner)
            .build();
        event.assembly(assembly);
    }
}
```

类型注册时库据此创建控制器，装配注册后控制器才能创建机器实例和恢复其组件。装配必须使用注册时的同一个 `MachineType` 对象；重新构造相同 ID 的类型不能代替它。

### 控制器是否需要网络

普通控制器的共同基类 `MachineInstance` 负责网络门限，多线程和跨配方控制器继承相同规则。配置文件 `config/prototype_eng/controllers.cfg` 在启动时读取：

```properties
network {
    B:defaultRequiresNetwork=false
}
```

未显式设置的普通控制器默认不需要网络；改成 `true` 后要求逻辑 FTB 队伍网络。Java 的 `MachineType.Builder.requiresNetwork(true/false)` 和 ZS 的 `MachineType.requiresNetwork(true/false)` 优先于该默认值。显式安装 `MachineNetworkDevice` 角色本身也表示选择接网；普通控制器显式 `false` 可关闭共同配方门限，但不会取消额外声明的 `NetworkRecipeWork` 研究或资源需求。

需要网络且未自定义角色时，装配自动添加等级 0、无资源供给的 `DEVICE` 及所有权组件，不必逐个控制器重复接线。缺少活动中心或失去许可时暂停配方，恢复后继续。网络中心和研究站保留专用角色，不受普通控制器默认开关控制。此处网络为逻辑队伍网络，不要求 ME 电缆或距离连通。

真实玩家新放置的物理控制器即使离线也保存所有者和机器 UUID，读档不改变所有者。此改动前完全没有所有权数据的旧离线存档无法推导原放置者，启用网络前需换用未携带旧机器存档的新控制器；不会在加载时凭空生成身份。自定义非方块宿主继续显式提供自己的所有权组件和网络运行时。没有机器类型的独立库执行对象不应用服务器默认值。

### 物料需求、能源与配方

一条 `MachineRecipe` 包含时长和需求列表。需求负责决定某个阶段需要消耗什么、产出什么，或者必须满足什么条件。`RecipeRequirements.RESOURCES.requirement(id, payload)` 把物料设置包装成配方需求，`ENERGY.requirement(...)` 则添加独立 FE 需求。

下面的数据表示每次配方开始消耗 2 个铁锭，完成时产出 1 个金锭，基础工作时间为 80 tick：

```java
var input = new GenericStack(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), 2);
var output = new GenericStack(AEItemKey.of(new ItemStack(Items.GOLD_INGOT)), 1);
var io = RecipeRequirements.RESOURCES.requirement(
    new ResourceLocation("example", "items"),
    new ResourceRequirement(ObjectList.of(input), ObjectList.of(output)));
var recipe = new MachineRecipe(
    new ResourceLocation("example", "iron_to_gold"), 80, ObjectList.of(io));
```

需要每 tick 消耗或产出时，使用完整的 `ResourceRequirement(inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs)`。四个列表依次表示开始消耗、每 tick 消耗、每 tick 产出和完成产出。`EnergyRequirement` 使用相同次序的四个 long 数值；例如 `new EnergyRequirement(0, 8, 0, 0, false)` 表示每个成功工作 tick 消耗 8 FE。

所有数量都按一次配方填写，执行时再乘最终并行数。`voidExcessOutputs` 允许丢弃输出仓装不下的余量，但仍需要有效输出目标。`FuzzyInputGroup` 允许在列出的资源中选料；`DynamicInputGroup` 根据匹配器在库存中找料。两者在开始阶段确定本次具体使用的资源。`ResourceChance` 设置概率，`RandomOutputPool` 则从带权重的候选输出中抽取产物。

### 配方池与库存匹配

配方池决定机器可以扫描哪些配方，也提供库存匹配策略。普通机器使用 `DefaultRecipePool` 即可。如果每条配方恰好只有一个固定的开始输入，并且只产生固定结束输出，可以使用 `SimpleRecipePool`；它按输入资源建立索引，输出数量不限。

`CompositeRecipePool` 把多个池合成一个入口，保留各子池的匹配方式。例如把简单池和 addon 提供的特殊池组合后，机器只需选择组合池。子池不会因为被组合而自动单独注册，是否需要独立 JEI 页面由作者决定。

`RecipePool.match(inventory)` 根据 `AEKey2LongMap` 库存快照返回第一个物料可用的候选，没有候选时返回 `null`。`match(inventory, parallelism)` 返回候选组，每个结果携带它的物料并行上限。两个方法都只提供物料判断，不扣料，也不检查 FE、研究权限或开始事件。

实际扫描器在最终开始前再次检查全部需求并预留材料。两台机器共用仓室时，都可能从同一快照中选中配方；后取得不到完整预留的进程会等待，不会重复扣除同一份材料。

### 并行数、工作速度与执行线程

`PROCESS_PARALLELISM` 控制一份配方同时处理多少份材料，`MAX_CONCURRENT_PROCESSES` 限制同时存在的进程数，`PROCESS_SPEED` 控制进度推进速度。增加并行属性不会自动增加逻辑工位；`MachineRecipe.maxConcurrentThreads` 还能限制同一条配方占用的进程数。

多线程控制器的各工位独立工作。配方完成后，该工位优先重新尝试刚完成的配方，失败后继续扫描。跨配方控制器把兼容配方组成一批，批次时长按 `ceil(Σ(原时长 × 最终并行数) / Σ最终并行数)` 计算，统一完成。只有单位并行的逐 tick 输入和输出都相同，配方才能合批；FE 按原总量分摊到共同批次时间。

新逻辑控制器默认使用 `FULL_ASYNC`。四种执行策略的区别如下：

| 策略          | 候选扫描 | 允许异步的配方工作 |
| ------------- | -------- | ------------------ |
| `FULL_ASYNC`  | 工作线程 | 工作线程           |
| `ASYNC_SCAN`  | 工作线程 | 主线程             |
| `ASYNC_WORK`  | 主线程   | 工作线程           |
| `SYNCHRONOUS` | 主线程   | 主线程             |

这里的主线程为服务器主线程。世界读取、最终准入、授权与保存仍在主线程处理；自定义需求可以通过 `requiresOwnerThread()` 要求其工作也留在主线程。`executionMode(...)` 配置的是机器调度方式，普通配方机器可保留默认值，通过 `executionPolicy(...)` 选择上表策略。

### 模板、子结构与可变长度结构

`StructurePattern` 保存相对坐标及该位置的方块条件。`StructurePredicates.state(...)` 要求精确方块状态，`blocks(...)` 接受给定方块集合，组合条件可表达同时满足或满足任一条件。

`StructureDefinitions.template(...)` 创建固定形状，`slice(...)` 创建可重复部分。两者接收结构 ID、基准朝向、相对偏移、预览设置、pattern、整体条件和子结构列表。切片另外接收最少/最多重复次数及每次重复的坐标步进。

`NORTH_UP` 下，X 正方向为右，Y 为上，Z 负方向为机器正前方。子结构偏移相对父结构计算。切片会重复自己的 pattern，子结构只连接到最后一次重复处，因此可以用一个切片表示机身，再用子结构表示随长度移动的顶盖。[Java 示例](docs/api-examples/java/ExampleMachines.java)展示了 1 至 4 层铁块和一次性金块顶盖。

通过 `event.definition(new StructureDefinition(shape, titleKey, displays))` 提交结构。明确方块条件能自动提供预览材料；自定义布尔条件需要通过 `withDisplay(...)` 或显式 displays 提供代表材料，否则匹配可以成功，预览和构造器仍无法知道该放什么。

### 并行控制器

内置五种并行控制器分别为 `parallel_controller_normal`、`reinforced`、`elite`、`super`、`ultimate`（后四项同样带 `parallel_controller_` 前缀），默认最大贡献为 4、16、64、256、512。配置文件 `config/prototype_eng/parallel_controllers.cfg` 的各等级 `maxParallelism` 控制最大值；右键 GUI 修改该方块当前贡献，范围为 0 到该级最大值。五级使用预先生成的独立纹理，未使用运行时着色。

结构内的并行贡献相加：`PROCESS_PARALLELISM + Σ控制器当前贡献`，再受 `StandardMachineAttributes.MAX_PROCESS_PARALLELISM`（`prototype_eng:max_process_parallelism`）限制；未声明最大值时为 `Integer.MAX_VALUE`。多线程与跨配方控制器用同一额度扣除当前占用，降低设置不取消已运行进程。新的扫描/准入会重新核对控制器版本和结构归属。没有控制器且没有声明新上限时保持原并行数。

Java 在 `ParallelControllerRegistrationEvent` 中向 `event.registry()` 注册 `ParallelControllerType(id, displayName, maximum, model)`；`displayName` 为 `LocalizationEnum`，`maximum` 必须为正数，`model` 为 blockstate/model 入口，可为 null。省略模型时使用保留的 PM 原始单套 `prototype_eng:parallelization_controller`。注册后库创建方块、物品与默认实体，重复 ID 或注册关闭后再登记会失败。

独立实现可以实现 `ParallelController`，通过 `snapshot()` 返回包含 `selected`、`maximum`、`revision`、`valid` 的不可变快照。设置或有效性改变时推进 revision；工作线程只能读取快照。宿主实现 `StructureComponentProvider`，返回 `new Entry<>(ParallelControllers.TYPE, "parallel", controller)` 后即可参与同一真实结构发现与并行汇总，不要求继承默认实体。若要复用右键配置界面，还需实现 `ParallelControllerGuiHost` 的宿主、名称与服务端设置职责。

### 仓室读写与容量

`ResourceHatch` 暴露输入和输出 `ResourceStorage`，`EnergyInput`、`EnergyOutput` 提供独立 FE 接口。结构匹配后发现的仓室保存在机器的 `MachineHatches` 组件中。

普通搬运可以调用 `ResourceStorage.insert/extract`，传 `Actionable.SIMULATE` 先计算可处理数量，传 `MODULATE` 实际修改。配方执行使用需求系统的快照和预留，以保证多个需求或多台机器共享仓室时不会重复占用。

内置输入仓、输出仓和混合仓都支持在 GUI 插入 AE 资源包，按包内资源的真实种类和数量存储。未能存入的数量保留在资源包中，已经预留的空间不可占用。这个手动解包操作不改变机器和自动化的输入、输出方向，操作规则见[仓室指南](docs/hatches.md#AE-资源包插入)。

`me_input_assembly`、`me_output_assembly`、`me_interface` 和 `me_pattern_assembly` 是通用 ME 资源组件，不按物品、流体或气体拆分。输入总成与接口的标记表示期望保有量，由 ME 补足；输入槽数在 `me_assemblies.cfg` 的 `resources.inputSlots` 配置，默认 18。输出按真实 `AEKey` 缓存动态显示，每种资源支持到 `Long.MAX_VALUE`，不设种类上限。ME 资源总成只通过 ME 和机器事务转移实际缓存。

所有资源仓室默认使用 AE2 福鲁伊克斯透明色（无色），并以无色输入形成独立库存；任一有色输入组也能通用读取这份库存，但不会读取其他不同颜色库存。开启样板槽隔离后，不再使用总成颜色：每个槽单独扫描，只接入自身输入、该总成共享催化剂及其他未分区无色库存，不与其他槽或有色普通仓拼料。未开启样板隔离时才按总成颜色分组。FE 仓室不分组；已运行进程保留选中的组。样板每页固定 36 槽，页数由 `patterns.pages` 配置；五种工作模式及实际 GUI 操作见[方块与 ME 总成](docs/block-registration.md)。

`ResourceHatchConfiguration.Side.fromItemEquivalent(...)` 以槽数和单槽物品等价值配置容量；流体默认每 1000 mB 对应一个物品单位。需要直接给每类资源设置原生容量时，向构造器传 `Reference2LongMap<AEKeyType>`。混合仓输入和输出分别配置。

`HatchConfigurationUpdates.configureDefaults(...)` 设置启动时的默认容量。运行中修改用 `request(server, targets)`，返回完成报告的 future。报告还包含各仓室的状态，忙碌或缩容后装不下的仓室可能暂缓应用。必须异步处理结果，禁止在服务器主线程阻塞等待。

### 研究与计算网络

`ResearchDefinition.builder(id)` 创建研究。`ResearchLevelStage.builder(first, last)` 为一段等级设置研究阶数、资源公式、前置、互斥、材料和奖励，多个阶段必须完整覆盖 `1..maxLevel`。阶数为 0 时使用玩家手动投料，正阶使用研究站。

`ResearchResourceFormula.builder().constant(type, demand)` 定义固定总需求；同一 builder 的 `.affine(type, base, slope)` 按阶段内的等级偏移计算总需求：`base + slope × (当前等级 - 阶段首级)`，最后调用 `.build()`。研究公式不设置单站速率，旧 `segmentGrantLimit` 参数已移除。`DATABASE_AND_ITEM` 允许个人研究写入数据库或下载成物品；`ITEM_ONLY` 的普通完成量归队伍并通过物品下载交付。奖励组可以含物品或自定义事件。

计算网络资源通过 `NetworkResourceRegistrationEvent` 注册，提供该资源的物品通过 `NetworkComputerComponentRegistrationEvent` 注册。机器用 `MachineOwnership` 保存所有者，以 `MachineNetworkDevice` 声明网络中心、计算机、研究站、数据中心或普通设备角色；`NetworkRecipeWork` 处理配方的研究和计算资源要求。给资源数量本身不会授予研究权限。

默认 `NetworkCenterController(type, level, threadCapacity, energyPerTick)` 提供网络等级和线程额度；默认 `ResearchStationController(type, level, rates, energyPerTick)` 提供研究等级、各资源每 tick 推进速率及 FE 消耗。二者直接继承 `MachineInstance`，不安装配方工位或机器业务 tick。中心通过网络发布服务工作，研究站通过共享研究服务工作。中心旧的两参数/三参数构造器保留，省略的线程额度为 0，两参数时耗能也为 0。

只有当前活动中心的供给计入队伍，并与计算机和外部参与者的供给相加；中心缺能或失效后按新活动中心重新取供给。`THREAD` 是已有的逻辑并发额度，研究站和普通配方共用整机租约，不是 JVM 工作线程数量。`NetworkCenter.networkResources()` 返回当前加载代固定的只读资源表；默认控制器只声明配置的 THREAD，并复用同一张表，自定义中心可返回自己的资源向量。

计算资源类型用 `new NetworkResourceType(id, logicalConcurrency)` 声明。资源类型不限制单件物品、整台计算机或研究站的资源量；线程、算力和自定义资源均由开发者决定供给或速率。计算物品的 `resourcesPerItem` 按实际安装数量相乘，再与机器直接声明的供给汇总。例如一件物品可声明 64 THREAD、2048 COMPUTE 和任意自定义资源量。旧 `maxPerTickValue` 字段及构造参数已删除；数量仍使用非负 long，实际累加/倍增不能溢出。

Java 分别给类型声明 `MachineOwnership.TYPE` 和 `NetworkCenter.DEVICE` / `ResearchStation.DEVICE`，给装配指定实例工厂并将对应 `DEVICE` 标为 `recreated`。例如研究站装配：

```java
MachineAssembly.builder(type, "research-station-v1")
    .instanceFactory(value -> new ResearchStationController(value, 1,
        Object2LongMaps.singleton(NetworkResources.COMPUTE, 10), 8))
    .recreated(ResearchStation.DEVICE).build();
```

`rates` 是 `Object2LongMap<NetworkResourceType>`，未列资源速率为 0；定义后按只读契约借用，不复制。速率不得为负，不能包含逻辑线程类型，具体速率由开发者定义。等级与耗能在当前加载代固定，耗能非负。自定义运行时可直接实现 `api.network.NetworkCenter` 或 `api.research.station.ResearchStation` 并安装对应角色组件，无需继承默认控制器；无机器业务 tick 的实现覆写 `requiresTick()` 返回 `false`。

每段每种资源增加 `min(网络研究供给, 各参与站该资源速率之和, 剩余需求)`。站点等级决定是否合格，速率由站点定义；多个站仍合摊一份 START → TICK → END 材料，不按站数复制。研究站只在成功推进的 END 与进度一起结算自己的 FE，待机或材料周期未完成时不扣这笔电费；材料定义自身的阶段 FE 按原规则另行结算。缺能站不进入新段候选。网络中心只对本次生效者收费，缺能时重选可供电中心。同一设备同一服务器 tick 只成功付费一次，重新发布或调用不会重复扣费。

完整 Java 示例见 [NetworkCenterExamples.java](run/visual-examples/src/main/java/pe/visual/examples/NetworkCenterExamples.java) 和 [ResearchStationExamples.java](run/visual-examples/src/main/java/pe/visual/examples/ResearchStationExamples.java)。网络中心与研究站当前只提供控制器和接线，相关 GUI 延缓实现。

### 计算机与运算仓

`NetworkComputer` 接口只暴露 `providedConcurrency()` 和 `providedResources()`；资源表包含 THREAD，按只读契约交接。默认 `ComputerController(type, maximumThreads, resourceLimits, recipes)` 可被继承，与网络中心、研究站控制器一样使用共同机器生命周期。类型声明 `MachineOwnership.TYPE`、`ComputerController.COMPONENTS`、`ComputerController.DEVICE`，装配将后两项标为 recreated。计算机自身不收费，组件的 FE 在网络发布事务中支付。

只从 `ComputationHatch` 读取安装组件，组成变化由事件通知，稳定时复用缓存。四级 `computation_hatch_1..4` 分别为1/2/3/4槽；每槽物品1件，非物品原生容量1000/4000/16000/64000。物品的 Item 必须实现 `NetworkComputerComponent`，非物品类型不限。共享库存接受外部插入/抽取，没有自动IO。

组件返回 `new NetworkComputerComponent.Capture(provided, requiredResources, energyPerTick, currentProof)`。本机汇总所有组件的消耗，任何资源或 FE 不足时全部组件供给停摆；可运行的 `ComputerRecipe` 独立继续。`ComputerRecipe(id, inputsPerTick, provided)` 是每tick的供给配方，复用原资源需求的 TICK 事务支付一份输入，也可传只读资格条件。控制器可用 `installedComponents()` 取得安装身份与定义。

`new NetworkResourceType(id, logicalConcurrency, true)` 声明内部资源，例如冷却。内部资源仅在本机产生和占用，不进入 `providedResources()` 或队伍网络；未用额度不跨tick累积。具体控制器的线程/资源上限按配置应用，不是资源类型的固有限制。

计算机启动须先取得中心或其他已获准设备提供的1份并发，不能用自己的待生效供给启动；活动数据库占用1份，研究站推进时申请，中心不占用。三类网络设备优先于普通设备，高优先级申请可收回普通租约并使其未提交凭据失效；同条件使用持久UUID，避免加载顺序决定结果。尚未获准的计算机只预留、不支付材料或组件FE。

ZS 入口为 `MachineType.computer(id, title, structure, maximumThreads, limits, recipes)`，每tick配方用 `ComputerRecipe.create(id, Resource[] inputs, ComputingResources provided)`，内部类型用 `ComputingResource.internal(id, logicalConcurrency)`。完整规则、Java/ZS 示例和边界见[计算机与运算仓](docs/computers.md)。计算机GUI继续延缓；本轮仅实现运算仓GUI。

### GUI、模型与 JEI

`MachineRenderRegistrationEvent` 为机器或结构绑定 Gecko 模型。`MachineGuiRegistry` 注册公共侧容器，`MachineScreenRegistry` 注册客户端屏幕。必须把屏幕、模型和 JEI 类的加载限制在客户端，否则专用服务器可能因缺少客户端类而启动失败。

普通配方池由库提供 JEI 页面，单输入池使用专用单输入布局。`withExistingJeiPage(uid)` 让机器使用其他模组已有的分类入口；有效的外部分类不会重复生成本池页面。

`StructurePreviews.client().request(id, settings)` 请求生成结构预览，调用者分次 `advance(...)` 推进，状态为 `READY` 后才能使用完整模型。`StructurePreviewScreens` 打开库的预览界面，`project(...)` 在世界中显示投影。模型取自服务端已同步的结构目录，后续使用前应检查该模型仍属于当前目录。

### 运行中替换配方与保存版本

`RecipeProcessNbt` 负责保存和恢复配方进程。配方时长、需求行为或组件保存格式改变后，必须更新配方 revision，并保留仍要恢复的旧版 codec。机器装配的 revision 则标识机器组件布局和保存方式。

需要跟随运行中目录替换时，扫描器与工作槽应共用 `RecipeProcessCodecs`，并调用 `RecipeRegistry.bindProcessCodecs(...)` 和扫描器的 `follow(...)`。目录替换只影响新扫描，已经开始的进程继续使用原配方和原 codec。客户端也必须有相应的新定义，才能正确显示更新后的配方。初始化期的 `register(...)` 不能用来修改运行中的目录。

## Java：添加自己的行为

### 自定义机器组件

组件把每台机器的状态和每 tick 的工作分开。`ComponentType<E,C>` 描述组件 ID、创建函数、依赖和系统；`ComponentSystem<E,C>` 提供 Pre、Normal、Post 回调。机器组件通常使用 `MachineSystem<C>`。

`runBefore/runAfter` 按系统 ID 指定顺序，组件依赖则使用实际的 `ComponentType` 对象。系统实例会被多台机器共用，必须把计数器、缓存等可变状态放在组件中，不能放在共享系统实例里。

有持久状态的组件实现 `MachineComponentCodec<C>`，再用 `assembly.component(type, codec)` 绑定。`encode` 写 NBT，`validate` 检查保存数据，`restore` 把数据恢复到尚未发布的新组件。无状态且可重建的组件使用 `recreated(type)`。这些设置必须与机器类型声明的组件一致，否则保存或恢复校验会拒绝该装配。

### 自定义配方条件和效果

`RecipeRequirementType<C>` 把不可变参数与 `RecipeRequirementSystem<C>` 关联起来。调用 `type.requirement(id, payload)` 即可把自定义需求加入配方，无需额外注册需求类型表。同一类型 ID 必须复用同一个类型对象。

需求系统的主要方法如下：

| 方法                          | 何时调用、应完成什么                                   |
| ----------------------------- | ------------------------------------------------------ |
| `start(process, requirement)` | 配方开始时检查或取得本次开始需要的效果，返回事务。     |
| `tick(process, requirement)`  | 工作期间处理持续消耗、输出或条件。                     |
| `end(process, requirement)`   | 配方完成时准备产出或结束效果。                         |
| `cancel(...)`                 | 清理本需求仍持有的持续状态；可能在开始尚未成功时调用。 |
| `requiresOwnerThread()`       | 返回 true 时要求相关工作留在拥有机器的主线程。         |
| `crossRecipeTickIo(payload)`  | 描述逐 tick IO 是否相同，供跨配方控制器决定能否合批。  |

[ExampleRequirements.java](docs/api-examples/java/ExampleRequirements.java)提供机器等级条件：在机器类型上声明等级属性，在配方中加入最低等级需求，开始时比较属性。它不扣料，因此成功时返回共享成功事务；不足时返回带本地化原因的阻塞结果。若等级需要全程满足，还必须在 `tick` 中继续检查。

`RequirementTransaction` 让一组需求能够共同成功或撤销。取得阶段先准备效果和资源预留，执行器检查全部需求后才提交。`commit` 应用业务效果，`rollback` 撤销已经取得或提交的效果，`complete` 在整组成功后释放补偿信息。必须能撤销部分提交，禁止在 `complete` 中再扣材料或进行可能拒绝业务的操作；否则其他需求失败时无法完整撤回。

大计算可通过 `prepare(process, requirement, stage)` 捕获不可变输入并返回纯计算任务，把计算交给工作线程。`prepare` 本身不能扣料或修改世界；需要保留主线程的需求仍应声明 `requiresOwnerThread()`。如果自定义需求还访问结构仓室之外的物理存储，必须用 `storageContexts(...)` 提前声明完整范围，让执行器统一协调这些存储；禁止开始取得资源后再临时添加未声明的存储。

资源不足返回 `ProcessResult.Blocked`，不可恢复的业务失败返回 `ProcessResult.Failure`。没有逐 tick IO 的需求可让 `crossRecipeTickIo` 返回 `null`；有 IO 时，只有相同返回值确实表示相同单位输入输出，才能允许合批。需要随批次时间重分配 IO 的扩展，还必须正确实现重计量规则，才可声明 `crossRecipeRetimesTickIo`。

### 自定义配方查找

实现 `RecipePool` 可为大型或特殊配方集提供自己的查找方式。`recipes()` 提供池内配方，`match(...)` 处理库存匹配，`beginMatch(...)` 和 `beginCandidates(...)` 允许把较大的查找分次推进。`freeze(recipes, machines)` 在目录冻结或替换时生成新池，必须保留传入配方对象及顺序，并保留原池自己的查找策略。

匹配只能读取传入的快照与已经捕获的机器信息。必须让结果包含所有仍可能成功的候选，禁止在查找时扣料、执行 START 或访问世界。候选索引返回 `null` 表示不筛选，空集合表示没有候选，两者的含义不同。

需要改变控制器的池选择时，可继承 `MultithreadController` 或 `CrossRecipeController` 并覆写 `getRecipePools(...)`；创建宿主和扫描器可分别覆写 `createRecipeHost(...)`、`createRecipeScanner(...)`。最后必须把子类构造函数交给 `MachineAssembly.instanceFactory(...)`，库才会实际创建该子类。

### 自定义结构条件与形状

单格条件实现 `StructurePredicate`。需要生物群系、方块 NBT 或 addon 数据时，用 `StructureQuery` 声明采样，由主线程读取并复制结果，再让匹配回调读取 `StructureFacts`。必须提前声明所有会读取的位置，禁止异步验证器直接访问 `World` 或 TileEntity；否则匹配时与安装前看到的事实可能不同。

需要全新形状时实现 `MachineStructure`。`transform` 同时变换坐标、谓词、验证器和子结构；`collectReads` 声明读取范围；`match` 根据快照产生独立的匹配树，失败返回 `null`。只有几何和读取声明终身不变时才能启用 `planReusable`，每次请求的游标与临时状态仍须独立。

### 自定义仓室与共享存储

`ResourceStorage` 实现需要支持普通读写、数量快照、revision 和预留。`reserveExtract/reserveInsert` 在成功时返回资源令牌，在库存不足、快照过期或存储无效时返回明确拒绝结果；令牌负责占用、提交和回滚。

同一物理余额必须返回同一个 `StorageContext` 和一致的存储 identity，包括资源和 FE 适配器。这样库才能去除重复来源并协调共享仓室。禁止为同一库存创建多个互不关联的 context；否则并行判断会把一份库存计算多次。输入输出确实独立的混合仓应分别提供 context。

`ResourceHatch.inputGroup()` 返回 `ResourceInputGroup(color, partition)`；默认 `AEColor.TRANSPARENT`（无色）、空分区。只有无色且空分区的库存是通用库存。非空 partition 表示独立库存，其颜色规范为透明色并保留完整分区标识，不受总成颜色影响，也不向其他组暴露为通用输入。可配置仓室实现 `ResourceHatchGrouping` 的颜色读写接口。`inputPartitions()` 可暴露多个稳定的物理视图；每个视图仍须保留原存储 context 和 identity。需要向同一总成各分区提供催化剂时，视图返回 `sharedInput() == true`，并使用该总成稳定分区前缀；隔离槽可共同读取未分区无色库存。外部实现改变分区成员后，旧捕获会失效；隔离进程按稳定分区恢复，不因总成染色改变归属。

实现 `ResourceRecipeObserver` 可接收真实供料后的 `inputsCommitted(process, stage, amounts)`，以及进程装入、退出与阶段成功结算通知。`amounts` 只包含该 observer 实际提供且完整阶段已提交的输入；探测和回滚不通知，回调在存储锁外执行。回调应只更新同步状态，不能从工作线程访问世界。需要跨存档关联时使用 `RecipeProcess.identity()` 的 UUID，codec 会保存它；配方 ID 或随机种子不能替代进程身份。

回调内可读 `process.committedInputs(stage)` 获取同一成功阶段跨所有来源的完整实际输入；它先于来源回调发布，新阶段、探测或回滚后不会沿用旧表，不写入存档。单种类跨来源总量超出 long 时返回 null，保持原事务成功语义；同一 observer 的来源合计超 long 时会分块通知，每块仍为精确正额，调用方不能假设一阶段只有一次回调。样板总成先用自身正额回执证明归属，再使用完整实际输入比例匹配正在运行的配方。

### 研究、网络和客户端扩展

`ResearchStartCondition` 添加研究资格判断，`RewardEventAction` 执行外部奖励。奖励可能因保存确认失败而重试，必须按 `RewardEventKey` 记录是否已应用；已发放的效果在重试时返回 `ALREADY_APPLIED`，禁止再次发放。

`NetworkRecipeExtension` 在目录发布时为配方追加网络需求，`NetworkParticipant` 为实际网络提供资源。参与者必须由服务器登记并在失效时关闭。`OwnerTeamResolver` 可替换团队来源，返回当前成员的独立快照。

`RecipePoolJeiPage` 实现自己的 JEI 布局和输入处理。支持目录替换时，用 `prepareUpdate` 生成待应用数据，再由 `apply` 切换。点击、拖拽和转移前必须检查 wrapper 的 `isCurrent()`，禁止让旧窗口操作替换前的配方。

## ZS：调用已有功能

### 脚本加载与机器声明

机器控制器要参与 Forge 方块注册，因此机器脚本必须用 `#loader preinit`。配方、结构和研究通常放在 `#loader crafttweaker` 脚本。两个 loader 的全局变量互不共享，使用 `Bindings.scope(id)` 取得同一份配方声明表。

下面的脚本注册一台熔炼机，最多使用 4 个并行，扫描 `example:smelting` 配方池：

```zenscript
#loader preinit
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;

MachineType.create("example:smelter", "example.machine.smelter", "example:smelter_shape")
    .recipePool("example:smelting")
    .attribute("prototype_eng:process_parallelism", 4)
    .processing(Bindings.scope("example:recipes"), 32, 64)
    .executionPolicy("FULL_ASYNC")
    .register("machine-v1");
```

`create` 的三个参数是机器 ID、显示名称的翻译键、结构 ID。`recipePool` 确定扫描范围。`processing` 安装工作槽、扫描器和配方保存支持，两个数字是候选预算与尝试预算，必须为正数。`register` 提交机器并关闭此 builder，之后继续修改会报错。

`MachineFactory.multithreaded()` 和 `crossRecipe()` 分别选择多线程与跨配方控制器，传给 `.factory(...)`。`.workSlot(...)` 添加指定名称和配方组的逻辑工位。执行策略与 Java 表格相同，脚本参数使用枚举名称字符串。

### 声明配方并让机器使用

配方先创建需求，再传给 `Recipe.create(id, revision, durationTicks, requirements)`。物料用 `Resource.item/fluid` 包装，第二个参数单独决定数量。

```zenscript
#loader crafttweaker
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;

var recipe = Recipe.create("example:iron_to_gold", "recipe-v1", 80, [
    Requirement.resources("example:items", [Resource.item(<minecraft:iron_ingot>, 2)],
        [Resource.item(<minecraft:gold_ingot>, 1)]),
    Requirement.energy("example:power", 0, 8, 0, 0, false)
]);
var pool = Pool.create("example:smelting", "example.recipe_pool.smelting", [recipe]);
var names = Bindings.scope("example:recipes");
names.registerPools([pool], []);
names.declareManifest("example:recipe_set", "v1");
```

此配方每个并行单位开始消耗 2 铁锭，成功工作 80 tick，每 tick 消耗 8 FE，完成产出 1 金锭。`Pool.create` 把配方组成机器可选的池。`registerPools` 注册池及配方保存信息，第二个数组放需要恢复的历史版本；`declareManifest` 为完整目录声明身份。

机器的 `processing` 必须使用与这里相同的 scope。仅调用 `pool.register()` 不会给机器的配方恢复器安装 codec；需要自动工作和存档恢复时必须使用共享 `Bindings.registerPools(...)`，禁止重复注册同一池。

`Pool.simple` 用于单个固定开始输入和任意数量固定结束输出。`Pool.combine` 混合多个不同策略的池。`Requirement.resourceIO` 支持逐 tick 物料；`fuzzyInput` 列出可替代资源；`checkpoint` 在指定进度执行子需求；`chance` 和 `randomOutputs` 设置概率与随机输出。它们的数量、返回值和使用限制见 [ZS 使用参考](api-zs-reference.md)。

### 声明结构与可变层数

`Pattern.create().put(x, y, z, predicate)` 为相对坐标填写条件。`Predicate.block(id)` 接受该方块的状态，`state(...)` 要求具体状态，`anyOf(...)` 允许多个候选。

下面的结构在控制器左右和后方各放一个仓室，上方重复 1 至 4 层铁块，最后再加金块顶盖：

```zenscript
#loader crafttweaker
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;

var cap = Structure.template("example:smelter_cap", "NORTH_UP", 0, 1, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.block("minecraft:gold_block")), [], [])
    .translated("example.structure.smelter_cap");
var column = Structure.slice("example:smelter_column", "NORTH_UP", 0, 1, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.block("minecraft:iron_block")),
    1, 4, 0, 1, 0, [], [cap]).translated("example.structure.smelter_column");
var shape = Structure.template("example:smelter_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create()
        .put(-1, 0, 0, Predicate.block("prototype_eng:input_hatch_1"))
        .put(1, 0, 0, Predicate.block("prototype_eng:output_hatch_1"))
        .put(0, 0, 1, Predicate.block("prototype_eng:energy_input_hatch_1")), [], [column])
    .translated("example.structure.smelter");
cap.register();
column.register();
shape.register();
```

`template` 的 `x/y/z` 移动整个节点，pattern 中的坐标再相对此节点计算。`validators` 放整体检查，`children` 放子结构。`slice` 在 pattern 后接 `minCount/maxCount` 和 `stepX/Y/Z`；上例的 `1, 4, 0, 1, 0` 表示沿 Y 正方向重复 1 至 4 次。顶盖是切片的 child，只在最后一段连接一次。

`.translated(key)` 返回设置了名称的新结构，必须保留该返回值。每个需要进入目录的定义调用 `register()`。结构中复用的 child 必须保留原对象，不能另建同 ID 的结构代替。

### 声明并行控制器

在 `#loader preinit` 脚本中使用 `mods.prototype_eng.ParallelController.create(id, translationKey, maximum)` 创建额外的物理并行控制器，可调用 `model(modelId)` 指定自有模型，再 `register()`。不调用 `model` 时使用原始单套模型，不自动染成内置五级颜色。

```zenscript
#loader preinit
import mods.prototype_eng.ParallelController;
ParallelController.create("example:parallel_module", "tile.example.parallel_module.name", 32).register();
```

机器仍通过 `MachineType.attribute("prototype_eng:process_parallelism", internal)` 设置内部贡献，通过 `attribute("prototype_eng:max_process_parallelism", cap)` 设置包含控制器后的整机上限。无需添加一个有镜像数据的扫描器组件。

### 仓室、研究、网络和显示

仓室容量由 `ResourceSide.itemEquivalent(slots, capacity)` 和 `EnergySide.create(capacity, transfer)` 定义，再交给 `Configuration.resources/energy(tier, input, output)`。两侧可以只提供一侧，不能都为空。`configureDefaults` 设置启动默认值，运行中调用 `request` 返回更新任务。

研究用 `Research.create(...)` 声明，`ResearchStage.create(...)` 设置每段等级，`ResearchFormula.constant(type, demand)` / `.affine(type, base, slope)` 给出资源总量，不接受单站限额。stage 上的 `.prerequisite(...)` 和 `.mutex(...)` 分别添加前置和互斥研究，`.material(...)` 添加研究材料，`.reward(...)` 添加可领取奖励。研究站的有状态材料配方要同时提供 `.materialPersistence(...)`。

在 preinit 脚本中，专用控制器使用下面的快捷入口（`compute` 是已经声明的数值计算资源）；它们不调用 `processing` 或添加配方工位：

```zenscript
MachineType.networkCenter("example:center", "example.center", "example:center_shape", 1, 4, 4)
    .register("center-v1");
MachineType.researchStation("example:station", "example.station", "example:station_shape", 1,
    ComputingResources.create().add(compute, 10), 8).register("station-v1");
```

中心在等级后依次填写线程额度、FE/t，研究站最后一项是 FE/t。资源类型使用 `ComputingResource.create(id, logicalConcurrency)`，不再传上限。普通 `MachineType.create(...)` 可链式调用 `.requiresNetwork(true)` 或 `.requiresNetwork(false)`；省略时遵循上述配置和显式角色规则。

计算资源用 `ComputingResource` 定义，`ComputingResources` 累加资源数量，`ComputingModifier` 设置数量修饰。`NetworkConfiguration.create(...)` 组合机器角色、等级、供给和配方需求，然后传给 preinit 中的 `.network(...)`。内置 `thread/compute/qubit` 已注册，禁止再次注册。

`RenderBindings.gecko()` 设置 geo、纹理和动画，再绑定到机器、组件或结构。`Options.create(...)` 设置 JEI 概率标记和材料候选显示；`pool.jeiOptions(...)` 返回带显示设置的新池。具体参数和示例见 [ZS 使用参考](api-zs-reference.md)。

## ZS：添加自己的行为

### 匹配资源、选择并行数与修改分支

脚本函数可以参与资源匹配、输入来源分配、最终并行选择和选择分支的修饰。`Matcher.create(id, function(candidate, pattern))` 返回资源是否匹配；`ParallelismSelection.create(function(suggested))` 返回最终正并行数；`SelectiveModifier.create(...)` 通过回调上下文修改本次进程的速度或需求。

[20_extensions.zs](docs/api-examples/zs/20_extensions.zs)将这三个回调实际用于玻璃配方：用 matcher 找沙子，选择是否额外消耗煤炭来增加玻璃产量，用 modifier 提高速度，再把最终并行限制到最多 2。只创建回调对象不会改变配方，必须分别传给 `dynamicInput`、`selective` 和 `Recipe.withSelection`。

这些函数可能在工作线程执行，也可能因模拟、重试而多次调用。必须只读取参数及不可变定义，禁止扣背包、修改世界或累加脚本全局计数器。执行策略设为全同步时也仍有重试，不能将匹配函数当作只执行一次的事件。

### 读取世界条件

`Query.create(id, function(world, position))` 在服务器主线程采样世界并返回 `IData`。`Predicate.custom(...)` 声明要读的事实和相对位置，验证函数只读取传入的 `Facts`。例如亮度查询在主线程读取亮度，异步验证阶段只比较已采集的数值。

`facts.at(...)` 使用随结构旋转的局部偏移，`facts.controller` 取得控制器事实，`facts.atWorld(...)` 按绝对位置读取已经声明的事实。必须先声明读取位置，禁止在验证函数里借助捕获的 World 引用直接查询未声明位置。

### 扩展回调的边界

`InputAllocation.create(...)` 返回输入如何分到各来源。份额使用本次 `sources` 的索引，数量必须为正且总和等于请求量。`SelectiveContext` 和 `OverlayContext` 的 patch 方法修改本次进程的需求层；`null` 保留原字段，空数组清空该资源项。直接写 `context.data` 不会自动随事务回滚。

`ResearchServer.permissions(...)` 返回研究操作的权限决定；需要默认权限时调用 `context.standardDecision(action)`。奖励函数返回 `APPLIED`、`ALREADY_APPLIED` 或 `RETRY_LATER`，必须按事件身份去重。下载输出函数只构造输出物品，材料扣除和交付由库处理。

新的组件系统、需求系统、仓室后端和 JEI 页面由 Java 实现，再提供给脚本。Java 可用 `ZenMachineComponent`、`ZenMachineFactory`、`ZenRequirement`、`ZenRecipe` 和 `ZenRecipePool.of(...)` 包装实际对象；脚本中的对应类型接收这些对象。自定义 JEI 页面先由客户端 Java 调用 `ScriptJeiEvents.page(id, page)` 注册，脚本再用 `Page.named(id)` 引用。

运行中替换配方用 `Updates.forServer(server).replace(...)`，先为客户端声明对应目录，再提交服务器更新。完成后检查 `result.published`；新扫描采用新定义，运行中的进程继续使用旧定义。具体回调参数见 [ZS 使用参考](api-zs-reference.md)。

## 示例文件与语言资源

Java 示例位于 [ExampleMachines.java](docs/api-examples/java/ExampleMachines.java)，自定义需求位于 [ExampleRequirements.java](docs/api-examples/java/ExampleRequirements.java)。它们编入已有 Forge addon，addon 自己提供 `@Mod` 入口。

ZS 示例由 [00_machine.zs](docs/api-examples/zs/00_machine.zs)、[10_content.zs](docs/api-examples/zs/10_content.zs) 和 [20_extensions.zs](docs/api-examples/zs/20_extensions.zs) 组成，可放入整合包的 `scripts`。Java 和 ZS 示例注册相同 ID，必须选择一种安装，禁止同时注册。

中文名称见 [zh_cn.lang](docs/api-examples/zh_cn.lang)。Java addon 放入 `assets/example/lang/zh_cn.lang`；资源包也使用该路径，根目录必须有 `pack.mcmeta`，Minecraft 1.12.2 的 `pack_format` 为 3。缺少该文件会使语言资源不能按资源包正确加载。
