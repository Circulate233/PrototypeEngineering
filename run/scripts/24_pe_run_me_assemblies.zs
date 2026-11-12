#loader crafttweaker
import mods.pe_run_tests.Text;
import mods.pe_run_tests.CoreCases;
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;

var casing = Predicate.anyOf([Predicate.block("prototype_eng:block_casing_plain"),
    Predicate.block("prototype_eng:block_casing"), Predicate.block("prototype_eng:casing_frame")]);
var input = Predicate.anyOf([Predicate.block("prototype_eng:me_input_assembly"), Predicate.block("prototype_eng:me_interface")]);
var output = Predicate.anyOf([Predicate.block("prototype_eng:me_output_assembly"), Predicate.block("prototype_eng:me_interface")]);
Structure.template("pe_run_tests:me_assemblies_shape", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0,input).put(1,0,0,output).put(0,1,0,casing), [], []).translated(Text.MeStructure.key).register();
Structure.template("pe_run_tests:me_interface_shape", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0,Predicate.block("prototype_eng:me_interface")).put(0,1,0,casing), [], [])
    .translated(Text.MeStructure.key).register();
Structure.template("pe_run_tests:me_pattern_shape", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0,Predicate.block("prototype_eng:me_pattern_assembly")).put(0,1,0,casing), [], [])
    .translated(Text.MeStructure.key).register();
Structure.template("pe_run_tests:me_colors_shape", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0,Predicate.block("prototype_eng:input_hatch_4"))
        .put(-1,0,1,Predicate.block("prototype_eng:input_hatch_4"))
        .put(1,0,0,Predicate.block("prototype_eng:output_hatch_4"))
        .put(1,0,1,Predicate.block("prototype_eng:output_hatch_4")).put(0,1,0,casing), [], [])
    .translated(Text.MeStructure.key).register();
var parallel = Predicate.anyOf([
    Predicate.block("prototype_eng:parallel_controller_normal"), Predicate.block("prototype_eng:parallel_controller_reinforced"),
    Predicate.block("prototype_eng:parallel_controller_elite"), Predicate.block("prototype_eng:parallel_controller_super"),
    Predicate.block("prototype_eng:parallel_controller_ultimate"), Predicate.block("pe_run_tests:parallel_zs"),
    Predicate.block("pe_visual_examples:parallel_java")]);
Structure.template("pe_run_tests:me_parallel_shape", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0,Predicate.block("prototype_eng:input_hatch_4"))
        .put(1,0,0,Predicate.block("prototype_eng:output_hatch_4")).put(0,0,1,parallel).put(0,1,0,casing), [], [])
    .translated(Text.MeStructure.key).register();

CoreCases.pool(Pool.create("pe_run_tests:me_resources_pool", Text.MeResourcePool.key, [
    Recipe.create("pe_run_tests:me_dry", "me-run-v1", 80, [
        Requirement.resources("pe_run_tests:me_dry_io", [Resource.item(<minecraft:iron_ingot>,2)], [Resource.item(<minecraft:iron_nugget>,9)])]),
    Recipe.create("pe_run_tests:me_wet", "me-run-v1", 100, [
        Requirement.resources("pe_run_tests:me_wet_io", [Resource.item(<minecraft:sand>,2),Resource.fluid(<liquid:water>,250)],
            [Resource.item(<minecraft:clay_ball>,1)])])
]));
// The first recipe exposes unsafe cross-pattern mixing. The two native patterns supplied by
// /pe_me_examples patterns omit redstone because the shared manual catalyst buffer provides it.
// Redstone is consumed once per recipe here; the example does not invent a reusable-catalyst rule.
CoreCases.pool(Pool.create("pe_run_tests:me_pattern_pool", Text.MePatternPool.key, [
    Recipe.create("pe_run_tests:me_pattern_pair", "me-run-v1", 100, [
        Requirement.resources("pe_run_tests:me_pair_io", [Resource.item(<minecraft:iron_ingot>,1),Resource.item(<minecraft:gold_ingot>,1),Resource.item(<minecraft:redstone>,1)],
            [Resource.item(<minecraft:diamond>,1)])]),
    Recipe.create("pe_run_tests:me_pattern_iron", "me-run-v1", 100, [
        Requirement.resources("pe_run_tests:me_iron_io", [Resource.item(<minecraft:iron_ingot>,1),Resource.item(<minecraft:redstone>,1)],
            [Resource.item(<minecraft:iron_nugget>,9)])]),
    Recipe.create("pe_run_tests:me_pattern_gold", "me-run-v1", 100, [
        Requirement.resources("pe_run_tests:me_gold_io", [Resource.item(<minecraft:gold_ingot>,1),Resource.item(<minecraft:redstone>,1)],
            [Resource.item(<minecraft:gold_nugget>,9)])])
]));
CoreCases.pool(Pool.create("pe_run_tests:me_color_pool", Text.MeColorPool.key, [
    Recipe.create("pe_run_tests:me_color_combine", "me-run-v1", 80, [
        Requirement.resources("pe_run_tests:me_color_io", [Resource.item(<minecraft:iron_ingot>,4)], [Resource.item(<minecraft:diamond>,1)])])
]));
CoreCases.pool(Pool.create("pe_run_tests:me_parallel_pool", Text.MeParallelPool.key, [
    Recipe.create("pe_run_tests:me_parallel_press", "me-run-v1", 200, [
        Requirement.resources("pe_run_tests:me_parallel_io", [Resource.item(<minecraft:iron_ingot>,1)], [Resource.item(<minecraft:iron_nugget>,9)])])
]));
CoreCases.pool(Pool.create("pe_run_tests:me_wide_pool", Text.MeWidePool.key, [
    Recipe.create("pe_run_tests:me_long_cache", "me-run-v1", 80, [
        Requirement.resources("pe_run_tests:me_long_io", [Resource.item(<minecraft:coal>,1)],
            [Resource.item(<minecraft:iron_nugget>,2147483648),Resource.fluid(<liquid:water>,9000000000)])]),
    Recipe.create("pe_run_tests:me_wide_cache", "me-run-v1", 80, [
        Requirement.resources("pe_run_tests:me_wide_io", [Resource.item(<minecraft:sand>,1)], [
            Resource.item(<minecraft:wool:0>, 1),
            Resource.item(<minecraft:wool:1>, 1),
            Resource.item(<minecraft:wool:2>, 1),
            Resource.item(<minecraft:wool:3>, 1),
            Resource.item(<minecraft:wool:4>, 1),
            Resource.item(<minecraft:wool:5>, 1),
            Resource.item(<minecraft:wool:6>, 1),
            Resource.item(<minecraft:wool:7>, 1),
            Resource.item(<minecraft:wool:8>, 1),
            Resource.item(<minecraft:wool:9>, 1),
            Resource.item(<minecraft:wool:10>, 1),
            Resource.item(<minecraft:wool:11>, 1),
            Resource.item(<minecraft:wool:12>, 1),
            Resource.item(<minecraft:wool:13>, 1),
            Resource.item(<minecraft:wool:14>, 1),
            Resource.item(<minecraft:wool:15>, 1),
            Resource.item(<minecraft:concrete:0>, 1),
            Resource.item(<minecraft:concrete:1>, 1),
            Resource.item(<minecraft:concrete:2>, 1),
            Resource.item(<minecraft:concrete:3>, 1),
            Resource.item(<minecraft:concrete:4>, 1),
            Resource.item(<minecraft:concrete:5>, 1),
            Resource.item(<minecraft:concrete:6>, 1),
            Resource.item(<minecraft:concrete:7>, 1),
            Resource.item(<minecraft:concrete:8>, 1),
            Resource.item(<minecraft:concrete:9>, 1),
            Resource.item(<minecraft:concrete:10>, 1),
            Resource.item(<minecraft:concrete:11>, 1),
            Resource.item(<minecraft:concrete:12>, 1),
            Resource.item(<minecraft:concrete:13>, 1),
            Resource.item(<minecraft:concrete:14>, 1),
            Resource.item(<minecraft:concrete:15>, 1),
            Resource.item(<minecraft:stained_glass:0>, 1),
            Resource.item(<minecraft:stained_glass:1>, 1),
            Resource.item(<minecraft:stained_glass:2>, 1),
            Resource.item(<minecraft:stained_glass:3>, 1),
            Resource.item(<minecraft:stained_glass:4>, 1),
            Resource.item(<minecraft:stained_glass:5>, 1),
            Resource.item(<minecraft:stained_glass:6>, 1),
            Resource.item(<minecraft:stained_glass:7>, 1),
            Resource.item(<minecraft:stained_glass:8>, 1),
            Resource.item(<minecraft:stained_glass:9>, 1),
            Resource.item(<minecraft:stained_glass:10>, 1),
            Resource.item(<minecraft:stained_glass:11>, 1),
            Resource.item(<minecraft:stained_glass:12>, 1),
            Resource.item(<minecraft:stained_glass:13>, 1),
            Resource.item(<minecraft:stained_glass:14>, 1),
            Resource.item(<minecraft:stained_glass:15>, 1)
        ])])
]));
