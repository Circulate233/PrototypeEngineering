package com.circulation.prototype_eng.research;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.persistence.ProcessComponentCodec;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResearchMaterialBindingTest {
    private static ResearchRegistry registry(ResearchDefinition... definitions) {
        var registry = new ResearchRegistry();
        for (var definition : definitions) {
            registry.register(definition);
        }
        registry.freeze(resources());
        return registry;
    }

    private static ResearchDefinition definition(String name, int maxLevel, ResearchLevelStage... stages) {
        var builder = ResearchDefinition.builder(id(name)).maxLevel(maxLevel).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM);
        for (var stage : stages) {
            builder.stage(stage);
        }
        return builder.build();
    }

    private static ResearchLevelStage.Builder stage(int first, int last) {
        return ResearchLevelStage.builder(first, last).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build());
    }

    private static RecipeRequirement<EnergyRequirement> energy() {
        return RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 7, false));
    }

    private static NetworkResourceRegistry resources() {
        var resources = new NetworkResourceRegistry();
        resources.freeze();
        return resources;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("research_material_binding_test", name);
    }

    @Test
    void frozenStageCodecRestoresCustomEffectsForEveryTargetButRejectsAnotherDefinitionSharingThatStage() {
        var extension = new Extension(4);
        var shared = extension.stage(1, 3, "1").build();
        var registry = registry(definition("first", 3, shared), definition("second", 3, shared));
        assertEquals(2, extension.configurations);
        var first = registry.materials(id("first"), 1);
        var later = registry.materials(id("first"), 3);
        var second = registry.materials(id("second"), 2);
        assertSame(first, later);
        assertNotSame(first.recipe(), second.recipe());
        var sources = new Sources(20, 100);
        var process = extension.blocked(first, sources);
        var saved = first.encode(process);
        var replacement = new Sources(15, 0);
        var corrupt = saved.copy();
        var components = corrupt.getTagList("Components", Constants.NBT.TAG_COMPOUND);
        for (int i = 0; i < components.tagCount(); i++) {
            var entry = components.getCompoundTagAt(i);
            if (entry.getString("Type").equals(extension.component.id().toString())) {
                entry.getCompoundTag("Data").setInteger("Ticks", 0);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> later.decodeMaterialCycle(replacement.owner, replacement.hatches, corrupt));
        assertThrows(IllegalArgumentException.class, () -> second.decodeMaterialCycle(replacement.owner, replacement.hatches, saved));
        var restored = later.decodeMaterialCycle(replacement.owner, replacement.hatches, saved);
        extension.complete(restored, replacement, 7);
        assertEquals(2, extension.configurations);
        assertEquals(new State(1, 1, 0), extension.state(process).snapshot());
        assertEquals(100, sources.generated.storedEnergy());
        assertEquals(15, sources.energy.storedEnergy());
        var otherSources = new Sources(20, 100);
        var otherProcess = extension.blocked(second, otherSources);
        assertThrows(IllegalArgumentException.class, () -> first.encode(otherProcess));
        var otherReplacement = new Sources(15, 0);
        extension.complete(second.decodeMaterialCycle(otherReplacement.owner, otherReplacement.hatches, second.encode(otherProcess)),
            otherReplacement, 7);
    }

    @Test
    void equalUserRevisionsAndRequirementsCannotRestoreAcrossStageIntervals() {
        var extension = new Extension(4);
        var registry = registry(definition("stages", 4, extension.stage(1, 2, "shared").build(), extension.stage(3, 4, "shared").build()));
        var first = registry.materials(id("stages"), 2);
        var second = registry.materials(id("stages"), 3);
        var sources = new Sources(20, 100);
        var paid = extension.blocked(first, sources);
        var replacement = new Sources(15, 0);
        assertThrows(IllegalArgumentException.class, () -> second.decodeMaterialCycle(replacement.owner, replacement.hatches, first.encode(paid)));
        extension.complete(first.decodeMaterialCycle(replacement.owner, replacement.hatches, first.encode(paid)), replacement, 7);
        var secondSources = new Sources(20, 100);
        var secondPaid = extension.blocked(second, secondSources);
        var secondReplacement = new Sources(15, 0);
        extension.complete(second.decodeMaterialCycle(secondReplacement.owner, secondReplacement.hatches, second.encode(secondPaid)),
            secondReplacement, 7);
        assertEquals(2, extension.configurations);
    }

    @Test
    void explicitBehaviorRevisionRejectsOldPaidWorkWhenCustomPayloadChanges() {
        var oldExtension = new Extension(4);
        var oldRegistry = registry(definition("revision", 1, oldExtension.stage(1, 1, "1").build()));
        var oldCodec = oldRegistry.materials(id("revision"), 1);
        var oldSources = new Sources(20, 100);
        var saved = oldCodec.encode(oldExtension.blocked(oldCodec, oldSources));
        var newExtension = new Extension(8);
        var newRegistry = registry(definition("revision", 1, newExtension.stage(1, 1, "2").build()));
        var newCodec = newRegistry.materials(id("revision"), 1);
        var replacement = new Sources(15, 0);
        assertThrows(IllegalArgumentException.class, () -> newCodec.decodeMaterialCycle(replacement.owner, replacement.hatches, saved));
        assertEquals(15, replacement.energy.storedEnergy());
        assertEquals(0, replacement.generated.storedEnergy());
        var newSources = new Sources(20, 100);
        var newPaid = newExtension.blocked(newCodec, newSources);
        newExtension.complete(newCodec.decodeMaterialCycle(replacement.owner, replacement.hatches, newCodec.encode(newPaid)), replacement, 11);
    }

    @Test
    void defaultBindingPersistsPositiveTierWorkAndTierZeroHasNoStationCodec() {
        var registry = registry(definition("default", 2, stage(1, 2).materialRequirement(energy()).build()),
            definition("manual", 1, stage(1, 1).researchTier(0).build()));
        var codec = registry.materials(id("default"), 1);
        var sources = new Sources(20, 100);
        var process = RecipeProcess.materialCycle(sources.owner, codec.recipe(), sources.hatches, 3);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(15, sources.energy.storedEnergy());
        var replacement = new Sources(15, 0);
        var restored = registry.materials(id("default"), 2).decodeMaterialCycle(replacement.owner, replacement.hatches, codec.encode(process));
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(1, restored.state().successfulTicks());
        assertEquals(15, replacement.energy.storedEnergy());
        assertEquals(7, replacement.generated.storedEnergy());
        assertThrows(IllegalArgumentException.class, () -> registry.materials(id("manual"), 1));
    }

    @Test
    void referenceValidationCanBeRepairedBeforeExtensionConfigurationStarts() {
        var extension = new Extension(4);
        var registry = new ResearchRegistry();
        registry.register(definition("dependent", 1, extension.stage(1, 1, "1").prerequisite(id("missing"), 1).build()));
        assertThrows(IllegalArgumentException.class, () -> registry.freeze(resources()));
        assertEquals(0, extension.configurations);
        assertFalse(registry.isFrozen());
        registry.register(definition("missing", 1, stage(1, 1).build()));
        registry.freeze(resources());
        assertEquals(1, extension.configurations);
        var codec = registry.materials(id("dependent"), 1);
        var sources = new Sources(20, 100);
        var paid = extension.blocked(codec, sources);
        var replacement = new Sources(15, 0);
        extension.complete(codec.decodeMaterialCycle(replacement.owner, replacement.hatches, codec.encode(paid)), replacement, 7);
        assertThrows(IllegalStateException.class, () -> registry.freeze(resources()));
        assertEquals(1, extension.configurations);
    }

    @Test
    void callbackFailurePublishesNoPartialBindingsAndCannotReplayAlreadyConfiguredExtensions() {
        var firstExtension = new Extension(4);
        var failingExtension = new Extension(8);
        var failure = new IllegalStateException("addon configuration failed");
        var registry = new ResearchRegistry();
        registry.register(definition("first", 1, firstExtension.stage(1, 1, "1").build()));
        registry.register(definition("failure", 1, stage(1, 1).materialRequirement(failingExtension.requirement)
                                                              .materialPersistence("1", builder -> {
                                                                  failingExtension.configure(builder);
                                                                  throw failure;
                                                              }).build()));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> registry.freeze(resources())));
        assertFalse(registry.isFrozen());
        assertThrows(IllegalStateException.class, registry::all);
        assertThrows(IllegalStateException.class, () -> registry.materials(id("first"), 1));
        assertThrows(IllegalStateException.class, () -> registry.register(definition("late", 1, stage(1, 1).build())));
        assertThrows(IllegalStateException.class, () -> registry.freeze(resources()));
        assertEquals(1, firstExtension.configurations);
        assertEquals(1, failingExtension.configurations);
    }

    @Test
    void loadingConfigurationCannotReenterFreezeOrRegistrationButCanFinishItsOriginalBinding() {
        var extension = new Extension(4);
        var registry = new ResearchRegistry();
        var configured = stage(1, 1).materialRequirement(extension.requirement).materialRequirement(energy())
                                    .materialPersistence("1", builder -> {
                                        assertThrows(IllegalStateException.class, () -> registry.freeze(resources()));
                                        assertThrows(IllegalStateException.class, () -> registry.register(definition("late", 1, stage(1, 1).build())));
                                        extension.configure(builder);
                                    }).build();
        registry.register(definition("reentrant", 1, configured));
        registry.freeze(resources());
        var codec = registry.materials(id("reentrant"), 1);
        var sources = new Sources(20, 100);
        var paid = extension.blocked(codec, sources);
        var replacement = new Sources(15, 0);
        extension.complete(codec.decodeMaterialCycle(replacement.owner, replacement.hatches, codec.encode(paid)), replacement, 7);
        assertEquals(1, extension.configurations);
        assertEquals(1, registry.all().size());
    }

    private record State(int starts, int ticks, int credits) {
    }

    private static final class Box {
        private State state = new State(0, 0, 0);

        private State snapshot() {
            return state;
        }
    }

    /**
     * A real external requirement whose reversible business result depends on its restored overlay.
     */
    private static final class Extension {
        private final ComponentType<RecipeProcess, Box> component = new ComponentType<>(id("box"), _ -> new Box(), List.of());
        private final RecipeRequirement<Integer> requirement;
        private final RecipeOverlayType<Integer, Integer> overlay;
        private int configurations;

        private Extension(int endCredits) {
            var type = new RecipeRequirementType<>(id("custom_type"), new RecipeRequirementSystem<Integer>() {
                @Override
                public RequirementTransaction start(RecipeProcess process, RecipeRequirement<Integer> current) {
                    var box = process.components().create(component, process);
                    return change(box, RecipeStage.START, current.payload());
                }

                @Override
                public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Integer> current) {
                    return change(state(process), RecipeStage.TICK, current.payload());
                }

                @Override
                public RequirementTransaction end(RecipeProcess process, RecipeRequirement<Integer> current) {
                    return change(state(process), RecipeStage.END, current.payload());
                }
            });
            requirement = type.requirement(id("custom"), endCredits);
            overlay = new RecipeOverlayType<>(id("custom_overlay"), type, Math::addExact);
        }

        private static RequirementTransaction change(Box box, RecipeStage stage, int credits) {
            var before = box.snapshot();
            return new RequirementTransaction() {
                private boolean applied;

                @Override
                public ProcessResult result() {
                    return ProcessResult.SUCCESS;
                }

                @Override
                public void commit() {
                    box.state = switch (stage) {
                        case START -> new State(before.starts + 1, before.ticks, before.credits);
                        case TICK -> new State(before.starts, before.ticks + 1, before.credits);
                        case END -> new State(before.starts, before.ticks, Math.addExact(before.credits, credits));
                    };
                    applied = true;
                }

                @Override
                public void rollback() {
                    if (applied) {
                        box.state = before;
                        applied = false;
                    }
                }
            };
        }

        private ResearchLevelStage.Builder stage(int first, int last, String revision) {
            return ResearchMaterialBindingTest.stage(first, last).materialRequirement(requirement).materialRequirement(energy())
                                              .materialPersistence(revision, this::configure);
        }

        private void configure(RecipeProcessNbt.Builder builder) {
            configurations++;
            builder.component(component, new ProcessComponentCodec<>() {
                @Override
                public NBTTagCompound encode(RecipeProcess process, Box box) {
                    var tag = new NBTTagCompound();
                    tag.setInteger("Starts", box.state.starts);
                    tag.setInteger("Ticks", box.state.ticks);
                    tag.setInteger("Credits", box.state.credits);
                    return tag;
                }

                @Override
                public void restore(RecipeProcess process, Box box, NBTTagCompound tag) {
                    StrictNbt.requireKeys(tag, "Starts", "Ticks", "Credits");
                    box.state = new State(StrictNbt.requireInt(tag, "Starts"), StrictNbt.requireInt(tag, "Ticks"), StrictNbt.requireInt(tag, "Credits"));
                }

                @Override
                public void validate(RecipeProcess process, Box box) {
                    if (box.state.starts < 0 || box.state.starts > 1 || box.state.ticks < 0 || box.state.ticks > 1 || box.state.credits < 0) {
                        throw new IllegalArgumentException("Invalid external business state");
                    }
                }
            }).overlay(overlay, new NbtCodec<>() {
                @Override
                public NBTTagCompound encode(Integer amount) {
                    var tag = new NBTTagCompound();
                    tag.setInteger("Amount", amount);
                    return tag;
                }

                @Override
                public Integer decode(NBTTagCompound tag) {
                    StrictNbt.requireKeys(tag, "Amount");
                    return StrictNbt.requireInt(tag, "Amount");
                }
            }).validator(process -> {
                var actual = state(process).snapshot();
                int expectedCredits = process.state().lifecycle() == RecipeProcess.Lifecycle.COMPLETED
                    ? RecipeOverlays.getOrCreate(process).resolve(requirement).payload() : 0;
                if (actual.starts != (process.state().started() ? 1 : 0) || actual.ticks != process.state().successfulTicks()
                    || actual.credits != expectedCredits) {
                    throw new IllegalArgumentException("External paid stages disagree with process state");
                }
            });
        }

        private Box state(RecipeProcess process) {
            return Objects.requireNonNull(process.components().get(component));
        }

        private RecipeProcess blocked(RecipeProcessNbt codec, Sources sources) {
            var process = RecipeProcess.materialCycle(sources.owner, codec.recipe(), sources.hatches, 13);
            RecipeOverlays.getOrCreate(process).put(requirement, "bonus", overlay, 3).complete();
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            assertEquals(new State(1, 1, 0), state(process).snapshot());
            assertEquals(1, process.state().successfulTicks());
            assertEquals(15, sources.energy.storedEnergy());
            return process;
        }

        private void complete(RecipeProcess process, Sources sources, int credits) {
            assertEquals(new State(1, 1, 0), state(process).snapshot());
            assertSame(sources.hatches, process.hatches());
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(new State(1, 1, credits), state(process).snapshot());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
            assertEquals(1, process.state().successfulTicks());
            assertEquals(15, sources.energy.storedEnergy());
            assertEquals(7, sources.generated.storedEnergy());
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(new State(1, 1, credits), state(process).snapshot());
            assertEquals(7, sources.generated.storedEnergy());
        }
    }

    private static final class Sources {
        private final MachineInstance owner = new MachineInstance();
        private final EnergyBuffer energy = new EnergyBuffer(100, () -> {
        });
        private final EnergyBuffer generated = new EnergyBuffer(100, () -> {
        });
        private final MachineHatches hatches = new MachineHatches(List.of(), List.of(energy), List.of(generated));

        private Sources(long inputEnergy, long outputEnergy) {
            energy.receiveEnergy(inputEnergy, Actionable.MODULATE);
            generated.receiveEnergy(outputEnergy, Actionable.MODULATE);
            owner.components().put(MachineHatches.TYPE, hatches);
        }
    }
}
