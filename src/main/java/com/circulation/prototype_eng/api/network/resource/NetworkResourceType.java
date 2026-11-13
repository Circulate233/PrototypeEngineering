package com.circulation.prototype_eng.api.network.resource;

import ae2.container.guisync.PacketWritable;
import ae2.core.localization.LocalizationEnum;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Developer-owned resource singleton. Reference2DoubleMap compares keys directly by identity;
 * reuse the same instance throughout network definitions and completed zero-default vectors.
 * Resources require no registration or identity/id validation. Accumulate quantities with addTo.
 * <p>
 * The fixed presentation packet is sent once when a resource enters a display. Its packet codec
 * reconstructs the client presentation; subsequent updates contain only changed double values.
 * Packet metadata must remain unchanged for this singleton's lifetime. Client reconstructions
 * are display objects, never replacement keys for the server's resource maps.
 */
public interface NetworkResourceType extends PacketWritable {
    ResourceLocation id();

    boolean internal();

    /**
     * Identifies the packet decoder, not a registered resource.
     */
    ResourceLocation packetType();

    @SideOnly(Side.CLIENT)
    String displayName();

    @SideOnly(Side.CLIENT)
    String format(double amount);

    /**
     * Create once and retain the result; pattern is a Java floating-point format, e.g. "%.2f FLOP/t".
     */
    static NetworkResourceType network(ResourceLocation id, LocalizationEnum name, String pattern) {
        return new FormattedNetworkResource(id, false, name.getTranslationKey(), pattern);
    }

    /**
     * A local computer resource never contributes to the team network.
     */
    static NetworkResourceType computer(ResourceLocation id, LocalizationEnum name, String pattern) {
        return new FormattedNetworkResource(id, true, name.getTranslationKey(), pattern);
    }
}
