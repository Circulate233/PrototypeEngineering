package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Register during RecipeRegistrationEvent; frozen definitions are available after mod post-init.
 */
public final class RecipeRegistry {
    public static final RecipeRegistry INSTANCE = new RecipeRegistry();
    private final Object2ObjectLinkedOpenHashMap<ResourceLocation, PoolRegistration> registrations = new Object2ObjectLinkedOpenHashMap<>();
    private final Reference2ObjectOpenHashMap<MachineRecipe, String> revisionDeclarations = new Reference2ObjectOpenHashMap<>();
    private final ObjectArrayList<Definition> historicalDeclarations = new ObjectArrayList<>();
    @Nullable
    private RecipeProcessCodecs processCodecs;
    @Nullable
    private RecipeProcessCodecs.RegistryBinding codecPublisher;
    private volatile Snapshot frozen;
    @Nullable
    private volatile Publication publication;

    private static Map<ResourceLocation, MachineRecipe> canonicalRecipes(RecipeCatalog catalog) {
        var recipes = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe>();
        var elements = catalog.recipes();
        for (int index1 = 0; index1 < elements.size(); index1++) {
            var recipe = elements.get(index1);
            recipes.put(recipe.id(), recipe);
        }
        return recipes;
    }

    private static void addRevision(Reference2ObjectOpenHashMap<MachineRecipe, String> revisions,
                                    Object2ObjectLinkedOpenHashMap<RecipeDefinitionRevision, MachineRecipe> history,
                                    MachineRecipe recipe, String revision) {
        var previousRevision = revisions.putIfAbsent(recipe, revision);
        var identity = new RecipeDefinitionRevision(recipe.id(), revision);
        var previousRecipe = history.putIfAbsent(identity, recipe);
        if (previousRevision != null && !previousRevision.equals(revision) || previousRecipe != null && previousRecipe != recipe) {
            throw new IllegalArgumentException("Conflicting recipe revision: " + identity);
        }
    }

    public void register(RecipePool pool) {
        requireRegistration();
        var id = pool.id();
        if (registrations.containsKey(id)) {
            throw new IllegalArgumentException("Duplicate recipe pool: " + id);
        }
        registrations.put(id, new PoolRegistration(pool));
    }

    public void registerPool(ResourceLocation id, String translationKey) {
        register(new DefaultRecipePool(id, translationKey, ObjectLists.emptyList()));
    }

    /**
     * Adds to a previously declared pool, including one owned by another addon. Recipe order is priority.
     */
    public void registerRecipe(ResourceLocation poolId, MachineRecipe recipe) {
        requireRegistration();
        var pool = registrations.get(poolId);
        if (pool == null) {
            throw new IllegalArgumentException("Unknown recipe pool: " + poolId);
        }
        if (pool.recipes.containsKey(recipe.id())) {
            throw new IllegalArgumentException("Duplicate recipe in pool " + poolId + ": " + recipe.id());
        }
        pool.recipes.put(recipe.id(), recipe);
    }

    /**
     * Declare a current canonical recipe's behavior revision, without requiring a persistence codec.
     */
    public void declareRevision(MachineRecipe recipe, String revision) {
        requireRegistration();
        var definition = new Definition(recipe, revision);
        var previous = revisionDeclarations.putIfAbsent(definition.recipe(), definition.revision());
        if (previous != null && !previous.equals(revision)) {
            throw new IllegalArgumentException("One recipe object cannot have multiple revisions: " + recipe.id());
        }
    }

    /**
     * Explicit cold-start history; these objects do not become automatic scan candidates.
     */
    public void declareHistoricalRevision(MachineRecipe recipe, String revision) {
        requireRegistration();
        historicalDeclarations.add(new Definition(recipe, revision));
    }

    /**
     * Optional shared resolver for machines that explicitly follow this registry with persistence.
     */
    public void bindProcessCodecs(RecipeProcessCodecs codecs) {
        requireRegistration();
        if (processCodecs != null) throw new IllegalStateException("Process codecs are already bound");
        codecPublisher = codecs.bindRegistry(this);
        processCodecs = codecs;
    }

    @Nullable
    public RecipeProcessCodecs processCodecs() {
        return processCodecs;
    }

    /**
     * Called by the lifecycle owner, once all Java registrations have been dispatched.
     */
    public void freeze() {
        freeze(List.of());
    }

    /**
     * Resolve every static machine reference before publishing pools, reverse bindings or indexes.
     */
    public void freeze(List<MachineType> machines) {
        if (frozen != null) {
            throw new IllegalStateException("Recipe registry already frozen");
        }
        var users = new Object2ObjectLinkedOpenHashMap<ResourceLocation, ObjectArrayList<MachineType>>();
        var machineIds = new Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineType>(machines.size());
        for (int index6 = 0; index6 < machines.size(); index6++) {
            var machine = machines.get(index6);
            if (machineIds.putIfAbsent(machine.id(), machine) != null) {
                throw new IllegalArgumentException("Duplicate machine declaration: " + machine.id());
            }
            var elements4 = machine.recipePoolIds();
            for (int index4 = 0; index4 < elements4.size(); index4++) {
                var poolId = elements4.get(index4);
                if (!registrations.containsKey(poolId)) {
                    throw new IllegalArgumentException("Unknown recipe pool " + poolId + " for machine " + machine.id());
                }
                users.computeIfAbsent(poolId, _ -> new ObjectArrayList<>()).add(machine);
            }
        }
        var pools = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>(registrations.size());
        for (var entry : registrations.object2ObjectEntrySet()) {
            var registration = entry.getValue();
            var recipes = new ObjectArrayList<>(registration.recipes.values());
            List<MachineType> boundMachines = users.get(entry.getKey());
            if (boundMachines == null) boundMachines = ObjectLists.emptyList();
            var pool = registration.source.freeze(recipes, boundMachines);
            pools.put(entry.getKey(), pool);
        }
        var ordered = new ObjectArrayList<>(pools.values());
        ordered.sort(Comparator.comparing(RecipePool::id));
        var catalog = new RecipeCatalog(ordered);
        var revisions = new Reference2ObjectOpenHashMap<MachineRecipe, String>();
        var history = new Object2ObjectLinkedOpenHashMap<RecipeDefinitionRevision, MachineRecipe>();
        var canonical = canonicalRecipes(catalog);
        revisionDeclarations.forEach((recipe, revision) -> {
            if (canonical.get(recipe.id()) != recipe) {
                throw new IllegalArgumentException("Revision declaration is not a current canonical recipe: " + recipe.id());
            }
            addRevision(revisions, history, recipe, revision);
        });
        for (int index = 0; index < historicalDeclarations.size(); index++) {
            var definition = historicalDeclarations.get(index);
            if (!canonical.containsKey(definition.recipe().id())) {
                throw new IllegalArgumentException("Historical recipe ID is not registered: " + definition.recipe().id());
            }
            if (canonical.get(definition.recipe().id()) == definition.recipe()) {
                throw new IllegalArgumentException("Current recipe must use declareRevision: " + definition.recipe().id());
            }
            addRevision(revisions, history, definition.recipe(), definition.revision());
        }
        if (processCodecs != null) {
            var elements2 = processCodecs.snapshot().codecs();
            for (int index2 = 0; index2 < elements2.size(); index2++) {
                var codec = elements2.get(index2);
                addRevision(revisions, history, codec.recipe(), codec.revision());
            }
        }
        frozen = new Snapshot(this, 0, pools, ordered, catalog, canonical, revisions, history);
        registrations.clear();
        revisionDeclarations.clear();
        historicalDeclarations.clear();
    }

    public Snapshot snapshot() {
        return requireFrozen();
    }

    public boolean isCurrent(Snapshot expected) {
        return frozen == expected;
    }

    /**
     * Adopts a frozen directory for one actual scheduler/session; closed bindings never become standalone again.
     */
    public synchronized Publication bindPublication(TaskScheduler scheduler, UUID sessionId) {
        if (!canBindPublication(scheduler))
            throw new IllegalStateException("Recipe publication is active or its scheduler is closing");
        publication = new Publication(this, scheduler, sessionId);
        return publication;
    }

    /**
     * Pure owner preflight used while a combined publisher holds registry and PN monitors.
     */
    public synchronized boolean canBindPublication(TaskScheduler scheduler) {
        var status = scheduler.status();
        requireFrozen();
        var previous = publication;
        return !status.closing() && !status.closed() && (previous == null || previous.closed
            && (previous.scheduler == scheduler || previous.schedulerTerminated));
    }

    public Prepared prepareReplacement(Snapshot expected, Collection<Definition> replacements) {
        return prepareReplacement(expected, replacements, List.of());
    }

    /**
     * Builds every changed pool privately using its original strategy. All owning pools receive the
     * same new recipe object at the original priority position; untouched pools retain their identity.
     * Supplied codecs are only for this replacement batch. An existing codec requires its successor,
     * but independent recipes without persistence bindings remain supported.
     */
    public Prepared prepareReplacement(Snapshot expected, Collection<Definition> replacements,
                                       Collection<RecipeProcessNbt> codecs) {
        var publisher = publication;
        if (!isCurrent(expected)) throw new IllegalArgumentException("Foreign or stale recipe snapshot");
        if (publisher != null && !publisher.live()) throw new IllegalStateException("Recipe publication is closed");
        var definitions = new Object2ObjectLinkedOpenHashMap<ResourceLocation, Definition>(replacements.size());
        var changedPools = new ReferenceOpenHashSet<RecipePool>();
        var revisions = new Reference2ObjectOpenHashMap<>(expected.revisions);
        var history = new Object2ObjectLinkedOpenHashMap<>(expected.history);
        for (var definition : replacements) {
            var recipe = definition.recipe();
            var old = expected.recipes.get(recipe.id());
            if (old == null || old == recipe)
                throw new IllegalArgumentException("Replacement needs a new object for an existing recipe: " + recipe.id());
            if (definitions.putIfAbsent(recipe.id(), definition) != null)
                throw new IllegalArgumentException("Duplicate replacement: " + recipe.id());
            addRevision(revisions, history, recipe, definition.revision());
            var owners = expected.catalog.poolsContaining(old);
            changedPools.addAll(owners);
        }
        if (definitions.isEmpty()) throw new IllegalArgumentException("A replacement batch must not be empty");
        var bindings = new ObjectArrayList<RecipeProcessNbt>(codecs.size());
        var supplied = new Reference2ObjectOpenHashMap<MachineRecipe, RecipeProcessNbt>(codecs.size());
        for (var codec : codecs) {
            var definition = definitions.get(codec.recipe().id());
            if (definition == null || definition.recipe() != codec.recipe() || !definition.revision().equals(codec.revision())) {
                throw new IllegalArgumentException("Codec does not match a replacement definition: " + codec.recipe().id());
            }
            var previous = supplied.putIfAbsent(codec.recipe(), codec);
            if (previous != null && previous != codec)
                throw new IllegalArgumentException("Conflicting replacement codec: " + codec.recipe().id());
            if (previous == null) bindings.add(codec);
        }
        RecipeProcessCodecs.Prepared codecPreparation = null;
        if (processCodecs == null) {
            if (!bindings.isEmpty())
                throw new IllegalArgumentException("Replacement codecs require the registry's shared resolver");
        } else {
            var currentCodecs = processCodecs.snapshot();
            for (var definition : definitions.values()) {
                var old = expected.recipes.get(definition.recipe().id());
                if (currentCodecs.get(old) != null && !supplied.containsKey(definition.recipe())) {
                    throw new IllegalArgumentException("Persisted recipe replacement requires its exact new codec: " + old.id());
                }
            }
            // The expected resolver is retained even when this batch adds no codec.
            codecPreparation = processCodecs.prepare(currentCodecs, bindings);
        }
        var pools = new Object2ObjectLinkedOpenHashMap<ResourceLocation, RecipePool>(expected.orderedPools.size());
        for (int index7 = 0; index7 < expected.orderedPools.size(); index7++) {
            var old = expected.orderedPools.get(index7);
            if (!changedPools.contains(old)) {
                pools.put(old.id(), old);
                continue;
            }
            var recipes = new ObjectArrayList<MachineRecipe>(old.recipes().size());
            var elements3 = old.recipes();
            for (int index3 = 0; index3 < elements3.size(); index3++) {
                var recipe = elements3.get(index3);
                var replacement = definitions.get(recipe.id());
                recipes.add(replacement == null ? recipe : replacement.recipe());
            }
            RecipePool pool = old.freeze(recipes, old.machines());
            pools.put(old.id(), pool);
        }
        var ordered = new ObjectArrayList<>(pools.values());
        var catalog = new RecipeCatalog(ordered);
        var next = new Snapshot(this, Math.addExact(expected.generation, 1), pools, ordered,
            catalog, canonicalRecipes(catalog), revisions, history);
        if (!isCurrent(expected) || publication != publisher || publisher != null && !publisher.live()) {
            throw new IllegalArgumentException("Recipe snapshot or publication changed during replacement preparation");
        }
        return new Prepared(this, expected, next, new ObjectArrayList<>(definitions.values()), codecPreparation, publisher);
    }

    public synchronized boolean canInstall(Prepared prepared) {
        return publication == null && matchesPrepared(prepared);
    }

    @SuppressWarnings("DataFlowIssue")
    private boolean matchesPrepared(Prepared prepared) {
        var capturedPublication = publication;
        return prepared.owner == this && !prepared.consumed && frozen == prepared.expected
            && prepared.publication == capturedPublication && (capturedPublication == null || !capturedPublication.closed)
            && (prepared.codecPreparation == null || processCodecs.canInstall(prepared.codecPreparation));
    }

    /**
     * Owner-boundary installation only, after external indexes and PN bindings have been prepared.
     * A combined publisher holds monitors in registry/resolver/PN order, rechecks all expected states,
     * then installs PN, codecs and finally this directory without callbacks or allocations. Keeping
     * those monitors across the entire interval excludes independent public installation calls.
     */
    public synchronized void install(Prepared prepared) {
        if (publication != null)
            throw new IllegalStateException("Adopted recipe registries publish only through their session owner");
        installWithCodecs(prepared);
    }

    private void installWithCodecs(Prepared prepared) {
        var resolver = processCodecs;
        if (resolver == null) installLocked(prepared);
        else synchronized (resolver) {
            installLocked(prepared);
        }
    }

    @SuppressWarnings("DataFlowIssue")
    private void installLocked(Prepared prepared) {
        if (!matchesPrepared(prepared))
            throw new IllegalStateException("Foreign, stale or consumed recipe preparation");
        if (prepared.codecPreparation != null)
            codecPublisher.install(prepared.codecPreparation);
        prepared.consumed = true;
        frozen = prepared.next;
    }

    /**
     * Downstream mods enumerate the frozen pools after registration has finished.
     */
    @SuppressWarnings({"unused", "UnusedReturnValue"})
    // External enumeration; internal tests only probe readiness failure.
    public List<RecipePool> pools() {
        return requireFrozen().orderedPools();
    }

    /**
     * Shared canonical recipe indexes; available only after the whole registry freezes successfully.
     */
    public RecipeCatalog catalog() {
        return requireFrozen().catalog();
    }

    @Nullable
    public RecipePool get(ResourceLocation id) {
        return requireFrozen().pools().get(id);
    }

    private Snapshot requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Recipe registry is not ready");
        }
        return current;
    }

    private void requireRegistration() {
        if (frozen != null) {
            throw new IllegalStateException("Recipe registration has ended");
        }
    }

    public record Definition(MachineRecipe recipe, String revision) {
        public Definition {
            if (revision.isBlank())
                throw new IllegalArgumentException("Recipe revision must not be blank");
        }
    }

    /**
     * Immutable canonical directory and declared history; identity, not generation alone, proves currency.
     */
    public static final class Snapshot {
        private final RecipeRegistry owner;
        private final long generation;
        private final Map<ResourceLocation, RecipePool> pools;
        private final List<RecipePool> orderedPools;
        private final RecipeCatalog catalog;
        private final Map<ResourceLocation, MachineRecipe> recipes;
        private final Reference2ObjectOpenHashMap<MachineRecipe, String> revisions;
        private final Object2ObjectLinkedOpenHashMap<RecipeDefinitionRevision, MachineRecipe> history;
        private final List<Definition> historicalDefinitions;

        private Snapshot(RecipeRegistry owner, long generation, Map<ResourceLocation, RecipePool> pools,
                         List<RecipePool> orderedPools, RecipeCatalog catalog, Map<ResourceLocation, MachineRecipe> recipes,
                         Reference2ObjectOpenHashMap<MachineRecipe, String> revisions,
                         Object2ObjectLinkedOpenHashMap<RecipeDefinitionRevision, MachineRecipe> history) {
            this.owner = owner;
            this.generation = generation;
            this.pools = pools;
            this.orderedPools = orderedPools;
            this.catalog = catalog;
            this.recipes = recipes;
            this.revisions = revisions;
            this.history = history;
            var definitions = new ObjectArrayList<Definition>(history.size());
            history.forEach((identity, recipe) -> definitions.add(new Definition(recipe, identity.revision())));
            historicalDefinitions = definitions;
        }

        public RecipeRegistry owner() {
            return owner;
        }

        public long generation() {
            return generation;
        }

        public List<RecipePool> orderedPools() {
            return orderedPools;
        }

        public RecipeCatalog catalog() {
            return catalog;
        }

        public Map<ResourceLocation, RecipePool> pools() {
            return pools;
        }

        /**
         * Declared current and historical objects for accurate cold-start dependency preparation.
         */
        public List<Definition> historicalDefinitions() {
            return historicalDefinitions;
        }

        /**
         * Null means this object has no explicit revision declaration, never an implied latest revision.
         */
        @Nullable
        public String revision(MachineRecipe recipe) {
            return revisions.get(recipe);
        }

        @Nullable
        public MachineRecipe historicalRecipe(RecipeDefinitionRevision identity) {
            return history.get(identity);
        }
    }

    /**
     * Single-use directory preparation; callers can prepare dependencies against next before installation.
     */
    public static final class Prepared {
        private final RecipeRegistry owner;
        private final Snapshot expected;
        private final Snapshot next;
        private final List<Definition> replacements;
        @Nullable
        private final RecipeProcessCodecs.Prepared codecPreparation;
        @Nullable
        private final Publication publication;
        private boolean consumed;

        private Prepared(RecipeRegistry owner, Snapshot expected, Snapshot next, List<Definition> replacements,
                         @Nullable RecipeProcessCodecs.Prepared codecPreparation, @Nullable Publication publication) {
            this.owner = owner;
            this.expected = expected;
            this.next = next;
            this.replacements = replacements;
            this.codecPreparation = codecPreparation;
            this.publication = publication;
        }

        public Snapshot expected() {
            return expected;
        }

        public Snapshot next() {
            return next;
        }

        public List<Definition> replacements() {
            return replacements;
        }

        @Nullable
        public RecipeProcessCodecs.Prepared codecPreparation() {
            return codecPreparation;
        }
    }

    /**
     * Concrete publication capability retained by the session owner, never retrieved from the registry.
     */
    public static final class Publication implements AutoCloseable {
        private final RecipeRegistry registry;
        private final TaskScheduler scheduler;
        private final UUID sessionId;
        private volatile boolean closed;
        private volatile boolean schedulerTerminated;

        private Publication(RecipeRegistry registry, TaskScheduler scheduler, UUID sessionId) {
            this.registry = registry;
            this.scheduler = scheduler;
            this.sessionId = sessionId;
        }

        public UUID sessionId() {
            return sessionId;
        }

        public boolean live() {
            return registry.publication == this && !closed;
        }

        public void requireOwner() {
            scheduler.status();
        }

        public void requireOwnerBoundary() {
            scheduler.requireOwnerBoundary();
            if (!live()) throw new IllegalStateException("Recipe publication is closed");
        }

        public boolean canInstall(Prepared prepared) {
            synchronized (registry) {
                return live() && prepared.publication == this && registry.matchesPrepared(prepared);
            }
        }

        public void install(Prepared prepared) {
            scheduler.requireOwnerBoundary();
            synchronized (registry) {
                if (!canInstall(prepared)) throw new IllegalStateException("Stale recipe publication or preparation");
                registry.installWithCodecs(prepared);
            }
        }

        /**
         * Shutdown may run after scheduler termination; it still belongs to the original owner.
         */
        @Override
        public void close() {
            var status = scheduler.status();
            synchronized (registry) {
                if (registry.publication != this) return;
                closed = true;
                schedulerTerminated |= status.closed();
            }
        }
    }

    private static final class PoolRegistration {
        final RecipePool source;
        final Object2ObjectLinkedOpenHashMap<ResourceLocation, MachineRecipe> recipes = new Object2ObjectLinkedOpenHashMap<>();

        PoolRegistration(RecipePool pool) {
            source = pool;
            String translationKey = pool.translationKey();
            if (translationKey == null || translationKey.isBlank())
                throw new IllegalArgumentException("Pool title is required");
            var elements5 = pool.recipes();
            for (int index5 = 0; index5 < elements5.size(); index5++) {
                MachineRecipe recipe = elements5.get(index5);
                if (recipes.putIfAbsent(recipe.id(), recipe) != null)
                    throw new IllegalArgumentException("Duplicate recipe in pool " + pool.id() + ": " + recipe.id());
            }
        }
    }
}
