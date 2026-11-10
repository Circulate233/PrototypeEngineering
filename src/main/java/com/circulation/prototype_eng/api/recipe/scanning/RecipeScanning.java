package com.circulation.prototype_eng.api.recipe.scanning;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.machine.RecipeScanningSystem;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Explicit automatic admission. Declare {@link MachineWorkSlots#TYPE} before the scanner so its
 * factory sees the installed host. Bind work slots with their existing persistence codec and this
 * scanner as recreated: its search cursor is transient. Component dependencies are execution-order
 * constraints, not factory dependencies; scanning runs before work-slot processing instead.
 */
public final class RecipeScanning {
    private RecipeScanning() {
    }

    /**
     * Resolves the shared catalog at machine assembly, after recipe registration has frozen.
     */
    public static ComponentType<MachineInstance, RecipeScanner> component(ResourceLocation id,
                                                                          Supplier<RecipeCatalog> catalog, ScanLimits limits) {
        Objects.requireNonNull(catalog, "catalog");
        return component(id, host -> host.owner().createRecipeScanner(host, Objects.requireNonNull(catalog.get(), "catalog result"),
            () -> ThreadLocalRandom.current().nextLong()), limits, _ -> true);
    }

    /** Persistent following keeps the controller's scanner factory and the source's stable resolver. */
    public static ComponentType<MachineInstance, RecipeScanner> component(ResourceLocation id,
                                                                          RecipeRegistry registry, ScanLimits limits) {
        Objects.requireNonNull(registry, "registry");
        return component(id, host -> host.owner().createRecipeScanner(host, registry.snapshot().catalog(),
            () -> ThreadLocalRandom.current().nextLong()).follow(registry,
            Objects.requireNonNull(registry.processCodecs(), "Persistent recipe source requires process codecs")), limits, _ -> true);
    }

    /**
     * The factory must return a fresh scanner for the supplied host; readiness only gates admission.
     */
    public static ComponentType<MachineInstance, RecipeScanner> component(ResourceLocation id,
                                                                          Function<MachineWorkSlots, RecipeScanner> factory, ScanLimits limits, Predicate<MachineInstance> ready) {
        Objects.requireNonNull(factory, "factory");
        var system = new RecipeScanningSystem(limits, ready);
        return new ComponentType<>(id, owner -> {
            var host = owner.components().get(MachineWorkSlots.TYPE);
            if (host == null || host.owner() != owner) {
                throw new IllegalArgumentException("Automatic recipe scanning requires this machine's declared work slots");
            }
            var scanner = Objects.requireNonNull(factory.apply(host), "scanner factory result");
            if (scanner.host() != host) {
                throw new IllegalArgumentException("Scanner factory changed its work-slot host");
            }
            return scanner;
        }, List.of(system));
    }
}
