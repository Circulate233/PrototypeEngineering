package com.circulation.prototype_eng.api.network.resource;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import net.minecraft.item.ItemStack;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Read-only owner-thread supply of one installed item, before multiplication by its physical count. */
@FunctionalInterface
public interface NetworkComputerComponent {
    /** Initial capture runs on the owner outside business storage locks; the item is a private count-one copy. */
    Capture capture(ItemStack item, MachineInstance computer);

    /** Fixed supply has no machine, recipe or gameplay defaults. */
    static NetworkComputerComponent fixed(Object2LongMap<NetworkResourceType> resources) {
        var frozen = NetworkResourceModifier.copyResources(resources);
        var captured = new Capture(frozen, () -> true);
        return (_, _) -> captured;
    }

    /** Version proof must be read-only and acquire no additional storage/domain locks, including inside a recipe fence. */
    record Capture(Object2LongMap<NetworkResourceType> resourcesPerItem, BooleanSupplier current) {
        public Capture {
            resourcesPerItem = NetworkResourceModifier.copyResources(resourcesPerItem);
            Objects.requireNonNull(current);
        }
    }
}
