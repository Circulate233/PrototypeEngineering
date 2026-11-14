#loader preinit
import mods.pe_run_tests.Text;
import mods.pe_run_tests.Resources;
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.resource.ComputerRecipe;
import mods.prototype_eng.resource.ComputerInputRecipe;
import mods.prototype_eng.recipe.Requirement;
import mods.prototype_eng.network.Events;
import mods.prototype_eng.network.DeviceEvent;
import mods.prototype_eng.network.ComputerRecipeEvent;
import mods.prototype_eng.resource.ComputingResources;
import mods.prototype_eng.resource.ComputingModifier;
import mods.prototype_eng.network.NetworkConfiguration;
import mods.prototype_eng.network.NetworkRequirements;

var names = Bindings.scope("pe_run_tests:bindings");
var compute = Resources.netCompute();
var cooling = Resources.cooling();
var waterCooling = ComputerRecipe.create("pe_run_tests:net_demo_water_cooling",
    [Resource.fluid(<liquid:water>, 3)], ComputingResources.create().add(cooling, 3));
var lavaCooling = ComputerRecipe.create("pe_run_tests:net_demo_lava_cooling",
    [Resource.fluid(<liquid:lava>, 1)], ComputingResources.create().add(cooling, 2));
var maintenance = ComputerInputRecipe.create("pe_run_tests:net_demo_maintenance", "v1", 20, [
    Requirement.resourceIO("pe_run_tests:maintenance_input", [Resource.item(<minecraft:redstone>, 1)],
        [Resource.fluid(<liquid:water>, 1)], [], [], false),
    ComputerInputRecipe.endInput(Requirement.resources("pe_run_tests:maintenance_end",
        [Resource.fluid(<liquid:water>, 5)], []))
], function(event as ComputerRecipeEvent) {
    if (event.stage == "TICK") event.values.required.set(Resources.cooling(), event.values.required.get(Resources.cooling()) * 0.9);
});
Events.onDevice(function(event as DeviceEvent) {
    if (event.network.hasDevice("pe_run_tests:net_demo_database"))
        event.values.energyMultiplier = event.values.energyMultiplier * 0.9;
});
var limits = ComputingResources.create().add(compute, 800).add(Resources.qubit(), 64)
    .add(Resources.memory(), 128.5).add(Resources.bandwidth(), 256.25)
    .add(Resources.storage(), 64).add(Resources.signal(), 256);

MachineType.networkCenter("pe_run_tests:net_demo_center", Text.DemoCenter.key,
    "pe_run_tests:net_demo_center_shape", 1, 4, 4).register("network-demo-v1");
MachineType.computer("pe_run_tests:net_demo_computer", Text.DemoComputer.key,
    "pe_run_tests:net_demo_computer_shape", 8, limits, [waterCooling, lavaCooling]).computerInputRecipe(maintenance).register("network-demo-v2");
MachineType.researchStation("pe_run_tests:net_demo_station", Text.DemoStation.key,
    "pe_run_tests:net_demo_work_shape", 1, ComputingResources.create().add(compute, 100).add(Resources.qubit(), 10), 8)
    .register("network-demo-v1");
MachineType.dataCenter("pe_run_tests:net_demo_database", Text.DemoDatabase.key,
    "pe_run_tests:net_demo_database_shape", 1, 16).register("network-demo-v1");

MachineType.create("pe_run_tests:net_demo_load", Text.DemoLoad.key, "pe_run_tests:net_demo_work_shape")
    .requiresNetwork(true).attribute("prototype_eng:process_parallelism", 1)
    .attribute("prototype_eng:max_concurrent_processes", 1)
    .recipePool("pe_run_tests:net_demo_pool").processing(names, 8, 8)
    .workSlot("main", [], false, "LOCKED_RETRY")
    .network(NetworkConfiguration.create("pe_run_tests:net_demo_load", "DEVICE", 0,
        ComputingResources.create(), ComputingModifier.create(), 0, NetworkRequirements.create()))
    .register("network-demo-v1");
