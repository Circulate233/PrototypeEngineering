package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import com.circulation.prototype_eng.api.recipe.RecipeDeclarationKey;
import com.circulation.prototype_eng.api.recipe.RecipeDeclarations;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionUpdates;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import crafttweaker.annotations.ZenRegister;
import crafttweaker.api.minecraft.CraftTweakerMC;
import crafttweaker.api.server.IServer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;
import stanhebben.zenscript.annotations.ZenMethod;

import java.util.UUID;

/**
 * One actual server publication handle, session and snapshot. A new lookup is required after publication.
 */
@SuppressWarnings("unused") // Discovered and accessed by ZenScript.
@ZenRegister
@ZenClass("mods.prototype_eng.recipe.Updates")
public final class ZenRecipeUpdates {
    private final RecipeDefinitionUpdates updates;
    private final RecipeRegistry.Snapshot snapshot;
    private final UUID session;

    public ZenRecipeUpdates(RecipeDefinitionUpdates updates) {
        this.updates = updates;
        snapshot = updates.snapshot();
        session = updates.sessionId();
    }

    @ZenMethod
    public static ZenRecipeUpdates forServer(IServer server) {
        return new ZenRecipeUpdates(RecipeDefinitionUpdates.forServer(CraftTweakerMC.getMCServer(server)));
    }

    @ZenGetter("session")
    public String session() {
        return session.toString();
    }

    @ZenGetter("generation")
    public long generation() {
        return snapshot.generation();
    }

    /**
     * Author supplies the complete new declaration identity; no closure equivalence is inferred.
     */
    @ZenMethod
    public ZenRecipeUpdateTask replace(ZenRecipe[] replacements, String setId, String version) {
        var key = new RecipeDeclarationKey(ZenRecipeSupport.id(setId), version);
        var definitions = new ObjectArrayList<RecipeRegistry.Definition>(replacements.length);
        var codecs = new ObjectArrayList<RecipeProcessNbt>(replacements.length);
        for (var recipe : replacements) {
            definitions.add(recipe.definition());
            codecs.add(recipe.codec());
        }
        var published = updates.replace(session, snapshot, definitions, codecs).thenApply(result -> {
            var publication = result.publication();
            if (publication == null) return result;
            try {
                RecipeDeclarations.INSTANCE.register(key, publication.current());
                return result;
            } catch (RuntimeException | Error failure) {
                var original = result.failure();
                if (original != null && original != failure) original.addSuppressed(failure);
                return new RecipeDefinitionUpdates.Result(RecipeDefinitionUpdates.Status.NOTIFICATION_FAILED, result.sessionId(),
                    publication, original == null ? failure : original);
            }
        });
        return new ZenRecipeUpdateTask(published);
    }
}
