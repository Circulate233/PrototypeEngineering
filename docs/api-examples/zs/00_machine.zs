#loader preinit
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;

MachineType.create("example:smelter", "example.machine.smelter", "example:smelter_shape")
    .recipePool("example:smelting")
    .attribute("prototype_eng:process_parallelism", 4)
    .processing(Bindings.scope("example:recipes"), 32, 64)
    .executionPolicy("FULL_ASYNC")
    .register("machine-v1");
