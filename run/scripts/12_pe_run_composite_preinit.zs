#loader preinit
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;

var names = Bindings.scope("pe_run_tests:bindings");
MachineType.create("pe_run_tests:nested_structure", "pe_run_tests.structure.nested", "pe_run_tests:nested_shape")
    .componentData().recipePool("pe_run_tests:nested_pool").processing(names, 8, 8).register("run-v1");
MachineType.create("pe_run_tests:expandable_structure", "pe_run_tests.structure.expandable", "pe_run_tests:expandable_shape")
    .componentData().recipePool("pe_run_tests:expandable_pool").processing(names, 8, 8).register("run-v1");
