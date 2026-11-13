package com.circulation.prototype_eng.api.network.resource;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import net.minecraft.item.ItemStack;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Read-only owner-thread supply of one installed item, before multiplication by its physical count.
 */
@FunctionalInterface
public interface NetworkComputerComponent {
    /**
     * Fixed supply has no machine, recipe or gameplay defaults.
     */
    static NetworkComputerComponent fixed(Object2LongMap<NetworkResourceType> resources) {
        var frozen = NetworkResourceModifier.copyResources(resources);
        var captured = new Capture(frozen, () -> true);
        return (_, _) -> captured;
    }

    /**
     * Initial capture runs on the owner outside business storage locks; the item is a private count-one copy.
     */
    Capture capture(ItemStack item, MachineInstance computer);

    /**
     * Version proof must be read-only and acquire no additional storage/domain locks, including inside a recipe fence.
     * The completed zero-default resource map is borrowed unchanged while this capture is retained.
     */
    record Capture(Object2LongMap<NetworkResourceType> resourcesPerItem,
                   Object2LongMap<NetworkResourceType> requiredResources, long energyPerTick, BooleanSupplier current) {
        public Capture(Object2LongMap<NetworkResourceType> resourcesPerItem, BooleanSupplier current) {
            this(resourcesPerItem, Object2LongMaps.emptyMap(), 0, current);
        }

        public Capture {
            NetworkResourceModifier.validateResources(resourcesPerItem);
            NetworkResourceModifier.validateResources(requiredResources);
            if (energyPerTick < 0) throw new IllegalArgumentException("Negative computing component FE cost");
            Objects.requireNonNull(current);
        }
    }
}
