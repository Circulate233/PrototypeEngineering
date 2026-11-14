package com.circulation.prototype_eng.api.network.resource;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import net.minecraft.item.ItemStack;

import java.util.function.BooleanSupplier;

/**
 * Read-only owner-thread supply of one installed item, before multiplication by its physical count.
 */
@FunctionalInterface
public interface NetworkComputerComponent {
    /**
     * Fixed supply has no machine, recipe or gameplay defaults.
     */
    static NetworkComputerComponent fixed(Reference2DoubleMap<NetworkResourceType> resources) {
        return fixed(0, resources);
    }

    static NetworkComputerComponent fixed(long concurrency, Reference2DoubleMap<NetworkResourceType> resources) {
        var captured = new Capture(concurrency, resources, Reference2DoubleMaps.emptyMap(), 0, () -> true);
        return (_, _) -> captured;
    }

    /**
     * Initial capture runs on the owner outside business storage locks; the item is a private count-one copy.
     */
    Capture capture(ItemStack item, MachineInstance computer);

    /**
     * Version proof must be read-only and acquire no additional storage/domain locks, including inside a recipe fence.
     * Resource quantities and costs must be finite and nonnegative. Completed zero-default maps are borrowed read-only.
     */
    record Capture(long concurrencyPerItem, Reference2DoubleMap<NetworkResourceType> resourcesPerItem,
                   Reference2DoubleMap<NetworkResourceType> requiredResources, long energyPerTick,
                   BooleanSupplier current) {
        public Capture(Reference2DoubleMap<NetworkResourceType> resourcesPerItem, BooleanSupplier current) {
            this(0, resourcesPerItem, Reference2DoubleMaps.emptyMap(), 0, current);
        }

        public Capture(Reference2DoubleMap<NetworkResourceType> resourcesPerItem, Reference2DoubleMap<NetworkResourceType> requiredResources,
                       long energyPerTick, BooleanSupplier current) {
            this(0, resourcesPerItem, requiredResources, energyPerTick, current);
        }


    }
}
