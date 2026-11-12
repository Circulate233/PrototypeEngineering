#loader preinit
import mods.pe_run_tests.Text;
import mods.prototype_eng.MachineType;
import mods.prototype_eng.MachineFactory;
import mods.prototype_eng.ParallelController;
import mods.prototype_eng.recipe.Bindings;

// All six machines use the same real declaration/codec scope as the other run scripts.
var names = Bindings.scope("pe_run_tests:bindings");
MachineType.create("pe_run_tests:me_assemblies", Text.MeAssemblies.key, "pe_run_tests:me_assemblies_shape")
    .recipePool("pe_run_tests:me_resources_pool").processing(names, 32, 32).register("me-run-v1");
MachineType.create("pe_run_tests:me_interface", Text.MeInterface.key, "pe_run_tests:me_interface_shape")
    .recipePool("pe_run_tests:me_resources_pool").processing(names, 32, 32).register("me-run-v1");
MachineType.create("pe_run_tests:me_pattern", Text.MePattern.key, "pe_run_tests:me_pattern_shape")
    .controllerModel("prototype_eng:block_controller_frame")
    .recipePool("pe_run_tests:me_pattern_pool").processing(names, 32, 32).register("me-run-v1");
MachineType.create("pe_run_tests:me_colors", Text.MeColors.key, "pe_run_tests:me_colors_shape")
    .recipePool("pe_run_tests:me_color_pool").processing(names, 32, 32).register("me-run-v1");
MachineType.create("pe_run_tests:me_parallel", Text.MeParallel.key, "pe_run_tests:me_parallel_shape")
    .factory(MachineFactory.crossRecipe()).recipePool("pe_run_tests:me_parallel_pool")
    .attribute("prototype_eng:process_parallelism", 1)
    .attribute("prototype_eng:max_process_parallelism", 1024)
    .processing(names, 32, 32).register("me-run-v1");
MachineType.create("pe_run_tests:me_wide", Text.MeWide.key, "pe_run_tests:me_assemblies_shape")
    .recipePool("pe_run_tests:me_wide_pool").processing(names, 32, 32).register("me-run-v1");

// The unchanged original PM sprite is the default model for external registrations.
ParallelController.create("pe_run_tests:parallel_zs", Text.MeParallelZs.key, 32).register();
