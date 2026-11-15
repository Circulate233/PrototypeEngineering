package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.structure.ReloadableStructureHost;
import com.circulation.prototype_eng.api.structure.StructureCatalog;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.machine.ControllerStructureUpdates;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectLinkedOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * One real scheduler owner; registration identities never collapse external hosts by PN UUID.
 */
public final class StructureReloadCoordinator {
    private final Thread owner = Thread.currentThread();
    private final TaskScheduler scheduler;
    private final MachineTypeRegistry registry;
    private final Supplier<StructureCatalog> readCandidate;
    private final Consumer<Publication> publishClients;
    private final Reference2ObjectLinkedOpenHashMap<ControllerStructureUpdates, Registration> registrations = new Reference2ObjectLinkedOpenHashMap<>();
    private final Reference2ObjectLinkedOpenHashMap<MachineLifecycle, Loaded> loadedHosts = new Reference2ObjectLinkedOpenHashMap<>();
    private Publication publication;
    private boolean applying;
    private boolean closed;
    @Nullable
    private List<LoadedIdentity> capturedLoaded;

    public StructureReloadCoordinator(TaskScheduler scheduler, MachineTypeRegistry registry, Supplier<StructureCatalog> readCandidate,
                                      Consumer<Publication> publishClients) {
        this.scheduler = scheduler;
        this.registry = registry;
        this.readCandidate = readCandidate;
        this.publishClients = publishClients;
        publication = presentation(registry.structureCatalog(), UUID.randomUUID(), 0);
    }

    private static Throwable append(@Nullable Throwable first, Throwable additional) {
        if (first == null) return additional;
        if (first != additional) first.addSuppressed(additional);
        return first;
    }

    private static Publication presentation(StructureCatalog catalog, UUID session, long sequence) {
        var view = StructureCatalogView.capture(catalog, session, sequence);
        return new Publication(catalog, view, StructureCatalogCodec.encode(view));
    }

    @SuppressWarnings("resource") // The updates scheduler is borrowed; this table owns its registration handles.
    public Registration register(ControllerStructureUpdates updates) {
        requireOwner();
        if (closed || applying || updates.scheduler() != scheduler || !updates.loaded())
            throw new IllegalStateException("Structure registration requires this live idle owner");
        if (registrations.containsKey(updates))
            throw new IllegalArgumentException("Structure updates already registered");
        if (registered(updates.lifecycle()))
            throw new IllegalArgumentException("This exact lifecycle already owns structure updates");
        // Reject unsupported hosts before they could silently miss later replacement.
        updates.requireReloadable();
        var registration = new Registration(this, updates);
        registrations.put(updates, registration);
        return registration;
    }

    @SuppressWarnings("resource") // Removal retires the exact handle; close delegates back to this operation.
    public void unregister(ControllerStructureUpdates updates) {
        requireOwner();
        var registration = registrations.remove(updates);
        if (registration != null) registration.active = false;
    }

    public void unloaded(MachineLifecycle lifecycle) {
        requireOwner();
        loadedHosts.remove(lifecycle);
        capturedLoaded = null;
        for (var entries = registrations.reference2ObjectEntrySet().fastIterator(); entries.hasNext(); ) {
            var entry = entries.next();
            if (entry.getKey().lifecycle() == lifecycle) {
                entry.getValue().active = false;
                entries.remove();
            }
        }
    }

    /**
     * Actual Loaded callback records the identity only; it must not enter a still-held load gate.
     */
    @SuppressWarnings("DataFlowIssue") // The actual machine Loaded callback runs after its lifecycle is attached.
    public void loaded(MachineInstance machine, ReloadableStructureHost host) {
        requireOwner();
        if (closed) return;
        var lifecycle = machine.lifecycle();
        if (lifecycle.host() != host)
            throw new IllegalArgumentException("Loaded structure host differs from its lifecycle");
        var existing = loadedHosts.get(lifecycle);
        if (existing != null && existing.machine == machine && existing.host == host) return;
        loadedHosts.put(lifecycle, new Loaded(machine, host));
        capturedLoaded = null;
    }

    /**
     * Constructor enrollment does not install definitions or change bindings, including before bad JSON.
     */
    @SuppressWarnings("resource") // Automatic registration handles belong to this coordinator until unload/close.
    public void enrollLoaded() {
        requireOwner();
        if (closed || applying)
            throw new IllegalStateException("Loaded enrollment requires a live idle structure owner");
        // Fastutil map entries retain table indexes; removing an earlier host can invalidate them.
        var elements7 = loadedSnapshot();
        for (int index7 = 0; index7 < elements7.size(); index7++) {
            var entry = elements7.get(index7);
            var lifecycle = entry.lifecycle();
            var loaded = entry.loaded();
            if (loadedHosts.get(lifecycle) != loaded) continue;
            if (lifecycle.state() != MachineLifecycle.State.LOADED || lifecycle.retired() || !loaded.host.structureLifecycleCurrent(lifecycle)) {
                unloaded(lifecycle);
                continue;
            }
            if (!registered(lifecycle)) {
                var updates = new ControllerStructureUpdates(loaded.machine, loaded.host, scheduler,
                    () -> loaded.host.structureLifecycleCurrent(lifecycle), () -> {
                });
                // Native server construction attaches through the session. Detached logical owners
                // use this same enrollment implementation without a Minecraft server fixture.
                if (!registrations.containsKey(updates)) register(updates);
                loaded.automatic = updates;
            }
            if (loaded.automatic != null && updates(lifecycle) != loaded.automatic) loaded.automatic = null;
        }
    }

    /**
     * Only service-created updates are driven here; existing TE/addon owners keep their own cadence.
     */
    public void tickLoaded(long now) {
        requireOwner();
        scheduler.runAtBoundary(() -> {
            if (closed) return;
            enrollLoaded();
            var elements1 = loadedSnapshot();
            for (int index1 = 0; index1 < elements1.size(); index1++) {
                var captured = elements1.get(index1);
                var loaded = captured.loaded();
                var automatic = loaded.automatic;
                if (automatic == null || !automatic.loaded()) continue;
                var catalog = registry.structureCatalog();
                if (!automatic.catalogCurrent(catalog)) {
                    var reload = automatic.prepareReload(catalog);
                    if (reload != null) {
                        automatic.revokeReload(reload);
                        automatic.pauseReload(reload);
                        automatic.installReload(reload);
                    }
                }
                automatic.enqueueTick(now);
            }
        });
    }

    private List<LoadedIdentity> loadedSnapshot() {
        if (capturedLoaded == null) {
            var captured = new ObjectArrayList<LoadedIdentity>(loadedHosts.size());
            for (var entry : loadedHosts.reference2ObjectEntrySet())
                captured.add(new LoadedIdentity(entry.getKey(), entry.getValue()));
            capturedLoaded = captured;
        }
        return capturedLoaded;
    }

    public boolean registered(MachineLifecycle lifecycle) {
        requireOwner();
        for (var updates : registrations.keySet())
            if (updates.lifecycle() == lifecycle && updates.loaded()) return true;
        return false;
    }

    public @Nullable ControllerStructureUpdates updates(MachineLifecycle lifecycle) {
        requireOwner();
        for (var updates : registrations.keySet())
            if (updates.lifecycle() == lifecycle && updates.loaded()) return updates;
        return null;
    }

    /**
     * Request may originate off-thread; no parsing, host access or execution occurs there.
     */
    public void reload(Consumer<Result> completed) {
        scheduler.runAtBoundary(() -> apply(completed));
    }

    private void apply(Consumer<Result> completed) {
        requireOwner();
        if (closed) {
            completed.accept(new Result(false, publication.view().sequence(), new IllegalStateException("Server structure session closed")));
            return;
        }
        if (applying) throw new IllegalStateException("Reentrant structure reload");
        enrollLoaded();
        applying = true;
        try {
            MachineTypeRegistry.Replacement replacement;
            Publication next;
            var batch = new ObjectArrayList<Prepared>();
            try {
                replacement = registry.prepareReplacement(readCandidate.get());
                next = presentation(replacement.catalog(), publication.view().session(), Math.incrementExact(publication.view().sequence()));

                var elements = new ObjectArrayList<>(registrations.values());
                for (int index6 = 0; index6 < elements.size(); index6++) {
                    var registration = elements.get(index6);
                    if (!registration.active || !registration.updates.loaded()) {
                        unregister(registration.updates);
                        continue;
                    }
                    var reload = registration.updates.prepareReload(replacement.catalog());
                    if (reload != null) batch.add(new Prepared(registration, reload));
                }

                if (!registry.replacementCurrent(replacement))
                    throw new IllegalStateException("Runtime changed during reload preflight");
                for (int index3 = 0; index3 < batch.size(); index3++) {
                    var prepared = batch.get(index3);
                    if (!current(prepared) || !prepared.registration.updates.reloadCurrent(prepared.reload))
                        throw new IllegalStateException("Host changed during reload preflight");
                }
            } catch (RuntimeException failure) {
                completed.accept(new Result(false, publication.view().sequence(), failure));
                return;
            }
            // Configuration setters assign only. Old request identities are revoked before any new snapshot can publish.
            for (int index2 = 0; index2 < batch.size(); index2++) {
                var prepared = batch.get(index2);
                if (current(prepared)) prepared.registration.updates.revokeReload(prepared.reload);
            }
            Throwable failure = null;
            for (int index4 = 0; index4 < batch.size(); index4++) {
                var prepared = batch.get(index4);
                try {
                    prepared.registration.updates.pauseReload(prepared.reload);
                } catch (RuntimeException | Error caught) {
                    failure = append(failure, caught);
                }
            }
            if (failure != null) {
                completed.accept(new Result(false, publication.view().sequence(), failure));
                if (failure instanceof RuntimeException runtime) throw runtime;
                if (failure instanceof Error error) throw error;
            }
            try {
                registry.publishReplacement(replacement);
            } catch (RuntimeException | Error caught) {
                completed.accept(new Result(false, publication.view().sequence(), caught));
                throw caught;
            }
            for (int index5 = 0; index5 < batch.size(); index5++) {
                var prepared = batch.get(index5);
                if (current(prepared)) {
                    try {
                        prepared.registration.updates.installReload(prepared.reload);
                    } catch (RuntimeException | Error caught) {
                        failure = append(failure, caught);
                    }
                }
            }
            publication = next;
            // The committed directory must be broadcast even if an individual refresh callback failed.
            try {
                publishClients.accept(next);
            } catch (RuntimeException | Error caught) {
                failure = append(failure, caught);
            }
            completed.accept(new Result(true, next.view().sequence(), failure));
            if (failure instanceof RuntimeException runtime) throw runtime;
            if (failure instanceof Error error) throw error;
        } finally {
            applying = false;
        }
    }

    private boolean current(Prepared prepared) {
        var registration = prepared.registration;
        return registration.active && registrations.get(registration.updates) == registration && registration.updates.loaded();
    }

    public Publication publication() {
        requireOwner();
        return publication;
    }

    public void close() {
        requireOwner();
        if (applying) throw new IllegalStateException("Cannot close a structure owner during publication callbacks");
        closed = true;
        for (var registration : registrations.values()) registration.active = false;
        registrations.clear();
        loadedHosts.clear();
        capturedLoaded = null;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Structure reload requires its owning server thread");
    }

    private record Prepared(Registration registration, ControllerStructureUpdates.Reload reload) {
    }

    private record LoadedIdentity(MachineLifecycle lifecycle, Loaded loaded) {
    }

    private static final class Loaded {
        private final MachineInstance machine;
        private final ReloadableStructureHost host;
        @Nullable
        private ControllerStructureUpdates automatic;

        private Loaded(MachineInstance machine, ReloadableStructureHost host) {
            this.machine = machine;
            this.host = host;
        }
    }

    public record Result(boolean published, long sequence, @Nullable Throwable failure) {
    }

    public record Publication(StructureCatalog catalog, StructureCatalogView view, byte[] bytes) {
    }

    public static final class Registration implements AutoCloseable {
        private final StructureReloadCoordinator owner;
        private final ControllerStructureUpdates updates;
        private boolean active = true;

        private Registration(StructureReloadCoordinator owner, ControllerStructureUpdates updates) {
            this.owner = owner;
            this.updates = updates;
        }

        @Override
        public void close() {
            owner.requireOwner();
            if (active && owner.registrations.get(updates) == this) owner.unregister(updates);
        }
    }
}
