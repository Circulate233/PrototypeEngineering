package com.circulation.prototype_eng.api.machine.lifecycle;

import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;

import java.util.List;
import java.util.Objects;

/**
 * Ordered, thread-safe registrations for machine lifecycle listeners.
 * Publishers capture one {@link #snapshot()} per publication, so changes made during a callback
 * take effect on the next publication. Callbacks run outside this registry's lock.
 */
public final class MachineLifecycleRegistry {
    public static final MachineLifecycleRegistry GLOBAL = new MachineLifecycleRegistry();

    private final ObjectArrayList<Entry> entries = new ObjectArrayList<>();
    private volatile List<MachineLifecycleListener> snapshot = List.of();

    /**
     * Registers a listener after the existing listeners.
     *
     * @throws IllegalArgumentException if the same listener object already has an active registration
     */
    public synchronized Subscription register(MachineLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener");
        for (var entry : entries) {
            if (entry.listener == listener) {
                throw new IllegalArgumentException("Machine lifecycle listener is already registered: "
                    + listener.getClass().getName());
            }
        }
        var entry = new Entry(listener);
        entries.add(entry);
        publishSnapshot();
        return new Subscription(this, entry);
    }

    /**
     * Removes the active registration for the listener by object identity.
     */
    public synchronized boolean unregister(MachineLifecycleListener listener) {
        Objects.requireNonNull(listener, "listener");
        for (var entry : entries) {
            if (entry.listener == listener) {
                return remove(entry);
            }
        }
        return false;
    }

    /**
     * Returns an immutable registration-order snapshot that remains valid after cancellation.
     */
    public List<MachineLifecycleListener> snapshot() {
        return snapshot;
    }

    /**
     * Actual hosts publish this after worker exit when a saved controller never had an instance.
     * Cleanup attempts the captured listeners once and preserves the first failure.
     */
    public void unassembledControllerBroken(MachineLifecycleHost host, PersistentMachineIdentity ownership) {
        Objects.requireNonNull(host).requireServerThread();
        var event = new MachineLifecycleEvent.UnassembledControllerBroken(host, host.identity(), ownership);
        Throwable failure = null;
        for (var listener : snapshot) {
            try {
                listener.onUnassembledControllerBroken(event);
            } catch (RuntimeException | Error cleanup) {
                if (failure == null) {
                    failure = cleanup;
                } else if (failure != cleanup) {
                    failure.addSuppressed(cleanup);
                }
            }
        }
        if (failure instanceof RuntimeException runtime) {
            throw runtime;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private synchronized boolean remove(Entry entry) {
        if (!entry.active) {
            return false;
        }
        entries.remove(entry);
        entry.active = false;
        publishSnapshot();
        return true;
    }

    private void publishSnapshot() {
        var listeners = new ObjectArrayList<MachineLifecycleListener>(entries.size());
        for (var entry : entries) {
            listeners.add(entry.listener);
        }
        snapshot = List.copyOf(listeners);
    }

    /**
     * A cancellable handle for one exact registration, including after the listener is registered again.
     */
    public static final class Subscription implements AutoCloseable {
        private final MachineLifecycleRegistry registry;
        private final Entry entry;

        private Subscription(MachineLifecycleRegistry registry, Entry entry) {
            this.registry = registry;
            this.entry = entry;
        }

        public boolean active() {
            synchronized (registry) {
                return entry.active;
            }
        }

        /**
         * Returns true only for the call that removes this active registration.
         */
        public boolean cancel() {
            return registry.remove(entry);
        }

        @Override
        public void close() {
            cancel();
        }
    }

    private static final class Entry {
        private final MachineLifecycleListener listener;
        private boolean active = true;

        private Entry(MachineLifecycleListener listener) {
            this.listener = listener;
        }
    }
}
