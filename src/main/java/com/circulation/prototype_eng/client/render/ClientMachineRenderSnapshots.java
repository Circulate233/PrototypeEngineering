package com.circulation.prototype_eng.client.render;

import java.util.List;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import com.circulation.prototype_eng.api.client.render.MachineRenderBindings;
import com.circulation.prototype_eng.api.client.structure.ClientStructureCatalog;
import com.circulation.prototype_eng.api.machine.render.MachineRenderSnapshot;
import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.machine.ControllerDisplayData;
import com.circulation.prototype_eng.machine.render.ControllerRenderChunks;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Client owner only. A complete fact can wait for its exact catalog, without retaining draw rights. */
@SuppressWarnings("resource") // This manager retains owner leases and retires them on replacement/unload/close.
public final class ClientMachineRenderSnapshots implements AutoCloseable {
    private final Thread thread = Thread.currentThread();
    private final ClientStructureCatalog catalog;
    private final MachineRenderBindings bindings;
    private final Object2ObjectOpenHashMap<BlockPos, Owner> owners = new Object2ObjectOpenHashMap<>();
    private final ObjectArrayList<Listener> listeners = new ObjectArrayList<>();
    private final AutoCloseable catalogListener;
    private final MachineRenderBindings.Registration bindingListener;
    private Object resourceEpoch = new Object();
    private boolean closed;
    @Nullable private List<Owner> capturedOwners;
    @Nullable private List<Listener> capturedListeners;

    public ClientMachineRenderSnapshots(ClientStructureCatalog catalog, MachineRenderBindings bindings) {
        this.catalog = Objects.requireNonNull(catalog);
        this.bindings = Objects.requireNonNull(bindings);
        catalogListener = catalog.onInvalidated(this::catalogChanged);
        bindingListener = bindings.onInvalidated(this::bindingsChanged);
    }

    @SuppressWarnings("ReturnOfInnerClass") // The returned lease must retain this exact manager and attachment.
    public Owner attach(Object world, Object tile, Object connection, BlockPos position, ResourceLocation type,
                        BooleanSupplier attached, Consumer<MachineRenderSnapshot> install) {
        requireOwner();
        Objects.requireNonNull(world);
        Objects.requireNonNull(tile);
        Objects.requireNonNull(connection);
        Objects.requireNonNull(type);
        Objects.requireNonNull(attached);
        Objects.requireNonNull(install);
        if (closed || !catalog.connectedTo(connection) || !attached.getAsBoolean())
            throw new IllegalStateException("Cannot attach a foreign or absent client render owner");
        var old = owners.get(position);
        if (old != null && old.world == world && old.tile == tile && old.connection == connection && old.type.equals(type))
            return old;
        var owner = new Owner(world, tile, connection, position.toImmutable(), type, attached, install);
        if (old != null) {
            old.active = false;
            old.view = null;
            old.assembler.retire();
        }
        owners.put(owner.position, owner);
        capturedOwners = null;
        invalidate();
        return owner;
    }

    public void receive(Owner owner, ControllerRenderChunks.Part part) {
        requireOwner();
        if (!attached(owner) || !owner.type.equals(part.display().type())) return;
        var directory = catalog.view();
        if (directory != null && (!directory.session().equals(part.catalog().session()) || part.catalog().sequence() < directory.sequence()))
            return;
        var previous = owner.assembler.header();
        MachineRenderSnapshot complete;
        ControllerRenderChunks.Part completedHeader;
        try {
            complete = owner.assembler.receive(part);
            completedHeader = owner.assembler.header();
        } catch (RuntimeException | Error failure) {
            owner.view = null;
            owner.waiting = null;
            try {
                invalidate();
            } catch (RuntimeException | Error cleanup) {
                if (cleanup != failure) failure.addSuppressed(cleanup);
            }
            throw failure;
        } finally {
            if (owner.assembler.header() != previous || owner.assembler.header() != null && owner.view != null &&
                !owner.assembler.header().display().equals(owner.view.snapshot.display())) {
                owner.view = null;
                owner.waiting = null;
                invalidate();
            }
        }
        if (complete == null || !attached(owner) || owner.assembler.header() != completedHeader || !owner.assembler.current(complete))
            return;
        if (!complete.controller().equals(owner.position))
            throw new IllegalArgumentException("Machine render payload belongs to a different controller position");
        owner.waiting = complete;
        publish(owner);
    }

    public void withdraw(Owner owner, ControllerDisplayData display) {
        requireOwner();
        if (!attached(owner) || !owner.type.equals(display.type())) return;
        if (!owner.assembler.withdraw(display)) return;
        owner.view = null;
        owner.waiting = null;
        invalidate();
    }

    public @Nullable View view(Object world, Object tile, BlockPos position) {
        requireOwner();
        var owner = owners.get(position);
        return owner != null && owner.world == world && owner.tile == tile && current(owner.view) ? owner.view : null;
    }

    public boolean current(@Nullable View captured) {
        requireOwner();
        return captured != null && !closed && attached(captured.owner) && captured.owner.view == captured &&
            captured.owner.assembler.current(captured.snapshot) &&
            catalog.current(captured.catalog) && bindings.current(captured.bindings) && resourceEpoch == captured.resourceEpoch;
    }

    public AutoCloseable onInvalidated(Runnable callback) {
        requireOwner();
        var entry = new Listener(Objects.requireNonNull(callback));
        listeners.add(entry);
        capturedListeners = null;
        return () -> {
            requireOwner();
            listeners.remove(entry);
            capturedListeners = null;
        };
    }

    public void release(Object world, Object tile, BlockPos position) {
        requireOwner();
        var owner = owners.get(position);
        if (owner != null && owner.world == world && owner.tile == tile) owner.close();
    }

    public void unloadWorld(Object world) {
        requireOwner();
        boolean removed = false;
        for (var owner : ownerSnapshot())
            if (owner.world == world) {
                owner.active = false;
                owner.view = null;
                owner.waiting = null;
                owner.assembler.retire();
                owners.remove(owner.position);
                capturedOwners = null;
                removed = true;
            }
        if (removed) invalidate();
    }

    public void invalidateResources() {
        requireOwner();
        resourceEpoch = new Object();
        bindingsChanged();
    }

    /** Tick removes unloaded/replaced owners even when vanilla has not called their close hook yet. */
    public void tick() {
        requireOwner();
        boolean removed = false;
        for (var owner : ownerSnapshot())
            if (!attached(owner)) {
                owner.active = false;
                owner.view = null;
                owner.waiting = null;
                owner.assembler.retire();
                owners.remove(owner.position);
                capturedOwners = null;
                removed = true;
            }
        if (removed) invalidate();
    }

    private void catalogChanged() {
        requireOwner();
        // Revoke all prior views before consumer cleanup. Partial directory data never calls this.
        for (var owner : owners.values()) owner.view = null;
        invalidate();
        for (var owner : ownerSnapshot()) {
            if (!catalog.connectedTo(owner.connection)) {
                owner.close();
                continue;
            }
            publish(owner);
        }
    }

    private void bindingsChanged() {
        requireOwner();
        for (var owner : owners.values()) owner.view = null;
        invalidate();
        for (var owner : ownerSnapshot()) publish(owner);
    }

    private void publish(Owner owner) {
        var snapshot = owner.waiting != null ? owner.waiting : owner.fact;
        var directory = catalog.view();
        var header = owner.assembler.header();
        if (!attached(owner) || snapshot == null || header == null || !header.display().equals(snapshot.display()) ||
            !owner.assembler.current(snapshot) ||
            directory == null || !directory.session().equals(snapshot.catalog().session()) || directory.sequence() != snapshot.catalog().sequence())
            return;
        // Capture every permission before the TE install callback and reject its reentrant replacement.
        var next = new View(owner, snapshot, directory, bindings.view(), resourceEpoch);
        if (owner.waiting != null) {
            // Consume pending before callbacks; only a successful, still-current installation becomes fact.
            var previousFact = owner.fact;
            owner.waiting = null;
            owner.install.accept(snapshot);
            if (!attached(owner) || owner.fact != previousFact || owner.assembler.header() != header || !owner.assembler.current(snapshot))
                return;
            owner.fact = snapshot;
        }
        if (attached(owner) && owner.fact == snapshot && owner.assembler.header() == header &&
            owner.assembler.current(snapshot) &&
            catalog.current(directory) && bindings.current(next.bindings) && resourceEpoch == next.resourceEpoch)
            owner.view = next;
    }

    private boolean attached(Owner owner) {
        return !closed && owner.active && owners.get(owner.position) == owner && catalog.connectedTo(owner.connection) && owner.attached.getAsBoolean();
    }

    private List<Owner> ownerSnapshot() {
        if (capturedOwners == null) capturedOwners = ObjectLists.unmodifiable(new ObjectArrayList<>(owners.values()));
        return capturedOwners;
    }

    private List<Listener> listenerSnapshot() {
        if (capturedListeners == null) capturedListeners = ObjectLists.unmodifiable(new ObjectArrayList<>(listeners));
        return capturedListeners;
    }

    private void invalidate() {
        Throwable failure = null;
        for (var entry : listenerSnapshot()) {
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

    @Override
    public void close() {
        requireOwner();
        if (closed) return;
        closed = true;
        for (var owner : owners.values()) {
            owner.active = false;
            owner.view = null;
            owner.waiting = null;
            owner.assembler.retire();
        }
        owners.clear();
        capturedOwners = null;
        Throwable failure = null;
        try {
            invalidate();
        } catch (RuntimeException | Error caught) {
            failure = caught;
        }
        try {
            catalogListener.close();
        } catch (Exception | Error caught) {
            if (failure == null) failure = caught;
            else if (failure != caught) failure.addSuppressed(caught);
        }
        try {
            bindingListener.close();
        } catch (RuntimeException | Error caught) {
            if (failure == null) failure = caught;
            else if (failure != caught) failure.addSuppressed(caught);
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
        if (failure != null) throw new IllegalStateException("Cannot close client render subscription", failure);
    }

    private void requireOwner() {
        if (Thread.currentThread() != thread)
            throw new IllegalStateException("Client machine render snapshots require their owner thread");
    }

    public static final class View {
        private final Owner owner;
        private final MachineRenderSnapshot snapshot;
        private final StructureCatalogView catalog;
        private final MachineRenderBindings.View bindings;
        private final Object resourceEpoch;

        private View(Owner owner, MachineRenderSnapshot snapshot, StructureCatalogView catalog, MachineRenderBindings.View bindings, Object resourceEpoch) {
            this.owner = owner;
            this.snapshot = snapshot;
            this.catalog = catalog;
            this.bindings = bindings;
            this.resourceEpoch = resourceEpoch;
        }

        public MachineRenderSnapshot snapshot() {
            return snapshot;
        }

        public MachineRenderBindings.View bindings() {
            return bindings;
        }
    }

    @SuppressWarnings("ClassCanBeRecord") // Subscription identity distinguishes two registrations of one Runnable.
    private static final class Listener {
        private final Runnable callback;

        private Listener(Runnable callback) {
            this.callback = callback;
        }
    }

    public final class Owner implements AutoCloseable {
        private final Object world, tile, connection;
        private final BlockPos position;
        private final ResourceLocation type;
        private final BooleanSupplier attached;
        private final Consumer<MachineRenderSnapshot> install;
        private final ControllerRenderChunks.Assembler assembler = new ControllerRenderChunks.Assembler();
        private boolean active = true;
        @Nullable
        private MachineRenderSnapshot fact, waiting;
        @Nullable
        private View view;

        private Owner(Object world, Object tile, Object connection, BlockPos position, ResourceLocation type,
                      BooleanSupplier attached, Consumer<MachineRenderSnapshot> install) {
            this.world = world;
            this.tile = tile;
            this.connection = connection;
            this.position = position;
            this.type = type;
            this.attached = attached;
            this.install = install;
        }

        @Override
        public void close() {
            requireOwner();
            if (!active) return;
            active = false;
            view = null;
            waiting = null;
            assembler.retire();
            if (owners.get(position) == this) {
                owners.remove(position);
                capturedOwners = null;
                invalidate();
            }
        }
    }
}
