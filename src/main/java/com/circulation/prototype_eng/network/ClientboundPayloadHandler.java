package com.circulation.prototype_eng.network;

import ae2.core.network.AppEngPayloadHandler;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import org.jetbrains.annotations.Nullable;

/** Capture receipt identity on Netty, then use AE's actual owner-thread client dispatch. */
public final class ClientboundPayloadHandler<T extends ConnectionClientboundPacket> implements IMessageHandler<T, IMessage> {
    private final AppEngPayloadHandler.Client<T> delegate = new AppEngPayloadHandler.Client<>();

    @Override
    public @Nullable IMessage onMessage(T message, MessageContext context) {
        if (context.side != Side.CLIENT) throw new IllegalStateException("Clientbound packet received on the server");
        message.receiveConnection(context.netHandler);
        return delegate.onMessage(message, context);
    }
}
