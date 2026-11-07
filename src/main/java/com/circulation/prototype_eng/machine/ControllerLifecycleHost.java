package com.circulation.prototype_eng.machine;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.HatchCapabilities;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.structure.MachineStructure;
import com.circulation.prototype_eng.api.structure.MachineStructureHost;
import com.circulation.prototype_eng.api.structure.StructureBounds;
import com.circulation.prototype_eng.api.structure.StructureConfiguration;
import com.circulation.prototype_eng.api.structure.StructureHatchBinding;
import com.circulation.prototype_eng.api.structure.StructureQuery;
import com.circulation.prototype_eng.structure.StructureRefresh;
import com.circulation.prototype_eng.structure.WorldStructureSource;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

import com.circulation.prototype_eng.api.structure.StructureComponentProvider;

/** Binds an already attached server-side controller without registering a block or a scheduler. */
@SuppressWarnings("unused") // Public adapter for downstream controller tile entities.
public final class ControllerLifecycleHost implements MachineStructureHost {
    private final TileEntity controller;
    private final World world;
    private final MachineIdentity identity;
    @Nullable
    private final ResourceLocation controllerBlockId;
    private final WorldStructureSource structureSource;
    @Nullable private final UUID placementPlayer;
    @Nullable private StructureConfiguration configuration;

    public ControllerLifecycleHost(ResourceLocation machineType, TileEntity controller) {
        this(machineType, controller, null);
    }

    public ControllerLifecycleHost(ResourceLocation machineType, TileEntity controller, @Nullable UUID placementPlayer) {
        this(machineType, controller, placementPlayer,
            Objects.requireNonNull(controller.getWorld(), "Machine controller has no world").getBlockState(controller.getPos()).getBlock().getRegistryName());
    }

    /** Terminal hosts may supply no block provenance after vanilla has replaced the old block. */
    public ControllerLifecycleHost(ResourceLocation machineType, TileEntity controller, @Nullable UUID placementPlayer,
                                   @Nullable ResourceLocation controllerBlockId) {
        this.controller = Objects.requireNonNull(controller);
        this.placementPlayer = placementPlayer;
        world = Objects.requireNonNull(controller.getWorld(), "Machine controller has no world");
        identity = new MachineIdentity(machineType, world.provider.getDimension(), controller.getPos());
        this.controllerBlockId = controllerBlockId;
        structureSource = new WorldStructureSource(this);
        requireServerThread();
    }

    @Override public MachineIdentity identity() { return identity; }

    @Override
    @Nullable
    public ResourceLocation controllerBlockId() {
        return controllerBlockId;
    }

    @Override public World world() { return world; }
    @Override @Nullable public UUID placementPlayer() { return placementPlayer; }

    /** The bound server thread remains available for cleanup after controller invalidation/removal. */
    @Override public void requireServerThread() {
        if (world.isRemote || !server().isCallingFromMinecraftThread()) {
            throw new IllegalStateException("Machine lifecycle requires its owning server thread");
        }
    }

    /**
     * Starts a new configuration identity on every call, including unchanged definitions/directions.
     * The caller must first join pending machine/structure work and use {@link StructureRefresh#invalidate()}
     * to clear old bindings. Definition, orientation and owner/permission generation changes must all
     * pass through this operation; configuration identities are never recovered from saved permission.
     */
    public void configureStructure(MachineStructure definition, BlockOrientation orientation) {
        requireServerThread();
        configuration = new StructureConfiguration(definition, orientation);
    }

    /**
     * Revokes current structure permission. The caller must first join pending machine/structure work
     * and use {@link StructureRefresh#invalidate()} to clear old bindings at its exclusive boundary.
     */
    public void revokeStructure() {
        requireServerThread();
        configuration = null;
    }

    @Override
    @Nullable
    public StructureConfiguration structureConfiguration() {
        requireServerThread();
        BlockPos position = identity.controllerPos();
        if (configuration == null || controller.getWorld() != world || controller.isInvalid()
            || !position.equals(controller.getPos()) || world.provider.getDimension() != identity.dimension()
            || !world.isAreaLoaded(position, position, false) || world.getTileEntity(position) != controller) {
            return null;
        }
        return configuration;
    }

    @Override public boolean isAreaLoaded(StructureBounds bounds) { return structureSource.isAreaLoaded(bounds); }

    @Override @Nullable public <T> T read(StructureQuery<T> query, BlockPos position) {
        return structureSource.read(query, position);
    }

    @Override
    public StructureHatchBinding hatchesAt(BlockPos position) {
        requireServerThread();
        BlockPos immutablePosition = Objects.requireNonNull(position).toImmutable();
        if (!world.isAreaLoaded(immutablePosition, immutablePosition, false)) {
            throw new IllegalStateException("Cannot discover hatches at an unloaded position: " + immutablePosition);
        }
        TileEntity provider = world.getTileEntity(immutablePosition);
        return new StructureHatchBinding(provider, HatchCapabilities.findResource(provider, null),
            HatchCapabilities.findEnergyInput(provider, null), HatchCapabilities.findEnergyOutput(provider, null));
    }

    @Override
    @Nullable
    public StructureComponentProvider structureComponentsAt(BlockPos position) {
        requireServerThread();
        BlockPos immutablePosition = Objects.requireNonNull(position).toImmutable();
        if (!world.isAreaLoaded(immutablePosition, immutablePosition, false)) {
            throw new IllegalStateException("Cannot discover structure ports at an unloaded position: " + immutablePosition);
        }
        TileEntity provider = world.getTileEntity(immutablePosition);
        return provider != null && !provider.isInvalid() && provider instanceof StructureComponentProvider ports ? ports : null;
    }
}
