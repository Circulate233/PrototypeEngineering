package com.circulation.prototype_eng.research.manual;

import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.requirement.InputAllocation;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import it.unimi.dsi.fastutil.objects.Object2LongLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Objects;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.api.research.manual.ManualMaterialKey;
import it.unimi.dsi.fastutil.objects.Object2LongMap;


/**
 * Player receipts retain each static input identity; payments for shared resources are distributed across those inputs.
 */
@ApiStatus.Internal
public final class ManualMaterials {
    private final ObjectList<Material> materials;
    private final Object2ObjectOpenHashMap<ManualMaterialKey, Material> byId = new Object2ObjectOpenHashMap<>();

    private ManualMaterials(ObjectList<Material> materials) {

        this.materials = materials;
        for (int index2 = 0; index2 < materials.size(); index2++) {
            var material = materials.get(index2);
            byId.put(material.id(), material);
        }
    }

    /**
     * ResearchLevelStage restricts manual research to fixed item inputs.
     */
    public static ManualMaterials from(List<RecipeRequirement<?>> requirements) {
        Objects.requireNonNull(requirements);
        var materials = new ObjectArrayList<Material>(requirements.size());
        var requirementIds = new ObjectOpenHashSet<ResourceLocation>(requirements.size());
        for (int index7 = 0; index7 < requirements.size(); index7++) {
            var requirement = requirements.get(index7);
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
        return new ManualMaterials(materials);
    }

    public Plan plan(Object2LongMap<ManualMaterialKey> currentWork, AEKey2LongMap payments, boolean complete, long sequence) {
        var increments = new Object2LongLinkedOpenHashMap<ManualMaterialKey>();
        var expected = new Object2LongLinkedOpenHashMap<>(currentWork);
        var remaining = new AEKey2LongMap.OpenHashMap();
        remaining.putAll(payments);
        for (int index6 = 0; index6 < materials.size(); index6++) {
            var material = materials.get(index6);
            long amount = Math.min(material.amount() - currentWork.getLong(material.id()), remaining.getLong(material.key()));
            if (amount == 0) continue;
            increments.addTo(material.id(), amount);
            expected.addTo(material.id(), amount);
            remaining.addTo(material.key(), -amount);
        }
        return new Plan(increments, expected, payments, sequence, complete);
    }

    public AEKey2LongMap quantities(Object2LongMap<ManualMaterialKey> delta) {
        var quantities = new AEKey2LongMap.OpenHashMap();
        for (var entry : delta.object2LongEntrySet())
            quantities.addTo(byId.get(entry.getKey()).key(), entry.getLongValue());
        return quantities;
    }

    public AEKey2LongMap remaining(Object2LongMap<ManualMaterialKey> work) {
        var result = new AEKey2LongMap.OpenHashMap();
        for (int index3 = 0; index3 < materials.size(); index3++) {
            var material = materials.get(index3);
            result.addTo(material.key(), material.amount() - work.getLong(material.id()));
        }
        return result;
    }

    public Object2LongMap<ManualMaterialKey> workForRemaining(AEKey2LongMap remaining) {
        var paid = new AEKey2LongMap.OpenHashMap();
        for (int index = 0; index < materials.size(); index++) {
            var material = materials.get(index);
            paid.addTo(material.key(), material.amount());
        }
        for (var entry : remaining.object2LongEntrySet()) paid.addTo(entry.getKey(), -entry.getLongValue());
        var work = new Object2LongLinkedOpenHashMap<ManualMaterialKey>();
        for (int index5 = 0; index5 < materials.size(); index5++) {
            var material = materials.get(index5);
            long amount = Math.min(material.amount(), paid.getLong(material.key()));
            if (amount > 0) work.addTo(material.id(), amount);
            paid.addTo(material.key(), -amount);
        }
        return work;
    }

    public void validateWork(Object2LongMap<ManualMaterialKey> work) {
        validateWorkValues(work);
    }

    public boolean complete(Object2LongMap<ManualMaterialKey> work) {
        validateWork(work);
        for (int index4 = 0; index4 < materials.size(); index4++) {
            var material = materials.get(index4);
            if (work.getOrDefault(material.id(), 0L) != material.amount()) {
                return false;
            }
        }
        return true;
    }

    private void validateWorkValues(Object2LongMap<ManualMaterialKey> work) {
        Objects.requireNonNull(work);
        for (var entry : work.object2LongEntrySet()) {
            var material = byId.get(entry.getKey());
            if (material == null) {
                throw new IllegalArgumentException("Unknown manual material work item " + entry.getKey());
            }
            long completed = entry.getLongValue();
            if (completed < 0 || completed > material.amount()) {
                throw new IllegalArgumentException("Manual material work outside 0.." + material.amount() + ": " + entry.getKey());
            }
        }
    }

    /**
     * Plan maps use zero defaults and are transferred read-only to material execution.
     */
    public record Plan(Object2LongMap<ManualMaterialKey> increments, Object2LongMap<ManualMaterialKey> expectedWork,
                       AEKey2LongMap quantities, long sequence, boolean completesResearch) {
    }

    private record Material(ManualMaterialKey id, AEKey key, long amount) {
    }

}
