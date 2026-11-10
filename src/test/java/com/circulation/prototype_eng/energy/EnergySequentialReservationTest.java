package com.circulation.prototype_eng.energy;

import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.StorageFence;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EnergySequentialReservationTest {
    private static ResourceReservation accepted(ReservationResult result) {
        assertEquals(ReservationResult.Status.ACCEPTED, result.status());
        return Objects.requireNonNull(result.reservation());
    }

    private static StorageFence fence(EnergyBuffer energy) {
        return Objects.requireNonNull(StorageFence.tryAcquire(List.of(energy.context())));
    }

    @Test
    void aFullBalanceCanPayStartAndRefillAtEndOnlyAfterTheAppliedRefundHoldIsExposed() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(100);
        var start = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 40));
        assertTrue(start.supportsSequentialUse());
        var fence = fence(energy);
        try {
            fence.access(() -> {
                start.apply();
                assertEquals(60, energy.storedEnergy());
                assertEquals(0, energy.energySnapshot().availableCapacity());
                assertEquals(ReservationResult.Status.INSUFFICIENT, energy.reserveReceiveEnergy(energy.energySnapshot(), 40).status());
                start.exposeApplied();
                assertEquals(ResourceReservation.Resolution.APPLIED, start.resolution());
                assertEquals(40, energy.energySnapshot().availableCapacity());
                assertThrows(IllegalStateException.class, () -> energy.restore(60), "Exposed undo still owns an active reservation");
                var end = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 40));
                end.apply();
                assertEquals(100, energy.storedEnergy());
                start.complete();
                end.complete();
                assertEquals(ResourceReservation.Resolution.COMPLETED, start.resolution());
                assertEquals(ResourceReservation.Resolution.COMPLETED, end.resolution());
                energy.restore(75);
            });
        } finally {
            fence.release();
        }
        assertEquals(75, energy.storedEnergy());
    }

    @Test
    void exposedOutputCanFeedALaterInputWhileBothExactTokensRemainReversible() {
        var energy = new EnergyBuffer(100, () -> {
        });
        var output = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 30));
        var fence = fence(energy);
        try {
            fence.access(() -> {
                output.apply();
                assertEquals(0, energy.energySnapshot().availableEnergy());
                assertEquals(ReservationResult.Status.INSUFFICIENT, energy.reserveExtractEnergy(energy.energySnapshot(), 30).status());
                output.exposeApplied();
                var input = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 30));
                input.apply();
                input.exposeApplied();
                assertEquals(0, energy.storedEnergy());
                assertEquals(ResourceReservation.Resolution.APPLIED, output.resolution());
                assertEquals(ResourceReservation.Resolution.APPLIED, input.resolution());
                output.complete();
                input.complete();
                assertEquals(100, energy.energySnapshot().availableCapacity());
            });
        } finally {
            fence.release();
        }
        energy.restore(35);
        assertEquals(35, energy.storedEnergy());
    }

    @Test
    void wholeSequenceRollbackReversesDependentEnergyStepsInTheirExactReverseOrder() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(80);
        var start = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 60));
        var fence = fence(energy);
        try {
            fence.access(() -> {
                start.apply();
                start.exposeApplied();
                var tick = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 70));
                tick.apply();
                tick.exposeApplied();
                var end = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 85));
                end.apply();
                end.exposeApplied();
                assertEquals(5, energy.storedEnergy());
                end.rollback();
                assertEquals(90, energy.storedEnergy());
                tick.rollback();
                assertEquals(20, energy.storedEnergy());
                start.rollback();
                assertEquals(80, energy.storedEnergy());
                for (var token : List.of(start, tick, end)) {
                    assertEquals(ResourceReservation.Resolution.ROLLED_BACK, token.resolution());
                }
                energy.restore(65);
            });
        } finally {
            fence.release();
        }
        assertEquals(65, energy.storedEnergy());
    }

    @Test
    void pendingOrdinaryAndTerminalTokensRejectExposureAndNeverReplayMutations() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(70);
        var pending = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 10));
        var fence = fence(energy);
        try {
            fence.access(() -> {
                assertThrows(IllegalStateException.class, pending::exposeApplied);
                assertEquals(ResourceReservation.Resolution.RESERVED, pending.resolution());
                pending.apply();
                pending.exposeApplied();
                assertThrows(IllegalStateException.class, pending::exposeApplied);
                assertThrows(IllegalStateException.class, pending::apply);
                assertThrows(IllegalStateException.class, pending::canApply);
                assertEquals(60, energy.storedEnergy());
                pending.rollback();
                assertThrows(IllegalStateException.class, pending::exposeApplied);
                assertThrows(IllegalStateException.class, pending::rollback);
                assertThrows(IllegalStateException.class, pending::complete);
                assertEquals(70, energy.storedEnergy());
            });
        } finally {
            fence.release();
        }
        var ordinary = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 10));
        energy.context().lock();
        try {
            ordinary.apply();
            assertThrows(IllegalStateException.class, ordinary::exposeApplied, "An ordinary lock cannot authorize sequential exposure");
            assertEquals(ResourceReservation.Resolution.APPLIED, ordinary.resolution());
            ordinary.complete();
            assertThrows(IllegalStateException.class, ordinary::exposeApplied);
            assertEquals(ResourceReservation.Resolution.COMPLETED, ordinary.resolution());
        } finally {
            energy.context().unlock();
        }
        assertEquals(60, energy.storedEnergy());
    }

    @Test
    void anExposedTokenPinsItsOriginalFenceAndCannotSettleOrQueryThroughAForeignScope() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(70);
        var token = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        var original = fence(energy);
        var foreignEnergy = new EnergyBuffer(100, () -> {
        });
        var foreign = fence(foreignEnergy);
        try {
            original.access(() -> {
                token.apply();
                token.exposeApplied();
            });
            assertThrows(IllegalStateException.class, original::release, "The exposed reservation keeps the original fence retained");
            assertThrows(IllegalStateException.class, energy.context()::lock);
            foreign.access(() -> {
                assertThrows(IllegalStateException.class, token::resolution);
                assertThrows(IllegalStateException.class, token::complete);
                assertThrows(IllegalStateException.class, token::rollback);
                assertThrows(IllegalStateException.class, token::exposeApplied);
            });
            original.access(() -> {
                assertEquals(ResourceReservation.Resolution.APPLIED, token.resolution());
                assertEquals(50, energy.storedEnergy());
                token.rollback();
                assertEquals(ResourceReservation.Resolution.ROLLED_BACK, token.resolution());
            });
        } finally {
            foreign.release();
            original.release();
        }
        assertEquals(70, energy.storedEnergy());
    }

    @Test
    void appliedCleanupSurvivesLoweredLimitsAndInvalidationAndPendingWorkCannotApply() {
        for (boolean complete : new boolean[]{false, true}) {
            var energy = new EnergyBuffer(100, () -> {
            });
            energy.restore(80);
            var token = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
            var fence = fence(energy);
            try {
                fence.access(() -> {
                    token.apply();
                    token.exposeApplied();
                    var pending = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 10));
                    energy.setTransferLimits(0, 0);
                    assertFalse(pending.canApply());
                    assertThrows(IllegalStateException.class, pending::apply);
                    pending.rollback();
                    energy.invalidate();
                    assertEquals(ReservationResult.Status.INVALID_STORAGE, energy.reserveReceiveEnergy(energy.energySnapshot(), 1).status());
                    if (complete) {
                        token.complete();
                    } else {
                        token.rollback();
                    }
                    assertEquals(complete ? ResourceReservation.Resolution.COMPLETED : ResourceReservation.Resolution.ROLLED_BACK, token.resolution());
                });
            } finally {
                fence.release();
            }
            assertEquals(complete ? 60 : 80, energy.savedEnergy());
            assertFalse(energy.isValid());
        }
    }

    @Test
    void exposureDoesNotPublishIntermediateEnergyToExternalReadersOrChangeNotifications() throws Exception {
        int[] notifications = {0};
        var energy = new EnergyBuffer(100, () -> notifications[0]++);
        energy.restore(80);
        var token = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 60));
        int before = notifications[0];
        var fence = fence(energy);
        var entered = new CountDownLatch(1);
        var reader = new FutureTask<>(() -> {
            entered.countDown();
            return energy.savedEnergy();
        });
        var thread = Thread.ofPlatform().daemon().unstarted(reader);
        try {
            fence.access(() -> {
                token.apply();
                token.exposeApplied();
            });
            thread.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            assertFalse(reader.isDone());
            assertThrows(IllegalStateException.class, fence::release);
            assertEquals(before, notifications[0]);
            fence.access(token::rollback);
            assertFalse(reader.isDone(), "Terminal compensation alone does not release external isolation");
        } finally {
            fence.release();
        }
        assertEquals(80, reader.get(5, TimeUnit.SECONDS));
        thread.join(5000);
        assertFalse(thread.isAlive());
        assertEquals(before + 1, notifications[0]);
    }

    @Test
    void ordinaryTerminalCleanupReleasesTheUnusedExposureRevisionCredit() {
        var energy = new EnergyBuffer(100, () -> {
        });
        energy.restore(60);
        long initialRevision = energy.energySnapshot().revision();
        var ordinary = accepted(energy.reserveExtractEnergy(energy.energySnapshot(), 20));
        energy.context().lock();
        try {
            ordinary.apply();
            ordinary.complete();
        } finally {
            energy.context().unlock();
        }
        assertEquals(initialRevision + 3, energy.energySnapshot().revision());
        var pending = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 10));
        energy.context().lock();
        try {
            pending.rollback();
        } finally {
            energy.context().unlock();
        }
        assertEquals(initialRevision + 5, energy.energySnapshot().revision());
        var staged = accepted(energy.reserveReceiveEnergy(energy.energySnapshot(), 15));
        var fence = fence(energy);
        try {
            fence.access(() -> {
                staged.apply();
                staged.exposeApplied();
                staged.complete();
            });
        } finally {
            fence.release();
        }
        assertEquals(initialRevision + 9, energy.energySnapshot().revision());
        energy.restore(70);
        assertEquals(70, energy.storedEnergy());
    }
}
