---
title: ZenScript API 使用参考
---

本页按实际操作解释 ZS 入口。机器、配方、结构和网络的完整流程见 [Java 与 ZS API](api.md)。这里说明每组方法负责什么、何时调用，以及它改变的对象。ZenScript 类型名采用 `mods.prototype_eng` 命名空间；CraftTweaker 自带的 `IData`、`IWorld`、`IItemStack` 等参数继续使用 CraftTweaker 原生类型。

使用前必须 import 完整类型名。`MachineType/MachineFactory/MachineComponent` 位于 `mods.prototype_eng`；配方类型位于 `.recipe`，结构位于 `.structure`，仓室位于 `.hatch`，研究位于 `.research`，计算资源位于 `.resource`，网络位于 `.network`，模型位于 `.render`，JEI 设置位于 `.jei`。例如配方需求写 `import mods.prototype_eng.recipe.Requirement;`，研究阶段写 `import mods.prototype_eng.research.ResearchStage;`。

## 脚本从哪里开始

`#loader preinit` 用于机器类型，因为控制器需要在 Forge 注册方块时创建。配方、结构和研究通常放在 `#loader crafttweaker`。两个 loader 的脚本变量不共用；`Bindings.scope("example:recipes")` 用 ID 取得双方共用的配方声明表。

常用入口按任务分组如下：

| 要完成的工作       | 使用的类型                                                                | 结果                                               |
| ------------------ | ------------------------------------------------------------------------- | -------------------------------------------------- |
| 声明机器           | `MachineType`、`MachineFactory`                                           | 创建机器类型、选择控制器并绑定扫描配置。           |
| 定义库存和配方     | `Bindings`、`Pool`、`Recipe`、`Requirement`、`Resource`                   | 组织资源需求并登记扫描目录。                       |
| 改变匹配或执行规则 | `Matcher`、`InputAllocation`、`SelectiveModifier`、`ParallelismSelection` | 为配方提供脚本函数，决定资源如何匹配、分配和执行。 |
| 定义结构           | `Structure`、`Pattern`、`Predicate`、`Query`                              | 声明方块位置、子结构及需要采集的世界事实。         |
| 配置仓室           | `ResourceSide`、`EnergySide`、`Configuration`                             | 设置默认容量或提交运行时改配请求。                 |
| 声明研究和网络     | `Research`、`ResearchStage`、`NetworkConfiguration`                       | 设置研究等级、交付方式及机器网络角色。             |
| 绑定显示           | `RenderBindings`、`Options`、`Page`                                       | 绑定模型、配方显示方式或 Java 页面。               |

## 声明机器

`controllerInventorySlots(count)` 设置控制器开放物品格数，默认0，合法范围0到16。`controllerInventorySlots(callback)` 配置动态函数，其参数为当前机器对应的 `ControllerContext`。`controller.data` 是 `componentData()` 安装的机器数据，`controller.inventorySize` 返回上次采集的开放槽数，`controller.inventoryStack(slot)` 返回物品副本，`controller.forEachInventory(visitor)` 按索引遍历开放格，visitor的参数是 `(slot as int, stack as IItemStack)`。回调必须只读取当前数据，禁止修改数据、库存或世界。未配置动态函数、机器尚未装配或已注销时使用固定值；用 `null as ControllerInventorySize` 移除函数后也恢复固定值。

`ControllerContext`、`ControllerInventorySize` 和 `ControllerSlotVisitor` 都位于 `mods.prototype_eng`。例如先配置固定2格，再根据机器数据计算开放数：

```zenscript
import mods.prototype_eng.MachineType;
import mods.prototype_eng.ControllerContext;

val machine = MachineType.create("example:assembler", "machine.example.assembler", "example:assembler");
machine.componentData().controllerInventorySlots(2);
machine.controllerInventorySlots(function(controller as ControllerContext) as int {
    return controller.data.getInt("inventory_slots", 2);
});
machine.register("v1");
```

`MachineType.create(id, translationKey, structureId)` 创建机器声明。链式方法会把设置写入此声明，`register(revision)` 同时安排机器类型和运行装配的注册，并结束声明。注册后不能再修改机器。

| 方法                                            | 对声明的影响                                                                                                |
| ----------------------------------------------- | ----------------------------------------------------------------------------------------------------------- |
| `recipePool(poolId)`                            | 把配方池加入机器扫描范围。                                                                                  |
| `recipeGroup(groupId)`                          | 在已选池中进一步筛选配方组，不会自动添加配方池。                                                            |
| `attribute(id, base)`                           | 设置并行、速度等机器属性的基础值。                                                                          |
| `componentData()`                               | 给机器安装库提供的普通 NBT 数据组件，并绑定它的保存方式。                                                   |
| `component(handle)`                             | 安装 Java addon 创建的组件和该组件对应的装配设置。                                                          |
| `factory(handle)`                               | 改用 `MachineFactory.multithreaded()` 或 `crossRecipe()` 创建逻辑控制器。                                   |
| `processing(bindings, candidates, probes)`      | 安装工作槽和自动扫描器。两个正整数分别限制一次扫描的候选量和尝试数；bindings 必须与配方脚本取得同一 scope。 |
| `workSlot(name, groups, isCore, restartPolicy)` | 为多线程控制器声明逻辑工位及其配方组和阻塞后的重扫方式。                                                    |
| `executionPolicy(policy)`                       | 选择扫描和可异步配方工作使用主线程还是工作线程。默认值为 `FULL_ASYNC`。                                     |
| `executionMode(mode)`                           | 设置机器调度方式，与上述配方执行策略分别生效。                                                              |
| `network(configuration)`                        | 给机器添加对应网络角色、身份及网络配方组件。                                                                |
| `controllerModel(modelId)`                      | 绑定控制器模型声明，不会改变机器的结构或配方。                                                              |

机器 API 不会替调用者创建对应的配方池和结构；这些定义必须在其它阶段注册。`MachineComponent` 只接收 Java addon 已经创建的组件，脚本没有无 codec 地生成任意 Java 组件的入口。

`MachineFactory.multithreaded()` 让控制器为不同工位分别扫描，工位完成配方后优先重试上次成功配方。`crossRecipe()` 允许把 IO 兼容的不同配方合成一批。它们影响控制器如何安排工作，`executionPolicy` 则决定哪些扫描和配方工作可以异步执行。

`workSlot` 的 `name` 在同一机器内唯一，`groups` 为空数组表示接受所选池内所有配方。`isCore` 保存工位的核心标记。`restartPolicy` 使用 `LOCKED_RETRY` 或 `RESCAN`：前者保留当前进程重试阻塞阶段，后者提出稍后重扫的请求，不会因此丢弃进程或产生固定冷却时间。通常没有额外调度需要时保留 `LOCKED_RETRY`。[机器声明源码](src/main/java/com/circulation/prototype_eng/integration/crafttweaker/ZenMachineType.java)同时展示各方法如何接到 Java builder。

`ComponentData.create()` 和 `ComponentData.from(data)` 创建可编辑的独立数据对象。成员访问、键索引及 `getInt/setInt` 等方法负责读写它自身的 NBT。普通 ZS 对象不会自动关联世界中的机器；只有 `componentData()` 安装的机器组件或 API 回调的 `context.data` 才由对应保存配置管理。

`getInt/getLong/getDouble/getBool/getString(key, defaultValue)` 在键缺失时返回给定默认值，对应 `set...` 写入确定类型。`has/remove/clear` 检查或删除键。`asIData()` 返回独立数据，修改它不会反向修改原组件；`merge(data)` 则把输入内容写入当前对象。需要保存进程局部数据时，必须使用进程 codec 已包含的数据组件，机器的 `componentData()` 不会代替这项设置。

## 创建配方与配方池

### 共用脚本声明

`Bindings.scope(id)` 让 preinit 与普通 loader 访问同一份具名声明。`defineMatcher/defineSelective/defineOverlay` 保存后续按 ID 查找的函数定义，`matcher/selective/overlay` 取出已声明对象。重新定义只影响之后的查找，不会改变已创建配方中已捕获的对象。

使用 `Bindings.registerPools(pools, historical)` 一次登记当前配方池、池内配方 codec 和需要恢复的旧版配方。该方法绑定机器扫描器使用的配方恢复表，并关闭该 scope 的定义表。已经使用它时，禁止再次对同一配方调用 `pool.register()`。

`Bindings.declareManifest(setId, version)` 为当前完整配方目录声明身份，客户端和服务端必须使用同一 ID 与版本。运行时替换需要先向客户端声明新目录，不能只更改服务器的脚本对象。具名函数、codec 和注册阶段的关系见 [Bindings 源码](src/main/java/com/circulation/prototype_eng/integration/crafttweaker/recipe/ZenRecipeBindings.java)。

### 选择配方池

`Pool.create(id, titleKey, recipes)` 创建普通池，适用于有多个输入、FE、逐 tick IO 或复杂输出的配方。`Pool.simple(...)` 建立只有一个固定开始输入的索引池，结束输出数量不限；输入以外的需求不能放入该池。`Pool.combine(...)` 把多个池组成一个机器扫描入口，保留每个子池的匹配规则，但不会自动注册子池。

`existingJeiPage(uid)` 让该池沿用已经存在的 JEI 分类入口。`jeiOptions(options)` 设置概率提示和候选材料的显示方式。这些设置方法返回一个新池；必须把返回结果用于注册。`customJeiPage(Page.named(id))` 只引用 Java 页面，并不会自行创建 JEI 布局。

### 创建配方和资源需求

`Recipe.create(id, revision, durationTicks, requirements)` 创建有时长和需求的普通配方。`withGroups(...)` 还设置所属配方组及其同时运行的线程上限。`withSelection(...)` 在此基础上设置本配方的最终并行选择规则。`revision` 是保存兼容版本，改变需求含义或外部回调后应更新它。

`withGroups` 的完整参数次序为 `(id, revision, durationTicks, requirements, groups, maxConcurrentThreads)`；`withSelection` 在最后再接一个 `ParallelismSelection`。时长可以为 0，线程上限必须为正数。配方的 `id` 和 `revision` 属性可用于检查实际注册的对象。

`Resource.item(stack, amount)` 和 `Resource.fluid(stack, amount)` 分别创建物品与流体资源；`amount` 直接指定本需求数量，不会乘以方括号物品自身的堆叠数。`fromTag(data, amount)` 从序列化的 AEKey 身份还原资源，`toTag()` 只写入资源种类，不包括数量。`withAmount(...)` 返回相同种类的新数量对象。`isItem`、`isFluid` 用于判断类型，只有物品资源才能读取 `item`。

下表说明常见需求如何安排材料：

| 需求方法                     | 处理内容                                        | 适用情况                         |
| ---------------------------- | ----------------------------------------------- | -------------------------------- |
| `Requirement.resources(...)` | 开始时消耗固定输入，完成时交付固定输出。        | 一次性物料转换。                 |
| `resourceIO(...)`            | 同时设置开始、逐 tick 和结束阶段的物料。        | 有持续物料消耗或产出的配方。     |
| `energy(...)`                | 设置独立 FE 的开始、逐 tick 和结束数值。        | 机器电力。                       |
| `fuzzyInput(...)`            | 开始时从固定候选资源里选择一种。                | 多种可替代原料。                 |
| `dynamicInput(...)`          | 用 `Matcher` 从捕获库存中寻找资源。             | 匹配条件由脚本函数决定的输入。   |
| `selective(...)`             | 按顺序尝试候选需求，并应用成功分支的 modifier。 | 依据库存选择配方分支。           |
| `checkpoint(...)`            | 达到指定进度或工作 tick 后执行子需求。          | 延迟产物或阶段性条件。           |
| `attribute(...)`             | 在开始时添加机器属性修饰，并可在结束时移除。    | 临时速度、并行数或其它属性效果。 |
| `combineResources(...)`      | 将多项资源需求合成一项资源需求。                | 共用来源分配或复杂输入声明。     |

`resources(id, inputs, outputs)` 的两个数组分别是开始消耗和结束产出。`resourceIO(id, inputs, inputsPerTick, outputsPerTick, outputs, voidExcessOutputs)` 在中间增加每 tick 输入、输出。没有某一阶段的资源时传空数组。`energy(id, input, inputPerTick, outputPerTick, output, voidExcessOutputs)` 使用相同次序的数值。

例如，以下需求在开始消耗 2 个铁锭，每 tick 消耗 1 mB 水，结束产出 1 个金锭；没有每 tick 输出，也不允许丢弃装不下的产物：

```zenscript
#loader crafttweaker
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;

var cooled = Requirement.resourceIO("example:cooled",
    [Resource.item(<minecraft:iron_ingot>, 2)],
    [Resource.fluid(<liquid:water>, 1)],
    [],
    [Resource.item(<minecraft:gold_ingot>, 1)], false);
```

数量均按单个并行单位填写。`voidExcessOutputs` 只影响输出装不下的部分；输入不足仍会阻塞。物料与 FE 的每 tick 数量只在成功工作时结算。跨配方控制器合批时会按统一时间计量，只有单位并行的逐 tick 物料输入和输出都相同才能合批。

`fuzzyInput(id, candidates, amount)` 把 candidates 当作资源种类列表，实际需求数量由 amount 决定。`dynamicInput(id, matcher, pattern, amount, displayedCandidates, maxCandidates)` 用 pattern 与 matcher 选择库存资源；displayedCandidates 用于展示，不能代替真实库存判断，maxCandidates 限制动态候选数量。

`checkpoint(id, time, child, scaleWithProcess)` 的 time 为非负阈值。scaleWithProcess 为 true 时看配方进度，为 false 时看成功工作 tick；time 为 0 时在开始阶段执行子需求。`selective(id, candidates, modifiers)` 按候选次序尝试，选中后才应用 modifiers。

`attribute(id, attributeId, modifierId, amount, operation, applyAtStart, removeAtEnd)` 设置一项属性修饰。operation 使用 `ADDITION`、`MULTIPLY_BASE` 或 `MULTIPLY_TOTAL`，依次表示平加、按基础值计算增量、按当前值乘以 `1 + amount`。`attributeAddition(...)` 省略 operation 并使用平加。applyAtStart 与 removeAtEnd 控制修饰的加入和移除时机。

`chance(percent, attribute)` 和 `randomOutputs(candidates, weights, pickCount)` 设置概率与加权输出，只接受资源需求。它们返回带新设置的需求；必须接住返回值。重复资源可作为多个抽取条目，权重逐项对应候选，单轮不会重复抽取同一个条目。

`chance` 的 percent 使用百分数，`50.0` 表示 50%；没有概率修饰属性时第二个参数传 null。`randomOutputs` 的 weights 长度必须等于候选数，pickCount 是每轮抽取数。随机输出、概率和固定输出的实际费用由需求系统统一计算，不能用 JEI 展示的概率值自行扣料。

`singleInputSource()` 和 `customInputAllocation(allocation)` 决定一个输入项目如何在捕获的仓室来源间分配。前者要求单个来源足够提供该项输入，后者使用作者给出的分配函数。使用 `combineResources` 时，应先组合原始资源项，再添加概率、随机输出、过量丢弃和来源分配设置。各需求的参数检查见 [Requirement 源码](src/main/java/com/circulation/prototype_eng/integration/crafttweaker/recipe/ZenRequirement.java)。

## 脚本自定义配方行为

### 匹配和输入分配

`Matcher.exact()` 检查资源身份完全相同，`itemOnly()` 按物品及 metadata 匹配，不比较 NBT。`Matcher.create(id, callback)` 则调用脚本的 `matches(candidate, pattern)` 函数；该函数只回答是否匹配，不能消耗资源。

`InputAllocation.create(id, callback)` 接收一次缩放后的输入量和这次扫描捕获的来源，回调返回 `AllocationPortion.create(sourceIndex, amount)` 数组。索引来自 `AllocationSource.index`，不是槽位号；每一项数量必须大于零，来源索引不能重复，总数必须等于 `context.amount`。处理 FE 时 `context.isEnergy` 为 true，`resource` 是 null。

### 选择并行和修改需求

`ParallelismSelection.fixed(n)` 始终选用指定正整数。`create(callback)` 让函数根据扫描建议返回最终并行数；库仍会检查库存和需求，选择器不代表库存预留。

例如，机器本身允许更高并行，而某条配方最多处理 2 份时，可以提供下面的选择器，再作为 `Recipe.withSelection` 的最后一个参数传入：

```zenscript
#loader crafttweaker
import mods.prototype_eng.recipe.ParallelismSelection;

var atMostTwo = ParallelismSelection.create(function(suggested as int) {
    return suggested > 2 ? 2 : suggested;
});
```

`SelectiveModifier.speedAddition(...)` 添加速度属性，`speedMultiplier(...)` 使用直接倍率，例如 `1.25` 表示速度乘 1.25。`create(id, callback)` 允许通过 `SelectiveContext` 添加属性修改或 patch 需求。

`OverlayModifier.create(...)` 为需求层创建命名修饰器，必须在需要应用它的 Java addon 流程中显式使用。`putResourcePatch` 和 `putEnergyPatch` 修改目标需求字段，patch 中的 null 表示保持原值，空资源数组表示清除该项。`putOverlay` 连接 Java addon 创建的需求覆盖对象。目标必须是当前配方定义中实际使用的需求对象。

选择上下文的 `candidateIndex` 从 0 开始，`selectionId` 标识本次选择；`modifierId/modifierOrdinal` 标识当前修饰器。`addProcessSpeed(id, amount)` 平加速度，`mulProcessSpeed(id, factor)` 乘直接倍率；`putAttribute` 使用前述 operation 规则。`putResourceIO` 设置完整四阶段资源，`putEnergyRates` 设置两个 FE tick 数值；只有对应 patch 方法允许部分参数为 null。

这些回调可能异步运行，也可能因库存变化和扫描重试而再次调用。必须只根据传入参数和不可变定义计算；不能修改世界、玩家背包或脚本全局状态。`context.data` 是进程数据，但直接修改它不自动加入需求层的撤销处理。

### 配方目录更新

`Updates.forServer(server)` 取得当前服务器的更新入口。`replace(replacements, setId, version)` 提交替换配方定义及新目录身份并返回 `UpdateTask`；它不能增加池或改变池成员。等待 `done` 后再读 `result`，或者用 `onComplete(function(result) {...})` 接收完成结果，然后检查 `result.published`。`status` 解释结果状态，`failure` 给出异常文本，没有异常时为空字符串。服务器主线程不能阻塞等待这个任务。

必须先用 `Bindings.declareManifest(...)` 或 `declareClientVariant(...)` 提供客户端使用的新目录身份。已运行的配方进程继续使用旧定义和旧 codec，新扫描使用成功发布的版本。

## 定义结构并读取世界事实

### 普通谓词与复合结构

`Pattern.create()` 创建相对坐标表，链式 `put(x,y,z,predicate)` 设置每个位置的方块条件。相同位置不能重复添加。

`Predicate.block(id)` 按方块匹配，`state(...)` 按完整 block state 匹配，`nbt(data)` 匹配 tile NBT。`all(predicates)` 要求全部成立，脚本 OR 使用 `anyOf(predicates)`；SDK 的 `any` 与脚本类型语法冲突。实例方法 `at(x,y,z)` 把检查条件放到指定相对位置。`notAir()`、`hasTileEntity()` 和各类仓室谓词用于常见结构格。

`Structure.template(...)` 创建固定父结构，`Structure.slice(...)` 创建可重复的结构部分。两者都接收 ID、朝向、父节点偏移、pattern、整体验证器和 child 结构。切片另外声明 `minCount/maxCount` 及每次重复的三轴步进。`translated(key)` 返回新结构并设置显示名称；`register()` 将定义提交到结构目录。

父结构的 `children` 应直接引用之后注册的结构对象。共享子结构可以被多个父结构复用，但同一个 ID 不能用不同对象重复声明。切片 child 只附加在最后一段，可用于随结构长度移动的顶部或端盖。

### 自定义验证器

`Predicate.custom(validator)` 仅开放默认基点的基础事实。需要其它世界信息时，使用带 `facts`、`customFacts`、`offsets` 和 `controllerOffsets` 的形式声明所有读取项。两种偏移都随结构朝向变换：`offsets` 相对当前结构基点，`controllerOffsets` 相对控制器。

例如，下面的采样函数读取当前位置亮度；验证器从复制后的事实中判断是否达到 8。把 `enoughLight` 放入结构的 validators 或相应位置的 predicate 后才会参与结构判断：

```zenscript
#loader crafttweaker
import crafttweaker.data.IData;
import crafttweaker.world.IWorld;
import crafttweaker.world.IBlockPos;
import mods.prototype_eng.structure.Query;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.structure.Facts;

var light = Query.create("example:light", function(world as IWorld, position as IBlockPos) {
    return world.getBrightness(position) as IData;
});
var enoughLight = Predicate.custom([], [light], [[0, 0, 0]], [], function(facts as Facts) {
    return facts.read(light).asInt() >= 8;
});
```

`Query.create(id, capture)` 在服务器主线程读取 `IWorld` 与 `IBlockPos`，并把结果返回为 `IData`。返回值被复制后才会给 `Facts` 验证函数使用。验证器通过 `Facts.read(query)`、`at(...)`、`atWorld(...)` 和 `controller` 查看快照。未声明或不可用的事实不能读取；验证器禁止捕获 World 引用再直接查询世界。[Predicate 源码](src/main/java/com/circulation/prototype_eng/integration/crafttweaker/structure/ZenStructurePredicate.java)中的 query 分支列出可按名称声明的内置事实。

若 JSON 结构要引用脚本谓词，调用 `predicate.register(id)` 在结构谓词注册阶段声明它。该注册只把 predicate 加到可查找表，不会自动把 predicate 应用到结构；JSON 再通过该 ID 引用它。

## 声明并行控制器

`mods.prototype_eng.ParallelController` 必须在 `#loader preinit` 阶段创建并注册。

| 方法                                  | 参数与行为                                                                                       |
|---------------------------------------|--------------------------------------------------------------------------------------------------|
| `create(id, translationKey, maximum)` | 创建定义；ID 唯一，语言键取对应中英文件，maximum 为正 int。                                      |
| `model(modelId)`                      | 选择完整的模型入口并返回当前定义；省略时用 `prototype_eng:parallelization_controller` 原始模型。 |
| `register()`                          | 注册额外方块/物品/默认实体；之后不能继续修改或重复注册。                                         |

```zenscript
#loader preinit
import mods.prototype_eng.ParallelController;
ParallelController.create("example:parallel_module", "tile.example.parallel_module.name", 32)
    .register();
```

内置五级默认最大贡献为 4、16、64、256、512，分别为普通、强化、精英、超级、终极；文件
`config/prototype_eng/parallel_controllers.cfg` 可修改。右键 GUI 选择 0 到最大值的当前贡献。

机器 `attribute("prototype_eng:process_parallelism", internal)` 是内部贡献；结构中的控制器当前贡献相加后，受
`attribute("prototype_eng:max_process_parallelism", cap)` 限制。未声明 cap 时为 `Integer.MAX_VALUE`
。多线程和跨配方工作共用此整机额度，已运行进程不因降低额度被取消。

## 配置仓室

`ResourceSide.itemEquivalent(slots, capacity)` 按槽数和单槽物品等价值设置资源容量。流体默认以 1000 mB 折算一个物品单位。带代表资源和比例的重载用于自定义资源类型。`nativeCapacities(slots, resources)` 直接按每种 AEKeyType 的原生单位设置上限，同一种 AEKeyType 不能重复。

`EnergySide.create(capacity, transfer)` 分别指定 FE 容量和单次操作的基础传输限额。`Configuration.resources/energy(tier, input, output)` 将两侧设置组合成一阶仓室；input/output 可各自为 null，但不能同时为空。两侧均提供时形成混合仓，彼此仍是独立存储。

`configureDefaults(configurations)` 设置启动时的默认仓室容量。`request(server, configurations)` 只能在对应服务器主线程调用，返回异步 `Update`。由后续服务器回调检查 `done`，完成后再读取 `result`，禁止在主线程循环等待。顶层更新完成不代表每个仓室都已经应用设置，`hostStatuses` 会说明哪些仓室因忙碌或容量不足等待处理。运行中需要传入的 `IServer` 必须由实际服务器上下文提供，脚本没有用于伪造服务器对象的工厂。

## 研究与奖励

`ResearchFormula.create()` 创建资源公式，随后调用 `.constant(type, demand, segmentLimit)` 给各等级设置相同需求，或 `.affine(type, base, slope, segmentLimit)` 按 `base + slope × (当前等级 - 阶段首级)` 计算需求。type 必须是数值计算资源，segmentLimit 限制一次工作分段授予量，逻辑线程数不能作为累计研究进度。

`ResearchStage.create(first, last, tier, formula)` 创建连续等级段。随后用 `prerequisite(...)` 和 `mutex(...)` 添加研究前置及互斥项，用 `material(requirement)` 指定研究站材料配方，用 `reward(level, reward)` 在指定等级添加可领取奖励。各 stage 合并后必须无重叠且完整覆盖 `1..maxLevel`。

first/last 包含首尾等级，tier 为 0 时由玩家手动投料，正数表示研究站阶数。`prerequisite(id, minimumLevel)` 要求前置研究达到指定等级，`mutex(id, minimumLevel)` 则在对应研究达到该等级时阻止这一阶段。奖励方法的 level 是研究的实际等级，必须位于本段范围内。

`Research.create(id, maxLevel, specialOnly, deliveryPolicy, itemDownloadLevelCost, stages, condition)` 组装整项研究。maxLevel 是最大等级，specialOnly 限制普通研究入口，deliveryPolicy 选择 `DATABASE_AND_ITEM` 或 `ITEM_ONLY`，itemDownloadLevelCost 是每次下载扣除的研究等级，stages 是等级段数组，condition 是入队资格函数。配置完毕调用 `register()` 提交定义。

`Research.alwaysAllowed()` 表示资格函数不增加额外条件，实际权限仍会由服务器规则检查。自定义资格函数读取 `targetLevel`、`networkCenterLevel`、`sharedLevel(researchId)` 和 `available(resource)` 等捕获事实，返回 bool；它不能修改资源或把当前数量当作后续工作已经预留的数量。`unlock(effectId, targetId, level)` 声明达到指定等级后的解锁效果，必须在研究定义被注册前完成。

`materialPersistence(revision, componentData)` 给研究站的材料需求绑定保存版本。componentData 为 true 时同时保存普通进程数据。需要自定义 Java 组件的研究站材料应由 addon 提供完整 codec，不能靠这个布尔参数保存所有外部组件。

`ResearchServer.permissions(callback)` 设置服务端权限回调。回调的 action 和返回值使用 Java 对应枚举的字符串名称；不添加特例时调用 `context.standardDecision(action)`。在启动阶段注册，否则研究服务可能已完成初始化。

`ResearchReward.create(groupId)` 创建可领取的奖励组，链式 `.item(childId, resource)` 添加物品奖励，`.event(childId, callback)` 添加自定义交付函数。childId 在组内必须唯一且保持稳定。事件结果为 `APPLIED`、`ALREADY_APPLIED` 或 `RETRY_LATER`，分别表示本次已交付、此前已交付和稍后重试。使用玩家、研究、等级、奖励组和 child ID 组成的事件身份去重；保存确认失败可能导致回调重试，不能再次发放已经完成的奖励。

`ResearchDownloads.register(material, callback)` 将一种消耗材料与输出函数配对。输出函数只返回有效 `IItemStack`，材料、研究等级和物品交付均由研究流程处理。

## 计算资源与机器网络角色

`ComputingResource.create(id, logicalConcurrency, maxPerTickValue)` 创建新的计算资源种类，随后调用 `register()` 提交。logicalConcurrency 表示该资源以并发占用管理，例如线程数；普通数值资源可以参与按 tick 的资源工作。maxPerTickValue 为正时限制单组件可提供的数值，不是整队伍的总上限。`thread()`、`compute()` 和 `qubit()` 是库提供的类型，不应再次注册。

`ComputingResources.create()` 创建资源数量表，链式 `add(resource, amount)` 累加供应量，`amount(resource)` 查询数值。`ComputingModifier` 用 `flat` 添加固定量，用 `multiplierPpm` 表示百万分比增量，`500000` 为额外 50%。

`NetworkRequirements.create()` 创建配方网络要求。链式 `research(id, minimumLevel)` 添加研究门槛，`resource(resource, amountPerWorkUnit)` 添加每工作单位消耗的计算资源，`bind(recipe, priority)` 将规则绑定到传入的 Recipe 对象。重新创建相同 ID 的 Recipe 后，需要为新对象重新绑定。

`NetworkConfiguration.create(id, roleName, level, supply, modifier, dataCenterCapacity, defaults)` 为机器配置网络。id 用来生成这套网络组件的 ID；roleName 使用 `NETWORK_CENTER`、`COMPUTER`、`RESEARCH_STATION`、`DATA_CENTER` 或 `DEVICE`；level 是对应等级，supply 是固定供应量，modifier 是供给修饰，defaults 是机器默认配方需求。只有数据中心填写正 dataCenterCapacity，其它角色必须填 0；网络中心和研究站的 level 必须为正。

将返回配置传给 `MachineType.network(...)` 后，库会安装所有者、角色和网络配方组件；COMPUTER 增加计算组件栏，DATA_CENTER 增加数据库存储及保存配置。只有真实服务器拥有的机器实例可以提供网络资源；脚本声明不能替代服务器所有者校验。

`NetworkRecipes.register(priority, callback)` 在配方目录发布时按优先级修改网络需求，数值小的优先执行，同优先级保持注册顺序。回调读取声明中的 Recipe，不查询运行机器。`ComputerComponent.fixed(resources).register(item)` 给计算机组件栏中的物品声明固定供给，不会把该物品本身变成机器。

## 客户端渲染与 JEI

`RenderBindings.gecko()` 创建 Gecko 绑定 builder，设置 geo、texture 和可选 animation 后，调用 `bindGeckoToMachineType(machineId, binding)`、`bindGeckoToComponentType(componentId, binding)` 或 `bindGeckoToStructure(machineId, structureId, binding)` 提交。调用时会保存当前配置，之后修改 builder 不改变已经绑定的模型。这些 ZS 方法只登记资源声明，可放在普通脚本中；实际模型读取和渲染由客户端处理。

`geo/texture/animation` 接受资源路径，前两项必填。`defaultAnimation` 选择默认动画，`animationLayer` 按顺序增加动画层，`animationStateKey/animationLayersStateKey` 从普通数据读取动画名或以逗号分隔的动画层。`modelOffset` 与 `yOffset` 调整模型位置；`pass` 接受 `default`、`transparent`、`bloom`、`bloom_transparent`；`sliceMode` 选择切片每段绘制或整个结构只绘制一次。

例如，模型与纹理文件位于资源包中时，用以下声明把它们绑定到熔炼机：

```zenscript
#loader crafttweaker
import mods.prototype_eng.render.RenderBindings;

var model = RenderBindings.gecko()
    .geo("example:geo/smelter.geo.json")
    .texture("example:textures/blocks/smelter.png");
RenderBindings.bindGeckoToMachineType("example:smelter", model);
```

`Options.create(chanceOverlay, candidates)` 创建某个配方池的显示设置；`candidates` 选择 `alternatives` 或 `expanded`。`Options.defaults(...)` 设置全局默认值，`pool.jeiOptions(...)` 则只设置指定池。

脚本 `Page.named(id)` 引用 Java 页面注册时提供的 ID。该调用只取得注册句柄，不创建布局。Java 客户端需先通过 `ScriptJeiEvents.page(id, page)` 注册实际 `RecipePoolJeiPage`，脚本再调用 `customJeiPage(...)` 使用它。Java 的模型对象和 JEI 页面类必须仅从客户端初始化入口加载；脚本中的声明本身可在专用服务器解析。

完整示例按 [机器声明](docs/api-examples/zs/00_machine.zs)、[切片与子结构](docs/api-examples/zs/10_content.zs)、[配方与自定义回调](docs/api-examples/zs/20_extensions.zs)拆成三个脚本。每个类型的定义来源位于 `src/main/java/com/circulation/prototype_eng/integration/crafttweaker`，其中 `@ZenClass` 标明脚本类型，`@ZenMethod`、`@ZenGetter` 和回调接口标明调用入口。
