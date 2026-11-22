# 机器配方池目录

当前提供 70 个独立池，本轮新增 58 个。Java 包为 `com.circulation.prototype_eng.api.recipe.importing.<modid>`，ZS 为
`mods.prototype_eng.recipe.importing.<modid>`，两边公开池类名一致。每个实例独立，构造不读取来源；首次读取才转换。Java 使用构造器
`(ResourceLocation id, String title[, OnCreate])`，ZS 使用 `create(id, title, revision[, callback])`。省略回调为
null，不创建回调专用参数。下表均省略首个可编辑配方参数 recipe。

SINGLE_INPUT 实际使用 SimpleRecipePool，GENERAL 使用 DefaultRecipePool。当前默认 44 个 SINGLE_INPUT、26 个
GENERAL，根据来源的消耗主输入数量决定，多模式机器分别提供独立池。Java `pool.poolType(RecipePoolType.GENERAL)`、ZS
`.poolType("general")` 可在首次读取前调整；也可改为 SINGLE_INPUT / `"single_input"`
，最终配方必须满足单主输入定义。公共辅助材料和非消耗存在条件独立于主输入。完整编辑与生命周期规则见 [配方导入](recipe-imports.md)。

## 原版熔炉

| 池类                | 默认底层       | Java 回调附加参数  | ZS 回调附加参数    |
|---------------------|----------------|--------------------|--------------------|
| `FurnaceRecipePool` | `SINGLE_INPUT` | `float experience` | `float experience` |

## IndustrialCraft 2

普通物品机器保留 IRecipeInput.matches 和实际输入数量，能源按 4 FE/EU 映射。洗矿机读取 metadata.amount
并消耗对应水量；罐装机分别导入装瓶和流体增浓来源。高炉读取 metadata.duration，并逐 tick 消耗 metadata.fluid 指定的空气量。离心机的
minHeat、切割机的 hardness 及高炉 requiredHeat 通过回调提供，必须由宿主添加对应资格或条件。

电解机保留原时长、输入流体、全部输出和每 tick EU 数量，输出方向只作为来源元数据提供，ZS 返回方向名称数组。发酵器池表示一次流体转换机会，时长为
1 tick；heat、biomassPerFertilizer 和 fertilizer 提供真实来源参数，宿主必须处理热量与跨配方肥料累计，池本身不自动生成固定肥料奖励。

| 池类                             | 默认底层       | Java 回调附加参数                                          | ZS 回调附加参数                                             |
|----------------------------------|----------------|------------------------------------------------------------|-------------------------------------------------------------|
| `BlastFurnaceRecipePool`         | `GENERAL`      | `NBTTagCompound metadata, int requiredHeat`                | `IData metadata, int requiredHeat`                          |
| `BlockCutterRecipePool`          | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `CannerBottleRecipePool`         | `GENERAL`      | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `CannerEnrichRecipePool`         | `GENERAL`      | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `CentrifugeRecipePool`           | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `CompressorRecipePool`           | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `ElectrolyzerRecipePool`         | `SINGLE_INPUT` | `int energyPerTickEu, EnumFacing[] outputDirections`       | `int energyPerTickEu, String[] outputDirections`            |
| `ExtractorRecipePool`            | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `FermenterRecipePool`            | `SINGLE_INPUT` | `int heat, int biomassPerFertilizer, ItemStack fertilizer` | `int heat, int biomassPerFertilizer, IItemStack fertilizer` |
| `MaceratorRecipePool`            | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `MetalFormerCuttingRecipePool`   | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `MetalFormerExtrudingRecipePool` | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `MetalFormerRollingRecipePool`   | `SINGLE_INPUT` | `NBTTagCompound metadata`                                  | `IData metadata`                                            |
| `OreWashingRecipePool`           | `GENERAL`      | `NBTTagCompound metadata`                                  | `IData metadata`                                            |

## NuclearCraft Overhauled

20 种普通加工机首次读取时使用 TileInfoHandler 对应容器信息中的实际 defaultProcessTime/defaultProcessPower，包含 NC
已配置倍率。来源配方的时间、功率倍率和辐射附加值分别提供给回调；材料备选、实际数量与概率输出继续使用完整资源规则。ElectricFurnace
同时包含自定义来源和原版熔炉来源，自定义配方优先。

三种加工多方块使用原 Logic 的 machine_electrolyzer/distiller/infiltrator 时间与功率配置。电解池额外提供
electrolyteGroup，蒸馏提供 sieveTrayCount，浸渗提供 heatingFactor。必须由宿主把电极、电解质效率、筛板、压力或结构条件转成实际要求，附加参数本身不执行这些条件。NC
概率输入无法按普通输入规则完整保留随机扣量时记录来源错误并拒绝该条配方。

| 池类                               | 默认底层       | Java 回调附加参数                                                                                            | ZS 回调附加参数                                                                                         |
|------------------------------------|----------------|--------------------------------------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| `AlloyFurnaceRecipePool`           | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `AssemblerRecipePool`              | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `CentrifugeRecipePool`             | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `ChemicalReactorRecipePool`        | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `CrystallizerRecipePool`           | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `DecayHastenerRecipePool`          | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `ElectricFurnaceRecipePool`        | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `ElectrolyzerRecipePool`           | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `EnricherRecipePool`               | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `ExtractorRecipePool`              | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `FuelReprocessorRecipePool`        | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `InfuserRecipePool`                | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `IngotFormerRecipePool`            | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `ManufactoryRecipePool`            | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `MelterRecipePool`                 | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `MultiblockDistillerRecipePool`    | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras, long sieveTrayCount`     | `double durationTicks, double energyPerTick, double radiation, IData[] extras, long sieveTrayCount`     |
| `MultiblockElectrolyzerRecipePool` | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras, String electrolyteGroup` | `double durationTicks, double energyPerTick, double radiation, IData[] extras, String electrolyteGroup` |
| `MultiblockInfiltratorRecipePool`  | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras, double heatingFactor`    | `double durationTicks, double energyPerTick, double radiation, IData[] extras, double heatingFactor`    |
| `PressurizerRecipePool`            | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `RockCrusherRecipePool`            | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `SaltMixerRecipePool`              | `GENERAL`      | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `SeparatorRecipePool`              | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |
| `SupercoolerRecipePool`            | `SINGLE_INPUT` | `double durationTicks, double energyPerTick, double radiation, List<Object> extras`                          | `double durationTicks, double energyPerTick, double radiation, IData[] extras`                          |

## Tinkers Construct

Drying 保留 RecipeMatch 匹配、amountNeeded、原时间与产物。TableCasting/BasinCasting 分别读取浇铸台和浇铸盆，保留原流体数量、冷却时间、产物及
consumesCast：消耗型模具作为普通输入，非消耗型模具作为 START 存在条件，执行后仍保留。switchOutputs 是原机器槽位元数据，通过回调提供。

标准 CastingRecipe、PreferenceCastingRecipe 和 OreCastingRecipe
可以取得完整材料与静态产物。产物在池首次读取时生成快照，之后修改矿物偏好不会自动刷新已初始化的池。无法枚举实际流体或会按模具动态改变产物的
BucketCastingRecipe、自定义 ICastingRecipe 不生成不准确配方，转换时记录具体来源并拒绝。

| 池类                     | 默认底层       | Java 回调附加参数                             | ZS 回调附加参数                               |
|--------------------------|----------------|-----------------------------------------------|-----------------------------------------------|
| `AlloyRecipePool`        | `GENERAL`      | 无                                            | 无                                            |
| `BasinCastingRecipePool` | `GENERAL`      | `boolean consumesCast, boolean switchOutputs` | `boolean consumesCast, boolean switchOutputs` |
| `DryingRecipePool`       | `SINGLE_INPUT` | 无                                            | 无                                            |
| `MeltingRecipePool`      | `SINGLE_INPUT` | `int temperature, int usableTemperature`      | `int temperature, int usableTemperature`      |
| `TableCastingRecipePool` | `GENERAL`      | `boolean consumesCast, boolean switchOutputs` | `boolean consumesCast, boolean switchOutputs` |

## Thaumcraft 6

Crucible 消耗一件匹配催化材料并生成原产物，保持原即时转换时长；Infusion 保留原固定或 NBT 变体规则。本质和研究资格通过
AspectList/research 提供，ZS 的本质为只读 IData 映射。必须由宿主附加实际本质支付与研究条件。

| 池类                 | 默认底层       | Java 回调附加参数                                                                                      | ZS 回调附加参数                                                                                    |
|----------------------|----------------|--------------------------------------------------------------------------------------------------------|----------------------------------------------------------------------------------------------------|
| `CrucibleRecipePool` | `SINGLE_INPUT` | `ResourceLocation sourceId, AspectList aspects, String research`                                       | `String sourceId, IData aspects, String research`                                                  |
| `InfusionRecipePool` | `GENERAL`      | `ResourceLocation sourceId, AspectList aspects, int instability, String research, ItemStack mainInput` | `String sourceId, IData aspects, int instability, String research, @Nullable IItemStack mainInput` |

## Thermal Expansion

各 manager 的无序来源按完整输入、输出、数量和附加数据排序，保留同主输入但不同副料的独立配方。CompactorRecipePool 只取 ALL
来源，Plate/Coin/Gear 分别取各模式，ALL 不表示合并三个模式。Enchanter、Extruder、Refinery、Centrifuge 和 Transposer
的不同来源均使用各自池实例。

总 FE 在 START 精确支付，时长按总能量除以 20 向上取整。Enchanter 实际消耗对应 experience mB 的经验流体，Pyrolysis 实际生成
creosote 流体。Extruder 保留冷热流体各至少 1000 mB 的存在条件，原耗量为零时也检查，实际消耗仍按来源数量和普通并行倍率执行。

Pulverizer、Sawmill、Smelter、Refinery 和 Insolator 的副产概率允许额外第二叠，按原生最多两叠的规则处理；普通 Centrifuge
每叠最多一次，生物 Centrifuge 每单位按原递减概率独立处理，Transposer 返回容器最多一次。回调收到原始概率值，概率材料与主产物分别保留，不按输入去重。

| 池类                            | 默认底层       | Java 回调附加参数                                                   | ZS 回调附加参数                                                     |
|---------------------------------|----------------|---------------------------------------------------------------------|---------------------------------------------------------------------|
| `BrewerRecipePool`              | `GENERAL`      | `int energy`                                                        | `int energy`                                                        |
| `CentrifugeMobRecipePool`       | `SINGLE_INPUT` | `int energy, int[] chances`                                         | `int energy, int[] chances`                                         |
| `CentrifugeRecipePool`          | `SINGLE_INPUT` | `int energy, int[] chances`                                         | `int energy, int[] chances`                                         |
| `ChargerRecipePool`             | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `CompactorCoinRecipePool`       | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `CompactorGearRecipePool`       | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `CompactorPlateRecipePool`      | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `CompactorRecipePool`           | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `CrucibleRecipePool`            | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `EnchanterEmpoweredRecipePool`  | `GENERAL`      | `int energy, int experience, String enchantName`                    | `int energy, int experience, String enchantName`                    |
| `EnchanterRecipePool`           | `GENERAL`      | `int energy, int experience, String enchantName`                    | `int energy, int experience, String enchantName`                    |
| `ExtruderIgneousRecipePool`     | `GENERAL`      | `int energy, int hotAmount, int coldAmount`                         | `int energy, int hotAmount, int coldAmount`                         |
| `ExtruderSedimentaryRecipePool` | `GENERAL`      | `int energy, int hotAmount, int coldAmount`                         | `int energy, int hotAmount, int coldAmount`                         |
| `FurnacePyrolysisRecipePool`    | `SINGLE_INPUT` | `int energy, int creosote`                                          | `int energy, int creosote`                                          |
| `FurnaceRecipePool`             | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `InsolatorRecipePool`           | `GENERAL`      | `int energy, int water, int secondaryChance, boolean hasFertilizer` | `int energy, int water, int secondaryChance, boolean hasFertilizer` |
| `InsolatorTreeRecipePool`       | `GENERAL`      | `int energy, int water, int secondaryChance, boolean hasFertilizer` | `int energy, int water, int secondaryChance, boolean hasFertilizer` |
| `PrecipitatorRecipePool`        | `SINGLE_INPUT` | `int energy`                                                        | `int energy`                                                        |
| `PulverizerRecipePool`          | `SINGLE_INPUT` | `int energy, int secondaryChance`                                   | `int energy, int secondaryChance`                                   |
| `RefineryPotionRecipePool`      | `SINGLE_INPUT` | `int energy, int secondaryChance`                                   | `int energy, int secondaryChance`                                   |
| `RefineryRecipePool`            | `SINGLE_INPUT` | `int energy, int secondaryChance`                                   | `int energy, int secondaryChance`                                   |
| `SawmillRecipePool`             | `SINGLE_INPUT` | `int energy, int secondaryChance`                                   | `int energy, int secondaryChance`                                   |
| `SmelterRecipePool`             | `GENERAL`      | `int energy, int secondaryChance, boolean hasFlux`                  | `int energy, int secondaryChance, boolean hasFlux`                  |
| `TransposerExtractRecipePool`   | `SINGLE_INPUT` | `int energy, int secondaryChance`                                   | `int energy, int secondaryChance`                                   |
| `TransposerFillRecipePool`      | `GENERAL`      | `int energy, int secondaryChance`                                   | `int energy, int secondaryChance`                                   |

## 非消耗材料条件

Java 使用 `RecipeRequirements.RESOURCE_PRESENCE.requirement(id, new ResourcePresenceRequirement(inputs))`。`inputs` 为
`List<GenericStack>`；需要 fuzzy、dynamic 或来源分配时，可向 ResourcePresenceRequirement 传入只有 START 输入的完整
ResourceRequirement。ZS 使用 `Requirement.presence(id, inputs)`，其中 inputs 是 `Requirement.resources(...)`、
`fuzzyInput(...)` 或 `dynamicInput(...)` 返回的普通资源需求。禁止传入输出、逐 tick 材料、概率、随机产物或溢出丢弃选项，否则创建时抛出异常。

存在条件在 START 及其预检中按声明数量检查，立即释放临时预留，不扣除材料、不长期占用资源、数量不随并行扩大。同一个非消耗模具或流体门槛可以供多个并行使用；配方开始后不持续检查。浇铸池自动建立模具条件，Extruder
自动建立冷热流体各 1000 mB 条件，调用方无需重复添加。普通消耗材料仍按并行倍率支付，同键的存在条件与消耗量分别处理。

JEI 显示需要存在的数量和非消耗说明，不显示消耗概率。加工样板只包含实际消耗材料，非消耗条件不加入样板输入；相同资源另有普通消耗时仍保留该消耗数量。

## 新池用例

以下 Java 工厂创建独立 Thermal 打粉机池，按来源能量筛选，并为接受的配方增加普通材料。增加第二个主输入前选择
GENERAL。省略第三个构造参数或传入 null 时，不调用创建回调。

```java
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.recipe.importing.RecipePoolType;
import com.circulation.prototype_eng.api.recipe.importing.thermalexpansion.PulverizerRecipePool;
import net.minecraft.util.ResourceLocation;

public final class MachinePoolExamples {
    public static PulverizerRecipePool declare(ResourceLocation id, String titleKey,
                                               GenericStack additive, int maximumSourceEnergy) {
        var pool = new PulverizerRecipePool(id, titleKey, (recipe, energy, secondaryChance) -> {
            if (energy > maximumSourceEnergy) return false;
            recipe.addInput(new ResourceLocation("example", "extra_material"), additive);
            return true;
        });
        pool.poolType(RecipePoolType.GENERAL);
        return pool;
    }
}
```

返回池通过普通 DefinitionRegistrations.recipes
事件注册及绑定机器；保存进程时沿用 [Java 导入用例](recipe-imports.md#java-用例) 的 revision 与 codec 要求。下面的 ZS
声明两个来源相同但结果独立的池，第一个保留默认单输入，第二个增加材料并改用通用池。revision 必须随实际定义变更更新。

```zenscript
import mods.prototype_eng.recipe.importing.thermalexpansion.PulverizerRecipePool;
import mods.prototype_eng.recipe.Resource;

var original = PulverizerRecipePool.create(
    "example:original_pulverizer", "pool.example.original_pulverizer", "v1");
original.register();

var enriched = PulverizerRecipePool.create(
    "example:enriched_pulverizer", "pool.example.enriched_pulverizer", "v1",
    function(recipe, energy, secondaryChance) {
        if (energy > 20000) return false;
        recipe.addInput("example:extra_material", Resource.item(<minecraft:redstone>, 1));
        return true;
    });
enriched.poolType("general");
enriched.register();
```

## 未导入的来源

IC2 recycler 的结果由白名单、黑名单和输入即时计算，动态装液/排液由物品 capability
决定流体及容器，公开接口不能枚举完整静态配方。matterAmplifier、发电燃料和 liquidHeatExchanger 操作专用 UU 增益、EU/HU
状态，当前不生成普通材料加工池。

NC 的 collector、decay_generator、radiation_block_mutation/purification 和 radiation_scrubber
依赖世界方块、寿命或世界辐射。裂变燃料、fission_irradiator、fission_heating、coolant_heater
和紧急冷却依赖临界度、通量、热量及结构规则；heat_exchanger、condenser 和 turbine 依赖温度、流向、转速与结构效率。这些来源没有直接映射成普通
FE/时长。machine_diaphragm、machine_sieve_assembly、电极、infiltrator_pressure_fluid、fission_moderator/reflector 和
condenser_dissipation_fluid 只定义结构效率或环境参数，不单独生成加工池。

匠魂 BucketCastingRecipe 和自定义 ICastingRecipe 缺少完整可枚举输入，动态产物不能替换成固定结果。TC6 的
IArcaneRecipe、注魔附魔和本质熔炼奖励依赖世界、玩家、动态产物或库存返回物，当前没有对应普通机器池。原版 crafting、brewing 和
anvil 属于动态合成或交互操作，本目录仅提供原版熔炉来源。来源加载中遇到不支持的动态定义时必须记录日志并拒绝该条转换，禁止生成缺少实际输入或产物的配方。
