---
title: Java 与 ZenScript API
---

## 源码布局与使用环境

Prototype Engineering 为模组和整合包提供机器、结构、配方、仓室、研究和计算网络。具体机器及其配方由使用者声明。运行环境为 Minecraft 1.12.2、Cleanroom、Java 25 和 AE2 Supergiant；使用 ZenScript 时还需要 CraftTweaker 2。

Java 公开 API 位于 `src/main/java/com/circulation/prototype_eng/api`。ZS 使用 `mods.prototype_eng` 命名空间，入口实现在 `integration/crafttweaker` 中。本文的 Java 和 ZS 部分都先介绍如何调用现有功能，再介绍如何添加自己的行为。[ZS 使用参考](api-zs-reference.md)按功能说明脚本方法及其参数。

机器、配方、结构等对象使用 `模组ID:名称` 形式的 ID。显示名称使用语言文件中的翻译键，不能用中文名称代替 ID。Java 的固定翻译键必须由实现 `LocalizationEnum` 的枚举管理。客户端用 `getLocal()` 取得译文，服务端使用可翻译文本。

`LocalizationEnum.getLocal()` 仅限客户端。服务端消息和容器标题必须使用 `text(...)`；`Item.getItemStackDisplayName(...)` 等双方都会调用且必须返回 String 的原生方法，必须使用公共侧 `net.minecraft.util.text.translation.I18n.translateToLocal(text.getTranslationKey())` 保留完整翻译键，禁止调用客户端 I18n 或 `getLocal()`。纯客户端的 GUI、JEI、预览入口和展示辅助方法必须标注 `@SideOnly(Side.CLIENT)`，并在客户端注册或调用。

物品、流体以及 addon 资源都用 AE2 的 `AEKey` 表示种类，用 `GenericStack` 携带数量。多个资源的数量表使用 `AEKey2LongMap`，累加和扣减优先使用 `addTo`。带原始类型的集合字段、参数和返回值必须使用 fastutil 专用接口，例如 `Reference2LongMap<AEKeyType>` 和 `IntList`；只有纯对象集合可以用标准 JDK 集合接口对外暴露。并发、位集合等特殊语义可以使用相应实现，必须说明实际需要的语义。

本库对象缺值返回带有 `@Nullable` 标注的 `null`，调用方必须判空。禁止在本库字段、参数或返回值中使用 `Optional`、`OptionalInt`、`OptionalLong`、`OptionalDouble`；只有依赖接口要求的类型保留。原始类型使用其业务范围之外的值表示缺失，例如非负奖励时间使用 `long` 的 `-1`；禁止仅为表示缺失而装箱。预算控制的接受判断使用共享 `BudgetResult`：`YIELDED` 暂停，`ACCEPTED` 完成并接受，`REJECTED` 完成并拒绝。

未标注 `@Nullable`、且未明确允许缺值的参数、集合元素和工厂结果必须非空。调用方必须满足这些要求，禁止通过反复 `requireNonNull` 或逐元素检查补偿错误调用。真实的资源缺失、权限变化、溢出、存档格式和事务预检仍按对应接口处理。

`StructureOperation<T>`、`StructureQuery<T>` 与结构读取接口通过泛型类型参数表示可空结果。自定义操作或查询允许返回 `null` 时，必须使用 `StructureOperation<@Nullable T>` 或 `StructureQuery<@Nullable T>`；只有操作完成且实际工作线程退出后才能读取 `result()`。`advance(work)` 返回 false 时必须保留同一游标继续推进；`StructureOperation<@Nullable Void>` 完成后返回 null，不能用这个返回值判断是否完成。内置 `TILE_NBT` 为可空查询，数值和状态查询完成后返回实际值。

所有 List 参数和返回值，包括嵌套列表，必须支持常数时间索引访问。使用 ArrayList、ObjectArrayList、List.of 或符合该要求的自定义实现；禁止传入 LinkedList。内部按索引遍历，不通过复制列表修正调用方的实现选择。

集合和 NBT 的所有权按对应 Javadoc 处理。定义构建器交付完成的数据后不得再修改；只读访问直接返回已有对象。`ResourceKeyNbt.INSTANCE` 使用 AE 默认类型解析器，自定义解析器创建一次 codec 后复用；`AttributeModifierNbt.INSTANCE` 和 `MachineAttributesNbt.INSTANCE` 提供默认属性编码器。资源类型必须复用单例，R2DMap 按对象身份索引，累加使用 `addTo`。

`ComponentType` 的 systems 使用支持常数时间索引的 List，dependencies 使用 Set，两个参数均直接借用，声明后不得修改。配方池、研究快照和结构显示数据按只读方式交付；为 JEI 更改分类时复用已建立的输入索引。`ComponentData.set/merge/replace` 借用不可再修改的子标签；快照按 revision 复用，仅保留独立顶层索引，使后续正常写入不改变已发布快照。`DataComponents` 的 codec 直接返回该只读快照，调用方必须通过 `set/merge/replace/remove/clear` 修改数据，否则 revision 和同步结果不会更新。`ResourceKeyNbt.decode(id, data)` 的类型加载器只读取自己的字段，不修改输入，允许输入同时包含库存元数据。

fastutil 的 `fastIterator()` 可以复用同一个 Entry；必须在下一次 `next()` 或 `remove()` 前读取需要保留的键和值。移除后禁止再次读取该 Entry；需要后续释放资源时先保存其值，不复制整个 Map。

RecipeOverlays 的 NBT 通过静态函数编解码，进程的固定需求图由 `RecipeProcess.definitionIndex()` 按需建立一次并复用。快照、下载输出和手动材料凭据的数组/NBT 按 Javadoc 只读交付；创建方在实际需要独立可修改状态时建立一次副本，禁止在 getter 或 DTO 构造器中重复复制。

## Java：调用已有功能

### 初始化与注册

Java addon 通过 Forge 事件提交定义。库在对应事件结束后检查并冻结注册结果，因此必须在该事件中或事件派发前完成声明。

| 注册事件                                    | 用途                                               |
| ------------------------------------------- | -------------------------------------------------- |
| `MachineTypeRegistrationEvent`              | 声明机器类型，库随后为这些类型创建控制器方块。     |
| `NetworkComputerComponentRegistrationEvent` | 声明哪些物品提供计算资源。                         |
| `ResearchRegistrationEvent`                 | 添加研究及等级要求。                               |
| `ResearchItemDownloadRegistrationEvent`     | 注册研究下载和物品导入配方，包含材料、输出、研究要求与费用。 |
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

`MachineType.builder(id, displayName, structureId)` 描述一种机器：它叫什么、匹配哪个结构、能够使用哪些配方池、安装哪些组件。`build()` 交付完成的只读定义，之后必须停止修改 builder 及其借用数据；`event.registry().register(type)` 让库注册该类型。

机器类型常用的设置如下：

| 设置                       | 对机器的作用                                             |
| -------------------------- | -------------------------------------------------------- |
| `recipePool(poolId)`       | 添加扫描范围，机器只从所选池中寻找配方。                 |
| `recipeGroup(groupId)`     | 在所选池中进一步按配方组筛选。                           |
| `component(type)`          | 声明每个机器实例都需要的组件。                           |
| `workSlot(definition)`     | 添加一个逻辑工位，多线程控制器可分别给各工位安排配方。   |
| `attribute(id, base)`      | 设置并行数、速度等属性的初始值。                         |
| `executionPolicy(policy)`  | 选择配方扫描和工作回调能否异步执行。                     |
| `requiresNetwork(boolean)` | 显式设置普通控制器是否必须接入逻辑队伍网络才能执行配方。 |
| `controllerModel(modelId)` | 选择控制器使用的模型声明。                               |

默认控制器左下角提供16个物品格。`controllerInventorySlots(int)` 设置开放数量，默认0；`controllerInventorySlots(ToIntFunction<MachineInstance>)` 按当前机器实例计算数量。配置动态函数后优先使用其返回值；没有函数、函数被设为null、机器尚未装配或已注销时使用固定值。返回值必须在0到16之间，越界会抛出 `IllegalArgumentException`，不会自动裁剪。动态函数在服务端工作线程结束后的边界执行，必须只读取状态，禁止在函数内修改机器、库存或世界。

`machine.controllerInventory()` 返回物理控制器的原生 `InternalInventory`，可用 `getStackInSlot(slot)` 按索引读取，用原生迭代器遍历。默认宿主的具体实现为 `ControllerInventory`，`availableSlots()` 返回当前开放数，`forEachAvailable((stack, slot) -> ...)` 遍历开放格，`forEachStored(...)` 只遍历实际有物品的格子，包括临时关闭的格子。`MachineControllerHost.controllerStack(slot)` 和 `forEachControllerStack(...)` 供自定义控制器使用。必须在服务端拥有机器执行权时读取这些物品，禁止从配方工作线程直接访问物品堆栈；需要异步计算时，先在服务端采集所需的只读事实。

格子从左到右、从上到下开放。未开放格子覆盖半透明白色，普通点击、拖动、双击、数字键及Shift移动都必须通过相同的服务端开放状态检查。关闭格子保留其中物品，重新开放后恢复访问；全部物品随控制器保存，拆除控制器时参与正常物品掉落。

槽位操作必须使用控制器的实际开放状态。AE 槽位通过公共侧 `AppEngSlot.isSlotEnabled()` 限制插入和取出；禁止在服务端调用仅限客户端的 `Slot.isEnabled()`。

`MachineAssembly.builder(type, revision)` 规定怎样创建和恢复这个类型的实际机器。`instanceFactory(MultithreadController::new)` 创建多线程控制器，`instanceFactory(CrossRecipeController::new)` 创建跨配方并行控制器。`component(type, codec)` 为有状态组件绑定保存方式，`recreated(type)` 则在恢复时重新创建不需要保存的组件。codec 负责把组件编码为 NBT、恢复组件，以及在全部组件恢复后检查组件状态。

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

```text
network {
    B:defaultRequiresNetwork=false
}
```

未显式设置的普通控制器默认不需要网络；改成 `true` 后要求逻辑 FTB 队伍网络。Java 的 `MachineType.Builder.requiresNetwork(true/false)` 和 ZS 的 `MachineType.requiresNetwork(true/false)` 优先于该默认值。`MachineType.networkRequirement()` 返回 int：`-1` 使用已声明角色及服务器默认值，`0` 不要求网络，`1` 要求网络。显式安装 `MachineNetworkDevice` 角色本身也表示选择接网；普通控制器显式 `false` 可关闭共同配方门限，但不会取消额外声明的 `NetworkRecipeWork` 研究或资源需求。

需要网络且未自定义角色时，装配自动添加等级 0、无资源供给的 `DEVICE` 及所有权组件，不必逐个控制器重复接线。缺少活动中心或失去许可时暂停配方，恢复后继续。网络中心和研究站保留专用角色，不受普通控制器默认开关控制。此处网络为逻辑队伍网络，不要求 ME 电缆或距离连通。

真实玩家新放置的物理控制器即使离线也保存所有者和机器 UUID，读档不改变所有者。此改动前完全没有所有权数据的旧离线存档无法推导原放置者，启用网络前需换用未携带旧机器存档的新控制器；不会在加载时凭空生成身份。自定义非方块宿主继续显式提供自己的所有权组件和网络运行时。没有机器类型的独立库执行对象不应用服务器默认值。

### 物料需求、能源与配方

一条 `MachineRecipe` 包含时长和需求列表。需求负责决定某个阶段需要消耗什么、产出什么，或者必须满足什么条件。`RecipeRequirements.RESOURCES.requirement(id, payload)` 把物料设置包装成配方需求，`ENERGY.requirement(...)` 则添加独立 FE 需求。

`RecipeRequirements.RESOURCE_PRESENCE` 接收 `ResourcePresenceRequirement`，在 START 检查材料存在后释放临时预留，不扣料、不长期占用、不按并行放大。固定、fuzzy、dynamic 及来源分配复用普通输入规则，JEI 与样板按非消耗条件处理。完整限制及 Java/ZS 方法见[非消耗材料条件](docs/machine-recipe-pools.md#非消耗材料条件)。

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

`ChanceMath.sampleTimes(random, parallels, chancePercent[, tuning])` 返回 long 成功次数：概率超过 100% 的整数部分保证执行，其余部分对整批并行做二项采样。非有限概率拒绝，非正并行或概率返回 0，计数正溢出饱和到 `Long.MAX_VALUE`；`maxTimes(parallels, chancePercent)` 提供保守容量上界。`BinomialSampler.sample(random, n, probability[, tuning])` 按批量和概率选择直接试验、随机位计数、反演或 BTRD，不使用泊松或高斯近似；旧 `ProbabilityTuning.exactSmallBinomial` 仅影响最多 64 次的直接试验分派，所有路径均使用二项分布。暂停时保留原采样游标，已确认的概率结果在重试中复用，详细规则见[概率与检查点](docs/recipes.md#概率随机输出与检查点)。

阶段列表、输入候选、选择分支及 modifier 列表都是借用的定义数据，发布后必须保持列表和元素不变。修改配方时必须发布新定义，禁止修改已发布列表。`RandomOutputPool` 仅在移除非正权重时建立过滤列表。

### 配方池与库存匹配

导入回调和普通配方创建共用可编辑的 `RecipeBuilder`，通过正常资源需求追加或删除材料，并调整四阶段 FE。材料入口为 `addInput/addOutput/addInputPerTick/addOutputPerTick(requirementId, GenericStack)`，删除为对应的 `remove*(requirementId, index)`，`clearInputs/clearOutputs` 可清空指定或全部顶层材料需求。`energy(id, input, inputPerTick, outputPerTick, output)` 保留既有来源分配与溢出参数。`requirement/resources/energy(id)` 查询当前定义，`replaceRequirement/removeRequirement` 编辑整项需求。输入索引包含完整 fuzzy/dynamic 组，输出索引包含随机候选；编辑不会改变原来源或此前创建的配方。完整签名、索引与错误规则见[普通配方编辑](docs/recipe-imports.md#编辑普通配方)。

所有 `RecipePool` 均支持公共配置：`withConfiguration`、`withRequirements`、`withAuxiliaryInputs`、`withEvent`。`RecipeBuilder` 让单个配方与池配置复用完整需求和阶段事件。当前 70 种机器来源与工作模式分别提供独立公开池类，Java 位于 `api.recipe.importing.<modid>`，ZS 位于 `mods.prototype_eng.recipe.importing.<modid>`。Java 直接构造，ZS 由各类型的 `create` 创建；构造不读取来源，首次读取才转换。创建回调返回 boolean，首个参数是可编辑配方，额外参数由各池直接定义，例如熔炉为 `(recipe, float experience)`，匠魂合金只有 `(recipe)`。熔炉保留原生小数经验。未配置的回调使用 `null`，跳过回调及专属参数转换。完整签名、执行映射、保存及 Java/ZS 用例见[配方池公共配置与模组导入](docs/recipe-imports.md)。

导入池的 `poolType(RecipePoolType.SINGLE_INPUT/GENERAL)` 选择实际的 `SimpleRecipePool/DefaultRecipePool`，必须在首次初始化前调用；设置类型和读取 `poolType()` 都不枚举来源。各机器按实际主输入形状设置默认底层，完整类名、默认类型与直接回调参数见[机器目录](docs/machine-recipe-pools.md)。可以把熔炉改为通用池后增加普通输入，也可以把其他来源改成单输入池；后者要求转换结果恰好含一个平铺的固定、fuzzy 或 dynamic START 主输入，拒绝多个主输入、TICK 资源输入及 `SELECTIVE/CHECKPOINT`，错误时记录日志并抛出异常。公共辅助输入独立于主输入。自定义来源可用 `new ImportedRecipePool(id, titleKey, supplier)`，supplier 首次访问时返回 `List<MachineRecipe>`；`withoutContext(id, titleKey, draftSupplier[, predicate])` 接收 `RecipeBuilder` 列表和可选 `Predicate<RecipeBuilder>`，省略 predicate 时使用 `null`。

`RecipePoolType.register(typeId, factory)` 注册外部底层工厂，factory 接收池 ID、标题和完整配方列表并返回自定义 RecipePool。Java 可直接使用返回类型或 `RecipePoolType.require(typeId)`；ZS `.poolType("addon:custom")` 查询同一注册表。内置短名使用 prototype_eng 命名空间。注册不创建底层，首次读取时才执行工厂，重复或未知 ID 明确失败，详见[底层类型注册](docs/recipe-imports.md#选择实际底层池)。

配方池决定机器可以扫描哪些配方，也提供库存匹配策略。普通机器使用 `DefaultRecipePool` 即可。`SimpleRecipePool.Entry` 用于单个固定 START 输入和任意数量固定 END 输出；`SimpleRecipePool.forRecipes(id, titleKey, recipes)` 接收完整配方，支持上述单个固定、fuzzy 或 dynamic 主输入及其他非嵌套需求。通过 `pool.withAuxiliaryInputs(List<GenericStack>)` 在池上配置公共辅助输入，例如 `pool.withAuxiliaryInputs(List.of(new GenericStack(AEFluidKey.of(new FluidStack(FluidRegistry.WATER, 1)), 1)))` 使每份配方额外消耗 1 mB 水。必须保留返回池，并对它的 `recipes()` 绑定 revision、codec 和按配方对象登记的行为。辅助材料按普通 START 输入支付和并行缩放，主辅同键累加；匹配候选及数量上界仍只看主输入。重复设置替换整份辅助列表，空列表清除。后续 `registry.registerRecipe(poolId, rawRecipe)` 自动应用池的公共输入；需要绑定保存及准确对象回调时，先调用 `pool.prepareRecipe(rawSingleInputRecipe)` 并使用返回配方，已准备的定义不会重复添加。定义替换也提交经此方法准备的完整配方。完整规则见[配方池接口](docs/recipes.md#配方池接口与简单配方索引)。

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

`StructurePattern` 使用 `com.circulation.prototype_eng.util.BlockPos2ObjectMap<StructurePredicate>` 保存相对坐标及方块条件。表直接借用且必须保持只读；用 `put(BlockPos, predicate)` 或 `put(BlockPos2ObjectMap.toLong(x, y, z), predicate)` 写入声明，后者不创建坐标对象。遍历使用 `fastIterator()` 的 long 键或原始 long 回调，禁止使用对象 keySet/entrySet/forEach 视图。`StructurePredicates.state(...)` 要求精确方块状态，`blocks(...)` 接受给定方块集合，组合条件可表达同时满足或满足任一条件。

`StructureDefinitions.template(...)` 创建固定形状，`slice(...)` 创建可重复部分。两者接收结构 ID、基准朝向、相对偏移、预览设置、pattern、整体条件和子结构列表。切片另外接收最少/最多重复次数及每次重复的坐标步进。

`NORTH_UP` 下，X 正方向为右，Y 为上，Z 负方向为机器正前方。子结构偏移相对父结构计算。切片会重复自己的 pattern，子结构只连接到最后一次重复处，因此可以用一个切片表示机身，再用子结构表示随长度移动的顶盖。[Java 示例](docs/api-examples/java/ExampleMachines.java)展示了 1 至 4 层铁块和一次性金块顶盖。

通过 `event.definition(new StructureDefinition(shape, titleKey, displays))` 提交结构，displays 使用 `BlockPos2ObjectMap<StructureDisplay>`，没有额外显示声明时用 `BlockPos2ObjectMap.emptyMap()`。明确方块条件能自动提供预览材料；自定义布尔条件需要通过 `withDisplay(...)` 或显式 displays 提供代表材料，否则匹配可以成功，预览和构造器仍无法知道该放什么。

### 并行控制器

内置五种并行控制器分别为 `parallel_controller_normal`、`reinforced`、`elite`、`super`、`ultimate`（后四项同样带 `parallel_controller_` 前缀），默认最大贡献为 4、16、64、256、512。配置文件 `config/prototype_eng/parallel_controllers.cfg` 的各等级 `maxParallelism` 控制最大值；右键 GUI 修改该方块当前贡献，范围为 0 到该级最大值。五级使用预先生成的独立纹理，未使用运行时着色。

结构内的并行贡献相加：`PROCESS_PARALLELISM + Σ控制器当前贡献`，再受 `StandardMachineAttributes.MAX_PROCESS_PARALLELISM`（`prototype_eng:max_process_parallelism`）限制；未声明最大值时为 `Integer.MAX_VALUE`。多线程与跨配方控制器用同一额度扣除当前占用，降低设置不取消已运行进程。新的扫描/准入会重新核对控制器版本和结构归属。没有控制器且没有声明新上限时保持原并行数。

Java 在 `ParallelControllerRegistrationEvent` 中向 `event.registry()` 注册 `ParallelControllerType(id, displayName, maximum, model)`；`displayName` 为 `LocalizationEnum`，`maximum` 必须为正数，`model` 为 blockstate/model 入口，可为 null。省略模型时使用保留的 PM 原始单套 `prototype_eng:parallelization_controller`。注册后库创建方块、物品与默认实体，重复 ID 或注册关闭后再登记会失败。

独立实现可以实现 `ParallelController`，通过 `snapshot()` 返回包含 `selected`、`maximum`、`revision`、`valid` 的不可变快照。设置或有效性改变时推进 revision；工作线程只能读取快照。宿主实现 `StructureComponentProvider`，返回 `new Entry<>(ParallelControllers.TYPE, "parallel", controller)` 后即可参与同一真实结构发现与并行汇总，不要求继承默认实体。若要复用右键配置界面，还需实现 `ParallelControllerGuiHost` 的宿主、名称与服务端设置职责。

### 仓室读写与容量

`ResourceHatch` 暴露输入和输出 `ResourceStorage`，`EnergyInput`、`EnergyOutput` 提供独立 FE 接口。结构匹配后发现的仓室保存在机器的 `MachineHatches` 组件中。

机器内部必须通过 `EnergyInput.extractEnergy/reserveExtractEnergy` 扣能，通过 `EnergyOutput.receiveEnergy/reserveReceiveEnergy` 写入产能，仅受未预留电量或剩余容量约束。禁止用 `EnergySnapshot.maxExtract/maxReceive` 限制机器消耗、配方并行分配或内部产能；这两个值只限制 Forge 接口和仓室自动传输。外部限速设为 0 时关闭对应外部转移，内部仍可使用缓存。已有预留的应用、完成和回滚沿原事务执行，不会因为调整外部限速失效。

外部端口可在持有 StorageContext 锁时调用 `EnergyHatch.externalReceiveLimit/externalExtractLimit` 读取限速。默认实现读取快照，内置 EnergyBuffer 直接读取字段，外部转移不会为取得限速额外创建能源快照。

普通搬运可以调用 `ResourceStorage.insert/extract`，传 `Actionable.SIMULATE` 先计算可处理数量，传 `MODULATE` 实际修改。配方执行使用需求系统的快照和预留，以保证多个需求或多台机器共享仓室时不会重复占用。

内置输入仓、输出仓和混合仓都支持在 GUI 插入 AE 资源包，按包内资源的真实种类和数量存储。未能存入的数量保留在资源包中，已经预留的空间不可占用。这个手动解包操作不改变机器和自动化的输入、输出方向，操作规则见[仓室指南](docs/hatches.md#ae-资源包插入)。

`me_input_assembly`、`me_output_assembly`、`me_interface` 和 `me_pattern_assembly` 是通用 ME 资源组件，不按物品、流体或气体拆分。输入总成与接口的标记表示期望保有量，由 ME 补足；输入槽数在 `me_assemblies.cfg` 的 `resources.inputSlots` 配置，默认 18。输出按真实 `AEKey` 缓存动态显示，每种资源支持到 `Long.MAX_VALUE`，不设种类上限。ME 资源总成只通过 ME 和机器事务转移实际缓存。

`me_pattern_mirror` 暴露稳定的 `ResourceHatch`，通过内存卡潜行右键样板总成、普通右键镜像绑定位置。机器访问缓存的源库存，保留原分区、StorageContext 和 observer 身份；未加载时供给为空，来源加载、替换、镜像重绑或卸载通过生命周期事件更新引用。旧端口和预留令牌失效，回滚仍操作原库存。镜像没有 GUI 或独立库存，也不强制加载源区块。内存卡普通右键其他样板总成还可复制配置并按 AE 规则支付、录入样板，见[样板镜像与内存卡](docs/block-registration.md#me-样板镜像与内存卡)。

所有资源仓室默认使用 AE2 福鲁伊克斯透明色（无色），并以无色输入形成独立库存；任一有色输入组也能通用读取这份库存，但不会读取其他不同颜色库存。开启样板槽隔离后，不再使用总成颜色：每个槽单独扫描，只接入自身输入、该总成共享催化剂及其他未分区无色库存，不与其他槽或有色普通仓拼料。未开启样板隔离时才按总成颜色分组。FE 仓室不分组；已运行进程保留选中的组。样板每页固定 36 槽，页数由 `patterns.pages` 配置；五种工作模式及实际 GUI 操作见[方块与 ME 总成](docs/block-registration.md)。

`ResourceHatchConfiguration.Side.fromItemEquivalent(...)` 以槽数和单槽物品等价值配置容量；流体默认每 1000 mB 对应一个物品单位。需要直接给每类资源设置原生容量时，向构造器传 `Reference2LongMap<AEKeyType>`。混合仓输入和输出分别配置。

`HatchConfigurationUpdates.configureDefaults(...)` 设置启动时的默认容量。运行中修改用 `request(server, targets)`，返回完成报告的 future。报告还包含各仓室的状态，忙碌或缩容后装不下的仓室可能暂缓应用。必须异步处理结果，禁止在服务器主线程阻塞等待。

### 研究与计算网络

`ResearchDefinition.builder(id)` 注册服务端研究定义，`.visibility(ResearchVisibility mode)` 设置总览可见性。`ALWAYS`（默认）不主动隐藏研究，`AFTER_PREREQUISITES` 要求达到全部前置要求等级，`AFTER_COMPLETION` 要求本研究至少完成一级。只要任一前置当前隐藏，依赖它的研究也隐藏。服务器按所需的最高前置等级检查跨等级段的前置要求；该设置不影响研究资格、入队或进度。服务端研究的基础展示 JSON 可通过 `visibility` 字段覆盖定义：`always`、`after_prerequisites` 或 `after_completion`。

基础展示来自服务端 `config/prototype_eng/research_content/data/<namespace>/<path>.json`。总览收到节点名称和图标后，客户端按当前语言覆盖文本；打开研究详情才请求完整内容。`ResearchIcons.register(kind, parser)` 在客户端初始化时注册图标解析器，接收完整图标 JSON。内置 `item` 按物品 ID 和可选 `meta` 解析为 `AEItemKey`，`fluid` 按 Forge 流体名称解析为 `AEFluidKey`，`icon` 按完整 AE 图标 ID 使用 `Icon.byId`。奖励组的标题和说明放在基础文件 `rewards` 对象中，使用完整奖励组 ID。

`RewardGroup(id, children)` 根据物品和事件生成默认展示，`RewardGroup(id, children, presentation)` 接收指定的 `RewardPresentation`。内置 `ItemRewardPresentation` 绘制物品槽和提示，`TextRewardPresentation` 显示一行文字，`CompositeRewardPresentation` 按子项尺寸分行。自定义类型实现 `type()`、`height(context, width)` 和 `render(context, x, y, width)`，也可重写 `width(context, available)` 指定组合中的占位宽度。在两端订阅 `RewardPresentationRegistrationEvent`，调用 `event.registry().register(typeId, implementationClass, encoder, decoder)`。编码器写出绘制所需数据；解码器构造同类型的展示实例。`RewardRenderContext` 提供物品槽、文字和悬停提示。客户端在请求研究详情时取得展示，实际奖励清单和发放回调由服务端执行。注册示例见[示例 mod](run/visual-examples/src/main/java/pe/visual/examples/ResearchOverviewExamples.java)。

分页文件由服务端读取，使用 `data/<pageId>.json`，可设置 `priority`（默认 0，越小越靠前）、`title`、`layout`、`researches` 和 `positions`。未设置 `researches` 时按页 ID 对应的研究命名空间分组；显式成员列表使用完整研究 ID，允许跨命名空间。没有可见研究的页不出现在普通总览中。`ResearchPage.move(String id, int x, int y)` 只更新内存坐标；授权编辑会话中的“全部导出”请求服务端保存所有分页的布局。语言文件的编辑结果仍保存在当前客户端。完整格式见[研究总览](docs/research-overview.md)。

`com.circulation.prototype_eng.api.gui.ResearchGuiAccess.open(EntityPlayerMP)` 在服务器打开玩家总览，`open(EntityPlayerMP, MachineControllerHost)` 以指定物理数据库打开下载目标选择模式；仅限客户端的 `requestOpen()` 请求服务器打开玩家模式。库不注册打开按键或物品，入口由 addon 决定，测试 mod 默认使用 R。数据库界面已通过容器接入选择和返回，保留原数据库的操作槽物品；`returnToDatabase(player, database, @Nullable selected)` 打开原库并提交可选研究 ID。编辑命令为 `/pe research edit [在线玩家名]`，执行者须有权限等级 2；省略参数时打开自己，指定在线玩家则打开对方，控制台必须指定玩家。`openEditor(EntityPlayerMP)` 保留本人管理员入口，`openEditor(ICommandSender authority, EntityPlayerMP target)` 为同服目标绑定本次新打开的准确容器会话。目标不需要成为 OP；关闭/更换容器、授权者离线/重连/失权使会话失效，编辑提交时再次校验。该授权允许保存服务端布局和基础展示、立即完成研究及写入特殊研究；常规研究操作仍使用目标本人的权限。编辑模式通过布局按钮切换自动/手动，手动才允许拖动；“全部导出”保存全部分页。`ResearchWorld.setAllForEditor(membership, permission, complete, time, online, inventories, current)` 按当前全体队员批量处理全部普通研究，complete 为 true 时完成到最高级并生成奖励资格，为 false 时清除个人和旧直属等级；两者清除队列并保留奖励记录及已付材料进度，要求有效管理员编辑授权。

`ResearchOverviewViewState.load(Path configRoot)` / `save(Path configRoot, State state)` 保存客户端视角到 `prototype_eng/client/research_overview_view.json`。`State(String page, double panX, double panY, double zoom)` 与研究内容及作者布局分开保存；加载要求非空白分页、有限坐标和 `0.5..2` 的缩放，文件缺失、损坏或无效时返回 null，保存失败记录告警。正式 GUI 在尺寸、分页与玩家数据就绪后恢复一次，缺页回退，resize 不重新覆盖当前视角。

`ResearchWorld.saveChanged(long time)` 是所属服务器线程的世界保存入口，时间不能倒退；无变更、无检查点请求且无失败待重试快照时不调用磁盘 writer，忙碌时登记检查点请求。普通研究站的时间、材料与网络累计先标记 dirty；手动无付款且未完成的纯时间推进也延后到世界保存。入队、管理、背包扣料、奖励、下载和导入继续同步保存；显式 `checkpoint(long time)` 请求耐久检查点，活动事务期间保留请求。`ManualResearch.Checkpoint.progressed(state)` 默认调用 `persist(state)`，仅世界适配器覆盖上述纯时间延后规则。服务器按玩家、队伍和单笔操作分片并使用 pending redo，只替换变化记录；仍捕获和编码完整业务快照来识别变化。旧单文件仅首次迁移并保留原字节，迁移后不以旧快照替代损坏的新存档；文件、校验和恢复规则见[研究增量存档](docs/research-persistence.md)。

`ResearchLevelStage.builder(first, last)` 必须设置 `.durationTicks(long)`、研究阶数和网络资源公式，多个阶段完整覆盖 `1..maxLevel`。时间为单站基准 tick，多站可加速。阶数 0 使用玩家手动研究，只允许 `AEItemKey` 材料且禁止网络资源；正阶由研究站推进，可使用其他 `AEKey` 材料。`.material(AEKey, long)` 添加普通资源总量，`.materialRequirement(...)` 只接受固定静态输入。

`ResearchResourceFormula.builder().constant(type, total)` / `.affine(type, base, slope)` 定义网络资源总量，`.perTick(type, limit)` / `.affinePerTick(type, base, slope)` 按单站每 tick 用量定义，并乘研究时间 T 换算为总量。等级增长使用 `目标等级 - 阶段首级`，数量为 double；同一资源重复添加及混用总量/tick 定义会相加。`ResearchLevelStage.resourceDemandForLevel(int level)` 返回合计总量，`resourceLimitsForLevel(int level)` 返回合计总量除以 T 的单站限制，均校验等级属于本阶段。实际网络增量同时受该限制、研究站自身 `rates`、可用网络量及剩余需求约束。公式直接借用 builder 完成的 Map，交付后必须停止修改；总览不显示“单站上限”文案，业务公式与研究站速率接口仍保留。普通研究完成时对当时的全体队员写个人等级，队伍按当前成员最高等级聚合。`DATABASE_AND_ITEM` 可写入数据库或下载成物品；`ITEM_ONLY` 仅改变交付方式，不改变等级归属。

计算网络资源由使用方实现并复用 `NetworkResourceType` 单例，不需要资源注册，库没有默认资源。提供资源的物品实现 `NetworkComputerComponent`；已有显式物品适配仍可用组件注册事件，但登记适配不会使普通物品符合默认运算仓的插入条件。物品接口定义优先于适配表。机器用 `MachineOwnership` 保存所有者，`MachineNetworkDevice` 声明角色，`NetworkRecipeWork` 处理研究和数值需求。

默认 `NetworkCenterController(type, level, threadCapacity, energyPerTick)` 提供网络等级和线程额度；默认 `ResearchStationController(type, level, rates, energyPerTick)` 提供研究等级、各资源每 tick 推进速率及 FE 消耗。二者直接继承 `MachineInstance`，不安装配方工位或机器业务 tick。中心通过网络发布服务工作，研究站通过共享研究服务工作。中心旧的两参数/三参数构造器保留，省略的线程额度为 0，两参数时耗能也为 0。

只有活动中心的供给计入队伍，并与计算机和外部参与者相加。并发是独立整数限制，中心不占用，其他工作机器共用整机租约。`NetworkCenter.providedConcurrency()` 提供并发；`networkResources()` 提供double资源表，默认控制器的资源表为空，自定义中心可提供自己的资源。

资源实现 `NetworkResourceType extends PacketWritable`，资源表、速率及研究需求均用 `Reference2DoubleMap<NetworkResourceType>`，按对象身份处理，复用单例并用 `addTo` 累加。数量为非负有限 double，保留真实算术溢出预检；并发单独使用 long，不注册为资源。默认便利入口 `NetworkResourceType.network(id, name, pattern)` / `.computer(...)`。Java addon 跨 ZS loader 暴露资源时必须包装同一个单例为 `ZenComputingResource`，禁止按相同 ID 重新创建；`Bindings` 只保存配方声明，不存取计算资源。详见[网络资源与展示包](docs/network-resources.md)。

Java 分别给类型声明 `MachineOwnership.TYPE` 和 `NetworkCenter.DEVICE` / `ResearchStation.DEVICE`，给装配指定实例工厂并将对应 `DEVICE` 标为 `recreated`。例如研究站装配：

```java
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.ResearchStationController;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.research.station.ResearchStation;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;

public final class ResearchStationRegistration {
    public static MachineAssembly assembly(MachineType type, NetworkResourceType compute) {
        return MachineAssembly.builder(type, "research-station-v1")
            .instanceFactory(value -> new ResearchStationController(value, 1,
                Reference2DoubleMaps.singleton(compute, 10), 8))
            .recreated(ResearchStation.DEVICE).build();
    }
}
```

`rates` 是 `Reference2DoubleMap<NetworkResourceType>`，未列资源速率为0；定义后按只读要求借用，不复制。速率由开发者定义，等级与非负FE在加载代固定。自定义运行时可直接实现 `NetworkCenter` 或 `ResearchStation`，无业务tick的实现返回 `requiresTick() = false`。

普通资源各自已支付比例等权累计开放进度，例如 A×2、B×10、C×100，支付 A×1 开放 1/6；缺少一种材料可先支付其他种类。各项网络资源在开放比例内独立累计，网络增量同时受普通设备结算后余额、各站有效速率和剩余总量限制。总时间必须同时满足材料开放比例和每项网络资源累计比例所支持的完整 tick，任一正需求的网络资源累计为 0 时不能推进；供给较快的资源可先累计，补足落后资源后继续推进。每台实际工作站占 1 并发并支付 FE，材料、FE、网络量、进度共同提交；付款不足以形成 1 tick 时也按工作收费。中心只对生效者收费，同设备同 tick 不重复扣费。详细计算见[研究站](docs/research-stations.md)。

研究阶级大于 0 时，开始研究要求有效网络中心与等级不低于研究阶级的研究站；材料汇总与推进也只检查研究站的等级和网络授权，不要求数据库。`TeamResearchStart.Environment(networkCenterLevel, highestResearchStationLevel, availableResources)` 捕获这两项设备等级及当前资源。总览详情合并为一行有效研究站需求，显示两项等级的较小值；顶部设备概况仍分别显示实际等级。

总览和研究站的材料显示使用“剩余可用 / 剩余需消耗”，可用量封顶到剩余需求。普通研究汇总本队合格研究站可供料输入；手动队首只读取实际贡献者的在线背包，无贡献者或暂停时不借用其他玩家或机器。总览无正在进行的研究时隐藏左侧状态面板，右上按数据库阶级显示已存/总容量，并显示网络中心与最高研究站等级。

`ResearchWorld` 的 writer 只保存传入的只读快照，禁止修改研究源或重入研究操作。写入失败会保留 pendingSave，后续状态修改前先重试同一快照，禁止在 writer 内修改快照或重新推进业务。

研究定义不包含下载等级费用。`ResearchItemDownloadRegistrationEvent.registry()` 注册自定义下载到物品的配方，`.imports()` 注册自定义物品导入数据库的配方。两份注册表的 `recipes()` 返回只读借用的随机访问列表。JEI 从这两类自定义定义自动生成输入、输出及研究说明，不运行转换回调。实现 `ResearchDiskCarrier` 的物品直接支持默认存盘下载与导入，禁止为这两项默认操作注册配方或生成 JEI 页面；使用方自行说明存盘用法。

`ResearchItemDownloadRegistry.register(id, material, researchId, researchLevel, levelCost, outputTemplate[, event])` 每次支付一件输入和配方定义的来源等级。静态重载使用 outputTemplate，event 重载通过纯 `createOutput(context)` 生成实际输出；模板仍用于 JEI。回调返回 `ItemStack.EMPTY` 表示本次没有匹配输出，不报告配方错误。默认空盘下载每次支付来源 1 级、编码所选研究 1 级，没有用户选择等级或存盘配方注册入口。

`ServerResearch.requestItemDownloadPreview(...)` 接收原输入库存与交付适配，生成服务端候选；`ResearchItemDownloadPreview` 借用准确原输入、输出与来源付款计划。其 `teamLevelLoss()` 是本次付款后队伍可见等级的实际下降，0 显示“无”；不能把它当作免除配方 levelCost 的条件。`requestItemDownload(..., preview, nonce, inventory, delivery, current, result)` 重新捕获玩家权限、原数据库及输入后冻结候选，禁止再次调用事件改变已经预览的产物。

`ResearchItemImportRegistry.register(id, material, researchId, levels, outputTemplate[, event])` 注册单输入、单输出导入。event 返回 `ResearchItemImportEvaluation(ResearchDiskData, output)`，其中研究等级表示本次增加量；返回 null 表示本次不匹配。默认存盘导入直接读取实际物品接口，对盘内每项研究增加 1 级并返回同一物品的空盘。研究最高等级、数据库阶级、容量及互斥检查作用于累加后的内容，不能以取最大值代替累加。模板和回调结果按只读要求借用，不在构造器或 getter 中重复复制。

`ResearchDiskCarrier.writeResearchDisk(stack, data)` 与 `emptyResearchDisk(stack)` 返回独立产物，输入为只读借用。默认方法仅写入或删除研究命名空间，保留物品其他 NBT 和 ForgeCaps。自定义存盘布局必须同时覆盖读取、写入和清空方法；物理阶级由 `researchDiskTier(actualStack)` 提供。

`ServerResearch.requestItemImportPreview(actor, team, databaseId, slot, inventory, delivery, current, result)` 生成材料、输出和累加后的数据库候选。`requestItemImport(..., preview, nonce, inventory, delivery, current, result)` 使用同一候选执行，不再次调用转换事件。两类预览及直接执行入口在付款前调用 `ItemDeliveryAdapter.canOutput(nativeTemplate, amount)` 检查实际完整产物；有限容量的自定义交付适配必须覆盖该方法。已付款操作按保存的交付计划恢复，不再使用初始空槽条件拒绝原义务。

默认 `MachineDataCenterStorage.transfers()` 提供数据库持久输入槽 0、输出槽 1。`forPlayer(uuid)` 的适配同时提供 `ResearchDownloadInventory` 和 `ItemDeliveryAdapter`；原输入付款及输出交付使用该物理库，同一组件保存库存和回执。研究世界记录原物理目标，卸载或重选库不能把未完成操作转移给玩家背包或新数据库。`ServerResearch.requestItemImportFromDataCenter(...)` 使用此库存执行导入；云端写库方法继续保留独立调用。`DataCenterStorage.removeResearch(id, current)` 与 `requestDataCenterRemove(...)` 负责按独立权限移出同队物理库条目，释放容量且不扣队伍研究等级。

完整 Java 示例见[NetworkCenterExamples.java](run/visual-examples/src/main/java/pe/visual/examples/NetworkCenterExamples.java)和[ResearchStationExamples.java](run/visual-examples/src/main/java/pe/visual/examples/ResearchStationExamples.java)。网络中心与研究站正式 GUI 均已接入。研究站队列操作和增量同步见[研究 GUI](docs/research-gui.md)。

### 资源展示包与增量同步

自定义资源提供 `packetType()` 和 `writeToPacket(ByteBuf)`，并在两侧mod初始化时通过 `NetworkResourcePackets.register(packetType, decoder)` 提供该包布局的解码函数；不逐项注册资源。固定展示数据在单例生命周期内保持不变，客户端实现 `displayName()`、`format(double)`。默认格式资源包发送翻译键与Java数量格式，例如 `%.2f FLOP/t`。

网络中心通过AE的 `PacketWritable` 增量发布：新增资源发送固定展示包与初值，后续只发送索引、字段标记和变化的double，归零发送移除索引。新容器取得完整基线，稳定数据不发送更新。客户端保留行，宽384、高162～242，超过5行才显示滑块，滚动不请求服务器。

### 计算机与运算仓

默认 `ComputerController.DEVICE` 自动使用计算机专用的概览和组件GUI，无需Java或ZS另行注册。自定义显示继续使用 `MachineGuiRegistry` 和 `MachineScreenRegistry`。`NetworkComputer` 接口只暴露并发和外部资源；默认控制器的 `installedInventory()`、`supplyState()`、`availableForComponents(resource)` 供所属线程边界读取，返回的列表与资源Map必须保持只读。

`NetworkComputer` 只暴露独立整数 `providedConcurrency()` 和R2D资源表 `providedResources()`。默认可继承 `ComputerController` 组合现有组件、材料事务和网络发布，无普通配方工位，本体不耗FE；声明纯输入配方时在所属线程执行普通配方进程。

只从 `ComputationHatch` 读取安装组件，组成变化由事件通知，稳定时复用缓存。四级 `computation_hatch_1..4` 分别为1/2/3/4槽；每槽物品1件，非物品原生容量1000/4000/16000/64000。物品的 Item 必须实现 `NetworkComputerComponent`，非物品类型不限。共享库存接受外部插入/抽取，没有自动IO。

组件返回 `new NetworkComputerComponent.Capture(concurrencyPerItem, provided, requiredResources, energyPerTick, currentProof)`。资源或FE不足时全部组件供给停摆，不扣组件FE。`ComputerRecipe(id, unitInputs, unitResources)` 按组件缺口向上取整计算所需份数；已有完整份数即使不足也支付，不满一份的余料不参与。转换仅供本机组件使用，不直接输出网络资源。`unitInputs()`、`unitResources()` 提供只读单位定义，`installedComponents()` 返回安装组件身份与定义。

`ComputerInputRecipe(id, revision, duration, inputs, callback)` 通过 `.computerInputRecipe(recipe)` 接入普通 START/TICK/END 输入、时长、暂停与保存，duration 使用 int；研究时间使用 long。END 一次输入用静态 `endInput(requirement)` 包装只声明 START 支付的需求。事件直接修改资源、并发、需求和能耗倍率，没有固定输出定义。阶段成功结果用于下一服务器 tick 的供给结算。Java完整示例、ZS签名及保存规则见[网络设备事件](docs/network-events.md)。

`NetworkResourceType.computer(id, name, pattern)` 或自定义internal资源声明本机资源，只在计算机内产生和占用，不进入队伍网络。具体控制器的并发/资源上限不构成类型固有限制。

Java 装配必须用 `MachineAssembly.Builder.computerRecipes(recipes)` 提供控制器使用的同一份完整只读配方列表，后续不得修改；ZS 的 `MachineType.computer(...)` 自动保存。JEI 为计算机注册独立分类和控制器入口，显示每单位AE材料输入及本机换算资源；纯输入定义从类型声明自动生成时长和阶段输入页面。资源名称和 double 数量分别调用 `NetworkResourceType.displayName()`、`format(double)`，不注册计算资源为物料。`ComputerRecipe.materialRecipe()` 和 `unitInputs()` 提供材料定义；展示不创建运行实例或执行配方条件。

计算机启动须先取得中心或其他已获准设备提供的 1 份并发，活动数据库占 1 份，中心不占用。计算机与数据库优先于普通设备，可收回普通租约并使其未提交凭据失效；同条件使用持久 UUID。研究站在普通设备后使用剩余并发，实际参与时各占 1 份。尚未获准的计算机只预留、不支付材料或组件 FE。

ZS入口为 `MachineType.computer(id, title, structure, maximumThreads, limits, recipes)`；单位转换用 `ComputerRecipe.create(id, Resource[] unitInputs, ComputingResources unitResources)`；资源用 `ComputingResource.network(id, translationKey, pattern)` / `.computer(...)`。自定义Java资源可经 `ZenComputingResource` 包装器交给脚本。计算机GUI保留概览与组件两页，配方展示统一使用JEI，见[计算机](docs/computers.md)。

所有接网控制器支持 `MachineType.Builder.networkEvent(callback)`，Java全局事件用 `NetworkDeviceEvents.register(callback)`，ZS全局事件用 `Events.onDevice(callback)`。全局事件先于类型事件。事件公开同队全部已加载设备快照，允许修改本设备资源、并发、研究站速率和 FE 输入倍率；资源表通过 `get/set/add/remove` 修改，`add` 使用 `addTo`。ZS 的 `DeviceEvent` 暴露 `network/values/machineType`，`ComputerRecipeEvent` 另提供 `stage/progress/duration`；`network` 提供 `teamId/tick/devices/hasDevice(typeId)`，`values` 提供 `resources/required/researchRates/concurrency/energyMultiplier`。完整调用顺序、设备字段及禁止外部副作用的说明见[网络设备事件](docs/network-events.md)。

默认数据库使用 `DataCenterController(type, storageComponent)`，`storageComponent` 是已声明的 `DataCenterStorage` 组件类型。等级、容量、研究和操作库存由存储组件保存，控制器组合使用持久化、下载及自动升级，无配方工位或独立业务 tick，并可继承。类型声明 `MachineOwnership.TYPE`、存储组件和 `DataCenter.DEVICE`；装配为存储配置 codec，将网络角色标为 recreated。自定义控制器也可直接实现 `DataCenter`，通过 `dataCenterStorage()` 提供实际存储组件。活动数据库占 1 份优先并发。默认 GUI 已接入 384×290 的已存列表、二次确认移出、下载/导入操作区及完整玩家背包，选择研究按钮通过专用容器打开总览并返回原数据库。专用容器使用 `MachineDataCenterStorage.transfers()`；采用其他 `DataCenterStorage` 实现的控制器必须通过 `MachineGuiRegistry` 和 `MachineScreenRegistry` 绑定对应容器与界面。Java/ZS 入口见[默认数据库控制器](docs/data-centers.md#默认控制器2026-11-13)。

### GUI、模型与 JEI

`MachineGuiRegistry.register(typeId, factory)` 在双方初始化时注册公共侧容器；客户端用 `MachineScreenRegistry.register(typeId, containerClass, factory)` 注册匹配屏幕。容器直接继承 `AEBaseContainer`，屏幕直接继承 `AEBaseGui`。默认普通机器、中心、计算机和研究站已经使用对应容器与屏幕，自定义界面只注册需要覆盖的类型。

`MachineRenderRegistrationEvent` 在客户端 postInit 派发，通过 `event.bindings()` 的 `bindMachine/bindComponent/bindStructure` 分别绑定机器、实际组件和结构；提前声明可用 `ClientRenderDeclarations.register`。返回的 `Registration` 可以关闭绑定，旧句柄不能删除后续替换的新绑定。该 bindings 的 `view()` Map 只读，使用同一 bindings 的 `current(view)` 检查实际视图身份。必须把屏幕、原生渲染器、GL 调用和 JEI 类限制在客户端加载。

`GeckoModelBinding.layers` 和 `MachineAnimationState` 发布的层列表在请求、快照或派生缓存仍被保留期间必须保持不变。未提供 animation 资源时不应用动画层；否则选择顺序为层列表非空的 `MachineAnimationState`、数据多层键、数据单层键、声明 layers、默认动画名，后面的层覆盖冲突骨骼通道。模型 offset 随机器朝向变换，yOffset 沿世界 Y 添加。`MachineRenderSnapshot`、预览模型的 cells/BOM/occurrences 和 occurrence 路径只读交付，禁止在构造器、getter 或每帧重复复制。

`PackedVertexDataWriter.write(data, offset, length)` 必须在回调内同步读取指定跨度，禁止保留或修改数组，下一次输出会复用 scratch。独立实体可继承 `MachineControllerRenderer<T>` 并在客户端 `register()`；默认 renderer 已注册，普通调用只提交模型，库在原生 TESR `drawBatch` 后统一绘制。当前模型缓存只在创建新条目时选择资源，控制器24种朝向预先烘焙；Mixin 与资源读取依据见 [mixins.md](docs/mixins.md)。

普通配方池由库提供 JEI 页面。无辅助材料的单输入池使用专用单输入布局；有公共辅助材料时显示完整输入，并沿通用材料分页保留全部 JEI 查询与样板导入。配方池的 `withExistingJeiPage(uid)` 返回带外部分类入口的新池，必须保留并注册该返回值。目标分类实际存在时只补充控制器入口，不重复生成本池页面。

`StructurePreviews.client().request(id, settings)` 从服务端已同步的目录请求预览，缺少条目时返回 null。非空请求分次 `advance(maxSteps)`，`READY` 后才能取得完整 `model()`；`STALE` 必须重新请求，`FAILED` 保存实际错误。已完成模型使用 `StructurePreviews.client().current(model)` 检查当前目录。`StructurePreviewScreens.open(...)` 打开预览，`StructurePreviewScreens.panel(...)` 为自定义 JEI 页面提供面板，`StructurePreviews.client().project(...)` 设置世界投影；这些入口仅限客户端。

`StructurePreviewSettings` 借用 sliceCounts、displayOptions、cellOptions，改变选择时建立新配置；`explicitOption(cell)` 的 `-1` 表示没有显式选择。`StructureProjectionOptions.layer()` 的 null 表示三维模式，层号为相对 Y 坐标且允许负数。`StructurePreviewModel.Builder.freeze(bounds)` 直接交付完成列表，调用后必须停止使用 builder。

### 运行中替换配方与保存版本

`RecipeProcessNbt` 负责保存和恢复配方进程。配方时长、需求行为或组件保存格式改变后，必须更新配方 revision，并保留仍要恢复的旧版 codec。机器装配的 revision 则标识机器组件布局和保存方式。

`RecipeProcessNbt.withRecipe(preparedRecipe)` 把已有 codec 绑定到相同 ID 的新完整定义，保留其 revision、属性编解码、自定义组件、overlay 和校验函数。必须保证原校验函数接受新定义；需求行为变化后使用更新过的 revision 构造 codec，禁止仅靠相同 ID 判断保存兼容。

本库存档与内部同步编解码必须直接处理业务字段，禁止附加固定 `Version`/`Schema` 数字及对应拒绝检查。NBT Map/Set 按键写入，集合唯一性由生产方负责；禁止为普通数据解码建立重复键检查集合。配方和机器定义的 `revision` 用于选择实际声明及恢复代码，网络增量序号用于识别新旧发布，继续按各自接口规则使用。

需要跟随运行中目录替换时，扫描器与工作槽应共用 `RecipeProcessCodecs`，并调用 `RecipeRegistry.bindProcessCodecs(...)` 和扫描器的 `follow(...)`。目录替换只影响新扫描，已经开始的进程继续使用原配方和原 codec。客户端也必须有相应的新定义，才能正确显示更新后的配方。初始化期的 `register(...)` 不能用来修改运行中的目录。

`RecipeDefinitionUpdates.forServer(server).replace(expectedSession, expectedSnapshot, definitions, codecs)` 在服务器工作线程退出后的边界准备并发布新目录；请求集合及元素必须保持不变直到 future 完成。已由同一发布入口准备的目录可用 `submit(expectedSession, prepared)` 提交。结果必须同时检查 `status()` 和 `published()`：`NOTIFICATION_FAILED` 表示安装后通知失败，`INSTALLATION_FAILED` 也可能已经安装，禁止仅按失败状态重复提交。

## Java：添加自己的行为

### 自定义机器组件

组件把每台机器的状态和每 tick 的工作分开。`ComponentType<E,C>` 描述组件 ID、创建函数、依赖和系统；`ComponentSystem<E,C>` 提供 Pre、Normal、Post 回调。机器组件通常使用 `MachineSystem<C>`。

创建函数可以用 `Function<E,C>`，也可以用 `BiFunction<E,ComponentType<E,C>,C>`。后一种在创建时直接收到 owner 和当前 type，组件需要保存自身绑定时使用该参数，禁止为取得 type 重复创建定义或借助可变数组保存引用。

`runBefore/runAfter` 按系统 ID 指定顺序，组件依赖则使用实际的 `ComponentType` 对象。系统实例会被多台机器共用，必须把计数器、缓存等可变状态放在组件中，不能放在共享系统实例里。

有持久状态的组件实现 `MachineComponentCodec<C>`，再用 `assembly.component(type, codec)` 绑定。`encode(owner, component)` 返回独立于后续可变状态的只读 NBT，可复用版本快照；调用方禁止修改返回标签。`restore(owner, component, saved)` 在全部组件创建后按依赖顺序恢复尚未发布的新组件，允许返回替换组件。`validate(owner, component)` 在全部恢复完成后检查组件状态，不接收保存 NBT。输入标签只读，恢复时可借用只读子标签；需要继续修改的数据必须建立自己的状态。无状态且可重建的组件使用 `recreated(type)`。组件保存配置必须与机器类型声明一致。

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

包装需求通过 `children(payload)` 声明不可变的完整结构子需求，用于能力判断和嵌套存档绑定，该方法不执行子需求。会有序取得后续步骤的系统必须在取得前用 `continuedAcquisition(payload)` 声明；事务的 `continuation()` 返回稳定且无副作用的游标，`acquireNext()` 返回下一事务或以 null 结束。外层执行器拥有每一步的取得、可撤销提交、完成与反序撤销，扩展不能自行结算所返回的资源令牌或后续事务。`reservations()` 必须交付全部已取得令牌，包括拒绝前取得的部分，返回后保持稳定只读。

[ExampleRequirements.java](docs/api-examples/java/ExampleRequirements.java)提供机器等级条件：在机器类型上声明等级属性，在配方中加入最低等级需求，开始时比较属性。它不扣料，因此成功时返回共享成功事务；不足时返回带本地化原因的阻塞结果。若等级需要全程满足，还必须在 `tick` 中继续检查。

`RequirementTransaction` 让一组需求能够共同成功或撤销。取得阶段先准备效果和资源预留，执行器检查全部需求后才提交。`commit` 应用业务效果，`rollback` 撤销已经取得或提交的效果，`complete` 在整组成功后释放补偿信息。必须能撤销部分提交，禁止在 `complete` 中再扣材料或进行可能拒绝业务的操作；否则其他需求失败时无法完整撤回。

`prepareCommitWithinBudget`、`validateCommitWithinBudget` 和资源令牌的预算验证返回 `BudgetResult`。收到 `YIELDED` 后必须继续推进同一个事务，禁止重新取得资源或重放已完成效果；`REJECTED` 按整组撤销处理。仅表示操作是否完成的 `advance/commitWithinBudget/rollbackWithinBudget` 继续返回 boolean；结果查询 `resolutionWithinBudget` 返回 null 表示查询暂停，`UNKNOWN` 表示该次效果尚未确认。结构快照的预算比较与有效性判断也使用 `BudgetResult`，无预算重载仍返回 boolean。

`MachineAttributes.LocalAttribute.baseOverride()` 使用原始 double，`Double.NaN` 表示未设置，必须用 `Double.isNaN` 判断；实际基础值、默认值和计算结果必须有限。`EnergyRequirementPatch(long inputsPerTick, long outputsPerTick, int voidExcessOutputs)` 的数量以 `-1` 保留原值、`0` 清零。它与 `ResourceRequirementPatch` 的布尔覆盖均使用 int，`-1` 保留、`0` 关闭、`1` 开启；调用方必须使用这三个选项值。对象字段继续用 null 保留，空资源列表清空。

大计算可通过 `prepare(process, requirement, stage)` 捕获不可变输入并返回纯计算任务，把计算交给工作线程。`prepare` 本身不能扣料或修改世界；需要保留主线程的需求仍应声明 `requiresOwnerThread()`。如果自定义需求还访问结构仓室之外的物理存储，必须用 `storageContexts(...)` 提前声明完整范围，让执行器统一协调这些存储；禁止开始取得资源后再临时添加未声明的存储。

资源不足返回 `ProcessResult.Blocked`，不可恢复的业务失败返回 `ProcessResult.Failure`。没有逐 tick IO 的需求可让 `crossRecipeTickIo` 返回 `null`；有 IO 时，只有相同返回值确实表示相同单位输入输出，才能允许合批。需要随批次时间重分配 IO 的扩展，还必须正确实现重计量规则，才可声明 `crossRecipeRetimesTickIo`。

`PlannedExecution.cancel(reason)` 撤销后续执行权限，已接受的异步工作仍须实际退出。`awaitExit()` 等待执行退出，`finished()` 表示资源、回调与所有权责任已经处理完成，两项都满足后才能丢弃执行对象。取消后的新 cleanup deadline 只能继续原取消与补偿，不能重新获得前向执行权限。

### 自定义配方查找

实现 `RecipePool` 可为大型或特殊配方集提供自己的查找方式。`recipes()` 提供池内配方，`match(...)` 处理库存匹配，`beginMatch(...)` 和 `beginCandidates(...)` 允许把较大的查找分次推进。`freeze(recipes, machines)` 在目录冻结或替换时生成新池，必须保留传入对象及顺序、池 ID、标题、JEI 元数据和查找策略。两份列表在定义生命周期内只读借用，自定义池必须遵守 `RecipePool.freeze` 的参数和返回值要求。

匹配只能读取传入的快照与已经捕获的机器信息。必须让结果包含所有仍可能成功的候选，禁止在查找时扣料、执行 START 或访问世界。候选索引返回 `null` 表示不筛选，空集合表示没有候选，两者的含义不同。

需要改变控制器的池选择时，可继承 `MultithreadController` 或 `CrossRecipeController` 并覆写 `getRecipePools(...)`；创建宿主和扫描器可分别覆写 `createRecipeHost(...)`、`createRecipeScanner(...)`。最后必须把子类构造函数交给 `MachineAssembly.instanceFactory(...)`，库才会实际创建该子类。

### 自定义结构条件与形状

单格条件实现 `StructurePredicate`。需要生物群系、方块 NBT 或 addon 数据时，用 `StructureQuery` 声明采样。普通 reader 在服务器主线程读取；copier 只在捕获时分离一次可变数据，不可变数据可直接返回原对象。匹配回调通过 `StructureFacts` 只读访问结果。显式 block-only `workerReader` 仅能在服务器主线程实际等待该任务完成的读取窗口内访问预先捕获的已加载方块引用。必须提前声明所有读取位置，禁止异步验证器直接访问 `World`、TileEntity 或强制加载区块。

reader 已建立独立数据时可用 identity copier，内置 `TILE_NBT` 直接交付新标签。`StructureSnapshot.read` 的合法空值由查询类型定义，未声明、未加载或采样失败按相应错误处理。`StructureReadSource.beginWorkerRead` 返回 null 表示位置在借用前失效，非空窗口仍可返回合法 null；窗口必须在原创建线程关闭。结构有效性检查必须在同一个宿主执行窗口内完成，预算不足后禁止拼接不同 tick 的检查前缀。

需要全新形状时实现 `MachineStructure`。`transform` 同时变换坐标、谓词、验证器和子结构；`collectReads` 声明读取范围；`match` 根据快照产生独立的匹配树，失败返回 `null`。只有几何和读取声明终身不变时才能启用 `planReusable`，每次请求的游标与临时状态仍须独立。

### 自定义仓室与共享存储

`ResourceStorage` 实现需要支持普通读写、数量快照、revision 和预留。`reserveExtract/reserveInsert` 返回 `ReservationResult`，必须在 `status() == ACCEPTED` 后读取 `reservation()`；`INVALID_STORAGE`、`STALE_SNAPSHOT`、`INSUFFICIENT` 表示真实拒绝，令牌为空。令牌负责占用、提交和回滚。

`StorageSnapshot.available()` 是只读规划数量表，不得修改；实际扣料通过预留和事务完成，原存储版本或范围失效后必须重新捕获。`snapshotFor(AEKey key)` 和 `beginQuantityCapture(AEKey key)` 提供单键规划入口，默认实现可回退完整快照；不带 key 的 `beginQuantityCapture()` 捕获完整数量。保留同一 `QuantityCapture` 并反复 `advance(RecipeWorkBudget budget)`，完成后读取只读 `available()`，使用前通过 `current()` 检查版本。

大批量预留用 `beginReserveExtract/beginReserveInsert`：它们按请求上限取得，`amount()` 返回实际取得量，可能小于请求。`advance(budget)` 完成后才能读取 `result()`，成功读取把令牌清理责任交给调用方。未转交且 `hasObligations()` 的捕获必须完成 `cancel(budget)`；返回 false 时必须继续清理。`ResourceReservation` 的状态方法在原 `StorageContext` 锁内执行，完整批次通过 `ResourceBatch` fence 协调。`UNKNOWN` 不允许重放原提交或终态回调，必须保留原事务直到结果明确。

同一物理余额必须返回同一个 `StorageContext` 和一致的存储 identity，包括资源和 FE 适配器。这样库才能去除重复来源并协调共享仓室。禁止为同一库存创建多个互不关联的 context；否则并行判断会把一份库存计算多次。输入输出确实独立的混合仓应分别提供 context。

`ResourceHatch.inputGroup()` 返回 `ResourceInputGroup(color, partition)`；默认 `AEColor.TRANSPARENT`（无色）、空分区。只有无色且空分区的库存是通用库存。非空 partition 表示独立库存，其颜色规范为透明色并保留完整分区标识，不受总成颜色影响，也不向其他组暴露为通用输入。可配置仓室实现 `ResourceHatchGrouping` 的颜色读写接口。`inputPartitions()` 可暴露多个稳定的物理视图；每个视图仍须保留原存储 context 和 identity。需要向同一总成各分区提供催化剂时，视图返回 `sharedInput() == true`，并使用该总成稳定分区前缀；隔离槽可共同读取未分区无色库存。外部实现改变分区成员后，旧捕获会失效；隔离进程按稳定分区恢复，不因总成染色改变归属。

实现 `ResourceRecipeObserver` 可接收真实供料后的 `inputsCommitted(process, stage, amounts)`，以及进程装入、退出与阶段成功结算通知。`amounts` 只包含该 observer 实际提供且完整阶段已提交的输入；探测和回滚不通知，回调在存储锁外执行。回调应只更新同步状态，不能从工作线程访问世界。需要跨存档关联时使用 `RecipeProcess.identity()` 的 UUID，codec 会保存它；配方 ID 或随机种子不能替代进程身份。

回调内可读 `process.committedInputs(stage)` 获取同一成功阶段跨所有来源的完整实际输入；它先于来源回调发布，新阶段、探测或回滚后不会沿用旧表，不写入存档。单种类跨来源总量超出 long 时返回 null，保持原事务成功语义；同一 observer 的来源合计超 long 时会分块通知，每块仍为精确正额，调用方不能假设一阶段只有一次回调。样板总成先用自身正额回执证明归属，再使用完整实际输入比例匹配正在运行的配方。

### 研究、网络和客户端扩展

`ResearchStartCondition` 添加研究资格判断，`RewardEventAction` 执行外部奖励。奖励可能因保存确认失败而重试，必须按 `RewardEventKey` 记录是否已应用；已发放的效果在重试时返回 `ALREADY_APPLIED`，禁止再次发放。

`NetworkRecipeExtension` 在目录发布时为配方追加网络需求。外部宿主实现 `NetworkParticipant`，通过 `NetworkParticipants.forServer(server).register(participant)` 接入该服务器已有网络，保留 `Registration` 并在失效时关闭；`OwnerTeamResolver` 可替换团队来源，返回当前成员的独立快照。

外部工作先读取登记句柄的可空 `snapshot()`，再调用 `plan(UUID planId, Snapshot snapshot, ResourceLocation workType, @Nullable ResourceLocation materialSource)`；必须使用该句柄返回的当前快照对象，只在 `status() == ACCEPTED` 时读取 `work()`。`Work.commit/cancel` 按结果处理，`CLEANUP_PENDING` 保留原句柄继续确认清理，禁止重放原 commit。已完成工作用显式 `compensate()` 撤销，退款仍归原 tick；关闭旧登记句柄不会解除后来的登记。除不可变值读取外，接口及回调均在网络所属线程调用，捕获快照可交给工作线程计算。

`MachineNetworkDevice.capturedComponent` 的 `current` 必须检查实际读取的后端身份与版本；组件或存储替换后旧捕获必须失效。禁止用组件列表副本或列表对象身份代替后端检查，默认数据库直接绑定其存储组件。

`RecipePoolJeiPage` 实现自己的 JEI 布局和输入处理。支持目录替换时，`prepareUpdate(target, displays)` 生成稳定的 wrapper 与 ingredients，null 表示不支持该次替换；`PreparedUpdate.apply()` 只使用一次，切换已准备的数据，禁止调用外部回调或重新生成布局。自定义 `RecipePoolJeiRecipe.drawInfo` 必须调用 `super.drawInfo`，库据此记录实际绘制区域并转发滚轮。点击、拖拽和转移前必须检查 wrapper 的 `isCurrent()`，禁止让旧窗口操作替换前的配方。

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

`controllerInventorySlots(int count)` 设置固定开放格数；函数重载接收 `ControllerContext` 并返回 `0..16`，null 清除动态设置后回到固定值。两种形式使用与 Java 相同的服务器执行边界和槽位检查，回调只读取捕获状态。

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

`Pool.simple` 用于单个固定开始输入和任意数量固定结束输出，返回池的 `.auxiliaryInputs(Resource[])` 为全池设置普通 START 辅助输入，匹配仍只看原主输入。`Pool.combine` 混合多个不同策略的池。`Requirement.resourceIO` 支持逐 tick 物料；`fuzzyInput` 列出可替代资源；`checkpoint` 在指定进度执行子需求；`chance` 和 `randomOutputs` 设置概率与随机输出。它们的数量、返回值和使用限制见 [ZS 使用参考](api-zs-reference.md)。

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

研究用 `Research.create(...)` 声明服务端数据，可在注册前用 `.visibility(...)` 设置总览可见性。服务端研究内容 JSON 提供基础名称、图标和布局，客户端按语言覆盖文本。`ResearchStage.create(first, last, tier, durationTicks, formula)` 设置等级段和 long 时间；`ResearchFormula.constant/affine` 定义网络资源总量，`perTick/affinePerTick` 按每 tick 用量乘时间换算，合计总量除以时间与研究站速率共同限制推进。stage 的 `.material(...)` 只接受静态总量输入，阶数 0 仅允许物品且禁止网络资源；`.prerequisite(...)`、`.mutex(...)` 和 `.reward(...)` 定义前置、互斥及奖励。原 `.materialPersistence(...)` 已删除。总览的打开、分页/内容编辑、视角保存和管理员会话入口见[Java 研究 API](#研究与计算网络)，脚本没有对应打开按键注册或自动打开方法。

在 preinit 脚本中，专用控制器使用下面的快捷入口（`compute` 是已经声明的数值计算资源）；它们不调用 `processing` 或添加配方工位：

```zenscript
MachineType.networkCenter("example:center", "example.center", "example:center_shape", 1, 4, 4)
    .register("center-v1");
MachineType.researchStation("example:station", "example.station", "example:station_shape", 1,
    ComputingResources.create().add(compute, 10), 8).register("station-v1");
```

中心在等级后填写并发、FE/t；研究站最后一项为FE/t。资源用 `ComputingResource.network(id, translationKey, pattern)` / `.computer(...)`，无需注册。普通机器可显式 `.requiresNetwork(true/false)`，省略时遵循配置默认false；专用角色保留。

资源由开发者定义单例，`ComputingResources` 累加 double，`ComputingModifier` 设置修饰。`NetworkConfiguration.create(id, roleName, level, supply, modifier, dataCenterCapacity, defaults)` 为普通控制器组合网络角色、等级、供给和配方需求；该入口不创建默认网络控制器，没有并发参数。并发由设备工厂、组件或事件定义，库不预置 thread/compute/qubit。

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

`InputAllocation.create(...)` 返回输入如何分到各来源。份额使用本次 `sources` 的索引，数量必须为正且总和等于请求量。`SelectiveContext` 和 `OverlayContext` 的 patch 方法修改本次进程的需求层：资源数组 null 保留原值，空数组清空；FE 数量使用 long 的 `-1` 保留、`0` 清零；过量丢弃使用 int 的 `-1` 保留、`0` 禁止、`1` 允许。完整设置入口 `putResourceIO(..., boolean)`、`putEnergyRates(..., boolean)` 保持 boolean，部分覆盖入口 `putResourcePatch(..., int)`、`putEnergyPatch(..., long, long, int)` 禁止对原始数值传 null。直接写 `context.data` 不会自动随事务回滚。

`ResearchServer.permissions(...)` 返回研究操作的权限决定；需要默认权限时调用 `context.standardDecision(action)`。奖励函数返回 `APPLIED`、`ALREADY_APPLIED` 或 `RETRY_LATER`，必须按事件身份去重。下载输出函数只构造输出物品，材料扣除和交付由库处理。

新的组件系统、需求系统、仓室后端和 JEI 页面由 Java 实现，再提供给脚本。Java 可用 `ZenMachineComponent`、`ZenMachineFactory`、`ZenRequirement`、`ZenRecipe` 和 `ZenRecipePool.of(...)` 包装实际对象；脚本中的对应类型接收这些对象。自定义 JEI 页面先由客户端 Java 调用 `ScriptJeiEvents.page(id, page)` 注册，脚本再用 `Page.named(id)` 引用。

运行中替换配方用 `Updates.forServer(server).replace(...)`，先为客户端声明对应目录，再提交服务器更新。完成后检查 `result.published`；新扫描采用新定义，运行中的进程继续使用旧定义。具体回调参数见 [ZS 使用参考](api-zs-reference.md)。

## 示例文件与语言资源

Java 示例位于 [ExampleMachines.java](docs/api-examples/java/ExampleMachines.java)，自定义需求位于 [ExampleRequirements.java](docs/api-examples/java/ExampleRequirements.java)。它们编入已有 Forge addon，addon 自己提供 `@Mod` 入口。

ZS 示例由机器声明 [00_machine.zs](docs/api-examples/zs/00_machine.zs)、结构声明 [10_content.zs](docs/api-examples/zs/10_content.zs) 和配方及回调 [20_extensions.zs](docs/api-examples/zs/20_extensions.zs) 组成，三份一起放入整合包的 `scripts`。Java 与 ZS 示例注册相同 ID，必须选择一种安装。

中文名称见 [zh_cn.lang](docs/api-examples/zh_cn.lang)。Java addon 放入 `assets/example/lang/zh_cn.lang`；资源包也使用该路径，根目录必须有 `pack.mcmeta`，Minecraft 1.12.2 的 `pack_format` 为 3。缺少该文件会使语言资源不能按资源包正确加载。
