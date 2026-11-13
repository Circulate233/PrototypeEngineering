package com.circulation.prototype_eng.api.recipe;

import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.util.ResourceLocation;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Only explicit declaration identities cross the connection; executable definitions stay local.
 * Pool and member lists are borrowed completed values and remain immutable for the manifest's lifetime.
 */
public record RecipeDefinitionManifest(UUID serverSession, long generation, RecipeDeclarationKey declaration,
                                       List<Pool> pools) {
    public RecipeDefinitionManifest {
        Objects.requireNonNull(serverSession);
        Objects.requireNonNull(declaration);
        if (generation < 0) throw new IllegalArgumentException("Negative recipe manifest generation");
        var revisions = new Object2ObjectOpenHashMap<ResourceLocation, String>();
        for (int p = 0; p < pools.size(); p++) {
            var pool = pools.get(p);
            var id = pool.id();
            if (p > 0 && pools.get(p - 1).id().compareTo(id) >= 0)
                throw new IllegalArgumentException("Recipe manifest pools must be uniquely sorted");
            for (var member : pool.members()) {
                var revision = revisions.putIfAbsent(member.recipeId(), member.revision());
                if (revision != null && !revision.equals(member.revision()))
                    throw new IllegalArgumentException("Inconsistent recipe manifest revision across pools: " + member.recipeId());
            }
        }
    }

    public static RecipeDefinitionManifest capture(UUID session, RecipeDeclarationKey declaration, RecipeRegistry.Snapshot snapshot) {
        return new RecipeDefinitionManifest(session, snapshot.generation(), declaration, describe(snapshot));
    }

    /**
     * No codec is required for display declarations, but every current member needs a real revision.
     */
    public static List<Pool> describe(RecipeRegistry.Snapshot snapshot) {
        var pools = new ObjectArrayList<Pool>(snapshot.orderedPools().size());
        for (var pool : Objects.requireNonNull(snapshot).orderedPools()) {
            var members = new ObjectArrayList<RecipeDefinitionRevision>(pool.recipes().size());
            for (var recipe : pool.recipes()) {
                var revision = snapshot.revision(recipe);
                if (revision == null)
                    throw new IllegalArgumentException("Missing explicit recipe revision: " + recipe.id());
                members.add(new RecipeDefinitionRevision(recipe.id(), revision));
            }
            pools.add(new Pool(pool.id(), members));
        }
        return pools;
    }

    /**
     * Session generations need not equal the independent local registry's generation.
     */
    public boolean matches(RecipeRegistry.Snapshot snapshot) {
        var localPools = snapshot.orderedPools();
        if (pools.size() != localPools.size()) return false;
        for (int p = 0; p < pools.size(); p++) {
            var wanted = pools.get(p);
            var local = localPools.get(p);
            if (!wanted.id().equals(local.id()) || wanted.members().size() != local.recipes().size()) return false;
            for (int r = 0; r < wanted.members().size(); r++) {
                var revision = wanted.members().get(r);
                var recipe = local.recipes().get(r);
                if (!revision.recipeId().equals(recipe.id()) || !revision.revision().equals(snapshot.revision(recipe)))
                    return false;
            }
        }
        return true;
    }

    /**
     * List order is the canonical declaration priority, including shared members in different pools.
     */
    public record Pool(ResourceLocation id, List<RecipeDefinitionRevision> members) {
        public Pool {
            Objects.requireNonNull(id);
            var ids = new ObjectOpenHashSet<ResourceLocation>(members.size());
            for (var member : members)
                if (!ids.add(member.recipeId()))
                    throw new IllegalArgumentException("Duplicate recipe manifest member: " + member.recipeId());
        }
    }
}
