package com.circulation.prototype_eng.api.network.resource;

import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/**
 * Material conversion unit. Complete input lists and resource maps before construction and retain them unchanged.
 */
public final class ComputerRecipe {
    private final MachineRecipe materialRecipe;
    private final List<GenericStack> unitInputs;
    private final AEKey2LongMap amounts;
    private final Reference2DoubleMap<NetworkResourceType> unitResources;
    private final Predicate<MachineInstance> condition;

    public ComputerRecipe(ResourceLocation id, List<GenericStack> unitInputs, Reference2DoubleMap<NetworkResourceType> unitResources) {
        this(id, unitInputs, unitResources, _ -> true);
    }

    public ComputerRecipe(ResourceLocation id, List<GenericStack> unitInputs,
                          Reference2DoubleMap<NetworkResourceType> unitResources, Predicate<MachineInstance> condition) {
        this.unitInputs = unitInputs;
        this.unitResources = unitResources;
        this.condition = condition;
        NetworkResourceModifier.validateResources(unitResources);
        var inputs = new ResourceRequirement(unitInputs, List.of(), List.of(), List.of(), false);
        materialRecipe = new MachineRecipe(id, 1, List.of(RecipeRequirements.RESOURCES.requirement(id, inputs)));
        amounts = new AEKeyLinkedOpenHashMap(unitInputs.size());
        for (int index4 = 0; index4 < unitInputs.size(); index4++) {
            var stack = unitInputs.get(index4);
            if (amounts.addTo(stack.what(), stack.amount()) > Long.MAX_VALUE - stack.amount())
                throw new ArithmeticException("Computer conversion input sum overflow");
        }
    }

    public ResourceLocation id() {
        return materialRecipe.id();
    }

    public MachineRecipe materialRecipe() {
        return materialRecipe;
    }

    public List<GenericStack> unitInputs() {
        return unitInputs;
    }

    public Reference2DoubleMap<NetworkResourceType> unitResources() {
        return unitResources;
    }

    /**
     * For multiple outputs, use the largest unit count required by unmet demand. Zero demand requires no conversion.
     */
    public long unitsFor(Reference2DoubleMap<NetworkResourceType> required, Reference2DoubleMap<NetworkResourceType> available) {
        long units = 0;
        for (var entry : unitResources.reference2DoubleEntrySet()) {
            double each = entry.getDoubleValue();
            double missing = required.getDouble(entry.getKey()) - available.getDouble(entry.getKey());
            if (each == 0 || missing <= 0) continue;
            double rounded = Math.ceil(missing / each);
            if (rounded >= 0x1p63) throw new ArithmeticException("Computer conversion unit count overflow");
            long count = (long) rounded;
            if (count > 1 && (count - 1) * each >= missing) count--;
            if (count * each < missing) count = Math.incrementExact(count);
            units = Math.max(units, count);
        }
        return units;
    }

    /**
     * Reserves as many complete units as available. The caller must settle every reservation. Null means eligibility was denied.
     */
    public @Nullable Conversion prepare(MachineInstance computer, long requestedUnits) {
        if (!condition.test(computer)) return null;
        var storages = new ObjectArrayList<ResourceStorage>();
        var seen = new ReferenceOpenHashSet<StorageContext>();
        var elements = MachineHatches.get(computer).resources();
        for (int index5 = 0; index5 < elements.size(); index5++) {
            var hatch = elements.get(index5);
            var storage = hatch.input();
            if (storage != null && hatch.isValid() && storage.isValid() && seen.add(storage.context()))
                storages.add(storage);
        }
        storages.sort(Comparator.comparingLong(value -> value.context().order()));
        var contexts = new ObjectArrayList<StorageContext>(storages.size());
        for (int index1 = 0; index1 < storages.size(); index1++) {
            var storage = storages.get(index1);
            contexts.add(storage.context());
        }
        var tokens = new ObjectArrayList<ResourceReservation>();
        try {
            long units = requestedUnits;
            boolean accepted = true;
            StorageContext.lockAll(contexts);
            try {
                for (var input : amounts.object2LongEntrySet()) {
                    long maximum = Math.multiplyExact(units, input.getLongValue()), found = 0;
                    for (int index2 = 0; index2 < storages.size(); index2++) {
                        var storage = storages.get(index2);
                        found += Math.min(maximum - found, storage.snapshotFor(input.getKey()).available().getLong(input.getKey()));
                        if (found == maximum) break;
                    }
                    units = Math.min(units, found / input.getLongValue());
                }
                inputs:
                for (var input : amounts.object2LongEntrySet()) {
                    long remaining = Math.multiplyExact(units, input.getLongValue());
                    for (int index3 = 0; index3 < storages.size(); index3++) {
                        var storage = storages.get(index3);
                        if (remaining == 0) break;
                        var snapshot = storage.snapshotFor(input.getKey());
                        long portion = Math.min(remaining, snapshot.available().getLong(input.getKey()));
                        if (portion == 0) continue;
                        var reserved = storage.reserveExtract(snapshot, input.getKey(), portion);
                        if (reserved.status() != ReservationResult.Status.ACCEPTED) {
                            accepted = false;
                            break inputs;
                        }
                        tokens.add(reserved.reservation());
                        remaining -= portion;
                    }
                    if (remaining != 0) {
                        accepted = false;
                        break;
                    }
                }
            } finally {
                for (int index = contexts.size() - 1; index >= 0; index--) contexts.get(index).unlockDeferred();
            }
            if (!accepted) {
                ResourceTransactions.rollback(tokens);
                return new Conversion(0, List.of());
            }
            return new Conversion(units, tokens);
        } catch (RuntimeException | Error failure) {
            try {
                ResourceTransactions.rollback(tokens);
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public record Conversion(long units, List<ResourceReservation> reservations) {
    }
}
