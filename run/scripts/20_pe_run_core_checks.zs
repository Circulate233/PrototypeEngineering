#loader crafttweaker
import mods.pe_run_tests.Text;

import crafttweaker.data.IData;
import crafttweaker.world.IWorld;
import crafttweaker.world.IBlockPos;
import mods.prototype_eng.data.ComponentData;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Matcher;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.ParallelismSelection;
import mods.prototype_eng.recipe.SelectiveModifier;
import mods.prototype_eng.recipe.OverlayModifier;
import mods.prototype_eng.recipe.InputAllocation;
import mods.prototype_eng.recipe.AllocationPortion;
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.structure.Query;
import mods.prototype_eng.structure.Facts;
import mods.prototype_eng.hatch.Configuration;
import mods.prototype_eng.hatch.ResourceSide;
import mods.prototype_eng.hatch.EnergySide;
import mods.pe_run_tests.CoreCases;
import mods.pe_run_tests.NativeChecks;

// Only local data behavior is asserted here; this object is not a saved machine owner.
var data = ComponentData.from({initial: 1});
data["count"] = 2;
data.flag = true;
var snapshot as IData = data.asIData();
data["count"] = 7;
data.flag = false;
var currentCount as int = data["count"] as int;
var currentFlag as bool = data.flag.asBool();
var saved = snapshot.asMap();
NativeChecks.check("core_sdk_member_index_snapshot", currentCount == 7 && !currentFlag
    && data.getInt("absent", 11) == 11 && saved["count"].asInt() == 2
    && saved["flag"].asBool() && saved["initial"].asInt() == 1);
data.merge({initial: 3, nested: {value: 9}});
var detached = data.asIData().asMap();
data.remove("nested");
NativeChecks.check("core_sdk_idata_merge_remove", data.getInt("initial", 0) == 3
    && !CoreCases.dataContains(data, "nested") && detached["nested"].asMap()["value"].asInt() == 9);
data.setLong("large", 2147483648 as long);
data.setDouble("fraction", 1.5);
data.setString("word", "ready");
data.setBool("typedFlag", true);
NativeChecks.check("core_sdk_typed", data.getLong("large", 0) == 2147483648 as long
    && data.getDouble("fraction", 0.0) == 1.5 && data.getString("word", "") == "ready"
    && data.getBool("typedFlag", false));
data.clear();
NativeChecks.check("core_sdk_clear", data.length == 0 && !CoreCases.dataContains(data, "count"));

var names = Bindings.scope("pe_run_tests:bindings");
CoreCases.checkBindings(names);
var oldMatcher = names.matcher("pe_run_tests:core_matcher");
names.defineMatcher(Matcher.create("pe_run_tests:core_matcher", function(candidate, pattern) { return false; }));
CoreCases.matchers(oldMatcher, names.matcher("pe_run_tests:core_matcher"));
var material = CoreCases.material(1);
var product = CoreCases.product(1);

// Exact stage costs at parallelism 1: input 14, product 10, FE input 19 / output 15.
var stageResources = Requirement.resourceIO("pe_run_tests:core_stage_resources",
    [material.withAmount(10)], [material.withAmount(2)], [product.withAmount(3)], [product.withAmount(4)], false);
var stageEnergy = Requirement.energy("pe_run_tests:core_stage_energy", 5, 7, 2, 11, false);
var stages = Recipe.create("pe_run_tests:core_stages", "run-v1", 2, [stageResources, stageEnergy]);
CoreCases.capture("stages", stages);

var allocation = InputAllocation.create("pe_run_tests:core_allocation", function(context) {
    if (context.isEnergy) {
        return [AllocationPortion.create(context.sources[1].index, context.amount)] as AllocationPortion[];
    }
    // Mutation of returned arrays must not change the frozen source facts used by the planner.
    var localSources = context.sources;
    localSources[0] = localSources[1];
    var localResources = context.sources[0].resources;
    localResources[0] = product.withAmount(99);
    return [AllocationPortion.create(context.sources[0].index, context.sources[0].resources[0].amount - 2),
            AllocationPortion.create(context.sources[1].index, context.amount - 7)] as AllocationPortion[];
});
var allocated = Recipe.create("pe_run_tests:core_allocated", "run-v1", 0,
    [Requirement.resources("pe_run_tests:core_alloc_input", [material.withAmount(10)], []).customInputAllocation(allocation),
     Requirement.energy("pe_run_tests:core_alloc_fe", 3, 0, 0, 0, false).customInputAllocation(allocation)]);
CoreCases.capture("allocated", allocated);
var duplicate = InputAllocation.create("pe_run_tests:core_duplicate", function(context) {
    return [AllocationPortion.create(context.sources[0].index, 5),
            AllocationPortion.create(context.sources[0].index, 5)] as AllocationPortion[];
});
CoreCases.capture("duplicate", Recipe.create("pe_run_tests:core_duplicate_recipe", "run-v1", 0,
    [Requirement.resources("pe_run_tests:core_duplicate_input", [material.withAmount(10)], []).customInputAllocation(duplicate)]));
CoreCases.capture("single", Recipe.create("pe_run_tests:core_single", "run-v1", 0,
    [Requirement.resources("pe_run_tests:core_single_input", [material.withAmount(10)], []).singleInputSource()]));

var weighted = Recipe.create("pe_run_tests:core_weighted", "run-v1", 1,
    [Requirement.resources("pe_run_tests:core_weighted_output", [], [product.withAmount(99)])
        .randomOutputs([CoreCases.secondary(99), product.withAmount(2), product.withAmount(3)], [0, 1, 2], 2).chance(200.0, null)]);
CoreCases.capture("weighted", weighted);
var zeroChance = Recipe.create("pe_run_tests:core_chance_zero", "run-v1", 1,
    [Requirement.resources("pe_run_tests:core_chance_zero_output", [], [product.withAmount(99)]).chance(0.0, null)]);
CoreCases.capture("chance_zero", zeroChance);
CoreCases.capture("chance_attribute", Recipe.create("pe_run_tests:core_chance_attribute", "run-v1", 1,
    [Requirement.attributeAddition("pe_run_tests:core_chance_multiplier", "pe_run_tests:core_chance", "chance", 1.0, true, true),
     Requirement.resources("pe_run_tests:core_chance_attribute_output", [], [product.withAmount(3)]).chance(50.0, "pe_run_tests:core_chance")]));

var selection = Requirement.selective("pe_run_tests:core_selective",
    [Requirement.resources("pe_run_tests:core_unavailable_candidate", [material.withAmount(200)], []),
     Requirement.energy("pe_run_tests:core_optional", 0, 0, 0, 0, false)],
    [SelectiveModifier.create("pe_run_tests:core_select_patch", function(context) {
        NativeChecks.check("core_selective_second_candidate", context.candidateIndex == 1);
        context.putResourcePatch(stageResources, "partial", [], null, null, [], null);
        context.putEnergyPatch(stageEnergy, "partial", 0, null, null);
        context.data.selectionSeen = true;
    })]);
CoreCases.capture("selective", Recipe.create("pe_run_tests:core_selected", "run-v1", 1,
    [selection, stageResources, stageEnergy]));
var partial = OverlayModifier.create("pe_run_tests:core_partial", function(context) {
    context.putResourcePatch(stageResources, "partial", [], null, null, [], null);
    context.putEnergyPatch(stageEnergy, "partial", 0, null, null);
    context.data.overlaySeen = true;
});
CoreCases.overlay("partial", partial);
CoreCases.capture("partial", Recipe.create("pe_run_tests:core_partial_recipe", "run-v1", 1, [stageResources, stageEnergy]));
CoreCases.capture("attributes", Recipe.create("pe_run_tests:core_attributes", "run-v1", 1,
    [Requirement.attribute("pe_run_tests:core_add", "prototype_eng:process_parallelism", "core_add", 1.0, "ADDITION", true, true),
     Requirement.attribute("pe_run_tests:core_base", "prototype_eng:process_parallelism", "core_base", 1.0, "MULTIPLY_BASE", true, true),
     Requirement.attribute("pe_run_tests:core_total", "prototype_eng:process_parallelism", "core_total", 1.0, "MULTIPLY_TOTAL", true, true),
     Requirement.resources("pe_run_tests:core_attr_output", [], [product]) ]));
CoreCases.capture("fuzzy", Recipe.create("pe_run_tests:core_fuzzy", "run-v1", 0,
    [Requirement.fuzzyInput("pe_run_tests:core_fuzzy_input", [material, product], 10)]));
CoreCases.capture("dynamic", Recipe.create("pe_run_tests:core_dynamic", "run-v1", 0,
    [Requirement.dynamicInput("pe_run_tests:core_dynamic_input", oldMatcher, material, 10, [material], 8)]));
CoreCases.capture("combined", Recipe.create("pe_run_tests:core_combined", "run-v1", 0,
    [Requirement.combineResources("pe_run_tests:core_combined_input",
        [Requirement.resources("pe_run_tests:core_combined_fixed", [material.withAmount(5)], [product.withAmount(2)]),
         Requirement.fuzzyInput("pe_run_tests:core_combined_fuzzy", [material], 10),
         Requirement.dynamicInput("pe_run_tests:core_combined_dynamic", oldMatcher, material, 10, [material], 8)])]));
CoreCases.capture("void", Recipe.create("pe_run_tests:core_void", "run-v1", 0,
    [Requirement.resourceIO("pe_run_tests:core_void_resources", [], [], [], [product.withAmount(10)], true),
     Requirement.energy("pe_run_tests:core_void_energy", 0, 0, 0, 10, true)]));
CoreCases.capture("excess_reject", Recipe.create("pe_run_tests:core_excess_reject", "run-v1", 0,
    [Requirement.resources("pe_run_tests:core_excess_resource", [], [product.withAmount(10)]),
     Requirement.energy("pe_run_tests:core_excess_energy", 0, 0, 0, 10, false)]));
CoreCases.capture("checkpoint", Recipe.create("pe_run_tests:core_checkpoint", "run-v1", 2,
    [Requirement.checkpoint("pe_run_tests:core_checkpoint_gate", 1,
        Requirement.resources("pe_run_tests:core_checkpoint_output", [], [product.withAmount(2)]), false)]));
CoreCases.capture("long", Recipe.create("pe_run_tests:core_long", "run-v1", 0,
    [Requirement.resources("pe_run_tests:core_long_io", [material.withAmount(2147483648 as long)],
        [product.withAmount(2147483650 as long)])]));

var selected = Recipe.withSelection("pe_run_tests:core_parallel", "run-v1", 10,
    [Requirement.resources("pe_run_tests:core_parallel_input", [material], [product]),
     Requirement.energy("pe_run_tests:core_parallel_fe", 2, 0, 0, 0, false)], [], 4,
    // Keep the selected amount at 5 even when the scanner retries a smaller suggestion.
    ParallelismSelection.create(function(suggested as int) { return 5; }));
CoreCases.capture("parallel", selected);
var fixed = Recipe.withSelection("pe_run_tests:core_fixed", "run-v1", 10,
    [Requirement.resources("pe_run_tests:core_fixed_input", [material], [product])], [], 4, ParallelismSelection.fixed(2));
CoreCases.capture("fixed", fixed);
// Addon supplies a real requirement system, pool lookup policy and exact overlay codec.
var exactOverlay = CoreCases.externalOverlay();
CoreCases.capture("external_script", Recipe.create("pe_run_tests:core_external_script", "run-v1", 1, [CoreCases.externalRequirement()]));
CoreCases.capture("external", CoreCases.externalRecipe());
CoreCases.overlay("external", OverlayModifier.create("pe_run_tests:core_external_modifier", function(context) {
    context.putOverlay(exactOverlay, "number");
}));

CoreCases.pool(Pool.create("pe_run_tests:core_pool", Text.CorePool.key, CoreCases.allRecipes()));
// SimplePool requires exactly one fixed resource requirement. FE stays in the ordinary pool.
CoreCases.pool(Pool.create("pe_run_tests:core_parallel_pool", Text.CoreParallelPool.key, [selected]));
CoreCases.pool(Pool.simple("pe_run_tests:core_simple_pool", Text.CoreSimplePool.key, [fixed]));
CoreCases.pool(CoreCases.customPool());

// Runtime-visible shape uses real hatch facts. No live world access occurs in validators.
Structure.template("pe_run_tests:core_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(-1,0,0, Predicate.resourceHatch()).put(1,0,0, Predicate.resourceHatch())
        .put(0,0,1, Predicate.energyInputHatch()).put(0,1,0, Predicate.notAir()), [], []).translated(Text.CoreStructure.key).register();
var query = Query.create("pe_run_tests:core_brightness", function(world as IWorld, pos as IBlockPos) {
    return world.getBrightness(pos) as IData;
});
CoreCases.query(query);
var callback = Predicate.custom(["biomeName", "biomeId", "dimension", "light", "worldTime"],
    [query], [[0,0,0], [1,0,0]], [[0,0,0]], function(ctx as Facts) {
        CoreCases.customVisited();
        return ctx.x == 10 && ctx.y == 64 && ctx.offsetZ == 6
            && ctx.biomeName == "Birch Forest" && ctx.biomeId == "pe_run_tests:plains"
            && ctx.worldTime == 6000 && ctx.read(query).asInt() == 17
            && ctx.at(1,0,0).light == 9 && ctx.controller.dimension == 12
            && ctx.atWorld(10,64,10).dimension == 12;
    });
var validator = Predicate.all([Predicate.anyOf([callback.at(1,0,0)])]);
validator.register("pe_run_tests:core_custom");
CoreCases.predicate(validator);
var child = Structure.slice("pe_run_tests:core_custom_child", "NORTH_UP", 3,0,0, false,
    Pattern.create(), 1,1, 0,1,0, [validator], []);
CoreCases.shape(Structure.template("pe_run_tests:core_custom_root", "NORTH_UP", 2,0,0, false,
    Pattern.create(), [], [child]));
NativeChecks.check("core_custom_registration_no_callback", CoreCases.customVisits() == 0);

// Construction is isolated; this check does not call configureDefaults/request.
// Actual loaded-host propagation is a separate user-world operation.
CoreCases.hatch(Configuration.resources(10,
    ResourceSide.nativeCapacities(2, [material.withAmount(100), CoreCases.secondary(200)]),
    ResourceSide.nativeCapacities(3, [material.withAmount(300), CoreCases.secondary(400)])));
CoreCases.hatch(Configuration.energy(10, EnergySide.create(1000, 100), EnergySide.create(2000, 200)));
