package com.circulation.prototype_eng.api.network.recipe;

import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import org.jetbrains.annotations.Nullable;

import java.util.Comparator;
import java.util.Objects;

/** Ordered loading callbacks, followed by one atomic publication covering every canonical recipe. */
@SuppressWarnings("unused")
public final class NetworkRecipeExtensions {
    public static final NetworkRecipeExtensions INSTANCE = new NetworkRecipeExtensions();
    private final ObjectArrayList<Registration> registrations = new ObjectArrayList<>();
    private long nextSequence;
    private boolean publicationStarted;
    @Nullable private volatile Frozen frozen;

    public void register(NetworkRecipeExtension extension) { register(0, extension); }

    /** Ascending priority, then registration order, matches PN's extension order. */
    public synchronized void register(int priority, NetworkRecipeExtension extension) {
        if (publicationStarted) { throw new IllegalStateException("Network recipe extension registration has ended"); }
        Objects.requireNonNull(extension);
        long sequence = nextSequence;
        nextSequence = Math.incrementExact(nextSequence);
        registrations.add(new Registration(priority, sequence, extension));
    }

    /** Failed publication stays unavailable and cannot replay callbacks or accept new registrations. */
    public synchronized void publish(RecipeCatalog catalog, ResearchRegistry research, NetworkResourceRegistry resources) {
        if (publicationStarted) { throw new IllegalStateException("Network recipe extension publication already started"); }
        publicationStarted = true;
        Objects.requireNonNull(catalog);
        if (!Objects.requireNonNull(research).isFrozen() || !Objects.requireNonNull(resources).isFrozen()) {
            throw new IllegalStateException("Network recipe extensions need frozen research and computing registries");
        }
        var recipes = new ObjectArrayList<>(catalog.recipes());
        recipes.sort(Comparator.comparing(recipe -> recipe.id().toString()));
        var ordered = new ObjectArrayList<>(registrations);
        ordered.sort(Comparator.comparingInt(Registration::priority).thenComparingLong(Registration::sequence));
        var completed = new Reference2ObjectOpenHashMap<MachineRecipe, NetworkWorkRequirements>();
        for (var recipe : recipes) {
            var builder = NetworkWorkRequirements.builder();
            try {
                for (var registration : ordered) { registration.extension.extend(recipe, builder); }
                var requirements = builder.build();
                requirements.validate(research, resources);
                completed.put(recipe, requirements);
            } finally {
                builder.seal();
            }
        }
        frozen = new Frozen(completed);
        registrations.clear();
    }

    public boolean published() { return frozen != null; }

    /** Rejects foreign and same-id replacement definitions by canonical object identity. */
    public NetworkWorkRequirements require(MachineRecipe recipe) {
        var current = frozen;
        if (current == null) { throw new IllegalStateException("Network recipe extension index is unavailable"); }
        Objects.requireNonNull(recipe);
        var requirements = current.requirements.get(recipe);
        if (requirements == null) { throw new IllegalArgumentException("Recipe is outside the canonical network catalog: " + recipe.id()); }
        return requirements;
    }

    private record Registration(int priority, long sequence, NetworkRecipeExtension extension) { }
    private record Frozen(Reference2ObjectOpenHashMap<MachineRecipe, NetworkWorkRequirements> requirements) { }
}
