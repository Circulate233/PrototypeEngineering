package com.circulation.prototype_eng.api.network.recipe;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Ordered definition callbacks and immutable exact-object PN bindings, retaining historical work. */
@SuppressWarnings("unused")
public final class NetworkRecipeExtensions {
    public static final NetworkRecipeExtensions INSTANCE = new NetworkRecipeExtensions();
    private final ObjectArrayList<Registration> registrations = new ObjectArrayList<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, Configuration> configured = new Reference2ObjectOpenHashMap<>();
    private long nextSequence;
    private boolean publicationStarted;
    private List<Registration> configuration = List.of();
    @Nullable
    private ResearchRegistry research;
    @Nullable
    private NetworkResourceRegistry resources;
    @Nullable
    private volatile Snapshot frozen;
    @Nullable
    private volatile PublicationBinding publicationBinding;

    public void register(NetworkRecipeExtension extension) { register(0, extension); }

    /** Ascending priority, then registration order, matches PN's extension order. */
    public synchronized void register(int priority, NetworkRecipeExtension extension) {
        if (publicationStarted) throw new IllegalStateException("Network recipe extension registration has ended");
        Objects.requireNonNull(extension);
        long sequence = nextSequence;
        nextSequence = Math.incrementExact(nextSequence);
        registrations.add(new Registration(priority, sequence, extension));
    }

    /** Failed initial publication remains unavailable and cannot replay callbacks or register again. */
    public void publish(RecipeCatalog catalog, ResearchRegistry research, NetworkResourceRegistry resources) {
        beginPublication(research, resources);
        var recipes = new ObjectArrayList<>(Objects.requireNonNull(catalog).recipes());
        recipes.sort(Comparator.comparing(recipe -> recipe.id().toString()));
        publishInitial(recipes);
    }

    /** Current and declared historical objects must all have accurate PN gates before machine restoration. */
    public void publish(RecipeRegistry.Snapshot catalog, ResearchRegistry research, NetworkResourceRegistry resources) {
        beginPublication(research, resources);
        Objects.requireNonNull(catalog);
        var identities = new ReferenceOpenHashSet<MachineRecipe>();
        var recipes = new ObjectArrayList<MachineRecipe>();
        for (var recipe : catalog.catalog().recipes()) if (identities.add(recipe)) recipes.add(recipe);
        for (var definition : catalog.historicalDefinitions()) if (identities.add(definition.recipe())) recipes.add(definition.recipe());
        recipes.sort(Comparator.comparing((MachineRecipe recipe) -> recipe.id().toString())
            .thenComparing(recipe -> Objects.requireNonNullElse(catalog.revision(recipe), "")));
        publishInitial(recipes);
    }

    private void beginPublication(ResearchRegistry research, NetworkResourceRegistry resources) {
        synchronized (this) {
            if (publicationStarted) throw new IllegalStateException("Network recipe extension publication already started");
            publicationStarted = true;
            if (!Objects.requireNonNull(research).isFrozen() || !Objects.requireNonNull(resources).isFrozen()) {
                throw new IllegalStateException("Network recipe extensions need frozen research and computing registries");
            }
            this.research = research;
            this.resources = resources;
            var ordered = new ObjectArrayList<>(registrations);
            ordered.sort(Comparator.comparingInt(Registration::priority).thenComparingLong(Registration::sequence));
            configuration = ObjectLists.unmodifiable(ordered);
            registrations.clear();
        }
    }

    private void publishInitial(List<MachineRecipe> recipes) {
        var completed = new Reference2ObjectOpenHashMap<MachineRecipe, NetworkWorkRequirements>();
        for (var recipe : recipes) completed.put(recipe, configure(recipe));
        synchronized (this) {
            frozen = new Snapshot(this, completed);
        }
    }

    public boolean published() { return frozen != null; }

    public Snapshot snapshot() {
        var current = frozen;
        if (current == null) throw new IllegalStateException("Network recipe extension index is unavailable");
        return current;
    }

    public boolean isCurrent(Snapshot expected) { return frozen == Objects.requireNonNull(expected); }

    /** Cached configuration is deliberately invisible here until its prepared union is installed. */
    public NetworkWorkRequirements require(MachineRecipe recipe) { return snapshot().require(recipe); }

    /** Adopts the table for the same concrete session that owns recipe directory publication. */
    public synchronized PublicationBinding bindPublication(RecipeRegistry.Publication publication) {
        Objects.requireNonNull(publication).requireOwner();
        if (!publication.live() || !canBindPublication()) {
            throw new IllegalStateException("Network recipe publication is already active or closed");
        }
        publicationBinding = new PublicationBinding(this, publication);
        return publicationBinding;
    }

    public synchronized boolean canBindPublication() {
        snapshot();
        var current = publicationBinding;
        return current == null || !current.publication.live();
    }

    /** Callbacks use the original frozen research/resources and execute outside the publication monitor. */
    public Prepared prepare(Snapshot expected, Collection<MachineRecipe> declaredRecipes) {
        var binding = publicationBinding;
        if (binding != null) binding.publication.requireOwnerBoundary();
        if (!isCurrent(expected)) throw new IllegalArgumentException("Foreign or stale network recipe snapshot");
        var unique = new ReferenceOpenHashSet<MachineRecipe>();
        var additions = new ObjectArrayList<MachineRecipe>();
        for (var recipe : Objects.requireNonNull(declaredRecipes, "declared recipes")) {
            Objects.requireNonNull(recipe);
            if (!expected.requirements.containsKey(recipe) && unique.add(recipe)) additions.add(recipe);
        }
        additions.sort(Comparator.comparing(recipe -> recipe.id().toString()));
        var next = expected;
        if (!additions.isEmpty()) {
            var union = new Reference2ObjectOpenHashMap<>(expected.requirements);
            for (var recipe : additions) union.put(recipe, configure(recipe));
            next = new Snapshot(this, union);
        }
        if (!isCurrent(expected) || publicationBinding != binding || binding != null && !binding.publication.live()) {
            throw new IllegalArgumentException("Network recipe snapshot or publication changed during preparation");
        }
        return new Prepared(this, expected, next, binding);
    }

    private NetworkWorkRequirements configure(MachineRecipe recipe) {
        Configuration state;
        synchronized (this) {
            state = configured.get(recipe);
            if (state != null) {
                if (!state.complete) throw new IllegalStateException("Network recipe configuration is already in progress: " + recipe.id());
                if (state.failure instanceof RuntimeException failure) throw failure;
                if (state.failure instanceof Error failure) throw failure;
                return Objects.requireNonNull(state.requirements);
            }
            state = new Configuration();
            configured.put(recipe, state);
        }
        var builder = NetworkWorkRequirements.builder();
        try {
            for (var registration : configuration) registration.extension.extend(recipe, builder);
            var requirements = builder.build();
            requirements.validate(Objects.requireNonNull(research), Objects.requireNonNull(resources));
            synchronized (this) {
                state.requirements = requirements;
                state.complete = true;
            }
            return requirements;
        } catch (RuntimeException | Error failure) {
            synchronized (this) {
                state.failure = failure;
                state.complete = true;
            }
            throw failure;
        } finally {
            builder.seal();
        }
    }

    public synchronized boolean canInstall(Prepared prepared) {
        return publicationBinding == null && matchesPrepared(prepared);
    }

    private boolean matchesPrepared(Prepared prepared) {
        Objects.requireNonNull(prepared);
        return prepared.owner == this && !prepared.consumed && frozen == prepared.expected
            && prepared.publicationBinding == publicationBinding
            && (publicationBinding == null || publicationBinding.publication.live());
    }

    public synchronized void install(Prepared prepared) {
        if (publicationBinding != null) throw new IllegalStateException("Adopted network bindings publish only with their recipe directory");
        installPrepared(prepared);
    }

    private void installPrepared(Prepared prepared) {
        if (!matchesPrepared(prepared)) throw new IllegalStateException("Foreign, stale or consumed network recipe preparation");
        prepared.consumed = true;
        frozen = prepared.next;
    }

    private record Registration(int priority, long sequence, NetworkRecipeExtension extension) {}

    private static final class Configuration {
        private boolean complete;
        @Nullable private NetworkWorkRequirements requirements;
        @Nullable private Throwable failure;
    }

    public static final class Snapshot {
        private final NetworkRecipeExtensions owner;
        private final Reference2ObjectOpenHashMap<MachineRecipe, NetworkWorkRequirements> requirements;

        private Snapshot(NetworkRecipeExtensions owner, Reference2ObjectOpenHashMap<MachineRecipe, NetworkWorkRequirements> requirements) {
            this.owner = owner;
            this.requirements = requirements;
        }

        public NetworkRecipeExtensions owner() { return owner; }

        public NetworkWorkRequirements require(MachineRecipe recipe) {
            var value = requirements.get(Objects.requireNonNull(recipe));
            if (value == null) throw new IllegalArgumentException("Recipe is outside the published network catalog: " + recipe.id());
            return value;
        }
    }

    public static final class Prepared {
        private final NetworkRecipeExtensions owner;
        private final Snapshot expected;
        private final Snapshot next;
        @Nullable private final PublicationBinding publicationBinding;
        private boolean consumed;

        private Prepared(NetworkRecipeExtensions owner, Snapshot expected, Snapshot next, @Nullable PublicationBinding publicationBinding) {
            this.owner = owner;
            this.expected = expected;
            this.next = next;
            this.publicationBinding = publicationBinding;
        }

        public Snapshot expected() { return expected; }
        public Snapshot next() { return next; }
    }

    /** Private capability retained by the combined publisher; exact registry ownership stays mandatory. */
    public static final class PublicationBinding {
        private final NetworkRecipeExtensions extensions;
        private final RecipeRegistry.Publication publication;

        private PublicationBinding(NetworkRecipeExtensions extensions, RecipeRegistry.Publication publication) {
            this.extensions = extensions;
            this.publication = publication;
        }

        public boolean canInstall(Prepared prepared) {
            synchronized (extensions) {
                return extensions.publicationBinding == this && extensions.matchesPrepared(prepared);
            }
        }

        public void install(Prepared prepared) {
            publication.requireOwnerBoundary();
            synchronized (extensions) {
                if (!canInstall(prepared)) throw new IllegalStateException("Stale network recipe publication");
                extensions.installPrepared(prepared);
            }
        }
    }
}
