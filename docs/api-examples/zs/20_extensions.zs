#loader crafttweaker
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Matcher;
import mods.prototype_eng.recipe.ParallelismSelection;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.SelectiveModifier;

var names = Bindings.scope("example:recipes");
var basicRecipe = Recipe.create("example:iron_to_gold", "recipe-v1", 80, [
    Requirement.resources("example:items", [Resource.item(<minecraft:iron_ingot>, 2)],
        [Resource.item(<minecraft:gold_ingot>, 1)]),
    Requirement.energy("example:power", 0, 8, 0, 0, false)
]);

var sameKey = Matcher.create("example:same_key", function(candidate, pattern) {
    return candidate.sameKey(pattern);
});
names.defineMatcher(sameKey);

var parallelism = ParallelismSelection.create(function(suggested as int) {
    return suggested > 2 ? 2 : suggested;
});
var faster = SelectiveModifier.create("example:faster", function(context) {
    context.mulProcessSpeed("speed", 1.25);
});
names.defineSelective(faster);

var sand = Resource.item(<minecraft:sand>, 1);
var dynamicInput = Requirement.dynamicInput("example:sand_input", names.matcher("example:same_key"),
    sand, 2, [sand], 16);
var glassOutput = Requirement.selective("example:glass_output", [
    Requirement.resources("example:bonus_output", [Resource.item(<minecraft:coal>, 1)],
        [Resource.item(<minecraft:glass>, 2)]),
    Requirement.resources("example:regular_output", [], [Resource.item(<minecraft:glass>, 1)])
], [names.selective("example:faster")]);
var extendedRecipe = Recipe.withSelection("example:sand_to_glass", "recipe-v1", 80,
    [dynamicInput, glassOutput, Requirement.energy("example:glass_power", 0, 8, 0, 0, false)], [], 4, parallelism);
var pool = Pool.create("example:smelting", "example.recipe_pool.smelting", [basicRecipe, extendedRecipe]);
names.registerPools([pool], []);
names.declareManifest("example:recipe_set", "v1");
