package com.circulation.prototype_eng.network;

import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.network.clientbound.ConstructionEffectPacket;
import com.circulation.prototype_eng.network.clientbound.HatchConfigurationPacket;
import com.circulation.prototype_eng.network.clientbound.RecipeDefinitionManifestPacket;
import com.circulation.prototype_eng.network.clientbound.ResearchViewPacket;
import com.circulation.prototype_eng.network.clientbound.StructureCatalogPacket;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
import com.circulation.prototype_eng.structure.reload.StructureReloadCoordinator;
import com.circulation.prototype_eng.structure.tool.effect.ConstructionEffectPayload;
import io.netty.buffer.ByteBuf;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.UUID;
import java.util.function.Consumer;

/**
 * Actual Forge channel registration, packet decoding, and server-owner delivery.
 */
public final class InitNetwork {
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("pe_structures");
    private static boolean registered;
    private static int nextPacketId;

    private InitNetwork() {
    }

    public static void init() {
        if (registered) throw new IllegalStateException("Structure network already registered");
        registerClientbound(StructureCatalogPacket.class);
        registerClientbound(HatchConfigurationPacket.class);
        registerClientbound(RecipeDefinitionManifestPacket.class);
        CHANNEL.registerMessage(ResearchOverviewOpenPacket.Handler.class, ResearchOverviewOpenPacket.class, nextPacketId++, Side.SERVER);
        registerClientbound(ResearchViewPacket.class);
        CHANNEL.registerMessage(ResearchOverviewEditPacket.Handler.class, ResearchOverviewEditPacket.class, nextPacketId++, Side.SERVER);
        registerClientbound(ConstructionEffectPacket.class);
        registered = true;
    }

    @SideOnly(Side.CLIENT)
    public static void requestResearchOverview() {
        CHANNEL.sendToServer(new ResearchOverviewOpenPacket());
    }

    public static void sendResearchView(EntityPlayerMP player, int kind, int window, Consumer<ByteBuf> encoder) {
        ResearchPayload.split(ResearchPayload.encode(encoder), part -> CHANNEL.sendTo(new ResearchViewPacket(kind, window, part), player));
    }

    public static void editResearch(int window, Consumer<ByteBuf> encoder) {
        ResearchPayload.split(ResearchPayload.encode(encoder), part -> CHANNEL.sendToServer(new ResearchOverviewEditPacket(window, part)));
    }

    public static void sendConstructionEffect(EntityPlayerMP player, ConstructionEffectPayload payload) {
        CHANNEL.sendTo(new ConstructionEffectPacket(payload), player);
    }

    private static <T extends ConnectionClientboundPacket> void registerClientbound(Class<T> packetClass) {
        CHANNEL.registerMessage(new ClientboundPayloadHandler<>(), packetClass, nextPacketId++, Side.CLIENT);
    }

    public static void sendHatchConfiguration(HatchConfigurationSnapshot snapshot, byte[] payload, EntityPlayerMP player) {
        int count = (payload.length + HatchConfigurationPacket.CHUNK_BYTES - 1) / HatchConfigurationPacket.CHUNK_BYTES;
        for (int index = 0; index < count; index++)
            CHANNEL.sendTo(HatchConfigurationPacket.part(snapshot, payload, index), player);
    }

    public static void announceRecipeDefinitions(UUID session, long generation, boolean declarationRequired, EntityPlayerMP player) {
        CHANNEL.sendTo(RecipeDefinitionManifestPacket.notice(session, generation, declarationRequired), player);
    }

    public static void sendRecipeDefinitions(RecipeDefinitionManifest manifest, byte[] payload, EntityPlayerMP player) {
        int count = (payload.length + RecipeDefinitionManifestPacket.CHUNK_BYTES - 1) / RecipeDefinitionManifestPacket.CHUNK_BYTES;
        for (int index = 0; index < count; index++)
            CHANNEL.sendTo(RecipeDefinitionManifestPacket.part(manifest, payload, index), player);
    }

    public static void send(StructureReloadCoordinator.Publication publication, EntityPlayerMP player) {
        byte[] payload = publication.bytes();
        int count = (payload.length + StructureCatalogPacket.CHUNK_BYTES - 1) / StructureCatalogPacket.CHUNK_BYTES;
        for (int i = 0; i < count; i++) CHANNEL.sendTo(StructureCatalogPacket.part(publication, payload, i), player);
    }
}
