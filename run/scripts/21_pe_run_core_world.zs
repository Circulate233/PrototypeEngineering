#loader crafttweaker
import mods.pe_run_tests.Text;
import crafttweaker.world.IWorld;
import crafttweaker.world.IBlockPos;
import crafttweaker.data.IData;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.structure.Query;
import mods.prototype_eng.structure.Facts;
import mods.pe_run_tests.CoreCases;

// SDK718 getBrightness(IBlockPos) is int World.getLight, not float lightBrightness.
var query = Query.create("pe_run_tests:core_world_light", function(world as IWorld, pos as IBlockPos) {
    var value as int = world.getBrightness(pos);
    CoreCases.observeWorld(world, pos, value);
    return value as IData;
});
CoreCases.worldQuery(query);
var test = Predicate.custom(["dimension"], [query], [[0,0,0], [1,0,0]], [[0,0,0]], function(ctx as Facts) {
    CoreCases.observeWorldValidation(ctx);
    return ctx.read(query).asInt() >= 8 && ctx.read(query).asInt() <= 15
        && ctx.at(1,0,0).read(query).asInt() >= 0 && ctx.at(1,0,0).read(query).asInt() <= 15
        && ctx.controller.read(query).asInt() >= 0 && ctx.controller.read(query).asInt() <= 15
        && ctx.controller.dimension == ctx.dimension
        && ctx.atWorld(ctx.controller.x, ctx.controller.y, ctx.controller.z).dimension == ctx.dimension
        && ctx.atWorld(ctx.controller.x, ctx.controller.y, ctx.controller.z).read(query).asInt() == ctx.controller.read(query).asInt()
        && ctx.offsetX * ctx.offsetX + ctx.offsetY * ctx.offsetY + ctx.offsetZ * ctx.offsetZ == 36;
});
var gate = Predicate.all([Predicate.anyOf([test.at(1,0,0)])]);
gate.register("pe_run_tests:core_world_gate");
CoreCases.worldPredicate(gate);
var leaf = Structure.slice("pe_run_tests:core_world_leaf", "NORTH_UP", 3,0,0, false,
    Pattern.create(), 1,1, 0,1,0, [gate], []).translated(Text.WorldLeaf.key);
var branch = Structure.template("pe_run_tests:core_world_branch", "NORTH_UP", 2,0,0, false,
    Pattern.create(), [], [leaf]).translated(Text.WorldBranch.key);
var shape = Structure.template("pe_run_tests:core_world_shape", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0, Predicate.resourceHatch()).put(1,0,0, Predicate.resourceHatch())
        .put(6,0,0, Predicate.anyOf([Predicate.block("minecraft:redstone_lamp"), Predicate.block("minecraft:lit_redstone_lamp")])),
    [], [branch]).translated(Text.WorldStructure.key);
leaf.register(); branch.register();
CoreCases.worldShape(shape);
shape.register();
CoreCases.pool(Pool.create("pe_run_tests:core_world_pool", Text.CoreWorldPool.key,
    [Recipe.create("pe_run_tests:core_world_recipe", "run-v1", 40,
        [Requirement.resources("pe_run_tests:core_world_io", [Resource.item(<minecraft:iron_ingot>, 1)],
            [Resource.item(<minecraft:gold_ingot>, 1)])]) ]));
