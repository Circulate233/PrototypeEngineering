package com.circulation.prototype_eng.client.gui;

import com.circulation.prototype_eng.network.clientbound.HatchConfigurationPacket;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.Arrays;
import java.util.Objects;

/**
 * Client-owner display cache; connection and session identity reject stale network messages.
 */
public final class ClientHatchConfigurations {
    private final Thread owner = Thread.currentThread();
    private @Nullable Object connection;
    private @Nullable HatchConfigurationSnapshot view;
    private @Nullable Pending pending;

    public void connected(Object connection) {
        requireOwner();
        Objects.requireNonNull(connection);
        if (this.connection == connection) return;
        this.connection = connection;
        view = null;
        pending = null;
    }

    public void disconnected(Object connection) {
        requireOwner();
        if (this.connection != connection) return;
        this.connection = null;
        view = null;
        pending = null;
    }

    public boolean connectedTo(Object candidate) {
        requireOwner();
        return connection != null && connection == candidate;
    }

    public @Nullable HatchConfigurationSnapshot view() {
        requireOwner();
        return view;
    }

    public boolean receive(Object connection, HatchConfigurationPacket packet) {
        requireOwner();
        if (!connectedTo(connection)) return false;
        if (view != null && (!view.session().equals(packet.session()) || packet.sequence() <= view.revision()))
            return false;
        if (pending != null) {
            if (!pending.first.session().equals(packet.session())) return false;
            if (packet.sequence() < pending.first.sequence()) return false;
            if (packet.sequence() > pending.first.sequence()) pending = null;
        }
        if (pending == null) pending = new Pending(packet);
        var candidate = pending;
        if (candidate.first.totalBytes() != packet.totalBytes()) {
            pending = null;
            throw new IllegalArgumentException("Conflicting hatch configuration chunk headers");
        }
        byte[] payload = packet.bytes();
        var previous = candidate.parts[packet.index()];
        if (previous != null) {
            if (!Arrays.equals(previous, payload)) {
                pending = null;
                throw new IllegalArgumentException("Conflicting repeated hatch configuration chunk");
            }
            return false;
        }
        candidate.parts[packet.index()] = payload;
        candidate.received++;
        if (candidate.received != candidate.parts.length) return false;
        byte[] complete = new byte[candidate.first.totalBytes()];
        int offset = 0;
        for (byte[] part : candidate.parts) {
            System.arraycopy(part, 0, complete, offset, part.length);
            offset += part.length;
        }
        pending = null;
        var next = HatchConfigurationSnapshot.decode(complete);
        if (!next.session().equals(packet.session()) || next.revision() != packet.sequence())
            throw new IllegalArgumentException("Hatch configuration payload generation differs from chunk header");
        view = next;
        return true;
    }

    private void requireOwner() {
        if (Thread.currentThread() != owner)
            throw new IllegalStateException("Hatch display configuration requires its client owner");
    }

    private static final class Pending {
        private final HatchConfigurationPacket first;
        private final byte[][] parts;
        private int received;

        private Pending(HatchConfigurationPacket first) {
            this.first = first;
            parts = new byte[first.chunks()][];
        }
    }
}
