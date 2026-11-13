package com.circulation.prototype_eng.api.network.resource;

import ae2.core.localization.LocalizationEnum;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.Locale;

/**
 * A packet-backed resource with a translatable name and a fixed numeric format pattern.
 */
public record FormattedNetworkResource(ResourceLocation id, boolean internal, String translationKey,
                                       String pattern) implements NetworkResourceType {
    public static final ResourceLocation PACKET_TYPE = new ResourceLocation("prototype_eng", "formatted_resource");

    /**
     * AE-compatible packet constructor for the fixed presentation data.
     */
    public FormattedNetworkResource(ByteBuf data) {
        this(new PacketBuffer(data).readResourceLocation(), data.readBoolean(), new PacketBuffer(data).readString(32767),
            new PacketBuffer(data).readString(32767));
    }

    @Override
    public ResourceLocation packetType() {
        return PACKET_TYPE;
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        var packet = new PacketBuffer(data);
        packet.writeResourceLocation(id);
        packet.writeBoolean(internal);
        packet.writeString(translationKey);
        packet.writeString(pattern);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public String displayName() {
        LocalizationEnum name = () -> translationKey;
        return name.getLocal();
    }

    @Override
    @SideOnly(Side.CLIENT)
    public String format(double amount) {
        return String.format(Locale.ROOT, pattern, amount);
    }
}
