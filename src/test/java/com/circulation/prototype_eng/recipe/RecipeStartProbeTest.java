package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.MachineAttributes;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
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
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeStartProbeTest {
    private static final KeyType FIRST_TYPE = new KeyType("probe_first");
    private static final KeyType SECOND_TYPE = new KeyType("probe_second");
    private static final Key MATERIAL = new Key(FIRST_TYPE, "material");
    private static final Key ALTERNATE = new Key(FIRST_TYPE, "alternate");
    private static final Key EXTRA = new Key(SECOND_TYPE, "extra");
    private static final Key PRODUCT = new Key(FIRST_TYPE, "product");
    private static final ResourceLocation SCORE = id("score");
    private static final Runnable NO_OP = () -> {
    };

    private static SlottedResourceBuffer buffer() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST_TYPE, 100);
        capacities.put(SECOND_TYPE, 100);
        return new SlottedResourceBuffer(4, capacities, NO_OP);
    }

    private static NumericAttributeModifier modifier(String name, double amount) {
        return new NumericAttributeModifier(name, amount, AttributeModifier.Operation.ADDITION);
    }

    private static List<GenericStack> stacks(Key key, long amount) {
        return List.of(new GenericStack(key, amount));
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("probe_test", name);
    }

    @Test
    void successfulProbeAcquiresRealMultiTypeResourceEnergyAndProvisionalStateThenUndoesEverything() {
        var fixture = new Fixture();
        for (int attempt = 0; attempt < 2; attempt++) {
            fixture.events.clear();
            assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(fixture.process));
            assertEquals(List.of("early", "child", "tail", "after"), fixture.acquisitions());
            assertEquals(List.of("after", "tail", "child", "early"), fixture.rollbacks());
            fixture.assertRestoredEffects();
            fixture.env.assertAllReservationsReleased();
            fixture.process.requirePersistenceBoundary();
            assertEquals(20, fixture.env.input.amount(MATERIAL));
            assertEquals(30, fixture.env.input.amount(EXTRA));
            assertEquals(40, fixture.env.energy.storedEnergy());
        }
        assertEquals(0, fixture.metrics.commits);
        assertEquals(0, fixture.metrics.completes);
        assertEquals(0, fixture.metrics.tokenApplications);
        assertEquals(0, fixture.metrics.tokenCompletions);
        assertEquals(0, fixture.metrics.descriptorApplications);
        assertEquals(0, fixture.metrics.ticks);
        assertEquals(0, fixture.metrics.ends);
    }

    @Test
    void blockedAndFailureResultsStopLaterAcquisitionAndStillUndoEveryEarlierEffect() {
        for (var result : List.of(new ProcessResult.Blocked("waiting"), new ProcessResult.Failure("invalid"))) {
            var fixture = new Fixture();
            fixture.tailResult = result;
            assertSame(result, RecipeStageExecutor.probeStart(fixture.process));
            assertEquals(List.of("early", "child", "tail"), fixture.acquisitions());
            assertEquals(List.of("tail", "child", "early"), fixture.rollbacks());
            fixture.assertRestoredEffects();
            fixture.env.assertAllReservationsReleased();
            fixture.process.requirePersistenceBoundary();
            assertEquals(0, fixture.metrics.commits);
            assertEquals(0, fixture.metrics.completes);
            assertEquals(0, fixture.metrics.descriptorApplications);
        }
    }

    @Test
    void realResourceRejectionAlsoReleasesPreviouslyAcquiredEnergyAndAttributeOverlayChanges() {
        var fixture = new Fixture();
        fixture.env.input.extract(EXTRA, 30, Actionable.MODULATE);
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.probeStart(fixture.process));
        assertEquals(List.of("early"), fixture.acquisitions());
        assertEquals(List.of("early"), fixture.rollbacks());
        fixture.assertRestoredEffects();
        fixture.env.assertAllReservationsReleased();
        assertEquals(20, fixture.env.input.amount(MATERIAL));
        assertEquals(0, fixture.env.input.amount(EXTRA));
        assertEquals(40, fixture.env.energy.storedEnergy());
        fixture.process.requirePersistenceBoundary();
    }

    @Test
    void acquisitionAndBusinessTokenRollbackFaultsAggregateWithoutSkippingOtherCleanup() {
        var fixture = new Fixture();
        var acquisition = new IllegalStateException("tail acquisition failed");
        var childRollback = new IllegalStateException("child rollback failed");
        var earlyRollback = new IllegalStateException("early rollback failed");
        var tokenRollback = new IllegalStateException("token rollback failed after release");
        fixture.tailFailure = acquisition;
        fixture.childRollbackFailure = childRollback;
        fixture.earlyRollbackFailure = earlyRollback;
        fixture.tokenRollbackFailure = tokenRollback;
        assertSame(acquisition, assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.probeStart(fixture.process)));
        assertEquals(List.of(childRollback, earlyRollback, tokenRollback), List.of(acquisition.getSuppressed()));
        assertEquals(List.of("early", "child", "tail"), fixture.acquisitions());
        assertEquals(List.of("child", "early"), fixture.rollbacks());
        fixture.assertRestoredEffects();
        fixture.env.assertAllReservationsReleased();
        assertEquals(0, fixture.metrics.commits);
        assertEquals(0, fixture.metrics.completes);
        assertThrows(IllegalStateException.class, fixture.process::requirePersistenceBoundary);
        var events = List.copyOf(fixture.events);
        assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.probeStart(fixture.process));
        assertEquals(events, fixture.events);
    }

    @Test
    void pureTokenCleanupFailureIsExposedAndInvalidatesAnOtherwiseUnchangedCandidate() {
        var fixture = new Fixture();
        var failure = new IllegalStateException("token cleanup failed after release");
        fixture.tokenRollbackFailure = failure;
        assertSame(failure, assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.probeStart(fixture.process)));
        assertEquals(List.of("after", "tail", "child", "early"), fixture.rollbacks());
        fixture.assertRestoredEffects();
        fixture.env.assertAllReservationsReleased();
        assertThrows(IllegalStateException.class, fixture.process::requirePersistenceBoundary);
        assertEquals(0, fixture.metrics.tokenApplications);
        assertEquals(0, fixture.metrics.tokenCompletions);
    }

    @Test
    void probesRequireUnattachedNewCandidatesAtAStableAcquisitionBoundary() {
        var fixture = new Fixture();
        var host = new MachineWorkSlots(fixture.env.machine, List.of());
        assertTrue(host.start(fixture.process));
        assertThrows(IllegalArgumentException.class, () -> RecipeStageExecutor.probeStart(fixture.process));
        assertTrue(fixture.events.isEmpty());
        assertTrue(host.detach(fixture.process));
        var pending = fixture.early.acquire(fixture.process, RecipeStage.START);
        var events = List.copyOf(fixture.events);
        assertThrows(IllegalStateException.class, () -> RecipeStageExecutor.probeStart(fixture.process));
        assertEquals(events, fixture.events);
        ResourceTransactions.rollback(pending.reservations(), pending::rollback);
        fixture.assertRestoredEffects();
        fixture.events.clear();
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(fixture.process));
        fixture.assertRestoredEffects();
        var running = new RecipeProcess(new MachineInstance(), new MachineRecipe(id("running"), 3, List.of()), 17);
        running.tick();
        assertThrows(IllegalArgumentException.class, () -> RecipeStageExecutor.probeStart(running));
        assertEquals(1, running.state().successfulTicks());
        var completed = new RecipeProcess(new MachineInstance(), new MachineRecipe(id("completed"), 0, List.of()), 17);
        completed.tick();
        assertThrows(IllegalArgumentException.class, () -> RecipeStageExecutor.probeStart(completed));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, completed.state().lifecycle());
    }

    private static final class Fixture {
        private final Environment env = new Environment();
        private final Metrics metrics = new Metrics();
        private final List<String> events = new ObjectArrayList<>();
        private final RecipeRequirement<ResourceRequirement> resources;
        private final RecipeRequirement<Boolean> early;
        private final RecipeRequirement<SelectiveRequirement> selection;
        private final RecipeProcess process;
        private final RecipeProcess.State initialState;
        private final Map<ResourceLocation, MachineAttributes.LocalAttribute> initialAttributes;
        private final List<RecipeOverlays.LayerSnapshot<?, ?>> initialLayers;
        private ProcessResult tailResult = ProcessResult.SUCCESS;
        private RuntimeException tailFailure;
        private RuntimeException childRollbackFailure;
        private RuntimeException earlyRollbackFailure;
        private RuntimeException tokenRollbackFailure;

        private Fixture() {
            env.machine.attributes().setBase(SCORE, 2);
            resources = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
                List.of(new GenericStack(MATERIAL, 2), new GenericStack(EXTRA, 3)), stacks(MATERIAL, 1), List.of(), stacks(PRODUCT, 5),
                false, ResourceChance.ALWAYS, null, List.of(new FuzzyInputGroup(List.of(MATERIAL, ALTERNATE), 4)), List.of()));
            early = new RecipeRequirementType<>(new ResourceLocation("aaa_probe_test", "early"), new TracingSystem() {
                @Override
                public RequirementTransaction start(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                    events.add("early:acquire");
                    assertEquals(requirement.path(null), owner.requirementPath());
                    assertTrue(owner.attributes().addModifier(SCORE, modifier("early", 3)));
                    var change = RecipeOverlays.getOrCreate(owner).put(resources, "probe", RecipeOverlayTypes.RESOURCE_IO,
                        new ResourceRequirementPatch(List.of(new GenericStack(MATERIAL, 3), new GenericStack(EXTRA, 2)), null, null, null, null));
                    return transaction(owner, "early", List.of(), ProcessResult.SUCCESS, () -> {
                        change.rollback();
                        assertTrue(owner.attributes().removeModifier(SCORE, "early"));
                        if (earlyRollbackFailure != null) {
                            throw earlyRollbackFailure;
                        }
                    });
                }
            }).requirement(id("early"), true);
            var child = new RecipeRequirementType<>(id("child_type"), new TracingSystem() {
                @Override
                public RequirementTransaction start(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                    events.add("child:acquire");
                    assertEquals(requirement.path(selection.path(null)), owner.requirementPath());
                    var material = env.input.reserveExtract(env.input.snapshot(), MATERIAL, 1);
                    var extra = env.input.reserveExtract(env.input.snapshot(), EXTRA, 1);
                    var energy = env.energy.reserveExtractEnergy(env.energy.energySnapshot(), 2);
                    assertEquals(ReservationResult.Status.ACCEPTED, material.status());
                    assertEquals(ReservationResult.Status.ACCEPTED, extra.status());
                    assertEquals(ReservationResult.Status.ACCEPTED, energy.status());
                    assertTrue(owner.attributes().addModifier(SCORE, modifier("child", 5)));
                    return transaction(owner, "child", List.of(
                            new TrackedReservation(Objects.requireNonNull(material.reservation()), metrics, () -> null),
                            new TrackedReservation(Objects.requireNonNull(extra.reservation()), metrics, () -> null),
                            new TrackedReservation(Objects.requireNonNull(energy.reservation()), metrics, () -> tokenRollbackFailure)),
                        ProcessResult.SUCCESS, () -> {
                            assertTrue(owner.attributes().removeModifier(SCORE, "child"));
                            if (childRollbackFailure != null) {
                                throw childRollbackFailure;
                            }
                        });
                }
            }).requirement(id("child"), true);
            selection = RecipeRequirements.SELECTIVE.requirement(id("selection"), new SelectiveRequirement(List.of(child), List.of(
                new SelectiveModifierDescriptor(id("descriptor"), context -> {
                    metrics.descriptorApplications++;
                    context.putProcessAttributeModifier(SCORE, modifier("selector", 999));
                    context.putRequirementOverlay(resources, "selector", RecipeOverlayTypes.RESOURCE_IO,
                        new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 999), null));
                }))));
            var attribute = RecipeRequirements.ATTRIBUTES.requirement(id("attribute"), new AttributeRequirement(SCORE, modifier("attribute", 4)));
            var energy = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(5, 7, 0, 11, false));
            var tail = new RecipeRequirementType<>(new ResourceLocation("zzz_probe_test", "tail"), new TracingSystem() {
                @Override
                public RequirementTransaction start(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                    events.add("tail:acquire");
                    assertEquals(requirement.path(null), owner.requirementPath());
                    assertEquals(12, env.input.availableAmount(MATERIAL));
                    assertEquals(27, env.input.availableAmount(EXTRA));
                    assertEquals(33, env.energy.energySnapshot().availableEnergy());
                    assertEquals(15, owner.attributes().value(SCORE));
                    var locks = Objects.requireNonNull(owner.components().get(ResourceInputLocks.TYPE)).snapshot();
                    assertEquals(List.of(MATERIAL), List.copyOf(locks.values()));
                    var provisional = Objects.requireNonNull(owner.components().get(SelectiveState.TYPE)).selection(selection.path(null));
                    assertEquals(0, Objects.requireNonNull(provisional).index());
                    assertFalse(provisional.committed());
                    if (tailFailure != null) {
                        throw tailFailure;
                    }
                    return transaction(owner, "tail", List.of(), tailResult, NO_OP);
                }
            }).requirement(id("tail"), true);
            var after = new RecipeRequirementType<>(new ResourceLocation("zzz_probe_test", "zz_after"), new TracingSystem() {
                @Override
                public RequirementTransaction start(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                    events.add("after:acquire");
                    return transaction(owner, "after", List.of(), ProcessResult.SUCCESS, NO_OP);
                }
            }).requirement(id("after"), true);
            // Deliberately shuffled declaration order: the probe must use the canonical ordered requirements.
            var recipe = new MachineRecipe(id("recipe"), 3, List.of(after, selection, resources, energy, attribute, early, tail));
            process = new RecipeProcess(env.machine, recipe, 17);
            process.attributes().addModifier(SCORE, modifier("foreign", 1));
            RecipeOverlays.getOrCreate(process).put(resources, "foreign", RecipeOverlayTypes.RESOURCE_IO,
                new ResourceRequirementPatch(null, null, null, stacks(PRODUCT, 6), null)).complete();
            initialState = process.state();
            initialAttributes = process.attributes().localSnapshot();
            initialLayers = RecipeOverlays.getOrCreate(process).snapshot();
        }

        private RequirementTransaction transaction(RecipeProcess owner, String name, List<ResourceReservation> tokens,
                                                   ProcessResult result, Runnable cleanup) {
            return new TrackedTransaction(owner, owner.requirementPath(), name, tokens, result, cleanup, events, metrics);
        }

        private List<String> acquisitions() {
            return events.stream().filter(event -> event.endsWith(":acquire")).map(event -> event.substring(0, event.indexOf(':'))).toList();
        }

        private List<String> rollbacks() {
            return events.stream().filter(event -> event.endsWith(":rollback")).map(event -> event.substring(0, event.indexOf(':'))).toList();
        }

        private void assertRestoredEffects() {
            assertEquals(initialState, process.state());
            assertNull(process.host());
            assertEquals(initialAttributes, process.attributes().localSnapshot());
            assertEquals(3, process.attributes().value(SCORE));
            assertEquals(initialLayers, RecipeOverlays.getOrCreate(process).snapshot());
            var locks = process.components().get(ResourceInputLocks.TYPE);
            if (locks != null) {
                assertTrue(locks.snapshot().isEmpty());
            }
            var selections = process.components().get(SelectiveState.TYPE);
            if (selections != null) {
                assertTrue(selections.snapshot().isEmpty());
            }
            assertThrows(NullPointerException.class, process::requirementPath);
            assertEquals(0, metrics.commits);
            assertEquals(0, metrics.completes);
            assertEquals(0, metrics.descriptorApplications);
        }

        private abstract class TracingSystem implements RecipeRequirementSystem<Boolean> {
            @Override
            public RequirementTransaction tick(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                metrics.ticks++;
                return RequirementTransaction.SUCCESS;
            }

            @Override
            public RequirementTransaction end(RecipeProcess owner, RecipeRequirement<Boolean> requirement) {
                metrics.ends++;
                return RequirementTransaction.SUCCESS;
            }
        }
    }

    private record TrackedTransaction(RecipeProcess process, RequirementPath path, String name,
                                      List<ResourceReservation> tokens,
                                      ProcessResult outcome, Runnable cleanup, List<String> events,
                                      Metrics metrics) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            assertEquals(path, process.requirementPath());
            events.add(name + ":result");
            return outcome;
        }

        @Override
        public List<ResourceReservation> reservations() {
            assertEquals(path, process.requirementPath());
            events.add(name + ":reservations");
            return tokens;
        }

        @Override
        public void commit() {
            assertEquals(path, process.requirementPath());
            metrics.commits++;
        }

        @Override
        public void complete() {
            assertEquals(path, process.requirementPath());
            metrics.completes++;
        }

        @Override
        public void rollback() {
            assertEquals(path, process.requirementPath());
            events.add(name + ":rollback");
            cleanup.run();
        }
    }

    private record TrackedReservation(ResourceReservation delegate, Metrics metrics,
                                      Supplier<RuntimeException> cleanupFailure) implements ResourceReservation {
        @Override
        public StorageContext context() {
            return delegate.context();
        }

        @Override
        public boolean canApply() {
            return delegate.canApply();
        }

        @Override
        public void apply() {
            metrics.tokenApplications++;
            delegate.apply();
        }

        @Override
        public void complete() {
            metrics.tokenCompletions++;
            delegate.complete();
        }

        @Override
        public void rollback() {
            delegate.rollback();
            var failure = cleanupFailure.get();
            if (failure != null) {
                throw failure;
            }
        }
    }

    private static final class Metrics {
        private int commits;
        private int completes;
        private int tokenApplications;
        private int tokenCompletions;
        private int descriptorApplications;
        private int ticks;
        private int ends;
    }

    private static final class Environment {
        private final SlottedResourceBuffer input = buffer();
        private final SlottedResourceBuffer output = buffer();
        private final EnergyBuffer energy = new EnergyBuffer(100, NO_OP);
        private final EnergyBuffer generated = new EnergyBuffer(100, NO_OP);
        private final MachineInstance machine = new MachineInstance();

        private Environment() {
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new Ports(input, output)), List.of(energy), List.of(generated)));
            input.insert(MATERIAL, 20, Actionable.MODULATE);
            input.insert(EXTRA, 30, Actionable.MODULATE);
            energy.receiveEnergy(40, Actionable.MODULATE);
        }

        private void assertAllReservationsReleased() {
            assertEquals(input.amount(MATERIAL), input.availableAmount(MATERIAL));
            assertEquals(input.amount(EXTRA), input.availableAmount(EXTRA));
            assertEquals(energy.storedEnergy(), energy.energySnapshot().availableEnergy());
            assertEquals(0, output.amount(PRODUCT));
            assertEquals(0, generated.storedEnergy());
        }
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override
        public boolean isValid() {
            return input.isValid() && output.isValid();
        }
    }
}
