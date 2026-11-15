package com.circulation.prototype_eng.integration.crafttweaker.jei;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiPage;
import com.circulation.prototype_eng.api.client.jei.RecipePoolJeiRegistrationEvent;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;


/**
 * Native client event consumes common parameters and the same independent Java page instances.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class ScriptJeiEvents {
    private static final Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePoolJeiPage> PAGES = new Object2ObjectLinkedOpenHashMap<>();

    private ScriptJeiEvents() {
    }

    /**
     * Addons register an actual public interface instance, created from their canonical pool.
     */
    @SuppressWarnings("unused") // Client Java addons expose the returned common handle to scripts.
    public static ZenJeiPage page(String id, RecipePoolJeiPage page) {
        var handle = ZenJeiPage.named(id);
        if (PAGES.putIfAbsent(handle.id(), page) != null)
            throw new IllegalArgumentException("Duplicate addon JEI page handle: " + id);
        return handle;
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void register(RecipePoolJeiRegistrationEvent event) {
        var bindings = ClientJeiDeclarations.freeze();
        var defaults = ClientJeiDeclarations.defaults();
        if (defaults != null) JeiRenderOptions.defaults(options(defaults));
        for (int index = 0; index < bindings.size(); index++) {
            var binding = bindings.get(index);
            if (binding.options() != null) event.pages().renderOptions(binding.pool(), options(binding.options()));
            if (binding.page() != null) {
                var page = PAGES.get(binding.page().id());
                if (page == null)
                    throw new IllegalArgumentException("Addon JEI page handle is not registered: " + binding.page().id());
                event.pages().register(binding.pool(), page);
            }
        }
    }

    private static JeiRenderOptions options(ZenJeiOptions settings) {
        return new JeiRenderOptions(settings.chanceOverlay(), JeiRenderOptions.CandidateMode.valueOf(settings.candidates()), settings.energyLedYOffset());
    }
}
