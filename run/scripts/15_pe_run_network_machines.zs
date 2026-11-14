#loader preinit
import mods.pe_run_tests.Text;
import mods.pe_run_tests.Resources;
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.recipe.Resource;
import mods.prototype_eng.resource.ComputerRecipe;
import mods.prototype_eng.resource.ComputingResource;
import mods.prototype_eng.resource.ComputingResources;
import mods.prototype_eng.resource.ComputingModifier;
import mods.prototype_eng.network.NetworkConfiguration;
import mods.prototype_eng.network.NetworkRequirements;

var names = Bindings.scope("pe_run_tests:bindings");
var compute = Resources.netCompute();
var empty = ComputingResources.create();
MachineType.networkCenter("pe_run_tests:net_center", Text.NetCenter.key, "pe_run_tests:net_center_shape", 1, 4, 4)
    .register("network-center-v1");
var cooling = Resources.cooling();
var coolingRecipe = ComputerRecipe.create("pe_run_tests:cooling_recipe", [Resource.fluid(<liquid:water>, 1)],
    ComputingResources.create().add(cooling, 1));
MachineType.computer("pe_run_tests:net_computer", Text.NetComputer.key, "pe_run_tests:net_computer_shape", 8,
    ComputingResources.create().add(compute, 1000), [coolingRecipe]).register("computer-v1");
MachineType.researchStation("pe_run_tests:net_station", Text.NetStation.key, "pe_run_tests:net_shape", 1,
    ComputingResources.create().add(compute, 10), 8).register("research-station-v1");
MachineType.dataCenter("pe_run_tests:net_database", Text.NetDatabase.key, "pe_run_tests:net_shape", 1, 16)
    .register("data-center-v1");
MachineType.create("pe_run_tests:net_device", Text.NetDevice.key, "pe_run_tests:net_shape")
    .attribute("prototype_eng:process_parallelism", 1).attribute("prototype_eng:max_concurrent_processes", 1)
    .componentData().recipePool("pe_run_tests:net_pool").processing(names, 8, 8).workSlot("main", [], false, "LOCKED_RETRY")
    .network(NetworkConfiguration.create("pe_run_tests:net_device", "DEVICE", 0, empty,
        ComputingModifier.create(), 0, NetworkRequirements.create())).register("v1");
MachineType.create("pe_run_tests:net_slots", Text.NetSlots.key, "pe_run_tests:net_shape")
    .attribute("prototype_eng:process_parallelism", 1).attribute("prototype_eng:max_concurrent_processes", 4)
    .componentData().recipePool("pe_run_tests:net_pool").processing(names, 8, 8)
    .workSlot("a", [], false, "LOCKED_RETRY").workSlot("b", [], false, "LOCKED_RETRY")
    .workSlot("c", [], false, "LOCKED_RETRY").workSlot("d", [], false, "LOCKED_RETRY")
    .network(NetworkConfiguration.create("pe_run_tests:net_slots", "DEVICE", 0, empty,
        ComputingModifier.create(), 0, NetworkRequirements.create())).register("v1");
