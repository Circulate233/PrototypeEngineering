package com.circulation.prototype_eng.network;

import ae2.core.network.ClientboundPacket;
import net.minecraft.client.Minecraft;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/** AE clientbound family with the exact connection captured before its client-owner scheduling. */
public abstract class ConnectionClientboundPacket extends ClientboundPacket {
    private @Nullable Object receivedConnection;

    final void receiveConnection(Object connection) {
        if (receivedConnection != null) throw new IllegalStateException("Clientbound packet was already received");
        receivedConnection = Objects.requireNonNull(connection);
    }

    final void dispatchClient(@Nullable Object currentConnection) {
        var captured = receivedConnection;
        if (captured == null || captured != currentConnection) return;
        handleClientConnection(captured);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public final void handleClient(Minecraft minecraft) {
        dispatchClient(minecraft.getConnection());
    }

    protected abstract void handleClientConnection(Object receivedConnection);
}
