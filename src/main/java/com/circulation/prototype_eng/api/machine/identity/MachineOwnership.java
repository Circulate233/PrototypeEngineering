package com.circulation.prototype_eng.api.machine.identity;

import com.circulation.prototype_eng.Tags;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.persistence.MachineComponentCodec;
import com.circulation.prototype_eng.machine.ServerMachineIdentities;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;

/**
 * Explicit persistent ownership. Unique registration is not a team, network or recipe-work permit.
 * Fresh placement requires a trusted real player; restored identity never adopts a new placer.
 */
public final class MachineOwnership implements MachineLifecycleListener {
    public static final ComponentType<MachineInstance, MachineOwnership> TYPE = new ComponentType<>(
        new ResourceLocation(Tags.MOD_ID, "ownership"),
        owner -> new MachineOwnership(owner, host -> ServerMachineIdentities.forServer(host.server())), List.of());

    public static final MachineComponentCodec<MachineOwnership> CODEC = new MachineComponentCodec<>() {
        @Override
        public NBTTagCompound encode(MachineInstance owner, MachineOwnership component) {
            component.requireOwner(owner);
            owner.requirePersistenceBoundary();
            return component.identity().encode();
        }

        @Override
        public MachineOwnership restore(MachineInstance owner, MachineOwnership component, NBTTagCompound saved) {
            component.requireOwner(owner);
            owner.requirePersistenceBoundary();
            if (component.loadAttempted || component.identity != null) {
                throw new IllegalStateException("Ownership restore requires a fresh component");
            }
            component.identity = PersistentMachineIdentity.decode(saved);
            return component;
        }

        @Override
        public void validate(MachineInstance owner, MachineOwnership component) {
            component.requireOwner(owner);
        }
    };

    private final MachineInstance owner;
    private final Function<MachineLifecycleHost, MachineIdentityRegistry> registry;
    @Nullable
    private PersistentMachineIdentity identity;
    @Nullable
    private MachineIdentityRegistry.Registration registration;
    private boolean loadAttempted;

    /**
     * External hosts may supply their own server-session registry at the same lifecycle boundary.
     */
    public MachineOwnership(MachineInstance owner, Function<MachineLifecycleHost, MachineIdentityRegistry> registry) {
        this.owner = owner;
        this.registry = registry;
    }

    public PersistentMachineIdentity identity() {
        return Objects.requireNonNull(identity, "Machine has no valid persistent ownership");
    }

    /**
     * The exact transient registration; callers must not interpret it as a work permit.
     */
    @Nullable
    public MachineIdentityRegistry.Registration registration() {
        return registration;
    }

    @Override
    @SuppressWarnings("resource")
    public void onLoaded(MachineLifecycleEvent.Loaded event) {
        requireOwner(event.context().machine());
        var host = event.context().host();
        host.requireServerThread();
        if (loadAttempted) {
            throw new IllegalStateException("Ownership component cannot load twice");
        }
        loadAttempted = true;
        if (event.boundary() == MachineLifecycleEvent.LoadBoundary.FRESH) {
            if (identity != null) {
                throw new IllegalStateException("Fresh ownership cannot contain a restored identity");
            }
            var actor = host.placementPlayer();
            if (actor == null) {
                throw new IllegalStateException("Fresh owned machine requires a real placement player");
            }
            identity = new PersistentMachineIdentity(UUID.randomUUID(), actor);
        }
        registration = registry.apply(host)
                               .register(identity(), event.context().identity(), host.worldIdentity(), owner);
    }

    @Override
    public void onUnloaded(MachineLifecycleEvent.Unloaded event) {
        requireOwner(event.context().machine());
        event.context().host().requireServerThread();
        if (registration != null) {
            registration.close();
        }
    }

    private void requireOwner(MachineInstance candidate) {
        if (owner != candidate) {
            throw new IllegalArgumentException("Ownership belongs to a different machine instance");
        }
    }
}
