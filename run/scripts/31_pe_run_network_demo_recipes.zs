#loader crafttweaker
import mods.pe_run_tests.Text;
import mods.pe_run_tests.Resources;
import mods.pe_run_tests.CoreCases;
import mods.prototype_eng.recipe.Recipe;
import mods.prototype_eng.recipe.Pool;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.recipe.ParallelismSelection;
import mods.prototype_eng.network.NetworkRequirements;
import mods.prototype_eng.structure.Structure;
import mods.prototype_eng.structure.Pattern;
import mods.prototype_eng.structure.Predicate;

var computeLoad = Recipe.withSelection("pe_run_tests:net_demo_compute_load", "network-demo-v1", 100,
    [Requirement.resources("pe_run_tests:net_demo_compute_io", [Resource.item(<minecraft:cobblestone>, 1)], [Resource.item(<minecraft:glass>, 1)]),
     Requirement.energy("pe_run_tests:net_demo_compute_fe", 0, 8, 0, 0, false)], [], 1, ParallelismSelection.fixed(1));
NetworkRequirements.create().resource(Resources.netCompute(), 25).resource(Resources.memory(), 8)
    .resource(Resources.bandwidth(), 16.5).bind(computeLoad, 10);
var quantumLoad = Recipe.withSelection("pe_run_tests:net_demo_quantum_load", "network-demo-v1", 60,
    [Requirement.resources("pe_run_tests:net_demo_quantum_io", [Resource.item(<minecraft:quartz>, 1)], [Resource.item(<minecraft:iron_nugget>, 2)]),
     Requirement.energy("pe_run_tests:net_demo_quantum_fe", 0, 4, 0, 0, false)], [], 1, ParallelismSelection.fixed(1));
NetworkRequirements.create().resource(Resources.qubit(), 2).resource(Resources.storage(), 8)
    .resource(Resources.signal(), 20).bind(quantumLoad, 10);
CoreCases.pool(Pool.create("pe_run_tests:net_demo_pool", Text.DemoPool.key, [computeLoad, quantumLoad]));

Structure.template("pe_run_tests:net_demo_center_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(0, 1, 0, Predicate.block("minecraft:iron_block"))
        .put(0, 0, 1, Predicate.energyInputHatch()), [], []).translated(Text.DemoCenter.key).register();
Structure.template("pe_run_tests:net_demo_computer_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(-1, 0, 0, Predicate.block("prototype_eng:computation_hatch_4"))
        .put(1, 0, 0, Predicate.resourceHatch()).put(0, 0, 1, Predicate.energyInputHatch()), [], [])
    .translated(Text.DemoComputer.key).register();
Structure.template("pe_run_tests:net_demo_work_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(1, 0, 0, Predicate.resourceHatch()).put(-1, 0, 0, Predicate.resourceHatch())
        .put(0, 0, 1, Predicate.energyInputHatch()), [], []).translated(Text.NetworkStructure.key).register();
Structure.template("pe_run_tests:net_demo_database_shape", "NORTH_UP", 0, 0, 0, false,
    Pattern.create().put(0, 1, 0, Predicate.block("minecraft:iron_block")), [], [])
    .translated(Text.DemoDatabase.key).register();
