package com.circulation.prototype_eng.storage;

import com.circulation.prototype_eng.Tags;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.World;
import net.minecraftforge.event.world.WorldEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import org.jetbrains.annotations.Nullable;

import java.util.function.Consumer;

/**
 * Server-owner location links. Tile lifecycles publish loaded sources and update subscribed mirrors;
 * machine work never resolves a tile through World. Callbacks only replace their local binding.
 */
@Mod.EventBusSubscriber(modid = Tags.MOD_ID)
public final class MEPatternMirrorLinks {
    private static final Reference2ObjectOpenHashMap<MinecraftServer, MEPatternMirrorLinks> SERVERS = new Reference2ObjectOpenHashMap<>();
    private final Int2ObjectOpenHashMap<Long2ObjectOpenHashMap<Endpoint>> dimensions = new Int2ObjectOpenHashMap<>();

    public static MEPatternMirrorLinks forWorld(World world) {
        return SERVERS.computeIfAbsent(world.getMinecraftServer(), _ -> new MEPatternMirrorLinks());
    }

    public static void stop() {
        for (var links : SERVERS.values()) links.clear();
        SERVERS.clear();
    }

    @SubscribeEvent
    public static void worldUnloaded(WorldEvent.Unload event) {
        var world = event.getWorld();
        if (world.isRemote) return;
        var links = SERVERS.get(world.getMinecraftServer());
        if (links != null) links.unloadDimension(world.provider.getDimension());
    }

    private Endpoint endpoint(int dimension, long position) {
        var entries = dimensions.computeIfAbsent(dimension, _ -> new Long2ObjectOpenHashMap<>());
        return entries.computeIfAbsent(position, _ -> new Endpoint(dimension, position));
    }

    public Publication publish(int dimension, long position, MEPatternAssemblyStorage storage) {
        var endpoint = endpoint(dimension, position);
        var publication = new Publication(endpoint, storage);
        endpoint.source = publication;
        endpoint.changed(storage);
        return publication;
    }

    public Subscription subscribe(int mirrorDimension, int sourceDimension, long position,
                                  Consumer<@Nullable MEPatternAssemblyStorage> changed) {
        var endpoint = endpoint(sourceDimension, position);
        var subscription = new Subscription(mirrorDimension, endpoint, changed);
        endpoint.mirrors.add(subscription);
        changed.accept(endpoint.source == null ? null : endpoint.source.storage);
        return subscription;
    }

    private void prune(Endpoint endpoint) {
        if (endpoint.source != null || !endpoint.mirrors.isEmpty()) return;
        var entries = dimensions.get(endpoint.dimension);
        entries.remove(endpoint.position);
        if (entries.isEmpty()) dimensions.remove(endpoint.dimension);
    }

    void unloadDimension(int dimension) {
        for (var worlds = dimensions.int2ObjectEntrySet().fastIterator(); worlds.hasNext(); ) {
            var entries = worlds.next().getValue();
            for (var positions = entries.long2ObjectEntrySet().fastIterator(); positions.hasNext(); ) {
                var endpoint = positions.next().getValue();
                if (endpoint.dimension == dimension && endpoint.source != null) {
                    endpoint.source = null;
                    endpoint.changed(null);
                }
                for (int index = endpoint.mirrors.size() - 1; index >= 0; index--) {
                    var subscription = endpoint.mirrors.get(index);
                    if (subscription.dimension != dimension) continue;
                    endpoint.mirrors.removeElements(index, index + 1);
                    subscription.closed = true;
                    subscription.changed.accept(null);
                }
                if (endpoint.source == null && endpoint.mirrors.isEmpty()) positions.remove();
            }
            if (entries.isEmpty()) worlds.remove();
        }
    }

    private void clear() {
        for (var entries : dimensions.values())
            for (var endpoint : entries.values()) {
                endpoint.source = null;
                for (int index = 0; index < endpoint.mirrors.size(); index++) {
                    var subscription = endpoint.mirrors.get(index);
                    subscription.closed = true;
                    subscription.changed.accept(null);
                }
            }
        dimensions.clear();
    }

    private static final class Endpoint {
        private final int dimension;
        private final long position;
        private final ObjectArrayList<Subscription> mirrors = new ObjectArrayList<>();
        private @Nullable Publication source;

        private Endpoint(int dimension, long position) {
            this.dimension = dimension;
            this.position = position;
        }

        private void changed(@Nullable MEPatternAssemblyStorage storage) {
            for (int index = 0; index < mirrors.size(); index++) mirrors.get(index).changed.accept(storage);
        }
    }

    public final class Publication implements AutoCloseable {
        private final Endpoint endpoint;
        private MEPatternAssemblyStorage storage;

        private Publication(Endpoint endpoint, MEPatternAssemblyStorage storage) {
            this.endpoint = endpoint;
            this.storage = storage;
        }

        public void replace(MEPatternAssemblyStorage next) {
            if (storage == next) return;
            storage = next;
            if (endpoint.source == this) endpoint.changed(next);
        }

        @Override
        public void close() {
            if (endpoint.source != this) return;
            endpoint.source = null;
            endpoint.changed(null);
            prune(endpoint);
        }
    }

    public final class Subscription implements AutoCloseable {
        private final int dimension;
        private final Endpoint endpoint;
        private final Consumer<@Nullable MEPatternAssemblyStorage> changed;
        private boolean closed;

        private Subscription(int dimension, Endpoint endpoint, Consumer<@Nullable MEPatternAssemblyStorage> changed) {
            this.dimension = dimension;
            this.endpoint = endpoint;
            this.changed = changed;
        }

        @Override
        public void close() {
            if (closed) return;
            closed = true;
            endpoint.mirrors.remove(this);
            changed.accept(null);
            prune(endpoint);
        }
    }
}
