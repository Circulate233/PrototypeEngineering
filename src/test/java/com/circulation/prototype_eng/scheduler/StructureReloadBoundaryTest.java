package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedCancellationToken.Reason;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedDeadline;
import com.circulation.prototype_eng.api.scheduler.planned.PlannedExecution;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.structure.reload.StructureReloadCoordinator;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StructureReloadBoundaryTest {
    @Test void pendingCleanupCancelsIndependentlyOfQueuedReloadAndPublishesOnlyAfterConfirmedFinish() {
        var pending = new Pending(); var offered = new AtomicBoolean();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1, (_, _) -> offered.compareAndSet(false, true) ? pending : null, Duration.ofSeconds(1))) {
            var registry = registry(); var before = registry.structureCatalog(); var broadcasts = new AtomicInteger();
            var coordinator = new StructureReloadCoordinator(scheduler, registry, () -> new StructureCatalog(List.of()), _ -> broadcasts.incrementAndGet());
            scheduler.tick(); assertNotNull(pending.cancelled); assertFalse(pending.finished());
            coordinator.reload(result -> assertTrue(result.published())); assertSame(before, registry.structureCatalog());
            scheduler.tick(); assertSame(before, registry.structureCatalog()); assertEquals(0, broadcasts.get());
            pending.confirmed = true; scheduler.tick(); assertNotSame(before, registry.structureCatalog()); assertEquals(1, broadcasts.get());
            assertTrue(pending.exitProofs > 0); coordinator.close();
        }
    }

    @Test void failedPhysicalExitRetainsTheQueuedReloadUntilRealExitAndCleanupAreProven() {
        var pending = new Pending(); pending.exited = false; var offered = new AtomicBoolean();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), 1, 1, (_, _) -> offered.compareAndSet(false, true) ? pending : null, Duration.ofSeconds(1))) {
            var registry = registry(); var before = registry.structureCatalog(); var broadcasts = new AtomicInteger();
            var coordinator = new StructureReloadCoordinator(scheduler, registry, () -> new StructureCatalog(List.of()), _ -> broadcasts.incrementAndGet());
            assertThrows(IllegalStateException.class, scheduler::tick);
            coordinator.reload(result -> assertTrue(result.published())); assertSame(before, registry.structureCatalog());
            pending.confirmed = true;
            assertThrows(IllegalStateException.class, scheduler::tick); assertSame(before, registry.structureCatalog()); assertEquals(0, broadcasts.get());
            pending.exited = true;
            assertThrows(IllegalStateException.class, scheduler::tick);
            assertNotSame(before, registry.structureCatalog()); assertEquals(1, broadcasts.get()); assertTrue(pending.exitProofs > 0);
            coordinator.close();
        }
    }

    private static MachineTypeRegistry registry() {
        var registry = new MachineTypeRegistry(); registry.freeze(); registry.resolveRuntime(new StructureCatalog(List.of()), List.of()); return registry;
    }
    private static final class Pending implements PlannedExecution {
        private boolean confirmed;
        private boolean exited = true;
        private Reason cancelled;
        private int exitProofs;
        @Override public boolean advance(PlannedDeadline deadline) { return confirmed; }
        @Override public void cancel(Reason reason) { if (cancelled == null) cancelled = reason; }
        @Override public void awaitExit() { if (!exited) throw new IllegalStateException("Source has not exited"); exitProofs++; }
        @Override public boolean finished() { return confirmed; }
    }
}
