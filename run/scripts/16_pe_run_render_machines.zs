#loader preinit
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;
import mods.pe_run_tests.NetworkRender;

var names = Bindings.scope("pe_run_tests:bindings");
for pass in ["default", "transparent", "bloom", "bloom_transparent"] {
    MachineType.create("pe_run_tests:render_" + pass, "pe_run_tests.machine.render_" + pass, "pe_run_tests:render_whole")
        .componentData().component(NetworkRender.markerComponent()).component(NetworkRender.typedAnimationComponent()).recipePool("pe_run_tests:client_custom_pool")
        .processing(names, 8, 8).workSlot("main", [], false, "LOCKED_RETRY").register("v1");
}
MachineType.create("pe_run_tests:render_component", "pe_run_tests.machine.render_component", "pe_run_tests:render_whole")
    .componentData().component(NetworkRender.markerComponent()).component(NetworkRender.typedAnimationComponent()).recipePool("pe_run_tests:render_default_pool")
    .recipePool("pe_run_tests:client_external_pool")
    .processing(names, 8, 8).workSlot("main", [], false, "LOCKED_RETRY").register("v1");
