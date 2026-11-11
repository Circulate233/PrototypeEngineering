#loader crafttweaker
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.render.RenderBindings;
import mods.prototype_eng.jei.Options;
import mods.prototype_eng.jei.Page;
import mods.pe_run_tests.NetworkRender;
import mods.pe_run_tests.CoreCases;

var names = Bindings.scope("pe_run_tests:bindings");
var recipe = Recipe.create("pe_run_tests:client_update", "v1", 120,
    [Requirement.fuzzyInput("pe_run_tests:client_input", [Resource.item(<minecraft:iron_ingot>, 1), Resource.item(<minecraft:gold_ingot>, 1)], 1),
     Requirement.resources("pe_run_tests:client_output", [], [Resource.item(<minecraft:emerald>, 1)]).chance(25.0, null)]);
Options.defaults(true, "alternatives");
var migration = Recipe.create("pe_run_tests:client_migration", "v1", 2400,
    [Requirement.resourceIO("pe_run_tests:client_migration_io", [Resource.item(<minecraft:quartz>, 1)],
        [Resource.item(<minecraft:redstone>, 1)], [], [Resource.item(<minecraft:emerald>, 1)], false)]);
CoreCases.pool(Pool.create("pe_run_tests:client_custom_pool", "pe_run_tests.pool.client_custom", [recipe, migration])
    .jeiOptions(Options.create(false, "expanded")).customJeiPage(Page.named("pe_run_tests:client_independent_page")));
var defaultRecipe = Recipe.create("pe_run_tests:render_default_recipe", "v1", 60,
    [Requirement.resources("pe_run_tests:render_default_io", [Resource.item(<minecraft:cobblestone>, 1)], [Resource.item(<minecraft:glass>, 1)])]);
CoreCases.pool(Pool.create("pe_run_tests:render_default_pool", "pe_run_tests.pool.render_default", [defaultRecipe])
    .jeiOptions(Options.create(true, "alternatives")));
CoreCases.pool(Pool.create("pe_run_tests:client_external_pool", "pe_run_tests.pool.render_default", [defaultRecipe])
    .existingJeiPage("minecraft.crafting"));

var slice = Structure.slice("pe_run_tests:render_per_slice", "NORTH_UP", 0, 1, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.block("minecraft:glass")), 2, 2, 0, 1, 0, [], []);
Structure.template("pe_run_tests:render_whole", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(0, 0, 0, Predicate.notAir()).put(1, 0, 0, Predicate.resourceHatch())
        .put(-1, 0, 0, Predicate.resourceHatch()), [], [slice]).register();

var passes = ["default", "transparent", "bloom", "bloom_transparent"];
for pass in passes {
    var id = "pe_run_tests:render_" + pass;
    var model = RenderBindings.gecko().geo("pe_visual_examples:geo/run_tests_machine.json")
        .texture("pe_visual_examples:textures/run_tests_machine.png")
        .animation("pe_visual_examples:animations/run_tests_machine.json")
        .defaultAnimation("idle").animationLayer("idle").animationLayer("overlay")
        .animationStateKey("animation").animationLayersStateKey("animation_layers")
        .modelOffset(0.25, 0.5, 0.75).yOffset(0.125).pass(pass).forceGlobalRenderer(true);
    RenderBindings.bindGeckoToMachineType(id, model);
    RenderBindings.bindGeckoToStructure(id, "pe_run_tests:render_per_slice", model.sliceMode("per_slice"));
    RenderBindings.bindGeckoToStructure(id, "pe_run_tests:render_whole", model.sliceMode("structure_only"));
}
RenderBindings.bindGeckoToComponentType("pe_run_tests:render_marker", RenderBindings.gecko()
    .geo("pe_visual_examples:geo/run_tests_marker.json").texture("pe_visual_examples:textures/run_tests_marker.png")
    .animation("pe_visual_examples:animations/run_tests_machine.json").defaultAnimation("overlay").pass("transparent")
    .modelOffset(-0.5, 1.0, 0.0));
RenderBindings.bindGeckoToMachineType("pe_run_tests:render_component", RenderBindings.gecko()
    .geo("pe_visual_examples:geo/run_tests_machine.json").texture("pe_visual_examples:textures/run_tests_machine.png")
    .animation("pe_visual_examples:animations/run_tests_machine.json").defaultAnimation("idle")
    .animationStateKey("animation").animationLayersStateKey("animation_layers").pass("default"));
