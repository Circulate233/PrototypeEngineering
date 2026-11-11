#loader preinit

import mods.prototype_eng.MachineType;
import mods.prototype_eng.MachineFactory;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Matcher;
import mods.pe_run_tests.CoreCases;

// Machine registration closes before the ordinary loader. Never use cross-loader globals.
var names = Bindings.scope("pe_run_tests:bindings");
CoreCases.bindings(names);
names.defineMatcher(Matcher.create("pe_run_tests:core_matcher", function(candidate, pattern) {
    return candidate.sameKey(pattern);
}));

MachineType.create("pe_run_tests:core_thread", "PE Run: Multithread owner", "pe_run_tests:core_shape")
    .componentData().recipePool("pe_run_tests:core_parallel_pool")
    .attribute("prototype_eng:process_parallelism", 4)
    .processing(names, 8, 8).register("run-v1");
MachineType.create("pe_run_tests:core_cross", "PE Run: Cross-recipe owner", "pe_run_tests:core_shape")
    .factory(MachineFactory.crossRecipe()).componentData().recipePool("pe_run_tests:core_parallel_pool")
    .attribute("prototype_eng:process_parallelism", 4)
    .processing(names, 8, 8).register("run-v1");
MachineType.create("pe_run_tests:core_addon", "PE Run: Addon owner", "pe_run_tests:core_shape")
    .factory(CoreCases.factory()).componentData().recipePool("pe_run_tests:core_custom_pool")
    .processing(names, 8, 8).executionMode("MAIN_THREAD").register("run-v1");
MachineType.create("pe_run_tests:core_fixed", "PE Run: Fixed parallelism", "pe_run_tests:core_shape")
    .componentData().recipePool("pe_run_tests:core_simple_pool")
    .attribute("prototype_eng:process_parallelism", 4)
    .processing(names, 8, 8).register("run-v1");
