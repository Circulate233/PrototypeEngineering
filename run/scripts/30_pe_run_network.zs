#loader crafttweaker
import mods.pe_run_tests.Text;
import mods.pe_run_tests.Resources;
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.ParallelismSelection;
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.resource.ComputingResource;
import mods.prototype_eng.resource.ComputingResources;
import mods.prototype_eng.resource.ComputingModifier;
import mods.prototype_eng.network.NetworkConfiguration;
import mods.prototype_eng.network.NetworkRequirements;
import mods.prototype_eng.network.NetworkRecipes;
import mods.prototype_eng.research.Research;
import mods.prototype_eng.research.ResearchStage;
import mods.prototype_eng.research.ResearchFormula;
import mods.prototype_eng.research.ResearchReward;
import mods.prototype_eng.research.ResearchDownloads;
import mods.prototype_eng.research.ResearchImports;
import mods.prototype_eng.research.ResearchServer;
import mods.pe_run_tests.NetworkRender;
import mods.pe_run_tests.NativeChecks;
import mods.pe_run_tests.CoreCases;

var names = Bindings.scope("pe_run_tests:bindings");
var compute = Resources.netCompute();
var supply = ComputingResources.create().add(compute, 100);
NativeChecks.check("net_supply_addTo", supply.amount(compute) == 100);
var empty = ComputingResources.create();
var modifier = ComputingModifier.create().flat(compute, 2).multiplierPpm(compute, 1000000);

var manualReward = ResearchReward.create("pe_run_tests:research_reward")
    .item("pe_run_tests:research_reward_item", Resource.item(<minecraft:emerald>, 2))
    .event("pe_run_tests:research_reward_event", function(event) {
        return NetworkRender.rewardEvent(event);
    });
var manual = ResearchStage.create(1, 2, 0, 40, ResearchFormula.create())
    .mutex("pe_run_tests:research_mutex", 1)
    .material(Requirement.resources("pe_run_tests:research_manual_material", [Resource.item(<minecraft:iron_ingot>, 2)], []))
    .reward(1, manualReward);
Research.create("pe_run_tests:research_manual", 2, false, "ITEM_ONLY", [manual], function(context) {
    return context.playerId != "" && context.teamId != "";
}).unlock("pe_run_tests:research_unlock", "pe_run_tests:net_device", 1).register();

var station = ResearchStage.create(1, 2, 1, 120, ResearchFormula.create().affine(compute, 1200, 100))
    .prerequisite("pe_run_tests:research_manual", 1)
    .material(Requirement.resources("pe_run_tests:research_station_material",
        [Resource.item(<minecraft:gold_ingot>, 2), Resource.item(<minecraft:redstone>, 10), Resource.item(<minecraft:iron_ingot>, 100)], []))
    .reward(1, manualReward);
Research.create("pe_run_tests:research_station", 2, false, "DATABASE_AND_ITEM", [station], function(context) {
    return context.teamHasNetworkCenter && context.networkCenterLevel >= 1 && context.available(compute) >= 10;
}).register();
Research.create("pe_run_tests:research_mutex", 2, false, "ITEM_ONLY",
    [ResearchStage.create(1, 2, 0, 40, ResearchFormula.create()).mutex("pe_run_tests:research_manual", 1)],
    Research.alwaysAllowed()).register();
Research.create("pe_run_tests:research_loop", 2147483647, false, "DATABASE_AND_ITEM",
    [ResearchStage.create(1, 2147483647, 1, 100, ResearchFormula.create().perTick(compute, 0.1))],
    Research.alwaysAllowed()).register();
Research.create("pe_run_tests:research_special", 1, true, "DATABASE_AND_ITEM",
    [ResearchStage.create(1, 1, 1, 100, ResearchFormula.create().constant(compute, 10))],
    Research.alwaysAllowed()).register();
ResearchServer.permissions(function(action, context) { return context.standardDecision(action); });
ResearchDownloads.register("pe_run_tests:paper_research_download", <minecraft:paper>, "pe_run_tests:research_station", 1, 1, <minecraft:book>, function(context) {
    NativeChecks.check("research_download_actual_context", context.teamId != "" && context.dataCenterId != "" && context.operationId != "");
    return <minecraft:book>;
});
ResearchImports.register("pe_run_tests:book_research_import", <minecraft:book>, "pe_run_tests:research_station", 1, <minecraft:paper>);

var netRecipe = Recipe.withSelection("pe_run_tests:net_recipe", "v1", 80,
    [Requirement.resources("pe_run_tests:net_io", [Resource.item(<minecraft:cobblestone>, 2)], [Resource.item(<minecraft:glass>, 4)]),
     Requirement.energy("pe_run_tests:net_fe", 0, 8, 0, 0, false)], [], 4, ParallelismSelection.fixed(3));
CoreCases.pool(Pool.create("pe_run_tests:net_pool", Text.NetworkPool.key, [netRecipe]));
NetworkRequirements.create().research("pe_run_tests:research_station", 1).resource(compute, 2).bind(netRecipe, 10);
NetworkRecipes.register(20, function(recipe, requirements) {
    if recipe.matches(netRecipe) { requirements.research("pe_run_tests:research_station", 1); }
});

Structure.template("pe_run_tests:net_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.notAir()).put(1, 0, 0, Predicate.resourceHatch())
        .put(-1, 0, 0, Predicate.resourceHatch()).put(0, 0, 1, Predicate.energyInputHatch()), [], []).translated(Text.NetworkStructure.key).register();

Structure.template("pe_run_tests:net_center_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(0, 1, 0, Predicate.block("minecraft:iron_block"))
        .put(0, 0, 1, Predicate.energyInputHatch()), [], []).register();

Structure.template("pe_run_tests:net_computer_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(-1, 0, 0, Predicate.block("prototype_eng:computation_hatch_4"))
        .put(0, 0, 1, Predicate.energyInputHatch()), [], []).register();
