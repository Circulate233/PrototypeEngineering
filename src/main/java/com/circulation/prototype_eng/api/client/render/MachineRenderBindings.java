package com.circulation.prototype_eng.api.client.render;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;

import java.util.Map;
import java.util.Objects;

/** Client-owner exact registrations. Closing a replaced handle cannot remove its successor. */
public final class MachineRenderBindings {
    private final Thread owner = Thread.currentThread();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, Entry<GeckoModelBinding>> machines = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, Entry<GeckoModelBinding>> components = new Object2ObjectLinkedOpenHashMap<>();
    private final Object2ObjectLinkedOpenHashMap<StructureKey, Entry<GeckoStructureBinding>> structures = new Object2ObjectLinkedOpenHashMap<>();
    private final ObjectArrayList<Listener> listeners = new ObjectArrayList<>();
    private long revision;
    private View view = new View(0, Map.of(), Map.of(), Map.of());

    public Registration bindMachine(ResourceLocation id, GeckoModelBinding binding) {
        return bind(machines, id, binding);
    }

    @SuppressWarnings("UnusedReturnValue") // Addons may retain a binding for their complete client lifetime.
    public Registration bindComponent(ResourceLocation id, GeckoModelBinding binding) {
        return bind(components, id, binding);
    }

    @SuppressWarnings("UnusedReturnValue")
    // Optional handle permits exact later revocation; dropping it retains registration.
    public Registration bindStructure(ResourceLocation machine, ResourceLocation structure, GeckoStructureBinding binding) {
        return bind(structures, new StructureKey(machine, structure), binding);
    }

    private <K, V> Registration bind(Map<K, Entry<V>> table, K key, V value) {
        requireOwner();
        Objects.requireNonNull(key);
        Objects.requireNonNull(value);
        long next = Math.incrementExact(revision);
        var entry = new Entry<>(value);
        var registration = new Registration(() -> {
            requireOwner();
            if (table.get(key) != entry) return;
            long nextRevision = Math.incrementExact(revision);
            table.remove(key);
            changed(nextRevision);
        });
        table.put(key, entry);
        changed(next);
        return registration;
    }

    public View view() {
        requireOwner();
        return view;
    }

    public boolean current(View captured) {
        requireOwner();
        return captured != null && view == captured;
    }

    public Registration onInvalidated(Runnable callback) {
        requireOwner();
        var entry = new Listener(Objects.requireNonNull(callback));
        listeners.add(entry);
        return new Registration(() -> {
            requireOwner();
            listeners.remove(entry);
        });
    }

    public void clear() {
        requireOwner();
        if (machines.isEmpty() && components.isEmpty() && structures.isEmpty()) return;
        long next = Math.incrementExact(revision);
        machines.clear();
        components.clear();
        structures.clear();
        changed(next);
    }

    private void changed(long next) {
        revision = next;
        view = new View(revision, values(machines), values(components), values(structures));
        Throwable failure = null;
        for (var entry : new ObjectArrayList<>(listeners)) {
            try {
                entry.callback.run();
            } catch (RuntimeException | Error caught) {
                if (failure == null) failure = caught;
                else if (failure != caught) failure.addSuppressed(caught);
            }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }

    private static <K, V> Map<K, V> values(Map<K, Entry<V>> entries) {
        var result = new Object2ObjectLinkedOpenHashMap<K, V>();
        entries.forEach((key, value) -> result.put(key, value.value));
        return Object2ObjectMaps.unmodifiable(result);
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Machine render bindings require the client owner");
    }

    public record StructureKey(ResourceLocation machine, ResourceLocation structure) {
        public StructureKey {
            Objects.requireNonNull(machine);
            Objects.requireNonNull(structure);
        }
    }

    /** Identity is the binding epoch; revision is a checked diagnostic sequence, never permission. */
    public static final class View {
        private final long revision;
        private final Map<ResourceLocation, GeckoModelBinding> machines;
        private final Map<ResourceLocation, GeckoModelBinding> components;
        private final Map<StructureKey, GeckoStructureBinding> structures;

        private View(long revision, Map<ResourceLocation, GeckoModelBinding> machines, Map<ResourceLocation, GeckoModelBinding> components,
                     Map<StructureKey, GeckoStructureBinding> structures) {
            this.revision = revision;
            this.machines = machines;
            this.components = components;
            this.structures = structures;
        }

        public long revision() {
            return revision;
        }

        public Map<ResourceLocation, GeckoModelBinding> machines() {
            return machines;
        }

        public Map<ResourceLocation, GeckoModelBinding> components() {
            return components;
        }

        public Map<StructureKey, GeckoStructureBinding> structures() {
            return structures;
        }
    }

    public static final class Registration implements AutoCloseable {
        private final Runnable close;

        private Registration(Runnable close) {
            this.close = close;
        }

        @Override
        public void close() {
            close.run();
        }
    }

    @SuppressWarnings("ClassCanBeRecord") // Entry identity protects replacements from an old handle's close.
    private static final class Entry<V> {
        private final V value;

        private Entry(V value) {
            this.value = value;
        }
    }

    @SuppressWarnings("ClassCanBeRecord") // Two equal callbacks still have independent subscription identities.
    private static final class Listener {
        private final Runnable callback;

        private Listener(Runnable callback) {
            this.callback = callback;
        }
    }
}
