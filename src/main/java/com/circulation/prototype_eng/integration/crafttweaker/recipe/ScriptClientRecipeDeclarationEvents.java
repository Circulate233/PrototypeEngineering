package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.client.jei.ClientRecipeDeclarationRegistrationEvent;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

/**
 * Resolves author-supplied local variants against the real initial snapshot and frozen providers.
 */
@SideOnly(Side.CLIENT)
@Mod.EventBusSubscriber(modid = Tags.MOD_ID, value = Side.CLIENT)
public final class ScriptClientRecipeDeclarationEvents {
    private static final Object2ObjectOpenHashMap<RecipeDeclarationKey, Resolved> RESOLVED = new Object2ObjectOpenHashMap<>();

    private ScriptClientRecipeDeclarationEvents() {
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void declarations(ClientRecipeDeclarationRegistrationEvent event) {
        var elements = ClientRecipeManifestDeclarations.freeze();
        for (int index = 0; index < elements.size(); index++) {
            var declaration = elements.get(index);
            var previous = RESOLVED.get(declaration.key());
            RecipeRegistry.Snapshot snapshot;
            if (previous == null) {
                snapshot = declaration.definitions().isEmpty() ? event.snapshot()
                    : event.snapshot().owner().prepareReplacement(event.snapshot(), declaration.definitions(), declaration.codecs()).next();
            } else {
                snapshot = previous.snapshot();
            }
            var providers = previous == null ? event.displays().frozenCopy() : previous.providers();
            var existing = event.declarations().get(declaration.key());
            if (existing != null) {
                if (existing.snapshot() != snapshot || !providers.sameProviders(existing.displays()))
                    throw new IllegalArgumentException("Client declaration key already binds other frozen local facts: " + declaration.key());
                if (previous == null) RESOLVED.put(declaration.key(), new Resolved(snapshot, providers));
                continue;
            }
            // This is a private display declaration: no directory, codec table or server state is installed.
            // Register installs before notifying listeners, which may fail or reenter this subscriber.
            if (previous == null) RESOLVED.put(declaration.key(), new Resolved(snapshot, providers));
            event.declarations().register(declaration.key(), snapshot, providers);
        }
    }

    private record Resolved(RecipeRegistry.Snapshot snapshot, RecipeJeiDisplays providers) {
    }
}
