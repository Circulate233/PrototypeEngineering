package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import net.minecraft.util.ResourceLocation;

import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Admission readiness only. The runtime must independently authorize scheduling existing work. */
public final class RecipeScanningSystem implements MachineSystem<RecipeScanner> {
    private static final ResourceLocation ID = new ResourceLocation(Tags.MOD_ID, "recipe_scanning");
    private final ScanLimits limits;
    private final Predicate<MachineInstance> ready;

    public RecipeScanningSystem(ScanLimits limits, Predicate<MachineInstance> ready) {
        this.limits = Objects.requireNonNull(limits);
        this.ready = Objects.requireNonNull(ready);
    }

    @Override public ResourceLocation id() { return ID; }
    @Override public Set<ResourceLocation> runBefore() { return Set.of(WorkSlotProcessingSystem.ID); }

    @Override public void onTick(MachineInstance machine, RecipeScanner scanner) {
        if (scanner.host().owner() != machine) { throw new IllegalArgumentException("Scanner belongs to a different machine"); }
        if (ready.test(machine)) { scanner.scan(limits); }
    }
}
