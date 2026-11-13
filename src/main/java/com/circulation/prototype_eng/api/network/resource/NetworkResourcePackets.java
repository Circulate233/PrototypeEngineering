package com.circulation.prototype_eng.api.network.resource;

import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

import java.util.function.Function;

/**
 * Packet-type decoders, not resource declarations. Register one decoder per custom packet layout
 * during mod initialization on both sides; resource instances themselves are never registered.
 * Decoders read exactly the bytes written by their resource's PacketWritable implementation.
 */
public final class NetworkResourcePackets {
    private static final Object2ObjectOpenHashMap<ResourceLocation, Function<ByteBuf, ? extends NetworkResourceType>> DECODERS = new Object2ObjectOpenHashMap<>();

    static {
        register(FormattedNetworkResource.PACKET_TYPE, FormattedNetworkResource::new);
    }

    private NetworkResourcePackets() {
    }

    public static void register(ResourceLocation packetType, Function<ByteBuf, ? extends NetworkResourceType> decoder) {
        DECODERS.put(packetType, decoder);
    }

    public static void write(ByteBuf data, NetworkResourceType resource) {
        new PacketBuffer(data).writeResourceLocation(resource.packetType());
        resource.writeToPacket(data);
    }

    public static NetworkResourceType read(ByteBuf data) {
        return DECODERS.get(new PacketBuffer(data).readResourceLocation()).apply(data);
    }
}
