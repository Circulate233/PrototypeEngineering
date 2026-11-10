package com.circulation.prototype_eng.client.render;

import ae2.tile.AEBaseTile;
import com.circulation.prototype_eng.api.machine.MachineControllerHost;
import com.circulation.prototype_eng.client.render.gecko.GeckoModelBaker;
import com.circulation.prototype_eng.client.render.gecko.GeckoRenderRequest;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.tileentity.TileEntityRendererDispatcher;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.function.BooleanSupplier;

/** Client-only collection of exact accepted views. Workers receive immutable requests and identity tokens. */
public class MachineModelRenderState implements AutoCloseable {
    private static final List<GeckoModelBaker.BakeMode> ANIMATED = List.of(GeckoModelBaker.BakeMode.PERMANENT_STATIC_ONLY,
        GeckoModelBaker.BakeMode.TEMP_STATIC_ONLY, GeckoModelBaker.BakeMode.ANIMATED_ONLY);
    private final ModelBuildCache cache;
    private final MachineRenderOptions options;
    private final MachineModelHides hides = new MachineModelHides();
    private final Reference2ObjectOpenHashMap<AEBaseTile, State> states = new Reference2ObjectOpenHashMap<>();
    private final ObjectArrayList<MachineModelPasses.Entry> pending = new ObjectArrayList<>();

    public MachineModelRenderState(ModelBuildCache cache, MachineRenderOptions options) {
        this.cache = cache;
        this.options = options;
    }

    /** A coarse camera frame keeps nearby machines in the same native draw bucket. */
    static BlockPos worldOrigin(double cameraX, double cameraY, double cameraZ, int renderDistanceChunks) {
        long grid = Math.max(256L, Math.max(2L, renderDistanceChunks) * 32L);
        return new BlockPos(snap(cameraX, grid), snap(cameraY, grid), snap(cameraZ, grid));
    }

    private static int snap(double camera, long grid) {
        double coordinate = Math.floor(camera);
        if (!Double.isFinite(coordinate) || coordinate < Long.MIN_VALUE || coordinate >= 0x1p63)
            throw new IllegalArgumentException("Invalid render camera coordinate: " + camera);
        return Math.toIntExact(Math.multiplyExact(Math.floorDiv((long) coordinate, grid), grid));
    }

    private static BlockPos currentWorldOrigin() {
        return worldOrigin(TileEntityRendererDispatcher.staticPlayerX, TileEntityRendererDispatcher.staticPlayerY,
            TileEntityRendererDispatcher.staticPlayerZ, Minecraft.getMinecraft().gameSettings.renderDistanceChunks);
    }

    private static boolean sameGeometry(List<StructureRenderAnchors.Anchor> first, List<StructureRenderAnchors.Anchor> second) {
        if (first.size() != second.size()) return false;
        for (int i = 0; i < first.size(); i++) {
            var a = first.get(i);
            var b = second.get(i);
            if (!a.part().equals(b.part()) || !a.origin().equals(b.origin()) || a.orientation() != b.orientation() || !a.binding().equals(b.binding()))
                return false;
        }
        return true;
    }

    private static void closeEntries(List<MachineModelPasses.Entry> entries) {
        Throwable failure = null;
        for (var entry : entries)
            try {
                entry.lease().close();
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        BuiltModelBuffers.rethrow(failure);
    }

    public <T extends AEBaseTile & MachineControllerHost> void submit(T tile, ClientMachineRenderSnapshots.View view,
                                                                      BooleanSupplier accepted, float partialTicks) {
        var origin = currentWorldOrigin();
        var state = states.get(tile);
        var selected = state != null && state.view == view ? state.geometry : StructureRenderAnchors.select(view.snapshot(), view.bindings());
        if (state != null && (state.world != tile.getWorld() || !state.origin.equals(origin) || state.view.bindings() != view.bindings() || !sameGeometry(state.geometry, selected))) {
            withdraw(tile);
            state = null;
        }
        if (state == null) {
            state = new State(tile.getWorld(), selected, view, accepted, origin);
            states.put(tile, state);
        }
        state.view = view;
        state.accepted = accepted;
        state.geometry = selected;
        var captured = state;
        BooleanSupplier current = () -> states.get(tile) == captured && captured.view == view && accepted.getAsBoolean() &&
            !tile.isInvalid() && tile.getWorld() == captured.world;
        if (!current.getAsBoolean()) return;
        var sample = GeckoRenderRequest.Sample.at(Math.max(0, tile.getWorld().getTotalWorldTime() + partialTicks), options.animationStepTicks(), options.sampleSnap());
        var entries = new ObjectArrayList<MachineModelPasses.Entry>();
        boolean complete = true, drawable = false;
        var bounds = view.snapshot().bounds();
        AxisAlignedBB renderBounds = new AxisAlignedBB(bounds.min(), bounds.max().add(1, 1, 1));
        int light = tile.getWorld().getCombinedLight(tile.getPos(), 0);
        try {
            for (var anchor : selected) {
                var modes = anchor.binding().animation() == null ? List.of(GeckoModelBaker.BakeMode.ALL) : ANIMATED;
                for (var mode : modes) {
                    var request = GeckoRenderRequest.from(captured.owner, captured.bindingEpoch, cache.resourceEpoch(), anchor, origin, mode, sample);
                    var lease = cache.request(request);
                    if (lease == null) {
                        complete = false;
                        continue;
                    }
                    var result = lease.buffers();
                    drawable |= result.hasVertices();
                    if (result.bounds() != null) renderBounds = renderBounds.union(result.bounds());
                    var center = result.bounds() == null ? new Vec3d(anchor.origin()).add(.5, .5, .5) : result.bounds().getCenter();
                    var entry = new MachineModelPasses.Entry(lease, center, current, light);
                    try {
                        entries.add(entry);
                    } catch (RuntimeException | Error failure) {
                        try {
                            lease.close();
                        } catch (RuntimeException | Error cleanup) {
                            failure.addSuppressed(cleanup);
                        }
                        throw failure;
                    }
                }
            }
            captured.complete = complete;
            captured.bounds = renderBounds;
            if (complete && drawable && view.snapshot().display().formed() && current.getAsBoolean()) {
                if (captured.hidden == null || !captured.hidden.current(tile.getWorld(), tile)) {
                    if (captured.hidden != null) captured.hidden.close();
                    captured.hidden = hides.acquire(tile.getWorld(), tile, tile.getPos(), view.snapshot().hidePositions(), current);
                }
            } else if (captured.hidden != null) {
                captured.hidden.close();
                captured.hidden = null;
            }
            int prior = pending.size();
            try {
                pending.addAll(entries);
                entries.clear();
            } catch (RuntimeException | Error failure) {
                pending.subList(prior, pending.size()).clear();
                throw failure;
            }
        } catch (RuntimeException | Error failure) {
            try {
                withdraw(tile);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            try {
                closeEntries(entries);
            } catch (RuntimeException | Error cleanup) {
                failure.addSuppressed(cleanup);
            }
            throw failure;
        }
    }

    public void flush() {
        if (pending.isEmpty()) return;
        var entries = new ObjectArrayList<>(pending);
        pending.clear();
        Throwable failure = null;
        try {
            var camera = new Vec3d(TileEntityRendererDispatcher.staticPlayerX,
                TileEntityRendererDispatcher.staticPlayerY, TileEntityRendererDispatcher.staticPlayerZ);
            MachineModelPasses.draw(cache, entries, camera, camera, true, failureRevoker());
        } catch (RuntimeException | Error exception) {
            failure = exception;
            // A failed native replacement must revoke its cached draw and world-hiding permissions.
            for (var tile : List.copyOf(states.keySet()))
                try {
                    withdraw(tile);
                } catch (RuntimeException | Error cleanup) {
                    failure = BuiltModelBuffers.append(failure, cleanup);
                }
            try {
                MachineModelBloom.clear();
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
        }
        try {
            closeEntries(entries);
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    public boolean global(AEBaseTile tile, ClientMachineRenderSnapshots.View view) {
        var state = states.get(tile);
        if (state == null || state.view != view || !state.accepted.getAsBoolean() || !state.origin.equals(currentWorldOrigin()) || !state.complete)
            return !StructureRenderAnchors.select(view.snapshot(), view.bindings()).isEmpty();
        return state.geometry.stream().anyMatch(anchor -> anchor.binding().forceGlobalRenderer()) ||
            state.bounds.maxX - state.bounds.minX > 32 || state.bounds.maxY - state.bounds.minY > 32 || state.bounds.maxZ - state.bounds.minZ > 32;
    }

    public AxisAlignedBB bounds(AEBaseTile tile) {
        var state = states.get(tile);
        return state == null || !state.accepted.getAsBoolean() ? new AxisAlignedBB(tile.getPos()) : state.bounds;
    }

    public AxisAlignedBB bounds(AEBaseTile tile, ClientMachineRenderSnapshots.View view) {
        var state = states.get(tile);
        // Unknown model extents must reach the owner once; native frustum/distance checks happen before render.
        if (state == null || state.view != view || !state.origin.equals(currentWorldOrigin()) || !state.complete || state.geometry.stream().anyMatch(anchor -> anchor.binding().forceGlobalRenderer()))
            return MachineControllerRenderer.CONSERVATIVE_BOUNDS;
        return state.bounds;
    }

    private Runnable failureRevoker() {
        var captured = new ObjectArrayList<OwnedView>();
        for (var entry : states.reference2ObjectEntrySet())
            captured.add(new OwnedView(entry.getKey(), entry.getValue(), entry.getValue().view));
        return () -> {
            Throwable failure = null;
            for (var owned : captured)
                if (states.get(owned.tile) == owned.state && owned.state.view == owned.view) try {
                    withdraw(owned.tile);
                } catch (RuntimeException | Error cleanup) {
                    failure = BuiltModelBuffers.append(failure, cleanup);
                }
            BuiltModelBuffers.rethrow(failure);
        };
    }

    /** Revoked views release draw/hide rights immediately while immutable geometry may remain cached for the successor. */
    public void invalidateViews() {
        for (var state : states.values()) state.complete = false;
        var entries = new ObjectArrayList<>(pending);
        pending.clear();
        Throwable failure = null;
        try {
            closeEntries(entries);
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            hides.tick();
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        try {
            MachineModelBloom.clear();
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    public void tick() {
        Throwable failure = null;
        BlockPos origin = null;
        try {
            origin = currentWorldOrigin();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        for (var tile : List.copyOf(states.keySet()))
            try {
                var state = states.get(tile);
                if (state != null && (tile.isInvalid() || tile.getWorld() != state.world || !state.origin.equals(origin)))
                    withdraw(tile);
            } catch (RuntimeException | Error cleanup) {
                failure = BuiltModelBuffers.append(failure, cleanup);
            }
        try {
            hides.tick();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    public void withdraw(AEBaseTile tile) {
        var old = states.remove(tile);
        if (old == null) return;
        Throwable failure = null;
        try {
            if (old.hidden != null) old.hidden.close();
        } catch (RuntimeException | Error exception) {
            failure = exception;
        }
        try {
            cache.retire(old.owner);
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    @Override
    public void close() {
        Throwable failure = null;
        for (var tile : List.copyOf(states.keySet()))
            try {
                withdraw(tile);
            } catch (RuntimeException | Error exception) {
                failure = BuiltModelBuffers.append(failure, exception);
            }
        try {
            closeEntries(pending);
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        pending.clear();
        try {
            hides.close();
        } catch (RuntimeException | Error exception) {
            failure = BuiltModelBuffers.append(failure, exception);
        }
        try {
            MachineModelBloom.clear();
        } catch (RuntimeException | Error cleanup) {
            failure = BuiltModelBuffers.append(failure, cleanup);
        }
        BuiltModelBuffers.rethrow(failure);
    }

    private static final class State {
        final GeckoRenderRequest.Owner owner = new GeckoRenderRequest.Owner();
        final GeckoRenderRequest.Epoch bindingEpoch = new GeckoRenderRequest.Epoch();
        final Object world;
        final BlockPos origin;
        List<StructureRenderAnchors.Anchor> geometry;
        ClientMachineRenderSnapshots.View view;
        BooleanSupplier accepted;
        AxisAlignedBB bounds;
        boolean complete;
        MachineModelHides.@Nullable Lease hidden;

        State(Object world, List<StructureRenderAnchors.Anchor> geometry, ClientMachineRenderSnapshots.View view, BooleanSupplier accepted, BlockPos origin) {
            this.world = world;
            this.origin = origin;
            this.geometry = geometry;
            this.view = view;
            this.accepted = accepted;
            var bounds = view.snapshot().bounds();
            this.bounds = new AxisAlignedBB(bounds.min(), bounds.max().add(1, 1, 1));
        }
    }

    private record OwnedView(AEBaseTile tile, State state, ClientMachineRenderSnapshots.View view) {
    }
}
