#loader crafttweaker
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;
import mods.pe_run_tests.CoreCases;

// Real shared nodes, not flattened copies of a large top-level pattern.
var glass = Predicate.anyOf([Predicate.block("minecraft:glass"), Predicate.block("minecraft:stained_glass")]);
var iron = Predicate.block("minecraft:iron_block");
var gold = Predicate.block("minecraft:gold_block");
var root = Pattern.create().put(-1,0,0, Predicate.block("prototype_eng:input_hatch_4"))
    .put(1,0,0, Predicate.block("prototype_eng:output_hatch_4"))
    .put(0,0,1, Predicate.block("prototype_eng:energy_input_hatch_4")).put(0,1,0, iron);
var marker = Structure.template("pe_run_tests:nested_marker", "NORTH_UP", 0,0,2, false,
    Pattern.create().put(0,1,0, Predicate.block("minecraft:lapis_block"))
        .put(1,1,0, Predicate.block("minecraft:emerald_block")), [], [])
    .translated("pe_run_tests.structure.marker");
var pod = Structure.template("pe_run_tests:nested_shared_pod", "NORTH_UP", 0,0,0, false,
    Pattern.create().put(-1,0,0,gold).put(0,0,0,glass).put(1,0,0,gold)
        .put(-1,1,0,glass).put(0,1,0,Predicate.block("minecraft:quartz_block")).put(1,1,0,glass)
        .put(-1,2,0,gold).put(0,2,0,glass).put(1,2,0,gold), [], [marker])
    .translated("pe_run_tests.structure.pod");
var left = Structure.template("pe_run_tests:nested_left", "NORTH_UP", -3,0,3, false, Pattern.create(), [], [pod])
    .translated("pe_run_tests.structure.left");
var right = Structure.template("pe_run_tests:nested_right", "NORTH_UP", 3,0,3, false, Pattern.create(), [], [pod])
    .translated("pe_run_tests.structure.right");
var nested = Structure.template("pe_run_tests:nested_shape", "NORTH_UP", 0,0,0, false, root, [], [left,right])
    .translated("pe_run_tests.structure.nested");
marker.register(); pod.register(); left.register(); right.register(); nested.register();

// A greedy run of 1..4 horizontal 3x3 layers. Children attach once at the last matched slice.
var cap = Structure.template("pe_run_tests:expandable_cap", "NORTH_UP", 0,1,0, false,
    Pattern.create().put(-1,0,-1,gold).put(0,0,-1,gold).put(1,0,-1,gold)
        .put(-1,0,0,gold).put(0,0,0,gold).put(1,0,0,gold)
        .put(-1,0,1,gold).put(0,0,1,gold).put(1,0,1,gold), [], [])
    .translated("pe_run_tests.structure.cap");
var body = Structure.slice("pe_run_tests:expandable_body", "NORTH_UP", 0,1,3, false,
    Pattern.create().put(-1,0,-1,iron).put(0,0,-1,glass).put(1,0,-1,iron)
        .put(-1,0,0,glass).put(0,0,0,Predicate.block("minecraft:redstone_block")).put(1,0,0,glass)
        .put(-1,0,1,iron).put(0,0,1,glass).put(1,0,1,iron), 1,4, 0,1,0, [], [cap])
    .translated("pe_run_tests.structure.body");
var expandable = Structure.template("pe_run_tests:expandable_shape", "NORTH_UP", 0,0,0, false, root, [], [body])
    .translated("pe_run_tests.structure.expandable");
cap.register(); body.register(); expandable.register();

// Both are actual processing machines with exact codecs installed by the final 95 manifest script.
CoreCases.pool(Pool.create("pe_run_tests:nested_pool", "pe_run_tests.pool.nested",
    [Recipe.create("pe_run_tests:nested_glass", "run-v1", 80,
        [Requirement.resources("pe_run_tests:nested_io", [Resource.item(<minecraft:sand>,1)], [Resource.item(<minecraft:glass>,2)]),
         Requirement.energy("pe_run_tests:nested_fe",0,4,0,0,false)])]));
CoreCases.pool(Pool.create("pe_run_tests:expandable_pool", "pe_run_tests.pool.expandable",
    [Recipe.create("pe_run_tests:expandable_glass", "run-v1", 100,
        [Requirement.resources("pe_run_tests:expandable_io", [Resource.item(<minecraft:sand>,1)], [Resource.item(<minecraft:glass>,4)]),
         Requirement.energy("pe_run_tests:expandable_fe",0,4,0,0,false)])]));
