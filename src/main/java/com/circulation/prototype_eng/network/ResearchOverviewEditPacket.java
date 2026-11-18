package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.container.ContainerResearchOverview;
import io.netty.buffer.ByteBuf;
import net.minecraftforge.fml.common.network.simpleimpl.IMessage;
import net.minecraftforge.fml.common.network.simpleimpl.IMessageHandler;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import org.jetbrains.annotations.Nullable;

public final class ResearchOverviewEditPacket implements IMessage {
    private int window;
    private ResearchPayload.Part part;

    public ResearchOverviewEditPacket() {
    }

    ResearchOverviewEditPacket(int window, ResearchPayload.Part part) {
        this.window = window;
        this.part = part;
    }

    @Override
    public void fromBytes(ByteBuf buffer) {
        window = buffer.readUnsignedByte();
        part = ResearchPayload.Part.read(buffer);
    }

    @Override
    public void toBytes(ByteBuf buffer) {
        buffer.writeByte(window);
        part.write(buffer);
    }

    public static final class Handler implements IMessageHandler<ResearchOverviewEditPacket, IMessage> {
        @Override
        public @Nullable IMessage onMessage(ResearchOverviewEditPacket message, MessageContext context) {
            var player = context.getServerHandler().player;
            player.getServerWorld().addScheduledTask(() -> {
                if (player.openContainer instanceof ContainerResearchOverview overview && overview.windowId == message.window
                    && overview.getPlayer() == player && overview.editorAuthorized()) {
                    overview.edit(message.part);
                }
            });
            return null;
        }
    }
}
