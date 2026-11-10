package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.SelectiveRequirement;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveModifierDescriptor;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchMaterialPersistenceTest {
    private static final KeyType TYPE = new KeyType("research_material_persistence");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key ALTERNATE = new Key(TYPE, "alternate");
    private static final Key INTERMEDIATE = new Key(TYPE, "intermediate");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ResourceLocation SCORE = id("score");
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(location -> location.equals(TYPE.getId()) ? TYPE : null);

    private static RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> observer(
        BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> action) {
        var type = new RecipeRequirementType<>(new ResourceLocation("aaa_material_test", "observer_type"),
            new RecipeRequirementSystem<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>>() {
                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> requirement) {
                    return requirement.payload().apply(process, RecipeStage.START);
                }

                @Override
                public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> requirement) {
                    return requirement.payload().apply(process, RecipeStage.TICK);
                }

                @Override
                public RequirementTransaction end(RecipeProcess process, RecipeRequirement<BiFunction<RecipeProcess, RecipeStage, RequirementTransaction>> requirement) {
                    return requirement.payload().apply(process, RecipeStage.END);
                }
            });
        return type.requirement(id("observer"), action);
    }

    private static NumericAttributeModifier add(String name, double value) {
        return new NumericAttributeModifier(name, value, AttributeModifier.Operation.ADDITION);
    }

    private static List<GenericStack> stacks(Key key, long amount) {
        return List.of(new GenericStack(key, amount));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("research_material_persistence_test", path);
    }

    private static SlottedResourceBuffer buffer() {
        return new SlottedResourceBuffer(4, Map.of(TYPE, 100L), () -> {
        });
    }

    @Test
    void blockedEndRestoresSelectorOverlayAttributeAndInputLockAgainstNewPhysicalSourcesWithoutRepeatingPaidStages() {
        for (double speed : new double[]{0, 0.5, 2}) {
            var source = new Sources();
            source.input.insert(MATERIAL, 100, Actionable.MODULATE);
            source.energy.receiveEnergy(20, Actionable.MODULATE);
            source.generated.receiveEnergy(100, Actionable.MODULATE);
            source.owner.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, speed);
            var phases = new ObjectArrayList<RecipeStage>();
            var clocks = new ObjectArrayList<RecipeProcess.Tick>();
            var io = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(stacks(MATERIAL, 2),
                stacks(MATERIAL, 1), stacks(INTERMEDIATE, 2), stacks(PRODUCT, 3), false));
            var candidate = RecipeRequirements.RESOURCES.requirement(id("candidate_io"), new ResourceRequirement(List.of(),
                stacks(MATERIAL, 1), List.of(), List.of(), false, ResourceChance.ALWAYS, null,
                List.of(new FuzzyInputGroup(List.of(MATERIAL, ALTERNATE), 3)), List.of()));
            int[] modifierApplications = {0};
            var selection = RecipeRequirements.SELECTIVE.requirement(id("choice"), new SelectiveRequirement(List.of(candidate),
                List.of(new SelectiveModifierDescriptor(id("effect"), context -> {
                    modifierApplications[0]++;
                    context.putProcessAttributeModifier(SCORE, add("selector", 3));
                    context.putRequirementOverlay(io, "selector_output", RecipeOverlayTypes.RESOURCE_IO,
                        new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 4), null));
                }))));
            var attribute = RecipeRequirements.ATTRIBUTES.requirement(id("attribute"), new AttributeRequirement(SCORE, add("requirement", 2)));
            var observer = observer((process, phase) -> {
                phases.add(phase);
                if (phase == RecipeStage.TICK) {
                    clocks.add(process.tickContext());
                }
                if (phase != RecipeStage.START) {
                    assertEquals(20, process.attributes().value(SCORE));
                }
                return RequirementTransaction.SUCCESS;
            });
            var cycle = RecipeProcess.materialCycle(source.owner, id("cycle"), List.of(observer, attribute, io, selection,
                RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 7, false))), source.hatches(), 31);
            cycle.attributes().setResolvedBase(SCORE, 10);
            cycle.attributes().addModifier(SCORE, add("foreign", 5));
            RecipeOverlays.getOrCreate(cycle).put(io, "foreign_output", RecipeOverlayTypes.RESOURCE_IO,
                new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 6), null)).complete();
            assertInstanceOf(ProcessResult.Blocked.class, cycle.tick());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END), phases);
            assertEquals(List.of(new RecipeProcess.Tick(0, speed, 0, 1)), clocks);
            assertEquals(RecipeProcess.Lifecycle.RUNNING, cycle.state().lifecycle());
            assertEquals(1, cycle.state().successfulTicks());
            assertEquals(speed, cycle.state().progress());
            assertEquals(93, source.input.amount(MATERIAL));
            assertEquals(2, source.output.amount(INTERMEDIATE));
            assertEquals(15, source.energy.storedEnergy());
            assertEquals(100, source.generated.storedEnergy());
            assertEquals(20, cycle.attributes().value(SCORE));
            var locks = Objects.requireNonNull(cycle.components().get(ResourceInputLocks.TYPE)).snapshot();
            assertEquals(1, locks.size());
            assertEquals(MATERIAL, locks.values().iterator().next());
            var codec = RecipeProcessNbt.builder(cycle.recipe(), "material-revision-1").resources(KEYS).build();
            var saved = codec.encode(cycle);
            assertThrows(IllegalArgumentException.class, () -> codec.decode(new MachineInstance(), saved));

            var replacement = new Sources();
            replacement.input.insert(MATERIAL, 93, Actionable.MODULATE);
            replacement.input.insert(ALTERNATE, 17, Actionable.MODULATE);
            replacement.output.insert(INTERMEDIATE, 2, Actionable.MODULATE);
            replacement.energy.receiveEnergy(15, Actionable.MODULATE);
            replacement.owner.attributes().setBase(SCORE, 500);
            replacement.owner.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 9);
            replacement.owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 8);
            replacement.owner.attributes().addModifier(StandardMachineAttributes.PROCESS_PARALLELISM,
                new NumericAttributeModifier("new_machine_parallel", 1, AttributeModifier.Operation.MULTIPLY_TOTAL));
            replacement.owner.components().replaceValue(MachineHatches.TYPE, new Sources().hatches());
            var restored = codec.decodeMaterialCycle(replacement.owner, replacement.hatches(), saved);
            assertNotSame(cycle, restored);
            assertSame(replacement.owner, restored.owner());
            assertEquals(cycle.state(), restored.state());
            assertEquals(locks, Objects.requireNonNull(restored.components().get(ResourceInputLocks.TYPE)).snapshot());
            assertEquals(20, restored.attributes().value(SCORE));
            assertEquals(1, modifierApplications[0]);
            assertFalse(Objects.requireNonNull(restored.components().get(SelectiveState.TYPE)).snapshot().get(selection.path(null)).completed());
            assertSame(ProcessResult.SUCCESS, restored.tick());
            assertEquals(List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END, RecipeStage.END), phases);
            assertEquals(1, clocks.size());
            assertEquals(1, restored.state().successfulTicks());
            assertEquals(speed, restored.state().progress());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, restored.state().lifecycle());
            assertEquals(93, replacement.input.amount(MATERIAL));
            assertEquals(17, replacement.input.amount(ALTERNATE));
            assertEquals(2, replacement.output.amount(INTERMEDIATE));
            assertEquals(4, replacement.output.amount(PRODUCT));
            assertEquals(15, replacement.energy.storedEnergy());
            assertEquals(7, replacement.generated.storedEnergy());
            assertEquals(15, restored.attributes().value(SCORE));
            assertTrue(Objects.requireNonNull(restored.components().get(RequirementAttributeState.TYPE)).snapshot().owned().isEmpty());
            assertTrue(Objects.requireNonNull(restored.components().get(SelectiveState.TYPE)).snapshot().get(selection.path(null)).completed());
            assertEquals(1, RecipeOverlays.getOrCreate(restored).snapshot().size());
            assertEquals(6, RecipeOverlays.getOrCreate(restored).resolve(io).payload().outputs().getFirst().amount());
            assertEquals(1, modifierApplications[0]);
            assertEquals(20, cycle.attributes().value(SCORE));
            assertEquals(93, source.input.amount(MATERIAL));
            assertEquals(0, source.output.amount(PRODUCT));
            assertEquals(100, source.generated.storedEnergy());
            // A completed zero-progress material cycle must also retain its distinct restore clock.
            var completed = codec.decodeMaterialCycle(replacement.owner, replacement.hatches(), codec.encode(restored));
            assertSame(ProcessResult.SUCCESS, completed.tick());
            assertEquals(4, replacement.output.amount(PRODUCT));
            assertEquals(4, phases.size());
        }
    }

    @Test
    void aPaidOrdinaryProcessSaveCannotEnterTheMaterialCycleRestorePath() {
        var sources = new Sources();
        sources.input.insert(MATERIAL, 10, Actionable.MODULATE);
        sources.energy.receiveEnergy(20, Actionable.MODULATE);
        sources.generated.receiveEnergy(100, Actionable.MODULATE);
        var recipe = new MachineRecipe(id("ordinary"), 1, List.of(
            RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(stacks(MATERIAL, 2), stacks(MATERIAL, 3),
                List.of(), stacks(PRODUCT, 4), false)),
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 7, false))));
        var process = new RecipeProcess(sources.owner, recipe, 5);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(5, sources.input.amount(MATERIAL));
        assertEquals(15, sources.energy.storedEnergy());
        var codec = RecipeProcessNbt.builder(recipe, "ordinary-revision-1").resources(KEYS).build();
        var saved = codec.encode(process);
        assertThrows(IllegalArgumentException.class, () -> codec.decodeMaterialCycle(new MachineInstance(), sources.hatches(), saved));
        var ordinary = codec.decode(sources.owner, saved);
        sources.generated.extractEnergy(100, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, ordinary.tick());
        assertEquals(5, sources.input.amount(MATERIAL));
        assertEquals(15, sources.energy.storedEnergy());
        assertEquals(4, sources.output.amount(PRODUCT));
        assertEquals(7, sources.generated.storedEnergy());
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }

    private static final class Sources {
        private final MachineInstance owner = new MachineInstance();
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final EnergyBuffer energy = new EnergyBuffer(100, () -> {
        });
        private final EnergyBuffer generated = new EnergyBuffer(100, () -> {
        });

        private Sources() {
            owner.components().put(MachineHatches.TYPE, hatches());
        }

        private MachineHatches hatches() {
            return new MachineHatches(List.of(new Ports(input, output)), List.of(energy), List.of(generated));
        }
    }
}
