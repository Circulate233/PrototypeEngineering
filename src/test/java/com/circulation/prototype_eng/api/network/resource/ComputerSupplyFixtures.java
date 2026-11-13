package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import com.circulation.prototype_eng.testsupport.TestResources;
import it.unimi.dsi.fastutil.objects.Object2LongMap;

import java.util.function.Function;

/**
 * Detached tests enter the same resolved-key registration boundary used by the public ItemStack adapter.
 */
public final class ComputerSupplyFixtures {
    public static final TestResources.KeyType TYPE = new TestResources.KeyType("computer_component_items");

    private ComputerSupplyFixtures() {
    }

    public static AEKey key(String name) {
        return new TestResources.Key(TYPE, name);
    }

    public static void register(NetworkComputerComponentRegistry registry, AEKey key, Function<MachineInstance, NetworkComputerComponent.Capture> capture) {
        registry.registerKey(key, capture);
    }

    public static void fixed(NetworkComputerComponentRegistry registry, AEKey key, Object2LongMap<NetworkResourceType> resources) {
        var capture = new NetworkComputerComponent.Capture(resources, () -> true);
        register(registry, key, _ -> capture);
    }

    /**
     * Explicit warehouse adapter for recipe-consumer fixtures; default computers use computation hatches.
     */
    public static MachineComputerComponents.Sources warehouses(MachineInstance machine) {
        var hatches = MachineHatches.get(machine);
        var storages = new ObjectArrayList<SlottedResourceStorage>();
        for (var hatch : hatches.resources()) {
            if (hatch.input() instanceof SlottedResourceStorage input) storages.add(input);
            if (hatch.output() instanceof SlottedResourceStorage output) storages.add(output);
        }
        return new MachineComputerComponents.Sources(storages, () -> MachineHatches.get(machine) == hatches);
    }
}
