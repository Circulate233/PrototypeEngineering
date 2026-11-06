package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import org.junit.jupiter.api.Test;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ExternalOverlayPersistenceTest {
    private static final KeyType TYPE = new KeyType("external_overlay_persistence");
    private static final Key ORIGINAL = new Key(TYPE, "original");
    private static final Key REPLACEMENT = new Key(TYPE, "replacement");

    @Test
    void customGroupHistorySurvivesLayerRemovalAndHostValidationRejectsCorruptChoices() {
        var requirement = RecipeRequirements.RESOURCES.requirement(id("input"), group(ORIGINAL));
        var recipe = new MachineRecipe(id("recipe"), 4, List.of(requirement));
        var capacity = new Reference2LongArrayMap<AEKeyType>();
        capacity.put(TYPE, 20);
        var hatch = new BufferedResourceHatch(HatchMode.INPUT, 1, capacity, () -> { });
        var input = Objects.requireNonNull(hatch.input());
        input.insert(REPLACEMENT, 6, Actionable.MODULATE);
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        var process = new RecipeProcess(machine, recipe, 12);
        var patch = new RecipeOverlayType<ResourceRequirement, AEKey>(id("replace_group"), RecipeRequirements.RESOURCES,
            (_, key) -> group(key));
        var resources = new ResourceKeyNbt(keyType -> keyType.equals(TYPE.getId()) ? TYPE : null);
        var builder = RecipeProcessNbt.builder(recipe, "external-groups-1").resources(resources).overlay(patch, resources);
        assertThrows(IllegalArgumentException.class, builder::build);
        var lock = new ResourceInputLocks.Key(requirement.path(null), RecipeStage.START, ResourceInputLocks.Kind.FUZZY, 0);
        var codec = builder.validator(candidate -> {
            if (candidate.state().started()) {
                var locks = candidate.components().get(ResourceInputLocks.TYPE);
                if (locks == null || locks.snapshot().size() != 1 || !REPLACEMENT.equals(locks.get(lock))) {
                    throw new IllegalArgumentException("Historical external input choice is missing or changed");
                }
            }
        }).build();
        RecipeOverlays.getOrCreate(process).put(requirement, "external", patch, REPLACEMENT).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(5, input.availableAmount(REPLACEMENT));
        var loaded = codec.decode(machine, codec.encode(process));
        assertEquals(REPLACEMENT, RecipeOverlays.getOrCreate(loaded).resolve(requirement).payload().fuzzyInputs().getFirst().candidates().getFirst());
        RecipeOverlays.getOrCreate(loaded).remove(requirement, "external").complete();
        var saved = codec.encode(loaded);
        var withoutLayer = codec.decode(machine, saved);
        assertSame(ProcessResult.SUCCESS, withoutLayer.tick());
        assertEquals(5, input.availableAmount(REPLACEMENT));
        assertEquals(REPLACEMENT, ResourceInputLocks.getOrCreate(withoutLayer).get(lock));

        var entries = saved.getTagList("Components", 10);
        for (int i = 0; i < entries.tagCount(); i++) {
            var entry = entries.getCompoundTagAt(i);
            if (entry.getString("Type").equals(ResourceInputLocks.TYPE.id().toString())) {
                entry.getCompoundTag("Data").getTagList("Choices", 10).getCompoundTagAt(0).setTag("Key", resources.encode(ORIGINAL));
            }
        }
        assertThrows(IllegalArgumentException.class, () -> codec.decode(machine, saved));
        assertEquals(REPLACEMENT, ResourceInputLocks.getOrCreate(withoutLayer).get(lock));
        assertEquals(5, input.availableAmount(REPLACEMENT));
    }

    private static ResourceRequirement group(AEKey key) {
        return new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false, ResourceChance.ALWAYS,
            null, List.of(new FuzzyInputGroup(List.of(key), 1)), List.of());
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("external_overlay_test", path); }
}
