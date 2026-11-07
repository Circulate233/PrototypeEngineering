package com.circulation.prototype_eng.api.machine.identity;

import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceLinkedOpenHashSet;

import java.util.Objects;
import java.util.UUID;

/**
 * Server owner-thread registrations of persistent identity for loaded runtime generations.
 * The host checks actual world and tile validity before registration. This registry does not inspect a world,
 * resolve teams, discover network centers, or publish work permission or budgets.
 */
public final class MachineIdentityRegistry implements AutoCloseable {
    private final Runnable ownerCheck;
    private final Reference2ObjectOpenHashMap<Object, Long2ObjectOpenHashMap<Registration>> byWorld = new Reference2ObjectOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<Object, Registration> byRuntime = new Reference2ObjectOpenHashMap<>();
    private final Object2ObjectOpenHashMap<UUID, ReferenceLinkedOpenHashSet<Registration>> byMachine = new Object2ObjectOpenHashMap<>();
    private final ReferenceLinkedOpenHashSet<Registration> registrations = new ReferenceLinkedOpenHashSet<>();
    private long nextLoadOrder;
    private boolean closed;

    public MachineIdentityRegistry(Runnable ownerCheck) {
        this.ownerCheck = Objects.requireNonNull(ownerCheck, "ownerCheck");
    }

    /**
     * Replaces another runtime at the exact world object and block position, regardless of machine type.
     * A currently registered runtime may repeat only its identical saved identity and location.
     */
    public Registration register(PersistentMachineIdentity identity, MachineIdentity location, Object worldIdentity, Object runtimeIdentity) {
        ownerCheck.run();
        if (closed) {
            throw new IllegalStateException("Machine identity registry is closed");
        }
        Objects.requireNonNull(identity, "identity");
        Objects.requireNonNull(location, "location");
        Objects.requireNonNull(worldIdentity, "worldIdentity");
        Objects.requireNonNull(runtimeIdentity, "runtimeIdentity");
        var existingRuntime = byRuntime.get(runtimeIdentity);
        if (existingRuntime != null) {
            if (!existingRuntime.identity.equals(identity) || !existingRuntime.location.equals(location) || existingRuntime.worldIdentity != worldIdentity) {
                throw new IllegalArgumentException("Registered runtime identity or location changed");
            }
            return existingRuntime;
        }
        if (nextLoadOrder == Long.MAX_VALUE) {
            throw new IllegalStateException("Machine registration load order exhausted");
        }

        long position = location.controllerPos().toLong();
        var locations = byWorld.get(worldIdentity);
        var previous = locations == null ? null : locations.get(position);
        if (previous != null) {
            remove(previous);
        }
        var registration = new Registration(this, identity, location, worldIdentity, runtimeIdentity, nextLoadOrder++);
        byWorld.computeIfAbsent(worldIdentity, _ -> new Long2ObjectOpenHashMap<>()).put(position, registration);
        byRuntime.put(runtimeIdentity, registration);
        registrations.add(registration);
        var machineRegistrations = byMachine.computeIfAbsent(identity.machineId(), _ -> new ReferenceLinkedOpenHashSet<>());
        machineRegistrations.add(registration);
        updateUniqueness(machineRegistrations);
        registration.current = true;
        return registration;
    }

    /** Removes only this exact generation; closing a replaced or already removed registration is harmless. */
    public void unregister(Registration registration) {
        ownerCheck.run();
        Objects.requireNonNull(registration, "registration");
        if (registration.registry != this) {
            throw new IllegalArgumentException("Registration belongs to another machine identity registry");
        }
        if (registration.current) {
            remove(registration);
        }
    }

    /** Frozen membership in increasing load order; individual registration state remains live and revocable. */
    public ObjectList<Registration> uniqueRegistrations() {
        ownerCheck.run();
        var result = new ObjectArrayList<Registration>(registrations.size());
        for (var registration : registrations) {
            if (registration.unique) {
                result.add(registration);
            }
        }
        return ObjectLists.unmodifiable(result);
    }

    /** Permanently revokes all registrations. A later server must create a new registry. */
    @Override
    public void close() {
        ownerCheck.run();
        if (closed) {
            return;
        }
        closed = true;
        for (var registration : registrations) {
            registration.current = false;
            registration.unique = false;
        }
        registrations.clear();
        byWorld.clear();
        byRuntime.clear();
        byMachine.clear();
    }

    @SuppressWarnings("resource") // Registrations are revoked here; removed/remaining index values must not be closed again.
    private void remove(Registration registration) {
        registration.current = false;
        registration.unique = false;
        registrations.remove(registration);
        byRuntime.remove(registration.runtimeIdentity);
        var locations = byWorld.get(registration.worldIdentity);
        long position = registration.location.controllerPos().toLong();
        if (locations.get(position) == registration) {
            locations.remove(position);
        }
        if (locations.isEmpty()) {
            byWorld.remove(registration.worldIdentity);
        }
        var machineRegistrations = byMachine.get(registration.identity.machineId());
        machineRegistrations.remove(registration);
        if (machineRegistrations.isEmpty()) {
            byMachine.remove(registration.identity.machineId());
        } else {
            updateUniqueness(machineRegistrations);
        }
    }

    @SuppressWarnings("resource") // These are borrowed live registrations; updating uniqueness must not unregister them.
    private static void updateUniqueness(ReferenceLinkedOpenHashSet<Registration> machineRegistrations) {
        if (machineRegistrations.size() == 1) {
            machineRegistrations.first().unique = true;
        } else if (machineRegistrations.size() == 2) {
            for (var registration : machineRegistrations) {
                registration.unique = false;
            }
        }
        // Larger duplicate groups already contain only non-unique entries; each new entry starts non-unique.
    }

    /** Exact loaded generation; a unique UUID is necessary identity evidence, never a team or work authorization. */
    public static final class Registration implements AutoCloseable {
        private final MachineIdentityRegistry registry;
        private final PersistentMachineIdentity identity;
        private final MachineIdentity location;
        private final Object worldIdentity;
        private final Object runtimeIdentity;
        private final long loadOrder;
        private volatile boolean current;
        private volatile boolean unique;

        private Registration(MachineIdentityRegistry registry, PersistentMachineIdentity identity, MachineIdentity location, Object worldIdentity, Object runtimeIdentity, long loadOrder) {
            this.registry = registry;
            this.identity = identity;
            this.location = location;
            this.worldIdentity = worldIdentity;
            this.runtimeIdentity = runtimeIdentity;
            this.loadOrder = loadOrder;
        }

        public PersistentMachineIdentity identity() {
            return identity;
        }

        public MachineIdentity location() {
            return location;
        }

        @SuppressWarnings("unused") // External discovery captures this world token without reading a World on workers.
        public Object worldIdentity() {
            return worldIdentity;
        }

        public Object runtimeIdentity() {
            return runtimeIdentity;
        }

        @SuppressWarnings("unused") // PN center/device selection uses this stable registration-order tie-break.
        public long loadOrder() {
            return loadOrder;
        }

        /** May be read from any thread; all registry mutations remain on the server owner thread. */
        public boolean current() {
            return current;
        }

        /** May be read from any thread and never confers saved or current execution permission. */
        public boolean unique() {
            return current && unique;
        }

        @Override
        public void close() {
            registry.unregister(this);
        }
    }
}
