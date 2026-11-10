package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

class MachineScheduledExecutionTest {
    private static RecipeProcess process(MachineWorkSlots slots, String name) {
        var process = new RecipeProcess(slots.owner(), new MachineRecipe(id(name), 1, List.of()), 1);
        assertTrue(slots.start(process));
        return process;
    }

    private static MachineHatches emptyHatches() {
        return new MachineHatches(List.of(), List.of(), List.of());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("scheduled_execution_test", path);
    }

    @Test
    void retainedExecutionRejectsSaveLifecycleAndOrdinaryTicksBetweenOwnerCallbacks() {
        var fixture = new Fixture();
        fixture.load();
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        int[] calls = {0};
        assertTrue(handle.run(() -> calls[0]++));
        assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.withPersistence(() -> "saved"));
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, fixture.lifecycle::beginScheduled);
        assertThrows(IllegalStateException.class, fixture.lifecycle::invalidateStructure);
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.form(emptyHatches()));
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
        assertThrows(IllegalStateException.class, fixture.lifecycle::controllerBroken);
        assertTrue(handle.run(() -> calls[0]++));
        assertEquals(2, calls[0]);
        assertTrue(handle.tryFinish());
        fixture.machine.requirePersistenceBoundary();
        assertEquals("saved", fixture.lifecycle.withPersistence(() -> "saved"));
        fixture.machine.onSchedule();
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
    }

    @Test
    void observedReplacementStillRetainsTheGateAfterTheOriginalWorkSlotsAreReinstalled() {
        var fixture = new Fixture();
        fixture.load();
        var originalProcess = process(fixture.slots, "original");
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        MachineWorkSlots.TickBatch[] original = {null};
        assertTrue(handle.run(() -> {
            original[0] = fixture.slots.prepareTickBatch();
            original[0].begin();
        }));
        var replacement = new MachineWorkSlots(fixture.machine, List.of());
        process(replacement, "replacement");
        fixture.machine.components().put(MachineWorkSlots.TYPE, replacement);
        MachineWorkSlots.TickBatch[] current = {null};
        assertTrue(handle.run(() -> {
            current[0] = replacement.prepareTickBatch();
            current[0].begin();
        }));
        assertFalse(handle.tryFinish());
        handle.cleanup(() -> {
            current[0].stop();
            assertTrue(current[0].tryFinish());
        });
        assertFalse(handle.tryFinish(), "Removing the original host must not conceal its pending tick");
        assertTrue(handle.run(() -> {
            current[0] = replacement.prepareTickBatch();
            current[0].begin();
        }));
        assertFalse(handle.tryFinish());
        fixture.machine.components().put(MachineWorkSlots.TYPE, fixture.slots);
        handle.cleanup(() -> {
            original[0].stop();
            assertTrue(original[0].tryFinish());
        });
        assertFalse(handle.tryFinish(), "Replacing an observed pending host must not conceal its tick");
        assertTrue(fixture.lifecycle.schedulingAllowed(), "Normal pending must not become a runtime fault");
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        handle.cleanup(() -> {
            current[0].stop();
            assertTrue(current[0].tryFinish());
        });
        assertTrue(handle.tryFinish());
        assertSame(fixture.slots, originalProcess.host());
        assertEquals(RecipeProcess.Lifecycle.NEW, originalProcess.state().lifecycle());
        fixture.machine.requirePersistenceBoundary();
        replacement.requireQuiescent();
        fixture.slots.requireQuiescent();
    }

    @Test
    void unknownRetiredCleanupRetainsOwnershipUntilExactConfirmationWithoutReplayingRefund() {
        var fixture = new Fixture();
        var energy = new EnergyBuffer(100, () -> {
        });
        assertEquals(100, energy.receiveEnergy(100, Actionable.MODULATE));
        boolean[] confirmed = {false};
        int[] rollbackCalls = {0};
        var failure = new IllegalStateException("refund applied but confirmation unknown");
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process,
                                                RecipeRequirement<String> requirement) {
                var token = Objects.requireNonNull(energy.reserveExtractEnergy(energy.energySnapshot(), 25).reservation());
                var delayed = new ResourceReservation() {
                    @Override
                    public StorageContext context() {
                        return token.context();
                    }

                    @Override
                    public boolean canApply() {
                        return token.canApply();
                    }

                    @Override
                    public void apply() {
                        token.apply();
                    }

                    @Override
                    public void complete() {
                        token.complete();
                    }

                    @Override
                    public void rollback() {
                        rollbackCalls[0]++;
                        token.rollback();
                        throw failure;
                    }

                    @Override
                    public Resolution resolution() {
                        return confirmed[0] ? token.resolution() : Resolution.UNKNOWN;
                    }
                };
                return new AbandonedRequirement(List.of(delayed));
            }
        };
        fixture.load();
        var definition = new RecipeRequirementType<>(id("held_energy"), system).requirement(id("held_energy"), "value");
        var process = new RecipeProcess(fixture.machine, new MachineRecipe(id("unknown"), 1, List.of(definition)), 1);
        assertTrue(fixture.slots.start(process));
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        MachineWorkSlots.TickBatch[] batch = {null};
        RecipeProcess.Stage[] stage = {null};
        assertTrue(handle.run(() -> {
            batch[0] = fixture.slots.prepareTickBatch();
            batch[0].begin();
            stage[0] = batch[0].nextRound().getFirst();
            stage[0].acquire();
        }));
        assertEquals(75, energy.energySnapshot().availableEnergy());
        fixture.lifecycle.retire();
        assertFalse(handle.run(() -> fail("Retirement must stop new forward work")));
        assertSame(failure, assertThrows(IllegalStateException.class, () -> handle.cleanup(stage[0]::rollbackSynchronously)));
        assertFalse(handle.tryFinish());
        handle.cleanup(() -> assertFalse(stage[0].resolveSynchronously()));
        assertFalse(handle.tryFinish());
        assertEquals(1, rollbackCalls[0]);
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
        confirmed[0] = true;
        handle.cleanup(() -> {
            assertTrue(stage[0].resolveSynchronously());
            batch[0].stop();
            assertTrue(batch[0].tryFinish());
        });
        assertTrue(handle.tryFinish());
        assertEquals(1, rollbackCalls[0], "Exact terminal proof must not replay the actual refund");
        assertEquals(100, energy.savedEnergy());
        assertFalse(fixture.lifecycle.schedulingAllowed());
        assertSame(failure, handle.failure());
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
    }

    @Test
    void retirementBetweenCallbacksAllowsCleanupAndReleaseWithoutRecordingAFault() {
        var fixture = new Fixture();
        fixture.load();
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        int[] calls = {0};
        assertTrue(handle.run(() -> calls[0]++));
        fixture.lifecycle.retire();
        assertFalse(handle.run(() -> fail("Retired body cannot run")));
        handle.cleanup(() -> calls[0]++);
        assertEquals(2, calls[0]);
        assertTrue(handle.tryFinish());
        assertNull(handle.failure());
        assertNull(fixture.lifecycle.beginScheduled());
        fixture.machine.requirePersistenceBoundary();
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
    }

    @Test
    void actualBusinessFaultRemainsFatalAfterDeferredCleanupAndGateRelease() {
        var fixture = new Fixture();
        fixture.load();
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        var primary = new AssertionError("business failed");
        var cleanupFailure = new IllegalArgumentException("cleanup failed");
        assertSame(primary, assertThrows(AssertionError.class, () -> handle.run(() -> {
            throw primary;
        })));
        assertFalse(handle.run(() -> fail("A runtime fault cannot resume forward work")));
        assertSame(cleanupFailure, assertThrows(IllegalArgumentException.class,
            () -> handle.cleanup(() -> {
                throw cleanupFailure;
            })));
        handle.recordFailure(primary);
        assertSame(primary, handle.failure());
        assertArrayEquals(new Throwable[]{cleanupFailure}, primary.getSuppressed());
        int[] cleanupCalls = {0};
        handle.cleanup(() -> cleanupCalls[0]++);
        assertTrue(handle.tryFinish());
        assertEquals(1, cleanupCalls[0]);
        assertFalse(fixture.lifecycle.schedulingAllowed());
        assertThrows(IllegalStateException.class, fixture.lifecycle::beginScheduled);
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertThrows(IllegalStateException.class, fixture.machine::requirePersistenceBoundary);
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
    }

    @Test
    void ownerCallbacksRejectHandleReentryAndForeignThreadsCannotReleaseTheGate() throws Exception {
        var fixture = new Fixture();
        fixture.load();
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        Runnable rejectReentry = () -> {
            assertThrows(IllegalStateException.class, handle::tryFinish);
            assertThrows(IllegalStateException.class, () -> handle.run(() -> {
            }));
            assertThrows(IllegalStateException.class, () -> handle.cleanup(() -> {
            }));
            assertThrows(IllegalStateException.class, () -> handle.recordFailure(new AssertionError("reentrant")));
        };
        assertTrue(handle.run(rejectReentry));
        handle.cleanup(rejectReentry);
        try (var worker = Executors.newSingleThreadExecutor()) {
            worker.submit(() -> {
                assertThrows(IllegalStateException.class, handle::tryFinish);
                assertThrows(IllegalStateException.class, () -> handle.run(() -> {
                }));
                assertThrows(IllegalStateException.class, () -> handle.cleanup(() -> {
                }));
                assertThrows(IllegalStateException.class, () -> handle.recordFailure(new AssertionError("foreign")));
                assertThrows(IllegalStateException.class, fixture.lifecycle::beginScheduled);
                assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
            }).get(5, TimeUnit.SECONDS);
        }
        assertTrue(handle.tryFinish());
        assertTrue(fixture.lifecycle.schedulingAllowed());
        assertNull(handle.failure());
        var next = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        assertThrows(IllegalStateException.class, handle::tryFinish);
        assertThrows(IllegalStateException.class, () -> handle.cleanup(() -> {
        }));
        assertThrows(IllegalStateException.class, fixture.machine::onSchedule);
        assertTrue(next.tryFinish());
        fixture.machine.requirePersistenceBoundary();
    }

    @Test
    void declinedNewExecutionReleasesItsGateAndOrdinarySchedulingKeepsOriginalFaultSemantics() {
        var fixture = new Fixture();
        assertNull(fixture.lifecycle.beginScheduled());
        fixture.load();
        var handle = Objects.requireNonNull(fixture.lifecycle.beginScheduled());
        assertTrue(handle.tryFinish());
        var primary = new IllegalArgumentException("ordinary failure");
        assertSame(primary, assertThrows(IllegalArgumentException.class,
            () -> fixture.lifecycle.runScheduled(() -> {
                throw primary;
            })));
        assertThrows(IllegalStateException.class, fixture.lifecycle::beginScheduled);
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));
    }

    private record AbandonedRequirement(List<ResourceReservation> reservations) implements RequirementTransaction {
        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
            fail("Abandoned stage must not commit");
        }

        @Override
        public void rollback() {
        }
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final MachineLifecycle lifecycle = new MachineLifecycle(machine, new Host(), new MachineLifecycleRegistry());
        private final MachineWorkSlots slots = new MachineWorkSlots(machine, List.of());

        private Fixture() {
            machine.components().put(MachineWorkSlots.TYPE, slots);
        }

        private void load() {
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
            lifecycle.form(emptyHatches());
        }
    }

    private static final class Host implements MachineLifecycleHost {
        private final Thread owner = Thread.currentThread();

        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new UnsupportedOperationException("Logical fixture has no game world");
        }

        @Override
        public void requireServerThread() {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Not the fixture owner");
            }
        }
    }
}
