package com.circulation.prototype_eng.network;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.component.MachineSystem;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleListener;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipePool;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanning;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamResolutionStatus;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import it.unimi.dsi.fastutil.objects.Object2LongMaps;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class MachineNetworkDeviceTest {
    private static final UUID OWNER = new UUID(0, 1);
    private static final UUID OTHER_PLACER = new UUID(0, 2);
    private static final ScanLimits SCAN = new ScanLimits(10, 10);

    @Test
    void pendingAndRejectedNetworksPauseAdmissionAndExistingWorkWhileOrdinaryComponentsKeepRunning() {
        try (var fixture = new Fixture()) {
            var machine = fixture.create();
            machine.owner.onSchedule();
            assertTrue(machine.work().activeProcesses().isEmpty());
            assertEquals(RecipeScanner.Status.PAUSED, fixture.scanner(machine).scan(SCAN).status());
            assertFalse(machine.work().start(new RecipeProcess(machine.owner, fixture.firstRecipe, 17)));
            assertEquals(1, fixture.ordinaryTicks(machine));
            assertEquals(50, machine.input.storedEnergy());

            assertTrue(fixture.network.refresh());
            machine.owner.onSchedule();
            var first = machine.process("first");
            var committed = first.state();
            assertEquals(1, committed.successfulTicks());
            assertEquals(45, machine.input.storedEnergy());
            assertNull(machine.work().requireSlot("second").process());

            fixture.rejected = true;
            assertTrue(fixture.network.refresh());
            machine.owner.onSchedule();
            machine.work().tickProcesses();
            first.tick();
            assertEquals(RecipeScanner.Status.PAUSED, fixture.scanner(machine).scan(SCAN).status());
            assertEquals(committed, first.state());
            assertNull(machine.work().requireSlot("second").process());
            assertEquals(45, machine.input.storedEnergy());
            assertEquals(3, fixture.ordinaryTicks(machine));

            fixture.rejected = false;
            assertTrue(fixture.network.refresh());
            machine.owner.onSchedule();
            assertSame(first, machine.process("first"));
            assertEquals(2, first.state().successfulTicks());
            assertEquals(1, machine.process("second").state().successfulTicks());
            assertEquals(38, machine.input.storedEnergy(), "Recovery ticks the old process without replaying its START");
            assertEquals(4, fixture.ordinaryTicks(machine));
        }
    }

    @Test
    void aCopiedSavePausesBothInstancesAndTheRestoredSurvivorNeedsAFreshCaptureBeforeResuming() {
        try (var fixture = new Fixture()) {
            var original = fixture.create();
            assertTrue(fixture.network.refresh());
            original.owner.onSchedule();
            var originalPermit = Objects.requireNonNull(fixture.networkDevice(original).workPermit());
            var originalWork = Objects.requireNonNull(originalPermit.reserve(id("saved_work"), 0, Object2LongMaps.emptyMap()));
            var first = original.process("first");
            var committed = first.state();
            var saved = fixture.assembly.encode(original.owner);
            var restored = fixture.restore(saved, new MachineLifecycleRegistry());
            var resumed = restored.process("first");
            assertNotSame(fixture.scanner(original), fixture.scanner(restored));
            assertNotSame(first, resumed);
            assertEquals(fixture.ownership(original).identity(), fixture.ownership(restored).identity());
            assertEquals(OWNER, fixture.ownership(restored).identity().ownerId());

            assertTrue(fixture.network.refresh());
            assertFalse(originalWork.commit());
            originalWork.rollback();
            original.owner.onSchedule();
            restored.owner.onSchedule();
            restored.work().tickProcesses();
            first.tick();
            resumed.tick();
            assertEquals(RecipeScanner.Status.PAUSED, fixture.scanner(restored).scan(SCAN).status());
            assertEquals(committed, first.state());
            assertEquals(committed, resumed.state());
            assertNull(original.work().requireSlot("second").process());
            assertNull(restored.work().requireSlot("second").process());
            assertEquals(45, original.input.storedEnergy());
            assertEquals(45, restored.input.storedEnergy());
            assertEquals(2, fixture.ordinaryTicks(original));
            assertEquals(1, fixture.ordinaryTicks(restored));

            fixture.unload(original);
            restored.owner.onSchedule();
            assertEquals(committed, resumed.state(), "Restored uniqueness cannot revive the pre-conflict publication");
            assertEquals(45, restored.input.storedEnergy());
            assertTrue(fixture.network.refresh());
            var restoredPermit = Objects.requireNonNull(fixture.networkDevice(restored).workPermit());
            var restoredWork = Objects.requireNonNull(restoredPermit.reserve(id("saved_work"), 1, Object2LongMaps.emptyMap()));
            assertTrue(restoredWork.commit());
            restored.owner.onSchedule();
            assertEquals(2, resumed.state().successfulTicks());
            assertEquals(1, restored.process("second").state().successfulTicks());
            assertEquals(38, restored.input.storedEnergy(), "Restored progress resumes without another START charge");
            assertEquals(committed, first.state());

            fixture.unload(restored);
            assertTrue(fixture.identities.uniqueRegistrations().isEmpty());
            assertTrue(fixture.network.refresh());
        }
    }

    @Test
    @SuppressWarnings("resource") // The listener subscription shares this fixture's lifecycle registry lifetime.
    void aLaterLoadFailureCannotRevokeAReplacementAlreadyPublishedDuringTheListener() {
        try (var fixture = new Fixture()) {
            var original = fixture.create();
            assertTrue(fixture.network.refresh());
            original.owner.onSchedule();
            var saved = fixture.assembly.encode(original.owner);
            var originalState = original.process("first").state();
            var listeners = new MachineLifecycleRegistry();
            AssembledMachine[] replacement = {null};
            MachineInstance[] failed = {null};
            var failure = new IllegalStateException("later listener failed after replacing its generation");
            listeners.register(new MachineLifecycleListener() {
                @Override public void onLoaded(MachineLifecycleEvent.Loaded event) {
                    failed[0] = event.context().machine();
                    replacement[0] = fixture.restore(saved, new MachineLifecycleRegistry());
                    fixture.unload(original);
                    assertTrue(fixture.network.refresh());
                    throw failure;
                }
            });

            assertSame(failure, assertThrows(IllegalStateException.class, () -> fixture.restore(saved, listeners)));
            var survivor = Objects.requireNonNull(replacement[0]);
            assertEquals(List.of(Objects.requireNonNull(fixture.ownership(survivor).registration())), fixture.identities.uniqueRegistrations());
            assertEquals(originalState, survivor.process("first").state());
            survivor.owner.onSchedule();
            assertEquals(2, survivor.process("first").state().successfulTicks());
            assertEquals(1, survivor.process("second").state().successfulTicks());
            assertEquals(38, survivor.input.storedEnergy(), "Failed old attachment must leave the replacement gate authorized");

            // A repeated, late unload of the failed generation must also preserve the replacement.
            assertFalse(Objects.requireNonNull(Objects.requireNonNull(failed[0]).lifecycle())
                .unload(MachineLifecycleEvent.UnloadReason.INVALIDATED));
            survivor.work().tickProcesses();
            assertEquals(3, survivor.process("first").state().successfulTicks());
            assertEquals(2, survivor.process("second").state().successfulTicks());
            assertEquals(34, survivor.input.storedEnergy());
        }
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("network_device_test", path); }

    private record AssembledMachine(MachineInstance owner, EnergyBuffer input) {
        private MachineWorkSlots work() { return Objects.requireNonNull(owner.components().get(MachineWorkSlots.TYPE)); }
        private RecipeProcess process(String name) { return Objects.requireNonNull(work().requireSlot(name).process()); }
    }

    private static NetworkResourceRegistry resources() {
        var registry = new NetworkResourceRegistry();
        registry.freeze();
        return registry;
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private final Object world = new Object();
        private final List<AssembledMachine> machines = new ObjectArrayList<>();
        private boolean rejected;
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime network = new NetworkRuntime(identities, resources(), owner -> rejected
            ? OwnerTeamResolution.rejected(owner, OwnerTeamResolutionStatus.NO_TEAM, "test owner has no team")
            : OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER, ObjectSets.singleton(owner), Optional.empty()),
            this::requireOwner);
        private final ComponentType<MachineInstance, MachineOwnership> ownershipType = new ComponentType<>(id("ownership"),
            owner -> new MachineOwnership(owner, _ -> identities), List.of());
        private final MachineRecipe firstRecipe = recipe("first_recipe");
        private final MachineRecipe secondRecipe = recipe("second_recipe");
        private final RecipeCatalog catalog = new RecipeCatalog(List.of(new RecipePool(id("recipes"), "network_device_test.recipes",
            List.of(firstRecipe, secondRecipe))));
        private final ComponentType<MachineInstance, RecipeScanner> scannerType = RecipeScanning.component(id("scanner"),
            host -> new RecipeScanner(host, catalog, () -> 17), SCAN, _ -> true);
        private final ComponentType<MachineInstance, int[]> ordinaryType = new ComponentType<>(id("ordinary"), _ -> new int[1],
            List.of(new MachineSystem<int[]>() {
                @Override public ResourceLocation id() { return MachineNetworkDeviceTest.id("ordinary_tick"); }
                @Override public void onTick(MachineInstance owner, int[] ticks) { ticks[0]++; }
            }));
        private final MachineAssembly assembly;
        private final ComponentType<MachineInstance, MachineNetworkDevice> networkType;

        private Fixture() {
            network.beginTick(0);
            networkType = MachineNetworkDevice.component(id("network"),
                ownershipType, (owner, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER,
                    1, Objects.requireNonNull(owner.lifecycle()).formed(), identity.loadOrder(), Object2LongMaps.emptyMap(),
                    NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
            var type = MachineType.builder(id("machine"), GuiText.ResourceStorage, id("structure"))
                .component(ownershipType).component(networkType).component(MachineWorkSlots.TYPE)
                .component(scannerType).component(ordinaryType)
                .workSlot(new WorkSlotDefinition("first")).workSlot(new WorkSlotDefinition("second"))
                .attribute(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2).build();
            var codec = new MachineWorkSlotsNbt("slots-v1", List.of(
                RecipeProcessNbt.builder(firstRecipe, "recipe-v1").build(),
                RecipeProcessNbt.builder(secondRecipe, "recipe-v1").build()));
            assembly = MachineAssembly.builder(type, "machine-v1").component(ownershipType, MachineOwnership.CODEC)
                .recreated(networkType, scannerType, ordinaryType).workSlots(codec).build();
        }

        private static MachineRecipe recipe(String name) {
            var energy = RecipeRequirements.ENERGY.requirement(id(name + "_energy"), new EnergyRequirement(3, 2, 0, 0, false));
            return new MachineRecipe(id(name), 100, List.of(energy));
        }

        private AssembledMachine create() {
            return attach(assembly.create(new Host(world, BlockPos.ORIGIN, OWNER), new MachineLifecycleRegistry()), 50);
        }

        private AssembledMachine restore(NBTTagCompound saved, MachineLifecycleRegistry listeners) {
            return attach(assembly.restore(new Host(world, new BlockPos(1, 0, 0), OTHER_PLACER), saved, listeners), 45);
        }

        private AssembledMachine attach(MachineInstance owner, long stored) {
            var input = new EnergyBuffer(100, () -> { });
            input.receiveEnergy(stored, Actionable.MODULATE);
            Objects.requireNonNull(owner.lifecycle()).form(new MachineHatches(List.of(), List.of(input), List.of()));
            var machine = new AssembledMachine(owner, input);
            machines.add(machine);
            return machine;
        }

        private RecipeScanner scanner(AssembledMachine machine) { return Objects.requireNonNull(machine.owner.components().get(scannerType)); }
        private MachineOwnership ownership(AssembledMachine machine) { return Objects.requireNonNull(machine.owner.components().get(ownershipType)); }
        private MachineNetworkDevice networkDevice(AssembledMachine machine) { return Objects.requireNonNull(machine.owner.components().get(networkType)); }
        private int ordinaryTicks(AssembledMachine machine) { return Objects.requireNonNull(machine.owner.components().get(ordinaryType))[0]; }
        private void unload(AssembledMachine machine) {
            Objects.requireNonNull(machine.owner.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        }

        private void requireOwner() {
            if (Thread.currentThread() != ownerThread) { throw new IllegalStateException("Not the test server owner"); }
        }

        @Override public void close() {
            machines.forEach(this::unload);
            network.close();
            identities.close();
        }
    }

    private record Host(Object worldIdentity, BlockPos position, @Nullable UUID placementPlayer) implements MachineLifecycleHost {
        @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, position); }
        @Override public World world() { throw new AssertionError("Network tests do not load a Minecraft world"); }
        @Override public void requireServerThread() { }
    }
}
