package com.circulation.prototype_eng.api.client.structure;

import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import com.circulation.prototype_eng.structure.reload.StructureCatalogCodec;
import com.circulation.prototype_eng.structure.reload.StructureCatalogPacket;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import org.jetbrains.annotations.Nullable;
import java.util.Arrays;
import java.util.Objects;

/** Authoritative client-thread directory; local config and incomplete network data never replace it. */
public final class ClientStructureCatalog {
    private final Thread owner = Thread.currentThread();
    @Nullable private Object connection;
    @Nullable private StructureCatalogView view;
    @Nullable private Pending pending;
    private final ObjectArrayList<Invalidation> invalidations = new ObjectArrayList<>();

    /** Detached, screen-owned world selection; never installed in the connected server directory. */
    public static ClientStructureCatalog local(StructureCatalogView view) {
        var catalog = new ClientStructureCatalog();
        catalog.view = Objects.requireNonNull(view);
        return catalog;
    }

    public void connected(Object connection) {
        requireOwner(); Objects.requireNonNull(connection);
        if (this.connection == connection) return;
        this.connection = connection; pending = null; view = null; invalidate();
    }
    public void disconnected(Object connection) {
        requireOwner(); if (this.connection != connection) return; this.connection = null; pending = null; view = null; invalidate();
    }
    public @Nullable StructureCatalogView view() { requireOwner(); return view; }
    public boolean current(StructureCatalogView captured) { requireOwner(); return captured != null && view == captured; }
    public boolean connectedTo(Object candidate) { requireOwner(); return connection != null && connection == candidate; }

    /** A caller may hold a cache token only for this exact completed view, including session identity. */
    public AutoCloseable onInvalidated(Runnable callback) {
        requireOwner(); var entry = new Invalidation(Objects.requireNonNull(callback)); invalidations.add(entry);
        return () -> { requireOwner(); invalidations.remove(entry); };
    }

    public boolean receive(Object connection, StructureCatalogPacket packet) {
        requireOwner();
        if (!connectedTo(connection)) return false;
        if (view != null && (!view.session().equals(packet.session()) || packet.sequence() <= view.sequence())) return false;
        if (pending != null) {
            if (!pending.first.session().equals(packet.session())) return false;
            if (packet.sequence() < pending.first.sequence()) return false;
            if (packet.sequence() > pending.first.sequence()) pending = null;
        }
        if (pending == null) pending = new Pending(packet);
        var candidate = pending;
        if (candidate.first.totalBytes() != packet.totalBytes()) { pending = null; throw new IllegalArgumentException("Conflicting structure catalog chunk headers"); }
        byte[] payload = packet.bytes();
        var previous = candidate.parts[packet.index()];
        if (previous != null) {
            if (!Arrays.equals(previous, payload)) { pending = null; throw new IllegalArgumentException("Conflicting repeated structure catalog chunk"); }
            return false;
        }
        candidate.parts[packet.index()] = payload; candidate.received++;
        if (candidate.received != candidate.parts.length) return false;
        byte[] complete = new byte[candidate.first.totalBytes()]; int offset = 0;
        for (byte[] part : candidate.parts) { System.arraycopy(part, 0, complete, offset, part.length); offset += part.length; }
        pending = null;
        var next = StructureCatalogCodec.decode(complete);
        if (!next.session().equals(packet.session()) || next.sequence() != packet.sequence()) throw new IllegalArgumentException("Structure catalog payload generation differs from chunk header");
        view = next; invalidate(); return true;
    }

    private void invalidate() {
        Throwable failure = null;
        for (var callback : new ObjectArrayList<>(invalidations)) {
            try { callback.callback.run(); }
            catch (RuntimeException | Error caught) { if (failure == null) failure = caught; else if (failure != caught) failure.addSuppressed(caught); }
        }
        if (failure instanceof RuntimeException runtime) throw runtime;
        if (failure instanceof Error error) throw error;
    }
    private void requireOwner() { if (Thread.currentThread() != owner) throw new IllegalStateException("Client structure directory requires its owner thread"); }
    private static final class Pending {
        private final StructureCatalogPacket first;
        private final byte[][] parts;
        private int received;
        private Pending(StructureCatalogPacket first) { this.first = first; parts = new byte[first.chunks()][]; }
    }
    @SuppressWarnings("ClassCanBeRecord") // Each subscription needs identity even when its Runnable is shared.
    private static final class Invalidation {
        private final Runnable callback;
        private Invalidation(Runnable callback) { this.callback = callback; }
    }
}
