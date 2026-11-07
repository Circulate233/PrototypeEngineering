package com.circulation.prototype_eng.api.client.gui;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import java.util.Objects;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/** Named research-content icons map to complete textures, drawn in a 16 by 16 display area. */
@SideOnly(Side.CLIENT)
public final class ResearchIconRegistry {
    private static final Object2ObjectOpenHashMap<ResourceLocation, ResourceLocation> TEXTURES = new Object2ObjectOpenHashMap<>();
    private ResearchIconRegistry() { }

    @SuppressWarnings("unused") // Downstream client initialization supplies its content icon table.
    public static void register(ResourceLocation id, ResourceLocation texture) {
        if (TEXTURES.putIfAbsent(Objects.requireNonNull(id), Objects.requireNonNull(texture)) != null) {
            throw new IllegalArgumentException("Duplicate research icon " + id);
        }
    }

    public static ResourceLocation require(ResourceLocation id) {
        var texture = TEXTURES.get(id);
        if (texture == null) throw new IllegalArgumentException("Unknown research icon " + id);
        return texture;
    }
}
