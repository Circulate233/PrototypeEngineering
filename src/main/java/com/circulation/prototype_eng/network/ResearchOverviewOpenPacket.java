package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.jetbrains.annotations.Nullable;

/**
 * A key or addon action asks the server to open its authoritative overview container.
 */
public final class ResearchOverviewOpenPacket implements IMessage {
    public ResearchOverviewOpenPacket() {
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
    }

    @Override
    public void toBytes(ByteBuf buffer) {
    }

    public static final class Handler implements IMessageHandler<ResearchOverviewOpenPacket, IMessage> {
        @Override
        public @Nullable IMessage onMessage(ResearchOverviewOpenPacket message, MessageContext context) {
            var player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (player.openContainer == player.inventoryContainer) ResearchGuiAccess.open(player);
            });
            return null;
        }
    }
}
