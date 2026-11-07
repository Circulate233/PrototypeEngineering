package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.FutureTask;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MachineIdentityRegistryTest {
    @Test
    void copiedMachineUuidRevokesAllCopiesAndOnlyTheLastRemainingCopyRecovers() {
        var registry = registry();
        var world = new Object();
        var identity = identity(1);
        var first = registry.register(identity, location(1), world, new Object());
        var beforeCopy = registry.uniqueRegistrations();
        var unrelated = registry.register(identity(2), location(4), world, new Object());
        var second = registry.register(identity, location(2), world, new Object());
        var third = registry.register(identity, location(3), world, new Object());

        assertTrue(first.current());
        assertTrue(second.current());
        assertTrue(third.current());
        assertFalse(first.unique());
        assertFalse(second.unique());
        assertFalse(third.unique());
        assertEquals(List.of(unrelated), registry.uniqueRegistrations());
        assertEquals(List.of(first), beforeCopy);
        assertThrows(UnsupportedOperationException.class, () -> beforeCopy.add(second));

        second.close();
        assertFalse(second.current());
        assertFalse(second.unique());
        assertFalse(first.unique());
        assertFalse(third.unique());
        third.close();
        assertTrue(first.unique());
        assertEquals(List.of(first, unrelated), registry.uniqueRegistrations());
        assertSame(first, registry.register(identity, location(1), world, first.runtimeIdentity()));
    }

    @Test
    void differentMachineTypeAtTheSameWorldPositionReplacesTheGenerationAndLateUnloadCannotRemoveIt() {
        var registry = registry();
        var world = new Object();
        var first = registry.register(identity(1), location(1), world, new Object());
        var changedType = new MachineIdentity(new ResourceLocation("test", "replacement"), 0, new BlockPos(1, 2, 3));
        var second = registry.register(identity(2), changedType, world, new Object());

        assertFalse(first.current());
        assertFalse(first.unique());
        assertTrue(second.current());
        assertTrue(second.unique());
        first.close();
        registry.unregister(first);
        assertEquals(List.of(second), registry.uniqueRegistrations());
        assertSame(second, registry.register(identity(2), changedType, world, second.runtimeIdentity()));

        second.close();
        assertFalse(second.current());
        assertTrue(registry.uniqueRegistrations().isEmpty());
    }

    @Test
    void equalWorldAndRuntimeObjectsRemainSeparateByReferenceEvenAtTheSameCoordinates() {
        var registry = registry();
        var first = registry.register(identity(1), location(1), new EqualToken("world"), new EqualToken("runtime"));
        var second = registry.register(identity(2), location(1), new EqualToken("world"), new EqualToken("runtime"));

        assertTrue(first.current());
        assertTrue(first.unique());
        assertTrue(second.current());
        assertTrue(second.unique());
        assertEquals(List.of(first, second), registry.uniqueRegistrations());
        first.close();
        assertTrue(second.current());
        assertEquals(List.of(second), registry.uniqueRegistrations());
    }

    @Test
    void repeatedRuntimeMustMatchEveryIdentityFieldAndFailurePreservesBothMappings() {
        try (var registry = registry()) {
            var world = new Object();
            var runtime = new Object();
            var identity = identity(1);
            var first = registry.register(identity, location(1), world, runtime);
            var other = registry.register(identity(2), location(2), world, new Object());

            assertSame(first, registry.register(identity, location(1), world, runtime));
            assertThrows(IllegalArgumentException.class, () -> registry.register(identity(3), location(1), world, runtime));
            var changedOwner = new PersistentMachineIdentity(identity.machineId(), new UUID(0, 99));
            assertThrows(IllegalArgumentException.class, () -> registry.register(changedOwner, location(1), world, runtime));
            var changedType = new MachineIdentity(new ResourceLocation("test", "other"), 0, new BlockPos(1, 2, 3));
            assertThrows(IllegalArgumentException.class, () -> registry.register(identity, changedType, world, runtime));
            var changedDimension = new MachineIdentity(location(1).machineType(), 1, new BlockPos(1, 2, 3));
            assertThrows(IllegalArgumentException.class, () -> registry.register(identity, changedDimension, world, runtime));
            assertThrows(IllegalArgumentException.class, () -> registry.register(identity, location(2), world, runtime));
            assertThrows(IllegalArgumentException.class, () -> registry.register(identity, location(1), new Object(), runtime));

            assertTrue(first.current());
            assertTrue(first.unique());
            assertTrue(other.current());
            assertTrue(other.unique());
            assertEquals(List.of(first, other), registry.uniqueRegistrations());
            assertSame(first, registry.register(identity, location(1), world, runtime));
            assertSame(other, registry.register(identity(2), location(2), world, other.runtimeIdentity()));
        }
    }

    @Test
    void replacingOneDuplicatePreservesConflictUntilTheOriginalMachineHasOnlyOneLiveRegistration() {
        var registry = registry();
        var world = new Object();
        var first = registry.register(identity(1), location(1), world, new Object());
        var second = registry.register(identity(1), location(2), world, new Object());
        var replacement = registry.register(identity(1), location(1), world, new Object());

        assertFalse(first.current());
        assertFalse(second.unique());
        assertFalse(replacement.unique());
        first.close();
        assertFalse(second.unique());
        assertFalse(replacement.unique());
        second.close();
        assertTrue(replacement.unique());
        assertEquals(List.of(replacement), registry.uniqueRegistrations());
    }

    @Test
    void closePermanentlyRevokesUniqueAndConflictedGenerationsAndRejectsForeignUnregister() {
        var registry = registry();
        var world = new Object();
        var first = registry.register(identity(1), location(1), world, new Object());
        var second = registry.register(identity(1), location(2), world, new Object());
        var unique = registry.register(identity(2), location(3), world, new Object());
        var otherRegistry = registry();
        assertThrows(IllegalArgumentException.class, () -> otherRegistry.unregister(unique));
        assertTrue(unique.current());

        registry.close();
        for (var registration : List.of(first, second, unique)) {
            assertFalse(registration.current());
            assertFalse(registration.unique());
            registration.close();
        }
        registry.close();
        assertTrue(registry.uniqueRegistrations().isEmpty());
        assertThrows(IllegalStateException.class, () -> registry.register(identity(2), location(3), world, unique.runtimeIdentity()));
        assertThrows(IllegalStateException.class, () -> registry.register(identity(3), location(4), world, new Object()));
    }

    @Test
    void allMutationAndEnumerationRequireTheOwnerThreadWhileRegistrationStateCanBeReadByWorkers() throws Exception {
        var registry = registry();
        var world = new Object();
        var registration = registry.register(identity(1), location(1), world, new Object());
        var worker = new FutureTask<>(() -> {
            assertTrue(registration.current());
            assertTrue(registration.unique());
            assertThrows(IllegalStateException.class, () -> registry.register(identity(2), location(2), world, new Object()));
            assertThrows(IllegalStateException.class, () -> registry.unregister(registration));
            assertThrows(IllegalStateException.class, registration::close);
            assertThrows(IllegalStateException.class, registry::uniqueRegistrations);
            assertThrows(IllegalStateException.class, registry::close);
            return true;
        });
        Thread.ofPlatform().start(worker);
        assertTrue(worker.get());
        assertEquals(List.of(registration), registry.uniqueRegistrations());
        registration.close();
        assertTrue(registry.uniqueRegistrations().isEmpty());
    }

    private static MachineIdentityRegistry registry() {
        var owner = Thread.currentThread();
        return new MachineIdentityRegistry(() -> {
            if (Thread.currentThread() != owner) {
                throw new IllegalStateException("Not the server owner thread");
            }
        });
    }

    private static PersistentMachineIdentity identity(long machine) {
        return new PersistentMachineIdentity(new UUID(0, machine), new UUID(1, 1));
    }

    private static MachineIdentity location(int x) {
        return new MachineIdentity(new ResourceLocation("test", "machine"), 0, new BlockPos(x, 2, 3));
    }

    private record EqualToken(String value) {
    }
}
