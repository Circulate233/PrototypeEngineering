#loader preinit
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;

MachineType.create("pe_run_tests:core_world", "PE Run: World light gate", "pe_run_tests:core_world_shape")
    .componentData().recipePool("pe_run_tests:core_world_pool")
    .processing(Bindings.scope("pe_run_tests:bindings"), 8, 8).register("run-v1");
