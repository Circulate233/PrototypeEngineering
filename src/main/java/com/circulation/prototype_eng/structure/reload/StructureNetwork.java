package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.api.recipe.RecipeDefinitionManifest;
import com.circulation.prototype_eng.recipe.RecipeDefinitionManifestPacket;
import com.circulation.prototype_eng.storage.HatchConfigurationPacket;
import com.circulation.prototype_eng.storage.HatchConfigurationSnapshot;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraftforge.fml.common.network.NetworkRegistry;
import net.minecraftforge.fml.common.network.simpleimpl.SimpleNetworkWrapper;
import net.minecraftforge.fml.relauncher.Side;

import java.util.UUID;

/**
 * Actual Forge channel registration, packet decoding, and server-owner delivery.
 */
public final class StructureNetwork {
    private static final SimpleNetworkWrapper CHANNEL = NetworkRegistry.INSTANCE.newSimpleChannel("pe_structures");
    private static boolean registered;

    private StructureNetwork() {
    }

    public static void register() {
        if (registered) throw new IllegalStateException("Structure network already registered");
        CHANNEL.registerMessage(StructureCatalogPacket.Handler.class, StructureCatalogPacket.class, 0, Side.CLIENT);
        CHANNEL.registerMessage(HatchConfigurationPacket.Handler.class, HatchConfigurationPacket.class, 1, Side.CLIENT);
        CHANNEL.registerMessage(RecipeDefinitionManifestPacket.Handler.class, RecipeDefinitionManifestPacket.class, 2, Side.CLIENT);
        registered = true;
    }

    public static void sendHatchConfiguration(HatchConfigurationSnapshot snapshot, byte[] payload, EntityPlayerMP player) {
        int count = (payload.length + HatchConfigurationPacket.CHUNK_BYTES - 1) / HatchConfigurationPacket.CHUNK_BYTES;
        for (int index = 0; index < count; index++)
            CHANNEL.sendTo(HatchConfigurationPacket.part(snapshot, payload, index), player);
    }

    public static void announceRecipeDefinitions(UUID session, long generation, EntityPlayerMP player) {
        CHANNEL.sendTo(RecipeDefinitionManifestPacket.notice(session, generation), player);
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
