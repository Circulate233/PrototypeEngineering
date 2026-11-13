package com.circulation.prototype_eng.api.recipe;

import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.recipe.ServerRecipeDefinitions;
import com.circulation.prototype_eng.scheduler.TaskScheduler;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * One session's concrete recipe/codec/PN publisher, using the existing joined scheduler boundary.
 */
public final class RecipeDefinitionUpdates implements AutoCloseable {
    private final RecipeRegistry registry;
    private final NetworkRecipeExtensions extensions;
    private final TaskScheduler scheduler;
    private final RecipeRegistry.Publication publication;
    private final NetworkRecipeExtensions.PublicationBinding networkPublication;
    private final Consumer<Published> observer;
    private volatile boolean accepting = true;

    /**
     * Explicit independent owner binding; real servers obtain their exact session through forServer.
     */
    public RecipeDefinitionUpdates(RecipeRegistry registry, NetworkRecipeExtensions extensions, TaskScheduler scheduler,
                                   UUID sessionId, Consumer<Published> observer) {
        this.registry = Objects.requireNonNull(registry);
        this.extensions = Objects.requireNonNull(extensions);
        this.scheduler = Objects.requireNonNull(scheduler);
        Objects.requireNonNull(sessionId);
        this.observer = Objects.requireNonNull(observer);
        synchronized (this.registry) {
            synchronized (this.extensions) {
                if (!registry.canBindPublication(scheduler) || !extensions.canBindPublication()) {
                    throw new IllegalStateException("Recipe and network definitions cannot be adopted by this owner");
                }
                var catalog = registry.snapshot();
                for (var recipe : catalog.catalog().recipes()) extensions.require(recipe);
                for (var definition : catalog.historicalDefinitions()) extensions.require(definition.recipe());
                publication = registry.bindPublication(scheduler, sessionId);
                networkPublication = extensions.bindPublication(publication);
            }
        }
    }

    public static RecipeDefinitionUpdates forServer(MinecraftServer server) {
        return ServerRecipeDefinitions.forServer(server);
    }

    public UUID sessionId() {
        return publication.sessionId();
    }

    public RecipeRegistry.Snapshot snapshot() {
        return registry.snapshot();
    }

    public boolean accepting() {
        return accepting && publication.live();
    }

    /**
     * Pool and PN callbacks run once on the owner, after earlier accepted workers have really exited.
     */
    public CompletableFuture<Result> replace(UUID expectedSession, RecipeRegistry.Snapshot expected,
                                             Collection<RecipeRegistry.Definition> definitions, Collection<RecipeProcessNbt> codecs) {
        var request = new Request(Objects.requireNonNull(expected), null,
            List.copyOf(definitions), List.copyOf(codecs));
        return enqueue(expectedSession, request);
    }

    /**
     * Submit an already prepared directory from this same live Publication without rebuilding its pools.
     */
    public CompletableFuture<Result> submit(UUID expectedSession, RecipeRegistry.Prepared prepared) {
        Objects.requireNonNull(prepared);
        return enqueue(expectedSession, new Request(prepared.expected(), prepared, List.of(), List.of()));
    }

    private CompletableFuture<Result> enqueue(UUID expectedSession, Request request) {
        Objects.requireNonNull(expectedSession);
        var result = new CompletableFuture<Result>();
        if (!accepting() || !sessionId().equals(expectedSession)) {
            result.complete(new Result(Status.REJECTED_SESSION, sessionId(), null, null));
            return result;
        }
        try {
            scheduler.runAtBoundary(() -> execute(request, result));
        } catch (RuntimeException | Error failure) {
            // Closed queue admission is separate from an already completed update.
            if (result.isDone()) throw failure;
            result.complete(new Result(accepting() ? Status.REJECTED_CONTEXT : Status.REJECTED_SESSION, sessionId(), null, failure));
        }
        return result;
    }

    private void execute(Request request, CompletableFuture<Result> result) {
        if (!accepting()) {
            result.complete(new Result(Status.REJECTED_SESSION, sessionId(), null, null));
            return;
        }
        if (!registry.isCurrent(request.expected) || request.prepared != null && !publication.canInstall(request.prepared)) {
            result.complete(new Result(Status.REJECTED_STALE, sessionId(), null, null));
            return;
        }
        RecipeRegistry.Prepared prepared;
        NetworkRecipeExtensions.Prepared network;
        Published notice;
        try {
            publication.requireOwnerBoundary();
            prepared = request.prepared == null
                ? registry.prepareReplacement(request.expected, request.definitions, request.codecs) : request.prepared;
            if (!publication.canInstall(prepared)) {
                result.complete(new Result(Status.REJECTED_STALE, sessionId(), null, null));
                return;
            }
            var recipes = new ObjectArrayList<MachineRecipe>(prepared.replacements().size());
            for (var definition : prepared.replacements()) recipes.add(definition.recipe());
            network = extensions.prepare(extensions.snapshot(), recipes);
            notice = new Published(sessionId(), prepared.expected(), prepared.next());
        } catch (RuntimeException | Error failure) {
            var status = !accepting() ? Status.REJECTED_SESSION
                : !registry.isCurrent(request.expected) ? Status.REJECTED_STALE : Status.PREPARATION_FAILED;
            result.complete(new Result(status, sessionId(), null, failure));
            return;
        }
        try {
            if (!install(prepared, network)) {
                result.complete(new Result(accepting() ? Status.REJECTED_STALE : Status.REJECTED_SESSION, sessionId(), null, null));
                return;
            }
        } catch (RuntimeException | Error failure) {
            result.complete(new Result(Status.INSTALLATION_FAILED, sessionId(),
                registry.isCurrent(prepared.next()) ? notice : null, failure));
            return;
        }
        try {
            observer.accept(notice);
            result.complete(new Result(Status.PUBLISHED, sessionId(), notice, null));
        } catch (RuntimeException | Error failure) {
            result.complete(new Result(Status.NOTIFICATION_FAILED, sessionId(), notice, failure));
        }
    }

    private boolean install(RecipeRegistry.Prepared prepared, NetworkRecipeExtensions.Prepared network) {
        synchronized (registry) {
            var resolver = registry.processCodecs();
            if (resolver == null) return installWithNetwork(prepared, network);
            synchronized (resolver) {
                return installWithNetwork(prepared, network);
            }
        }
    }

    private boolean installWithNetwork(RecipeRegistry.Prepared prepared, NetworkRecipeExtensions.Prepared network) {
        synchronized (extensions) {
            if (!accepting() || !publication.canInstall(prepared) || !networkPublication.canInstall(network))
                return false;
            // Every potentially rejecting assertion precedes the first assignment, with all three monitors held.
            publication.requireOwnerBoundary();
            networkPublication.install(network);
            publication.install(prepared);
            return true;
        }
    }

    public void stopAccepting() {
        publication.requireOwner();
        accepting = false;
    }

    /**
     * Actual server shutdown calls this only after its scheduler has proved worker termination.
     */
    @Override
    public void close() {
        stopAccepting();
        publication.close();
    }

    public enum Status {PUBLISHED, REJECTED_SESSION, REJECTED_STALE, REJECTED_CONTEXT, PREPARATION_FAILED, INSTALLATION_FAILED, NOTIFICATION_FAILED}

    public record Published(UUID sessionId, RecipeRegistry.Snapshot previous, RecipeRegistry.Snapshot current) {
    }

    public record Result(Status status, UUID sessionId, @Nullable Published publication, @Nullable Throwable failure) {
        public boolean published() {
            return publication != null;
        }
    }

    private record Request(RecipeRegistry.Snapshot expected, @Nullable RecipeRegistry.Prepared prepared,
                           List<RecipeRegistry.Definition> definitions, List<RecipeProcessNbt> codecs) {
    }
}
