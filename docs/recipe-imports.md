# 配方池公共配置与模组配方导入

`RecipePool` 提供 `withConfiguration`、`withRequirements`、`withAuxiliaryInputs`、`withEvent` 和 `withExistingJeiPage`
。普通池、单输入池、组合池及导入池都可以使用。方法返回新的声明，必须使用返回值注册并绑定配方版本和
codec。公共需求采用普通配方的需求类型、材料匹配、数量倍率、获取、提交和回滚规则。

`RecipeConfiguration.builder()` 支持
`requirements/addRequirement/auxiliaryInputs/durationTicks/recipeGroups/maxConcurrentThreads/parallelismSelection/event`
。连续设置公共需求或事件按顺序追加，同一需求 ID 冲突直接报错。时长、分组、线程上限和并行选择以后一次设置为准。辅助材料替换整份辅助列表，空列表清除。公共配置同时适用于初始配方和后续
`registerRecipe`；后者需要准确对象绑定时，必须先使用 `pool.prepareRecipe(recipe)` 的返回值。

普通材料和能量配置保留原池的候选策略。单输入池依然只按原主输入查找候选及数量上界，完整公共材料在 START
检查。公共配置包含事件、属性、选择器或外部需求时，无法证明原数量下界仍有效，匹配保留全部组兼容配方并交给普通准入处理。包装后的匹配结果、冻结目录、修订及
codec 始终使用完整配方的准确实例。

## 创建与首次读取

每种来源都有独立的公开配方池类。Java 按 `api.recipe.importing.<modid>` 分包，ZS 按
`mods.prototype_eng.recipe.importing.<modid>` 分包。Java 直接构造具体类型，ZS 调用该类型的 `create`。没有按来源 ID
查找适配器的集中注册表。每次创建得到独立实例，必须给不同池使用不同 ID，该 ID 只标识实例。同来源的两份池分别执行自己的创建回调，可以得到不同配方内容与筛选结果。

构造池、设置公共配置、设置 JEI 元数据、切换 `poolType`，以及读取 `id/translationKey/machines/poolType` 都不枚举来源。首次读取 `recipes`
、执行匹配或冻结时，读取当时的来源注册表、转换、调用创建回调并一次发布完整结果。后续访问复用该实例的结果，不再次调用回调。初始化失败记录日志并继续报告原失败，不重新执行已经发生的回调。需要重新读取来源时必须创建新实例。来源读取必须发生在该来源
API 允许的线程及配方注册完成之后。

ZS 的 `pool.register()` 和 `Bindings.registerPools(...)` 在配方注册事件内才首次读取导入池，包含导入池的 `Pool.combine`
也延迟组装子池。Java 显式调用 `pool.recipes()` 会提前触发首次读取。

创建回调接收可编辑 `RecipeBuilder`，其后的参数由每种池分别定义。没有额外数据的匠魂合金回调只接收配方。省略回调时使用
`null`，枚举来源时直接构建配方，不创建默认返回 `true` 的函数，也不构造上下文对象或转换仅供回调使用的数据。返回 `false` 直接排除这一转换配方，返回 `true` 后才构建最终
`MachineRecipe`。池公共配置在该结果上继续应用。回调异常记录池 ID 并传播，禁止当作筛选拒绝后继续初始化。来源自身无法转换的配方会记录来源与池
ID 并拒绝，不生成缺材料或错误产物的配方。

`RecipeBuilder` 固定配方 ID，支持修改时长、完整需求、分组、最大线程数、并行选择及事件。Java 使用
`requirements/addRequirement/addRequirements` 编辑需求，`build()` 创建独立定义。回调收到的来源数据和集合必须按只读数据使用，禁止修改全局来源配方。工厂产生的配方 ID 含本池 ID，避免不同适配实例互相覆盖；来源注册顺序或定义变更时，使用方必须更新保存
revision。

## 编辑普通配方

导入回调和普通配方创建共用 `RecipeBuilder`，ZS 对应 `mods.prototype_eng.recipe.Builder`。回调可以使用正常需求、事件和材料接口编辑完整配方，返回 true 后才生成进入池的普通 `MachineRecipe`。Java 也可以用 `RecipeBuilder.from(recipe)` 编辑已有定义的副本；修改不会改变原定义或此前 build 的结果。

`addInput(id, resource)`、`addOutput(id, resource)`、`addInputPerTick(id, resource)` 和 `addOutputPerTick(id, resource)` 分别追加 START 输入、END 输出及 TICK 输入/输出。Java 的 id 为 ResourceLocation、resource 为 GenericStack，ZS 分别使用 string 和 Resource。需求 ID 不存在时创建普通材料需求；已存在时必须是资源需求，并继承其概率、溢出和来源分配设置。必须使用独立 ID 添加需要独立概率和分配规则的材料，禁止把不同来源需求合并后改变概率。

`removeInput(id, index)` 按固定输入、fuzzy 组、dynamic 组的顺序删除一个 START 逻辑输入槽，后两种会删除整个组。`removeOutput(id, index)` 按固定 END 输出、随机候选的顺序删除一个条目，随机输出的剩余权重和 pickCount 保留。`removeInputPerTick/removeOutputPerTick(id, index)` 按对应 TICK 列表删除。索引从零开始，每次删除后按当前定义重新计算；动态组的展示候选不会用作实际匹配条件。

`clearInputs(id)` 清空一个资源需求的 START/TICK 输入及 fuzzy/dynamic 组，`clearOutputs(id)` 清空其 TICK/END 输出及随机输出池。无参版本处理当前配方的全部顶层资源需求，保留能量、事件和 SELECTIVE/CHECKPOINT 容器。必须显式重建并替换需要修改的嵌套需求，禁止自动递归改变独立时序。删除材料后保留原资源需求 ID 和另一侧的全部数据。

`energy(id, input, inputPerTick, outputPerTick, output)` 设置四个阶段的 FE 数量，保留已有能量需求的溢出与来源分配参数；ID 不存在时创建普通能量需求。数量必须非负，零表示该阶段不消耗或产出。Java 的 `energy(id)` 和 `resources(id)` 返回当前普通 payload。`requirement(id)` 查询完整需求，`replaceRequirement(requirement)` 按同 ID 替换已有项并保留位置，`removeRequirement(id)` 删除整项需求，可用于删除能量或其他条件。查询、替换、删除的 ID 缺失，材料/能量类型不符或索引越界时明确抛出异常，失败操作不得部分修改配方。

ZS 的 `recipe.requirements` 和 `recipe.requirement(id)` 返回当前需求快照。通过 `isResource/isEnergy` 判断类型，`inputCount/outputCount` 取得逻辑槽数，`inputCandidates(index)` 查看固定/fuzzy 候选或 dynamic 的声明展示候选，`output(index)` 查看固定或随机候选产物，`inputsPerTick/outputsPerTick` 查看 TICK 材料。dynamic 展示列表可以为空，实际匹配仍由原 matcher 决定。能量字段为 `energyInput/energyInputPerTick/energyOutputPerTick/energyOutput`，修改后需要重新查询取得新快照。

```zenscript
import mods.prototype_eng.recipe.importing.minecraft.FurnaceRecipePool;
import mods.prototype_eng.recipe.Resource;

var changed = FurnaceRecipePool.create("example:edited_smelting", "pool.example.edited_smelting", "v2",
    function(recipe, experience) {
        recipe.clearInputs().clearOutputs();
        recipe.addInput("example:materials", Resource.item(<minecraft:iron_ingot>, 1));
        recipe.addInput("example:materials", Resource.item(<minecraft:coal>, 1));
        recipe.removeInput("example:materials", 1);
        recipe.addOutput("example:materials", Resource.item(<minecraft:gold_ingot>, 1));
        recipe.energy("example:power", 20, 8, 0, 0);
        return true;
    });
changed.register();
```

该回调替换熔炉来源材料与产物，最终仍有一个主输入，默认单输入底层继续有效。保留两个主输入时必须在首次读取前选择 general。编辑材料、能源或回调逻辑后必须更新 revision。

## 选择实际底层池

`RecipePoolType` 为可注册的工厂类型。外部 Java 模组通过 `RecipePoolType.register(ResourceLocation typeId, RecipePoolType.Factory factory)` 注册，返回该类型的准确实例；factory 签名为 `(ResourceLocation poolId, String translationKey, List<MachineRecipe> recipes) -> RecipePool`，可以创建外部自行实现的 RecipePool。注册与查询不创建底层池、不枚举配方来源，工厂在导入池首次初始化时调用一次。必须在脚本选择该 ID 之前完成注册，重复 ID 或未知 ID 明确报错，禁止覆盖已有工厂或改用默认池。

Java 可直接传入注册返回值，也可通过 `RecipePoolType.require(typeId)` 查询后传入。ZS `.poolType("addon:custom")` 使用同一注册表，不限于内置类型。未包含命名空间的字符串统一使用 `prototype_eng`，因此 `general` 与 `prototype_eng:general` 查询相同类型；`single_input` 同理。`type.id()` 返回完整注册 ID。底层工厂必须保留池 ID、标题和输入配方的准确实例及顺序，禁止在此筛选、重排定义或返回 null；需要筛选和修改配方时使用来源创建回调，自定义扫描优先级通过 RecipePool 的匹配接口实现。工厂失败记录池 ID 并保持失败状态，后续读取不重新调用来源或工厂。冻结调用实际底层的 freeze，保留原注册类型，不再次执行工厂。

Java 调用 `pool.poolType(RecipePoolType.SINGLE_INPUT)` 或 `pool.poolType(RecipePoolType.GENERAL)`，ZS 调用
`.poolType("single_input")` 或 `.poolType("general")`。各来源按主输入形状确定默认类型，完整默认值见[机器目录](machine-recipe-pools.md)。前者实际创建
`SimpleRecipePool`，后者实际创建 `DefaultRecipePool`。必须在首次初始化前设置，禁止在初始化执行中、已经成功或已经失败后修改，否则抛出异常。读取 Java `poolType()` 不触发初始化，`implementation()` 会触发初始化并返回实际底层池。

切换方法直接修改所选导入实例的底层类型，返回当前对象，不枚举来源；共享同一来源池的配置链采用该实例最终设置。ZS 在 `.auxiliaryInputs(...)`、`.requirements(...)`、`.durationTicks(...)`
或 JEI 配置之后仍能通过 `.poolType(...)` 设置同一来源池。把熔炉切到 `GENERAL` 后，可以在创建回调里增加其他普通输入。把其他来源切到
`SINGLE_INPUT` 时，转换结果必须恰好含一个平铺的 START 主输入，可以是固定输入、fuzzy 输入组或 dynamic 输入组。公共辅助材料独立追加，不计入主输入个数。

单输入底层拒绝多个主输入、TICK 资源输入及 `SELECTIVE/CHECKPOINT` 嵌套输入定义。初始化会记录错误并抛出异常，禁止自动换成通用池。固定主输入满足稳定性条件时使用资源和数量索引；fuzzy、dynamic
或其他不能证明固定输入下界的需求仍沿原匹配器及完整 START 准入处理。池类型不会改变材料实际消耗、来源分配、并行倍率或事务规则。

## 来源组与回调参数

当前提供 70 个独立机器池，其中 44 个默认单输入，26 个默认通用池。本轮补入其他机器与独立工作模式，完整类名、各池默认底层、Java/ZS 回调签名和专有规则见[机器配方池目录](machine-recipe-pools.md)。下表保留基础来源的参数说明。

Java 构造器参数为 `(ResourceLocation id, String titleKey)`，可加创建回调；ZS `create` 参数为
`(String id, String titleKey, String revision)`，可加对应回调。Java 和 ZS 的公开池类名相同。Java 使用各具体池的嵌套
`OnCreate` 接口，首个参数是 `RecipeBuilder recipe`；ZS 首个参数是 `mods.prototype_eng.recipe.Builder recipe`。下表按顺序列出首个参数之后的全部参数，省略回调或显式传入 `null` 都表示没有创建回调。

| modid 与独立池类                           | 配方来源                           | Java 额外参数                                                                                          | ZS 额外参数                                                                                             |
|--------------------------------------------|------------------------------------|--------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `minecraft.FurnaceRecipePool`              | Minecraft 熔炉                     | `float experience`                                                                                     | `experience as float`                                                                                   |
| `ic2.CompressorRecipePool`                 | IC2 压缩机                         | `NBTTagCompound metadata`                                                                              | `metadata as IData`                                                                                     |
| `ic2.MaceratorRecipePool`                  | IC2 打粉机                         | `NBTTagCompound metadata`                                                                              | `metadata as IData`                                                                                     |
| `nuclearcraft.AlloyFurnaceRecipePool`      | NuclearCraft Overhauled 合金炉     | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                    | `durationTicks as double, energyPerTick as double, radiation as double, extras as IData[]`              |
| `nuclearcraft.InfuserRecipePool`           | NCO 灌注机                         | 同上                                                                                                   | 同上                                                                                                    |
| `nuclearcraft.ChemicalReactorRecipePool`   | NCO 化学反应器                     | 同上                                                                                                   | 同上                                                                                                    |
| `nuclearcraft.MelterRecipePool`            | NCO 熔化机                         | 同上                                                                                                   | 同上                                                                                                    |
| `tconstruct.MeltingRecipePool`             | TConstruct 熔炼                    | `int temperature, int usableTemperature`                                                               | `temperature as int, usableTemperature as int`                                                          |
| `tconstruct.AlloyRecipePool`               | TConstruct 合金                    | 无，完整签名为 `(recipe) -> boolean`                                                                   | 无，完整签名为 `function(recipe) as bool`                                                               |
| `thaumcraft.InfusionRecipePool`            | Thaumcraft 6 注魔                  | `ResourceLocation sourceId, AspectList aspects, int instability, String research, ItemStack mainInput` | `sourceId as string, aspects as IData, instability as int, research as string, mainInput as IItemStack` |
| `thermalexpansion.InsolatorRecipePool`     | Thermal Expansion 非树木日光感应器 | `int energy, int water, int secondaryChance, boolean hasFertilizer`                                    | `energy as int, water as int, secondaryChance as int, hasFertilizer as bool`                            |
| `thermalexpansion.InsolatorTreeRecipePool` | Thermal Expansion 树木日光感应器   | 同上                                                                                                   | 同上                                                                                                    |

熔炉经验使用原生 `float`，保留小数经验，不转换成 `int`。NC 的 `extras` 保留来源附加字段，ZS 按原字段类型转换为
`IData[]`，可通过 `extras[index].asDouble()`、`.asString()` 等读取；不支持的数据类型明确报错。IC2 元数据为来源 NBT 的独立快照，ZS 以只读
`IData` 提供。TC6 的 Java `aspects` 是 `AspectList`，ZS 以本质名到数量的只读 `IData` 映射提供，例如
`aspects.memberGet("aer").asInt()`。`mainInput` 在 NBT 变换配方中表示本次具体主输入，普通固定输出时 Java 为 `ItemStack.EMPTY`，ZS 为 `null`，脚本读取前必须判空。

各来源直接使用公开 SDK，不采用反射，也不把可选模组打包进主产物。目标模组未安装时仍可创建工厂、配置池和解析 ZS
类型，首次读取该来源才明确报告缺少模组。

## 执行映射

库对上述可选来源模组声明加载顺序，配方注册事件在其 post-init 之后执行。未安装的来源不会变成强制依赖。冻结目录保留各具体池类型、准确配方对象和机器绑定，复制目录不会再次读取来源或执行创建回调。

熔炉消耗一件匹配输入，保留输出数量，默认 200 tick。输入按原生物品及 metadata/通配规则匹配，不额外要求 NBT
相等。经验值直接作为回调的 `float experience` 提供，由机器或回调决定如何使用。

IC2 保留 `IRecipeInput.matches`、输入数量、全部输出和元数据，压缩机与打粉机为 300 tick、8 FE/t，其他机器使用各自原生基础时长与功率。NC 普通加工机读取 SDK 容器信息中的配置后基础时长与功率，再应用来源配方的倍率。不同数量的输入备选展开成独立转换配方，相同数量备选保留原
SDK 匹配。概率输出保留最小产量和逐步二项分布，流体保留原步长。NC 概率输入当前无法由普通需求准确保留单槽选键与随机实际扣量，会记录错误并拒绝整条来源配方。

匠魂熔炼按 `amountNeeded` 消耗并调用原 `matches`，输出完整流体；时长为温度除以 10 四舍五入且至少一 tick。合金保留全部流体输入与输出，默认四
tick。温度通过 `temperature/usableTemperature` 参数提供，需要温度限制时必须在创建回调筛选或添加实际需求。

TC6 注魔保留全部材料及 `Ingredient` 匹配。固定产物只生成一个输出，NBT 修改产物按具体主输入分别生成对应变体，禁止一次输出全部变体。默认
instability 为零时 300 tick，否则为 instability ×
1000。本质、研究资格和不稳定度未自动转成机器资源或权限需求，必须根据 `aspects/research/instability` 参数添加使用方实际的本质与研究需求；这些参数本身不执行相应检查或支付。

日光感应器保留每条来源配方、主副材料、肥料、水、主产物和独立概率副产物。总 FE 在 START 精确支付，时长为 energy/20 向上取整且至少一
tick。不会按主输入去重或删除肥料。

## Java 用例

```java
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.api.recipe.importing.minecraft.FurnaceRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.AlloyRecipePool;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.MeltingRecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import net.minecraft.util.ResourceLocation;

import java.util.List;

public final class ImportedPoolExample {
    public static void declare(ResourceLocation furnaceId, ResourceLocation meltingId, ResourceLocation alloyId,
                               LocalizationEnum title, int maximumTemperature,
                               RecipeRequirement<?> extraStartInput,
                               List<RecipeRequirement<?>> commonRequirements) {
        var furnace = new FurnaceRecipePool(furnaceId, title.getTranslationKey(), (recipe, experience) -> {
            recipe.addRequirement(extraStartInput);
            return experience <= 1.0F;
        });
        furnace.poolType(RecipePoolType.GENERAL);
        var lowHeat = new MeltingRecipePool(meltingId, title.getTranslationKey(),
            (recipe, temperature, usableTemperature) -> temperature <= maximumTemperature);
        lowHeat.poolType(RecipePoolType.GENERAL);
        lowHeat.poolType(RecipePoolType.SINGLE_INPUT);
        var configuredMelting = lowHeat.withRequirements(commonRequirements);
        var alloy = new AlloyRecipePool(alloyId, title.getTranslationKey(), recipe -> {
            recipe.durationTicks(8);
            return true;
        });
        DefinitionRegistrations.recipes(event -> {
            event.registry().register(furnace);
            event.registry().register(configuredMelting);
            event.registry().register(alloy);
        });
    }
}
```

`extraStartInput` 由调用者提供普通 START 资源需求；熔炉增加该输入前选择 `GENERAL`。匠魂熔炼先设为 `GENERAL`，再在首次读取前切回 `SINGLE_INPUT`，仅最终设置生效；合金回调只接收配方。每个池独立读取来源，同一来源可以用不同 ID 再创建独立实例。需要保存进程时，在注册事件中根据最终 `pool.recipes()` 创建
`RecipeProcessNbt`、声明 revision，并绑定原有 `RecipeProcessCodecs`。自定义 Java 来源使用
`new ImportedRecipePool(id, titleKey, supplier)`，supplier 返回完整 `List<MachineRecipe>`，可自行声明来源专用回调参数。需要库逐个调用普通配方回调时使用
`ImportedRecipePool.withoutContext(id, titleKey, draftSupplier, recipe -> boolean)`，其中 supplier 返回创建中的
`RecipeBuilder` 列表，回调类型为可空 `Predicate<RecipeBuilder>`。省略最后一个参数或传入 `null` 会跳过回调。两种入口均在首次访问时执行 supplier，默认底层为 `GENERAL`，也可提前调用 `poolType(...)`。

## ZS 用例

```zenscript
import mods.prototype_eng.recipe.importing.minecraft.FurnaceRecipePool;
import mods.prototype_eng.recipe.importing.tconstruct.MeltingRecipePool;
import mods.prototype_eng.recipe.importing.tconstruct.AlloyRecipePool;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.Requirement;

var washing = FurnaceRecipePool.create("example:washed_smelting", "pool.example.washed_smelting", "v1",
    function(recipe, experience) {
        recipe.durationTicks(100);
        recipe.addRequirement(Requirement.resources("example:fuel", [Resource.item(<minecraft:coal>, 1)], []));
        return experience <= 1.0;
    })
    .auxiliaryInputs([Resource.fluid(<liquid:water>, 1)])
    .requirements([Requirement.energy("example:heat", 0, 8, 0, 0, false)])
    .poolType("general");
var original = FurnaceRecipePool.create("example:ordinary_smelting", "pool.example.ordinary_smelting", "v1");
var lowHeat = MeltingRecipePool.create("example:low_heat", "pool.example.low_heat", "v1",
    function(recipe, temperature, usableTemperature) {
        return temperature <= 1000;
    }).poolType("general");
lowHeat.poolType("single_input");
var alloy = AlloyRecipePool.create("example:alloy", "pool.example.alloy", "v1", function(recipe) {
    recipe.durationTicks(8);
    return true;
});
Bindings.create().registerPools([washing, original, lowHeat, alloy], []);
```

脚本 `recipe` 类型为 `mods.prototype_eng.recipe.Builder`，提供 `id/duration/requirements` 属性和
`durationTicks/setRequirements/addRequirement/groups/maxThreads/parallelism/event` 方法。单独创建配方可用
`Builder.create(id, ticks)` 或 `Builder.from(recipe)`，最后 `build(revision)`。`build` 创建默认进程 codec，需要自定义 codec
的 Java addon 应通过原有完整 `ZenRecipe(codec)` 入口提交。

## 通用阶段事件

`RecipeBuilder.event`、`RecipeConfiguration.Builder.event` 和 `RecipePool.withEvent` 使用同一 `RecipeEventHandler`，阶段为
`START/TICK/END`，线程由机器 `executionPolicy` 决定。处理器返回普通 `RequirementTransaction`
，获取及提交效果必须可以回滚；后续需求拒绝时沿原协调器逆序撤销，只有全阶段成功才 complete。禁止在 complete 中执行可能失败的业务操作。

准入 probe 不调用事件。START 事件若改变准入前提，必须另提供 `RecipeScanPreparation` 对等可逆投影，否则扫描不能依据该事件推断准入成功。任意外部效果都不能用空
rollback 冒充可逆。

ZS 的 `pool.event(id, stage, callback)` 和 `builder.event(...)` 接收 `EventContext` 并返回 `EventTransaction`。上下文提供
`recipeId/stage/parallelism/data`；`data` 需要宿主已经安装 `DataComponents.PROCESS` 并使用包含该组件的 codec 保存。
`EventTransaction.success()` 表示无业务效果，`blocked(reason)` 与 `failure(reason)` 表示业务结果，
`create(commit, rollback)` 接收两个无参 `EventAction` 函数。rollback 必须撤销获取和提交的全部已有影响，包括提交抛异常前的部分修改。

```zenscript
import mods.prototype_eng.recipe.EventTransaction;

var counted = original.event("example:completed_count", "END", function(context) {
    var previous = context.data.getInt("completed", 0);
    return EventTransaction.create(function() {
        context.data.setInt("completed", previous + 1);
    }, function() {
        context.data.setInt("completed", previous);
    });
});
```

该片段要求机器安装上述进程数据组件，`counted` 必须替代 `original` 注册。阶段事件不另增持久状态，已完成阶段的事件不会在恢复时重放。改变回调实现仍须更新配方
revision。
