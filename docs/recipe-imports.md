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

构造池、设置公共配置、设置 JEI 元数据，以及读取 `id/translationKey/machines` 都不枚举来源。首次读取 `recipes`
、执行匹配或冻结时，读取当时的来源注册表、转换、调用创建回调并一次发布完整结果。后续访问复用该实例的结果，不再次调用回调。初始化失败记录日志并继续报告原失败，不重新执行已经发生的回调。需要重新读取来源时必须创建新实例。来源读取必须发生在该来源
API 允许的线程及配方注册完成之后。

ZS 的 `pool.register()` 和 `Bindings.registerPools(...)` 在配方注册事件内才首次读取导入池，包含导入池的 `Pool.combine`
也延迟组装子池。Java 显式调用 `pool.recipes()` 会提前触发首次读取。

创建回调接收可编辑 `RecipeBuilder` 及该来源专用上下文。返回 `false` 直接排除这一转换配方，返回 `true` 后才构建最终
`MachineRecipe`。池公共配置在该结果上继续应用。回调异常记录池 ID 并传播，禁止当作筛选拒绝后继续初始化。来源自身无法转换的配方会记录来源与池
ID 并拒绝，不生成缺材料或错误产物的配方。

`RecipeBuilder` 固定配方 ID，支持修改时长、完整需求、分组、最大线程数、并行选择及事件。Java 使用
`requirements/addRequirement/addRequirements` 编辑需求，`build()` 创建独立定义。来源上下文中的原始 SDK
对象和集合必须按只读数据使用，禁止修改全局来源配方。工厂产生的配方 ID 含本池 ID，避免不同适配实例互相覆盖；来源注册顺序或定义变更时，使用方必须更新保存
revision。

## 来源组与上下文

Java 构造器参数为 `(ResourceLocation id, String titleKey)`，可加创建回调；ZS `create` 参数为
`(String id, String titleKey, String revision)`，可加对应回调。Java 和 ZS 的公开池类名相同，匠魂合金回调仅接收配方，其余回调参数为
`(recipe, context)`。

| modid 与独立池类                           | 配方来源                           | ZS 上下文内容                                                                                       |
|--------------------------------------------|------------------------------------|-----------------------------------------------------------------------------------------------------|
| `minecraft.FurnaceRecipePool`              | Minecraft 熔炉                     | `input`、`output`、`experience`                                                                     |
| `ic2.CompressorRecipePool`                 | IC2 压缩机                         | `group`、`sourceIndex`、`inputAmount`、`metadata`                                                   |
| `ic2.MaceratorRecipePool`                  | IC2 打粉机                         | 同上                                                                                                |
| `nuclearcraft.AlloyFurnaceRecipePool`      | NuclearCraft Overhauled 合金炉     | `group`、`sourceIndex`、`variantIndex`、`durationTicks`、`energyPerTick`、`radiation`、`extraCount` |
| `nuclearcraft.InfuserRecipePool`           | NCO 灌注机                         | 同上                                                                                                |
| `nuclearcraft.ChemicalReactorRecipePool`   | NCO 化学反应器                     | 同上                                                                                                |
| `nuclearcraft.MelterRecipePool`            | NCO 熔化机                         | 同上                                                                                                |
| `tconstruct.MeltingRecipePool`             | TConstruct 熔炼                    | `temperature`、`usableTemperature`                                                                  |
| `tconstruct.AlloyRecipePool`               | TConstruct 合金                    | 仅配方参数                                                                                          |
| `thaumcraft.InfusionRecipePool`            | Thaumcraft 6 注魔                  | `sourceId`、`instability`、`research`、`aspectNames`、`aspectAmount(name)`、`mainInput`             |
| `thermalexpansion.InsolatorRecipePool`     | Thermal Expansion 非树木日光感应器 | `tree`、`energy`、`water`、`secondaryChance`、`hasFertilizer`                                       |
| `thermalexpansion.InsolatorTreeRecipePool` | Thermal Expansion 树木日光感应器   | 同上                                                                                                |

Java 上下文另有 `source()` 返回实际 SDK 配方对象。NC 的 `extras()` 保留原数据；ZS 用 `extraText(index)`、
`extraNumber(index)` 读取，非数值字段调用后者会报错。IC2 元数据为来源 NBT 的独立快照。TC6 的 `mainInput` 在 NBT
变换配方中表示本次具体主输入，普通固定输出时为空堆栈。

各来源直接使用公开 SDK，不采用反射，也不把可选模组打包进主产物。目标模组未安装时仍可创建工厂、配置池和解析 ZS
类型，首次读取该来源才明确报告缺少模组。

## 执行映射

库对上述可选来源模组声明加载顺序，配方注册事件在其 post-init 之后执行。未安装的来源不会变成强制依赖。冻结目录保留各具体池类型、准确配方对象和机器绑定，复制目录不会再次读取来源或执行创建回调。

熔炉消耗一件匹配输入，保留输出数量，默认 200 tick。输入按原生物品及 metadata/通配规则匹配，不额外要求 NBT
相等。经验值通过上下文提供，由机器或回调决定如何使用。

IC2 保留 `IRecipeInput.matches`、输入数量、全部输出和元数据，默认 300 tick、8 FE/t。NC 四组基准分别为合金炉 400 tick/10
FE/t、灌注 600/10、化学反应 400/5、熔化 800/40，并应用来源配方的时长与功率倍率。不同数量的输入备选展开成独立转换配方，相同数量备选保留原
SDK 匹配。概率输出保留最小产量和逐步二项分布，流体保留原步长。NC 概率输入当前无法由普通需求准确保留单槽选键与随机实际扣量，会记录错误并拒绝整条来源配方。

匠魂熔炼按 `amountNeeded` 消耗并调用原 `matches`，输出完整流体；时长为温度除以 10 四舍五入且至少一 tick。合金保留全部流体输入与输出，默认四
tick。温度是上下文数据，需要温度限制时必须在创建回调筛选或添加实际需求。

TC6 注魔保留全部材料及 `Ingredient` 匹配。固定产物只生成一个输出，NBT 修改产物按具体主输入分别生成对应变体，禁止一次输出全部变体。默认
instability 为零时 300 tick，否则为 instability ×
1000。本质、研究资格和不稳定度未自动转成机器资源或权限需求，必须通过上下文添加使用方实际的本质与研究需求；禁止把上下文元数据当作已执行这些规则。

日光感应器保留每条来源配方、主副材料、肥料、水、主产物和独立概率副产物。总 FE 在 START 精确支付，时长为 energy/20 向上取整且至少一
tick。不会按主输入去重或删除肥料。

## Java 用例

```java
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.recipe.importing.tconstruct.MeltingRecipePool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import net.minecraft.util.ResourceLocation;

import java.util.List;

public final class ImportedPoolExample {
    public static void declare(ResourceLocation lowPoolId, ResourceLocation fullPoolId,
                               LocalizationEnum title, int maximumTemperature,
                               List<RecipeRequirement<?>> commonRequirements) {
        var lowHeat = new MeltingRecipePool(lowPoolId, title.getTranslationKey(), (recipe, context) -> {
            if (context.temperature() > maximumTemperature) return false;
            recipe.durationTicks(Math.max(1, recipe.durationTicks() / 2));
            return true;
        }).withRequirements(commonRequirements);
        var fullHeat = new MeltingRecipePool(fullPoolId, title.getTranslationKey());
        DefinitionRegistrations.recipes(event -> {
            event.registry().register(lowHeat);
            event.registry().register(fullHeat);
        });
    }
}
```

两池独立读取来源，第一池的筛选和时长更改不会修改第二池。需要保存进程时，在注册事件中根据最终 `pool.recipes()` 创建
`RecipeProcessNbt`、声明 revision，并绑定原有 `RecipeProcessCodecs`。自定义 Java 来源使用
`new ImportedRecipePool<C>(id, titleKey, supplier, (recipe, context) -> boolean)`；无额外上下文时使用
`ImportedRecipePool.withoutContext(id, titleKey, draftSupplier, recipe -> boolean)`。后者 supplier 返回创建中的
`RecipeBuilder` 列表，构造时不会执行 supplier。

## ZS 用例

```zenscript
import mods.prototype_eng.recipe.importing.minecraft.FurnaceRecipePool;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.Requirement;

var washing = FurnaceRecipePool.create("example:washed_smelting", "pool.example.washed_smelting", "v1",
    function(recipe, context) {
        recipe.durationTicks(100);
        return context.experience <= 1.0;
    })
    .auxiliaryInputs([Resource.fluid(<liquid:water>, 1)])
    .requirements([Requirement.energy("example:heat", 0, 8, 0, 0, false)]);
var original = FurnaceRecipePool.create("example:ordinary_smelting", "pool.example.ordinary_smelting", "v1");
Bindings.create().registerPools([washing, original], []);
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
