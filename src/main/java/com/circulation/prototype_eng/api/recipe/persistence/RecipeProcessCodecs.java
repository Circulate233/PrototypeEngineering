package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeDefinitionRevision;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

/**
 * Stable process persistence resolver. Published snapshots retain all explicitly supplied historical
 * definitions, including revisions that may still occur in unloaded chunks. A declaration loader must
 * supply those same revisions again on a cold start; an unknown revision never means the latest one.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class RecipeProcessCodecs {
    private final boolean fixed;
    @Nullable
    private RegistryBinding registryBinding;
    private volatile Snapshot current;

    public RecipeProcessCodecs(Collection<RecipeProcessNbt> codecs) {
        this(codecs, false);
    }

    private RecipeProcessCodecs(Collection<RecipeProcessNbt> codecs, boolean fixed) {
        this.fixed = fixed;
        current = new Snapshot(this, null, codecs);
    }

    /**
     * Compatibility binding for a work-slot codec that does not follow definition replacements.
     */
    public static RecipeProcessCodecs fixed(Collection<RecipeProcessNbt> codecs) {
        return new RecipeProcessCodecs(codecs, true);
    }

    public Snapshot snapshot() {
        return current;
    }

    public boolean isCurrent(Snapshot expected) {
        return current == Objects.requireNonNull(expected);
    }

    /**
     * Claims the sole publication capability when a registry adopts this resolver before freezing.
     */
    public synchronized RegistryBinding bindRegistry(RecipeRegistry registry) {
        Objects.requireNonNull(registry);
        if (fixed || registryBinding != null)
            throw new IllegalStateException("Process codec resolver cannot be bound to this registry");
        registryBinding = new RegistryBinding(this, registry);
        return registryBinding;
    }

    /**
     * Pure preparation; no current binding is modified when validation fails.
     */
    public synchronized Prepared prepare(Snapshot expected, Collection<RecipeProcessNbt> additions) {
        if (fixed) throw new IllegalStateException("Fixed process codecs cannot be extended");
        if (!isCurrent(expected)) throw new IllegalArgumentException("Foreign or stale process codec snapshot");
        boolean changed = false;
        for (var codec : Objects.requireNonNull(additions, "process codecs")) {
            Objects.requireNonNull(codec, "process codec");
            if (expected.get(codec.recipe()) != codec) changed = true;
        }
        if (!changed) return new Prepared(this, expected, expected);
        return new Prepared(this, expected, new Snapshot(this, expected, additions));
    }

    public synchronized boolean canInstall(Prepared prepared) {
        Objects.requireNonNull(prepared);
        return prepared.owner == this && !prepared.consumed && current == prepared.expected;
    }

    /**
     * Callback-free installation. A combined owner publication must hold this resolver's monitor from
     * its final dependency checks through directory installation, preventing another writer between
     * those steps. The ordering is registry, resolver, then network bindings when all are involved.
     */
    public synchronized void install(Prepared prepared) {
        if (registryBinding != null)
            throw new IllegalStateException("Bound process codecs publish only with their recipe registry");
        installPrepared(prepared);
    }

    private void installPrepared(Prepared prepared) {
        if (!canInstall(prepared))
            throw new IllegalStateException("Foreign, stale or consumed process codec preparation");
        prepared.consumed = true;
        current = prepared.next;
    }

    /**
     * Kept privately by the adopting registry; regular resolver callers cannot publish around it.
     */
    public static final class RegistryBinding {
        private final RecipeProcessCodecs resolver;
        private final RecipeRegistry registry;

        private RegistryBinding(RecipeProcessCodecs resolver, RecipeRegistry registry) {
            this.resolver = resolver;
            this.registry = registry;
        }

        public void install(Prepared prepared) {
            if (!Thread.holdsLock(registry))
                throw new IllegalStateException("Registry publication monitor is required");
            synchronized (resolver) {
                resolver.installPrepared(prepared);
            }
        }
    }

    public static final class Snapshot {
        private final RecipeProcessCodecs owner;
        private final Reference2ObjectOpenHashMap<MachineRecipe, RecipeProcessNbt> byRecipe;
        private final Object2ObjectLinkedOpenHashMap<RecipeDefinitionRevision, RecipeProcessNbt> byRevision;
        private final List<RecipeProcessNbt> codecs;

        private Snapshot(RecipeProcessCodecs owner, @Nullable Snapshot previous, Collection<RecipeProcessNbt> additions) {
            this.owner = owner;
            byRecipe = previous == null ? new Reference2ObjectOpenHashMap<>() : new Reference2ObjectOpenHashMap<>(previous.byRecipe);
            byRevision = previous == null ? new Object2ObjectLinkedOpenHashMap<>() : new Object2ObjectLinkedOpenHashMap<>(previous.byRevision);
            for (var codec : Objects.requireNonNull(additions, "process codecs")) {
                Objects.requireNonNull(codec, "process codec");
                var identity = new RecipeDefinitionRevision(codec.recipe().id(), codec.revision());
                var oldRecipe = byRecipe.putIfAbsent(codec.recipe(), codec);
                var oldRevision = byRevision.putIfAbsent(identity, codec);
                if (oldRecipe != null && oldRecipe != codec || oldRevision != null && oldRevision != codec) {
                    throw new IllegalArgumentException("Conflicting process persistence binding: " + identity);
                }
            }
            codecs = ObjectLists.unmodifiable(new ObjectArrayList<>(byRevision.values()));
        }

        public RecipeProcessCodecs owner() {
            return owner;
        }

        public List<RecipeProcessNbt> codecs() {
            return codecs;
        }

        @Nullable
        public RecipeProcessNbt get(MachineRecipe recipe) {
            return byRecipe.get(Objects.requireNonNull(recipe));
        }

        public RecipeProcessNbt require(MachineRecipe recipe) {
            var codec = get(recipe);
            if (codec == null) throw new IllegalArgumentException("Unknown process recipe identity: " + recipe.id());
            return codec;
        }

        public RecipeProcessNbt require(ResourceLocation recipeId, String revision) {
            var identity = new RecipeDefinitionRevision(recipeId, revision);
            var codec = byRevision.get(identity);
            if (codec == null) throw new IllegalArgumentException("Unknown process recipe revision: " + identity);
            return codec;
        }
    }

    /**
     * Single-use result tied to this resolver and its exact previous snapshot.
     */
    public static final class Prepared {
        private final RecipeProcessCodecs owner;
        private final Snapshot expected;
        private final Snapshot next;
        private boolean consumed;

        private Prepared(RecipeProcessCodecs owner, Snapshot expected, Snapshot next) {
            this.owner = owner;
            this.expected = expected;
            this.next = next;
        }

        public Snapshot expected() {
            return expected;
        }

        public Snapshot next() {
            return next;
        }
    }
}
