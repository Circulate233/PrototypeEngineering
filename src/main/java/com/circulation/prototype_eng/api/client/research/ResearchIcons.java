package com.circulation.prototype_eng.api.client.research;

import ae2.api.client.AEKeyRendering;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey;
import ae2.client.gui.Icon;
import com.google.gson.JsonObject;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fml.common.registry.ForgeRegistries;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import java.util.function.Function;

/**
 * Register icon kinds during client initialization, before research content is first displayed.
 * Parsers receive the complete JSON object and may use addon-specific fields.
 */
@SideOnly(Side.CLIENT)
public final class ResearchIcons {
    private static final Object2ObjectOpenHashMap<String, Function<JsonObject, ResearchIcon>> PARSERS = new Object2ObjectOpenHashMap<>();

    static {
        register("item", ResearchIcons::item);
        register("fluid", ResearchIcons::fluid);
        register("icon", ResearchIcons::icon);
    }

    private ResearchIcons() {
    }

    public static void register(String kind, Function<JsonObject, ResearchIcon> parser) {
        PARSERS.put(kind, parser);
    }

    public static ResearchIcon parse(JsonObject data) {
        String kind = data.get("kind").getAsString();
        var parser = PARSERS.get(kind);
        if (parser == null) throw new IllegalArgumentException("Unregistered research icon kind: " + kind);
        return parser.apply(data);
    }

    private static ResearchIcon fluid(JsonObject data) {
        var id = data.get("id").getAsString();
        var fluid = FluidRegistry.getFluid(id);
        if (fluid == null) throw new IllegalArgumentException("Unknown research icon fluid: " + id);
        var key = AEFluidKey.of(fluid);
        return new AEKeyIcon(key);
    }

    private static ResearchIcon item(JsonObject data) {
        var id = new ResourceLocation(data.get("id").getAsString());
        var item = ForgeRegistries.ITEMS.getValue(id);
        if (item == null) throw new IllegalArgumentException("Unknown research icon item: " + id);
        var key = AEItemKey.of(new ItemStack(item, 1, data.has("meta") ? data.get("meta").getAsInt() : 0));
        if (key == null) throw new IllegalArgumentException("Empty research icon item: " + id);
        return new AEKeyIcon(key);
    }

    private static ResearchIcon icon(JsonObject data) {
        var id = new ResourceLocation(data.get("id").getAsString());
        var icon = Icon.byId(id);
        if (icon == null) throw new IllegalArgumentException("Unknown AE icon: " + id);
        return (_, x, y) -> icon.getBlitter().dest(x, y, 16, 16).blit();
    }

    private record AEKeyIcon(AEKey item) implements ResearchIcon {
        @Override
        public void draw(Minecraft minecraft, int x, int y) {
            AEKeyRendering.drawInGui(minecraft, x, y, item);
        }

        @Override
        public AEKey key() {
            return item;
        }
    }
}
