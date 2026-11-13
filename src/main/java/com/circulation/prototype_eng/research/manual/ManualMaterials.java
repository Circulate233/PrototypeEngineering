package com.circulation.prototype_eng.research.manual;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import com.circulation.prototype_eng.util.AEKeyLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;

/**
 * Tier-zero unit advancement retains each static input's identity even when several use one key.
 */
@ApiStatus.Internal
public final class ManualMaterials {
    private final ObjectList<Material> materials;
    private final Object2ObjectOpenHashMap<ManualMaterialKey, Material> byId = new Object2ObjectOpenHashMap<>();

    private ManualMaterials(ObjectList<Material> materials) {
        // from() transfers its completed private list; no caller can mutate it.
        this.materials = materials;
        for (var material : materials) {
            byId.put(material.id(), material);
        }
    }

    /**
     * ResearchLevelStage additionally restricts real tier-zero definitions to AEItemKey inputs.
     */
    public static ManualMaterials from(List<RecipeRequirement<?>> requirements) {
        Objects.requireNonNull(requirements);
        var materials = new ObjectArrayList<Material>(requirements.size());
        var requirementIds = new ObjectOpenHashSet<ResourceLocation>(requirements.size());
        for (var requirement : requirements) {
            Objects.requireNonNull(requirement);
            if (!requirementIds.add(requirement.id())) {
                throw new IllegalArgumentException("Duplicate manual material requirement " + requirement.id());
            }
            if (requirement.type() != RecipeRequirements.RESOURCES
                || !(requirement.payload() instanceof ResourceRequirement(
                var inputs, var inputsPerTick, var outputsPerTick,
                var outputs, var voidExcess, var chance, var randomOutputs, var fuzzyInputs, var dynamicInputs,
                var inputAllocation
            ))
                || !inputsPerTick.isEmpty() || !outputsPerTick.isEmpty() || !outputs.isEmpty()
                || voidExcess || !chance.equals(ResourceChance.ALWAYS) || randomOutputs != null
                || !fuzzyInputs.isEmpty() || !dynamicInputs.isEmpty() || inputAllocation != InputAllocation.SPLITTABLE) {
                throw new IllegalArgumentException("Manual research supports only static START inputs with default source allocation: " + requirement.id());
            }
            for (int inputIndex = 0; inputIndex < inputs.size(); inputIndex++) {
                var input = inputs.get(inputIndex);
                materials.add(new Material(new ManualMaterialKey(requirement.id(), inputIndex), input.what(), input.amount()));
            }
        }
        if (materials.isEmpty()) {
            throw new IllegalArgumentException("Manual research requires at least one material input");
        }
        return new ManualMaterials(materials);
    }

    public Plan plan(Object2LongMap<ManualMaterialKey> currentWork) {
        long sequence = validateWorkAndSequence(currentWork);
        var increments = new Object2LongLinkedOpenHashMap<ManualMaterialKey>();
        var expectedWork = new Object2LongLinkedOpenHashMap<>(currentWork);
        expectedWork.defaultReturnValue(0);
        var quantities = new AEKeyLinkedOpenHashMap(6);
        boolean completesResearch = true;
        for (var material : materials) {
            long completed = expectedWork.getLong(material.id());
            if (completed == material.amount()) {
                continue;
            }
            increments.addTo(material.id(), 1);
            expectedWork.addTo(material.id(), 1);
            // At most one per row of an int-sized list; this sum cannot overflow long.
            quantities.addTo(material.key(), 1);
            if (completed + 1 != material.amount()) {
                completesResearch = false;
            }
        }
        // The resulting progress must still have a representable operation sequence after recovery.
        if (sequence > Long.MAX_VALUE - increments.size()) {
            throw new ArithmeticException("Manual material sequence overflow after advancement");
        }
        return new Plan(increments, expectedWork, quantities, sequence, completesResearch);
    }

    public void validateWork(Object2LongMap<ManualMaterialKey> work) {
        validateWorkAndSequence(work);
    }

    public boolean complete(Object2LongMap<ManualMaterialKey> work) {
        validateWork(work);
        for (var material : materials) {
            if (work.getOrDefault(material.id(), 0L) != material.amount()) {
                return false;
            }
        }
        return true;
    }

    private long validateWorkAndSequence(Object2LongMap<ManualMaterialKey> work) {
        Objects.requireNonNull(work);
        long sequence = 0;
        for (var entry : work.object2LongEntrySet()) {
            var material = byId.get(entry.getKey());
            if (material == null) {
                throw new IllegalArgumentException("Unknown manual material work item " + entry.getKey());
            }
            long completed = entry.getLongValue();
            if (completed < 0 || completed > material.amount()) {
                throw new IllegalArgumentException("Manual material work outside 0.." + material.amount() + ": " + entry.getKey());
            }
            sequence = Math.addExact(sequence, completed);
        }
        return sequence;
    }

    /**
     * Completed zero-default maps are transferred read-only for the plan's lifetime.
     */
    public record Plan(Object2LongMap<ManualMaterialKey> increments, Object2LongMap<ManualMaterialKey> expectedWork,
                       AEKey2LongMap quantities, long sequence, boolean completesResearch) {
    }

    private record Material(ManualMaterialKey id, AEKey key, long amount) {
    }

}
