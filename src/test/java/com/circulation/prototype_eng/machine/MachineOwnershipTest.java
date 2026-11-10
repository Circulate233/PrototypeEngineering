package com.circulation.prototype_eng.machine;

import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.localization.GuiText;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("resource") // Fixture-local registries and subscriptions share the test lifetime.
class MachineOwnershipTest {
    private static Definition definition(MachineIdentityRegistry registry) {
        var ownership = new ComponentType<MachineInstance, MachineOwnership>(id("ownership"),
            owner -> new MachineOwnership(owner, _ -> registry), List.of());
        var type = MachineType.builder(id("machine"), GuiText.ResourceStorage, id("structure")).component(ownership).build();
        return new Definition(ownership, MachineAssembly.builder(type, "v1").component(ownership, MachineOwnership.CODEC).build());
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("prototype_eng_test", path);
    }

    @Test
    void copiedSaveKeepsOriginalOwnerAndBothCopiesLoseUniquenessUntilOneUnloads() {
        var registry = new MachineIdentityRegistry(() -> {
        });
        var definition = definition(registry);
        var world = new Object();
        var actor = UUID.randomUUID();
        var first = definition.assembly.create(new Host(world, BlockPos.ORIGIN, actor), new MachineLifecycleRegistry());
        var original = Objects.requireNonNull(first.components().get(definition.component));
        var saved = definition.assembly.encode(first);
        var restored = definition.assembly.restore(new Host(world, new BlockPos(1, 0, 0), UUID.randomUUID()), saved, new MachineLifecycleRegistry());
        var copy = Objects.requireNonNull(restored.components().get(definition.component));
        assertEquals(actor, copy.identity().ownerId());
        assertEquals(original.identity(), copy.identity());
        assertFalse(Objects.requireNonNull(original.registration()).unique());
        assertFalse(Objects.requireNonNull(copy.registration()).unique());
        assertFalse(Objects.requireNonNull(restored.lifecycle()).formed());
        Objects.requireNonNull(first.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        assertTrue(copy.registration().unique());
        assertEquals(saved, definition.assembly.encode(restored));
    }

    @Test
    void replacedLocationDoesNotLetOldUnloadEraseNewMachineRegistration() {
        var registry = new MachineIdentityRegistry(() -> {
        });
        var definition = definition(registry);
        var host = new Host(new Object(), BlockPos.ORIGIN, UUID.randomUUID());
        var first = definition.assembly.create(host, new MachineLifecycleRegistry());
        var firstOwned = Objects.requireNonNull(first.components().get(definition.component));
        var replacement = definition.assembly.restore(host, definition.assembly.encode(first), new MachineLifecycleRegistry());
        var nextOwned = Objects.requireNonNull(replacement.components().get(definition.component));
        assertFalse(Objects.requireNonNull(firstOwned.registration()).current());
        Objects.requireNonNull(first.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.INVALIDATED);
        assertTrue(Objects.requireNonNull(nextOwned.registration()).current());
        assertEquals(List.of(nextOwned.registration()), registry.uniqueRegistrations());
    }

    @Test
    void partialLoadFailureCleansOnlyItsOwnRegistrationAndRestoresOtherCopyUniqueness() {
        var registry = new MachineIdentityRegistry(() -> {
        });
        var definition = definition(registry);
        var world = new Object();
        var first = definition.assembly.create(new Host(world, BlockPos.ORIGIN, UUID.randomUUID()), new MachineLifecycleRegistry());
        var original = Objects.requireNonNull(first.components().get(definition.component));
        var listeners = new MachineLifecycleRegistry();
        listeners.register(new MachineLifecycleListener() {
            @Override
            public void onLoaded(MachineLifecycleEvent.Loaded event) {
                assertFalse(Objects.requireNonNull(original.registration()).unique());
                throw new IllegalStateException("later listener failed");
            }
        });
        var saved = definition.assembly.encode(first);
        assertThrows(IllegalStateException.class, () -> definition.assembly.restore(
            new Host(world, new BlockPos(1, 0, 0), null), saved, listeners));
        assertTrue(Objects.requireNonNull(original.registration()).unique());
        assertEquals(List.of(original.registration()), registry.uniqueRegistrations());
    }

    @Test
    void missingRealPlacerAndMalformedRestoredIdentityNeverRegister() {
        var registry = new MachineIdentityRegistry(() -> {
        });
        var definition = definition(registry);
        var world = new Object();
        assertThrows(IllegalStateException.class, () -> definition.assembly.create(
            new Host(world, BlockPos.ORIGIN, null), new MachineLifecycleRegistry()));
        assertTrue(registry.uniqueRegistrations().isEmpty());
        var first = definition.assembly.create(new Host(world, BlockPos.ORIGIN, UUID.randomUUID()), new MachineLifecycleRegistry());
        var saved = definition.assembly.encode(first);
        var entries = saved.getTagList("Components", 10);
        for (int index = 0; index < entries.tagCount(); index++) {
            var entry = entries.getCompoundTagAt(index);
            if (entry.getString("Type").equals(definition.component.id().toString())) {
                entry.getCompoundTag("Data").removeTag("ownerMost");
            }
        }
        var registration = Objects.requireNonNull(Objects.requireNonNull(first.components().get(definition.component)).registration());
        assertThrows(IllegalArgumentException.class, () -> definition.assembly.restore(
            new Host(world, new BlockPos(1, 0, 0), UUID.randomUUID()), saved, new MachineLifecycleRegistry()));
        assertEquals(List.of(registration), registry.uniqueRegistrations());
    }

    private record Definition(ComponentType<MachineInstance, MachineOwnership> component, MachineAssembly assembly) {
    }

    private record Host(Object worldIdentity, BlockPos position,
                        @Nullable UUID placementPlayer) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("machine"), 0, position);
        }

        @Override
        public World world() {
            throw new AssertionError("Ownership tests do not load a Minecraft world");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
