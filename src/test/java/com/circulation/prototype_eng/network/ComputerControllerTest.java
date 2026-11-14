package com.circulation.prototype_eng.network;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.ComputerController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.NetworkWorkState;
import com.circulation.prototype_eng.api.network.resource.ComputationHatch;
import com.circulation.prototype_eng.api.network.resource.ComputerRecipe;
import com.circulation.prototype_eng.api.network.resource.ComputerSupplyFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkTeamBudget;
import com.circulation.prototype_eng.api.research.DataCenterResearchSnapshot;
import com.circulation.prototype_eng.api.storage.SlottedResourceStorage;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSet;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ComputerControllerTest {
    private static final UUID OWNER = new UUID(0, 10);
    private static final AEKey CARD = ComputerSupplyFixtures.key("computer");
    private static final AEKey WATER = ComputerSupplyFixtures.key("water");
    private static final NetworkResourceType COOLING = ComputingResourceFixtures.local(id("cooling"));

    private static ResourceLocation id(String name) {
        return new ResourceLocation("computer_test", name);
    }

    @Test
    void recipesAndComponentsSettleOnceWhileComputerAndDatabaseLeasesShareTheBudget() {
        try (var fixture = new Fixture(1)) {
            fixture.hatch.install(1);
            fixture.hatch.buffer.insert(WATER, 4_000, Actionable.MODULATE);
            fixture.power.receiveEnergy(10, Actionable.MODULATE);
            assertTrue(fixture.network.refresh());
            var supply = fixture.computer.providedResources();
            assertEquals(4, fixture.computer.providedConcurrency());
            assertEquals(600, supply.getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(7, supply.getDouble(ComputingResourceFixtures.QUBIT));
            assertFalse(supply.containsKey(COOLING));
            assertEquals(3_000, fixture.hatch.buffer.amount(WATER));
            assertEquals(0, fixture.power.storedEnergy());
            assertEquals(ComputerController.SupplyState.ACTIVE, fixture.computer.supplyState());
            assertEquals(10, fixture.computer.energyPaid());
            assertEquals(1_000, fixture.computer.availableForComponents(COOLING));
            assertEquals(ComputerController.RECIPE_ACTIVE, fixture.computer.recipeStates()[0]);
            assertEquals(1, fixture.computer.installedComponents().size());
            assertEquals(1, fixture.captures);
            assertEquals(NetworkWorkState.ACTIVE_DATA_CENTER, fixture.database.state());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, fixture.client(0).requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, fixture.client(1).requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, fixture.client(2).requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, fixture.client(3).requestLease());
            assertTrue(fixture.network.refresh());
            assertEquals(3_000, fixture.hatch.buffer.amount(WATER));
            assertEquals(1, fixture.captures);

            fixture.centerThreads = 2;
            fixture.nextTick();
            assertTrue(fixture.network.refresh());
            assertEquals(0, fixture.computer.providedConcurrency());
            assertEquals(0, fixture.computer.providedResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(7, fixture.computer.providedResources().getDouble(ComputingResourceFixtures.QUBIT));
            assertEquals(2_000, fixture.hatch.buffer.amount(WATER));
            assertEquals(ComputerController.SupplyState.ENERGY_SHORTAGE, fixture.computer.supplyState());
            assertEquals(0, fixture.computer.energyPaid());
            assertEquals(0, fixture.power.storedEnergy());
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, fixture.client(0).requestLease());

            fixture.hatch.install(2);
            fixture.power.receiveEnergy(100, Actionable.MODULATE);
            fixture.nextTick();
            assertTrue(fixture.network.refresh());
            assertEquals(0, fixture.computer.providedResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(7, fixture.computer.providedResources().getDouble(ComputingResourceFixtures.QUBIT));
            assertEquals(100, fixture.power.storedEnergy(), "All component costs fail together when cooling cannot cover both cards");
            assertEquals(1_000, fixture.hatch.buffer.amount(WATER));
            assertEquals(ComputerController.SupplyState.RESOURCE_SHORTAGE, fixture.computer.supplyState());
            assertEquals(2_000, fixture.computer.installedInventory().requiredResources().getDouble(COOLING));
            assertEquals(1_000, fixture.computer.availableForComponents(COOLING));
        }
    }

    @Test
    void noConcurrencyRollsBackRecipeInputsAndComponentPowerBeforePublication() {
        try (var fixture = new Fixture(0)) {
            fixture.hatch.install(1);
            fixture.hatch.buffer.insert(WATER, 1_000, Actionable.MODULATE);
            fixture.power.receiveEnergy(100, Actionable.MODULATE);
            assertTrue(fixture.network.refresh());
            assertEquals(NetworkWorkState.INSUFFICIENT_THREADS, Objects.requireNonNull(fixture.computer.networkDevice()).state());
            assertEquals(1_000, fixture.hatch.buffer.amount(WATER));
            assertEquals(100, fixture.power.storedEnergy());
            assertEquals(0, fixture.computer.providedResources().getDouble(ComputingResourceFixtures.QUBIT));
            assertEquals(ComputerController.SupplyState.WAITING, fixture.computer.supplyState());
            assertEquals(ComputerController.RECIPE_WAITING, fixture.computer.recipeStates()[0]);
        }
    }

    @Test
    void workingResearchStationPreemptsOrdinaryLeasesAndRevokesTheirUncommittedReceipts() {
        try (var fixture = new Fixture(1)) {
            fixture.hatch.install(1);
            fixture.hatch.buffer.insert(WATER, 2_000, Actionable.MODULATE);
            fixture.power.receiveEnergy(100, Actionable.MODULATE);
            assertTrue(fixture.network.refresh());
            for (int i = 0; i < 3; i++)
                assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, fixture.client(i).requestLease());
            var receipt = Objects.requireNonNull(fixture.client(2).reserve(id("ordinary_work"), Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 10)));
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, Objects.requireNonNull(fixture.station.workPermit()).requestLease());
            assertFalse(receipt.commit());
            receipt.rollback();
            assertEquals(NetworkTeamBudget.LeaseStatus.INSUFFICIENT_CAPACITY, fixture.client(2).requestLease());
            assertEquals(NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED, Objects.requireNonNull(fixture.database.workPermit()).requestLease());
        }
    }

    @Test
    void laterFormedInfrastructureCanUseTheBootThreadBeforeOrdinaryMachines() {
        try (var fixture = new Fixture(1)) {
            Objects.requireNonNull(fixture.computer.lifecycle()).invalidateStructure();
            fixture.databaseFormed = false;
            assertTrue(fixture.network.refresh());
            assertEquals(NetworkTeamBudget.LeaseStatus.GRANTED, fixture.client(0).requestLease());
            fixture.hatch.install(1);
            fixture.hatch.buffer.insert(WATER, 2_000, Actionable.MODULATE);
            fixture.power.receiveEnergy(100, Actionable.MODULATE);
            fixture.databaseFormed = true;
            Objects.requireNonNull(fixture.computer.lifecycle()).form(new MachineHatches(List.of(fixture.hatch), List.of(fixture.power), List.of()));
            assertTrue(fixture.network.refresh());
            assertEquals(4, fixture.computer.providedConcurrency());
            assertEquals(NetworkWorkState.ACTIVE_DATA_CENTER, fixture.database.state());
            assertEquals(1_000, fixture.hatch.buffer.amount(WATER));
            assertEquals(90, fixture.power.storedEnergy());
            assertEquals(NetworkTeamBudget.LeaseStatus.ALREADY_GRANTED, Objects.requireNonNull(Objects.requireNonNull(fixture.computer.networkDevice()).workPermit()).requestLease());
        }
    }

    private static final class Hatch implements ComputationHatch {
        private final ObjectArrayList<Runnable> listeners = new ObjectArrayList<>();
        private final SlottedResourceBuffer buffer = new SlottedResourceBuffer(4,
            Reference2LongMaps.singleton(ComputerSupplyFixtures.TYPE, 64_000), () -> {
        });

        private void install(int count) {
            assertTrue(buffer.tryReplaceSlot(0, buffer.getStack(0), new GenericStack(CARD, count)));
            listeners.forEach(Runnable::run);
        }

        @Override
        public SlottedResourceStorage inventory() {
            return buffer;
        }

        @Override
        public void addListener(Runnable listener) {
            listeners.add(listener);
        }

        @Override
        public void removeListener(Runnable listener) {
            listeners.remove(listener);
        }

        @Override
        public boolean isValid() {
            return buffer.isValid();
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(() -> {
        });
        private final NetworkRuntime network;
        private final Hatch hatch = new Hatch();
        private final EnergyBuffer power = new EnergyBuffer(1_000, () -> {
        });
        private final ComputerController computer;
        private final NetworkRuntime.Registration database;
        private final NetworkRuntime.Registration station;
        private final NetworkRuntime.Registration[] clients = new NetworkRuntime.Registration[4];
        private long centerThreads;
        private boolean databaseFormed = true;
        private long tick;
        private int captures;

        private Fixture(long centerThreads) {
            this.centerThreads = centerThreads;
            network = new NetworkRuntime(identities, owner -> OwnerTeamResolution.resolved(owner, "team",
                OwnerTeamType.SERVER, ObjectSet.of(owner), Optional.empty()), () -> {
            });
            network.beginTick(0);
            var components = new NetworkComputerComponentRegistry();
            var supply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            supply.put(ComputingResourceFixtures.COMPUTE, 1_000);
            var definition = new NetworkComputerComponent.Capture(8, supply, Reference2DoubleMaps.singleton(COOLING, 1_000), 10, () -> true);
            ComputerSupplyFixtures.register(components, CARD, _ -> {
                captures++;
                return definition;
            });
            components.freeze();
            var ownership = new ComponentType<MachineInstance, MachineOwnership>(id("ownership"), owner -> new MachineOwnership(owner, _ -> identities), List.of());
            var reader = MachineComputerComponents.component(id("components"), components);
            var role = MachineNetworkDevice.capturedComponent(id("computer_role"), ownership,
                (owner, identity) -> ((ComputerController) owner).networkCapture(identity), _ -> network);
            var type = MachineType.builder(id("computer"), GuiText.ResourceStorage, id("shape"))
                                  .component(ownership).component(reader).component(role).build();
            var recipeSupply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            recipeSupply.put(COOLING, 1_000);
            recipeSupply.put(ComputingResourceFixtures.QUBIT, 7);
            var recipe = new ComputerRecipe(id("cooling_recipe"), List.of(new GenericStack(WATER, 1_000)), recipeSupply);
            var assembly = MachineAssembly.builder(type, "v1").component(ownership, MachineOwnership.CODEC).recreated(reader, role)
                                          .instanceFactory(value -> new ComputerController(value, 4, Reference2DoubleMaps.singleton(ComputingResourceFixtures.COMPUTE, 600), List.of(recipe)) {
                                              @Override
                                              protected MachineComputerComponents computerComponents() {
                                                  return Objects.requireNonNull(components().get(reader));
                                              }
                                          }).build();
            computer = (ComputerController) assembly.create(new Host(), new MachineLifecycleRegistry());
            Objects.requireNonNull(computer.lifecycle()).form(new MachineHatches(List.of(hatch), List.of(power), List.of()));
            var center = identity(100);
            network.register(center, () -> new NetworkRuntime.Capture(new NetworkDeviceSnapshot(center.identity(), NetworkDeviceRole.NETWORK_CENTER,
                1, true, center.loadOrder(), this.centerThreads, Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), () -> true));
            var data = identity(101);
            database = network.register(data, () -> new NetworkRuntime.Capture(new NetworkDeviceSnapshot(data.identity(), NetworkDeviceRole.DATA_CENTER,
                1, databaseFormed, data.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 16, 0,
                new DataCenterResearchSnapshot(data.identity().machineId(), 1, 16, 0, Object2IntMaps.emptyMap())), () -> true));
            var research = identity(200);
            station = network.register(research, () -> new NetworkRuntime.Capture(new NetworkDeviceSnapshot(research.identity(), NetworkDeviceRole.RESEARCH_STATION,
                1, true, research.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), () -> true));
            for (int i = 0; i < clients.length; i++) {
                var device = identity(102 + i);
                clients[i] = network.register(device, () -> new NetworkRuntime.Capture(new NetworkDeviceSnapshot(device.identity(), NetworkDeviceRole.DEVICE,
                    0, true, device.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), () -> true));
            }
        }

        private MachineIdentityRegistry.Registration identity(int value) {
            return identities.register(new PersistentMachineIdentity(new UUID(0, value), OWNER),
                new MachineIdentity(id("device"), 0, new BlockPos(value, 0, 0)), this, new Object());
        }

        private NetworkRuntime.WorkPermit client(int index) {
            return Objects.requireNonNull(clients[index].workPermit());
        }

        private void nextTick() {
            network.endTick();
            network.beginTick(++tick);
        }

        @Override
        public void close() {
            Objects.requireNonNull(computer.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            network.close();
            identities.close();
        }
    }

    private record Host() implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(id("computer"), 0, BlockPos.ORIGIN);
        }

        @Override
        public World world() {
            throw new AssertionError("No game world required");
        }

        @Override
        public Object worldIdentity() {
            return ComputerControllerTest.class;
        }

        @Override
        public UUID placementPlayer() {
            return OWNER;
        }

        @Override
        public void requireServerThread() {
        }
    }
}
