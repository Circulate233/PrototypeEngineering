#loader preinit
import mods.prototype_eng.MachineType;
import mods.prototype_eng.recipe.Bindings;
import mods.prototype_eng.resource.ComputingResource;
import mods.prototype_eng.resource.ComputingResources;
import mods.prototype_eng.resource.ComputingModifier;
import mods.prototype_eng.network.NetworkConfiguration;
import mods.prototype_eng.network.NetworkRequirements;

var names = Bindings.scope("pe_run_tests:bindings");
var compute = ComputingResource.create("pe_run_tests:net_compute", false, 0);
var empty = ComputingResources.create();
MachineType.create("pe_run_tests:net_center", "pe_run_tests.machine.net_center", "pe_run_tests:net_shape")
    .network(NetworkConfiguration.create("pe_run_tests:net_center", "NETWORK_CENTER", 1, empty,
        ComputingModifier.create(), 0, NetworkRequirements.create())).register("v1");
MachineType.create("pe_run_tests:net_computer", "pe_run_tests.machine.net_computer", "pe_run_tests:net_shape")
    .network(NetworkConfiguration.create("pe_run_tests:net_computer", "COMPUTER", 0, empty,
        ComputingModifier.create().flat(compute, 2).multiplierPpm(compute, 1000000), 0, NetworkRequirements.create())).register("v1");
MachineType.create("pe_run_tests:net_station", "pe_run_tests.machine.net_station", "pe_run_tests:net_shape")
    .network(NetworkConfiguration.create("pe_run_tests:net_station", "RESEARCH_STATION", 1, empty,
        ComputingModifier.create(), 0, NetworkRequirements.create())).register("v1");
MachineType.create("pe_run_tests:net_database", "pe_run_tests.machine.net_database", "pe_run_tests:net_shape")
    .network(NetworkConfiguration.create("pe_run_tests:net_database", "DATA_CENTER", 1, empty,
        ComputingModifier.create(), 16, NetworkRequirements.create())).register("v1");
MachineType.create("pe_run_tests:net_device", "pe_run_tests.machine.net_device", "pe_run_tests:net_shape")
    .attribute("prototype_eng:process_parallelism", 1).attribute("prototype_eng:max_concurrent_processes", 1)
    .componentData().recipePool("pe_run_tests:net_pool").processing(names, 8, 8).workSlot("main", [], false, "LOCKED_RETRY")
    .network(NetworkConfiguration.create("pe_run_tests:net_device", "DEVICE", 0, empty,
        ComputingModifier.create(), 0, NetworkRequirements.create())).register("v1");
MachineType.create("pe_run_tests:net_slots", "pe_run_tests.machine.net_device", "pe_run_tests:net_shape")
    .attribute("prototype_eng:process_parallelism", 1).attribute("prototype_eng:max_concurrent_processes", 4)
    .componentData().recipePool("pe_run_tests:net_pool").processing(names, 8, 8)
    .workSlot("a", [], false, "LOCKED_RETRY").workSlot("b", [], false, "LOCKED_RETRY")
    .workSlot("c", [], false, "LOCKED_RETRY").workSlot("d", [], false, "LOCKED_RETRY")
    .network(NetworkConfiguration.create("pe_run_tests:net_slots", "DEVICE", 0, empty,
        ComputingModifier.create(), 0, NetworkRequirements.create())).register("v1");
