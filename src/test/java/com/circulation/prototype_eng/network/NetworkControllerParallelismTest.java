package com.circulation.prototype_eng.network;

import ae2.api.config.Actionable;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.MultithreadController;
import com.circulation.prototype_eng.api.machine.RecipeController;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.network.resource.ComputerSupplyFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeParallelismSelection;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceBatch;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkControllerParallelismTest {
    private static final KeyType ITEMS = new KeyType("network_controller_parallelism");
    private static final Key INPUT = new Key(ITEMS, "input");
    private static final Key OUTPUT = new Key(ITEMS, "output");

    private static ResourceLocation id(String path) {
        return new ResourceLocation("network_controller_test", path);
    }

    /**
     * Executes actual source/kernel continuations directly; the scheduler regression separately proves worker dispatch.
     */
    private static void step(RecipeScanner.ScanAttempt attempt) {
        var budget = new RecipeWorkBudget(1);
        for (var source : attempt.sources()) {
            if (attempt.resolvingSources()) attempt.finishSourceResolution(source, source.resolveWithinBudget(budget));
            else if (source.state() == ResourceBatch.SourceState.RESERVED) source.applyWithinBudget(budget);
        }
        var computation = attempt.computation();
        if (computation != null && computation.advance(budget)) attempt.finishComputation(computation);
        attempt.advance(budget, () -> true);
    }

    @Test
    void finalOverAllowanceQuantityDrivesRealNetworkLedgerAndPhysicalStartTickEndResources() {
        for (var factory : List.<Function<MachineType, MachineInstance>>of(MultithreadController::new, CrossRecipeController::new)) {
            for (boolean sliced : List.of(false, true)) {
                try (var fixture = new Fixture(factory)) {
                    var worker = fixture.worker();
                    var other = fixture.worker();
                    assertTrue(fixture.network.refresh());
                    var owner = assertInstanceOf(RecipeController.class, worker.owner);
                    owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
                    owner.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
                    var host = owner.createRecipeHost(List.of(new WorkSlotDefinition("a"), new WorkSlotDefinition("b")), Set.of());
                    var scanner = owner.createRecipeScanner(host, fixture.catalog, () -> 17);
                    var limits = new ScanLimits(32, 32);
                    if (sliced) {
                        var attempt = scanner.beginScan(limits);
                        for (int slice = 0; slice < 10000 && !attempt.finished(); slice++) step(attempt);
                        assertTrue(attempt.finished());
                    } else assertEquals(RecipeScanner.Status.ADMITTED, scanner.scan(limits).status());
                    assertEquals(1, host.activeProcesses().size());
                    var process = host.activeProcesses().getFirst();
                    assertSame(fixture.recipe, process.recipe());
                    assertEquals(5, RecipeParallelism.effective(process));
                    assertEquals(5, host.sharedUsage(fixture.recipe).occupied());
                    assertEquals(0, host.sharedUsage(fixture.recipe).available());
                    assertEquals(100, fixture.permit(worker).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
                    assertEquals(100, worker.input.storedEnergy());
                    assertEquals(100, Objects.requireNonNull(worker.items.input()).amount(INPUT), "Admission probes must restore resource holds");
                    host.tickProcesses();
                    assertEquals(50, fixture.permit(worker).availableResources().getDouble(ComputingResourceFixtures.COMPUTE), "(Machine 4 + recipe 6) × final 5");
                    var competitor = new RecipeProcess(other.owner, fixture.recipe, 19);
                    competitor.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
                    assertInstanceOf(ProcessResult.Blocked.class, competitor.tick(), "The admitted machine owns the one real PN thread lease");
                    assertEquals(100, other.input.storedEnergy());
                    for (int tick = 0; tick < 2; tick++) host.tickProcesses();
                    assertEquals(85, worker.input.storedEnergy(), "START 2 × 5 plus TICK 1 × 5");
                    assertEquals(15, worker.output.storedEnergy(), "END 3 × 5");
                    assertEquals(90, Objects.requireNonNull(worker.items.input()).amount(INPUT));
                    assertEquals(15, Objects.requireNonNull(worker.items.output()).amount(OUTPUT));
                    assertEquals(50, fixture.permit(worker).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
                    assertTrue(host.activeProcesses().isEmpty());
                    assertEquals(0, owner.recipeThreadCount(fixture.recipe.id()));
                }
            }
        }
    }

    /**
     * Uses the same real center/computer/identity setup as NetworkRecipeWorkTest, without research gates.
     */
    private static final class Fixture implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private final Object world = new Object();
        private final UUID player = new UUID(0, 21);
        private final ObjectArrayList<MachineInstance> machines = new ObjectArrayList<>();
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime network;
        private final ComponentType<MachineInstance, MachineOwnership> ownership = new ComponentType<>(id("ownership"),
            owner -> new MachineOwnership(owner, _ -> identities), List.of());
        private final ComponentType<MachineInstance, MachineNetworkDevice> workerRole;
        private final MachineAssembly workerAssembly;
        private final MachineRecipe recipe = new MachineRecipe(id("over"), 1,
            List.of(RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 1, 0, 3, false)),
                RecipeRequirements.RESOURCES.requirement(id("items"), new ResourceRequirement(List.of(new GenericStack(INPUT, 2)), List.of(new GenericStack(OUTPUT, 3))))),
            Set.of(), 1, RecipeParallelismSelection.fixed(5));
        private final RecipeCatalog catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("recipes"), "network.controller.pool", List.of(recipe))));
        private int positions;

        private Fixture(Function<MachineType, MachineInstance> factory) {
            var research = new ResearchRegistry();
            research.freeze();
            network = new NetworkRuntime(identities, owner -> OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER,
                ObjectSets.singleton(owner), Optional.empty()), this::requireOwner);
            workerRole = role("worker_role", NetworkDeviceRole.DEVICE);
            var extensions = new NetworkRecipeExtensions();
            extensions.register((_, builder) -> builder.resource(ComputingResourceFixtures.COMPUTE, 6));
            extensions.publish(catalog, research);
            var work = NetworkRecipeWork.component(id("network_work"), workerRole, NetworkWorkRequirements.builder().resource(ComputingResourceFixtures.COMPUTE, 4).build(),
                extensions, research);
            var workerType = type("worker").component(ownership).component(workerRole).component(work).recipePool(id("recipes")).build();
            workerAssembly = MachineAssembly.builder(workerType, "v1").instanceFactory(factory).component(ownership, MachineOwnership.CODEC).recreated(workerRole, work).build();
            network.beginTick(0);
            var centerRole = role("center_role", NetworkDeviceRole.NETWORK_CENTER);
            var centerType = type("center").component(ownership).component(centerRole).build();
            attach(MachineAssembly.builder(centerType, "v1").component(ownership, MachineOwnership.CODEC).recreated(centerRole).build()
                                  .create(host(centerType.id()), new MachineLifecycleRegistry()));
            var supply = new Reference2DoubleOpenHashMap<NetworkResourceType>();
            supply.put(ComputingResourceFixtures.COMPUTE, 100);
            var card = ComputerSupplyFixtures.key("controller_card");
            var components = new NetworkComputerComponentRegistry();
            ComputerSupplyFixtures.register(components, card, _ -> new NetworkComputerComponent.Capture(1, NetworkResourceModifier.copyResources(supply), Reference2DoubleMaps.emptyMap(), 0, () -> true));
            components.freeze();
            var installed = MachineComputerComponents.component(id("computer_supply"), components, ComputerSupplyFixtures::warehouses);
            var computerRole = MachineNetworkDevice.capturedComponent(id("computer_role"), ownership,
                (owner, identity) -> Objects.requireNonNull(owner.components().get(installed)).networkCapture(identity, 1,
                    Objects.requireNonNull(owner.lifecycle()).formed(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
            var computerType = type("computer").component(ownership).component(installed).component(computerRole).build();
            var computer = MachineAssembly.builder(computerType, "v1").component(ownership, MachineOwnership.CODEC).recreated(installed, computerRole).build()
                                          .create(host(computerType.id()), new MachineLifecycleRegistry());
            var warehouse = new BufferedResourceHatch(HatchMode.INPUT, 1, Reference2LongMaps.singleton(ComputerSupplyFixtures.TYPE, 64L), () -> {
            });
            assertEquals(1, Objects.requireNonNull(warehouse.input()).insert(card, 1, Actionable.MODULATE));
            Objects.requireNonNull(computer.lifecycle()).form(new MachineHatches(List.of(warehouse), List.of(), List.of()));
            machines.add(computer);
        }

        private static MachineType.Builder type(String path) {
            return MachineType.builder(id(path), GuiText.ResourceStorage, id("shape"));
        }

        private ComponentType<MachineInstance, MachineNetworkDevice> role(String path, NetworkDeviceRole role) {
            return MachineNetworkDevice.component(id(path), ownership, (owner, identity) -> new NetworkDeviceSnapshot(identity.identity(), role, 1,
                Objects.requireNonNull(owner.lifecycle()).formed(), identity.loadOrder(), role == NetworkDeviceRole.NETWORK_CENTER ? 1 : 0, Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
        }

        private Worker worker() {
            return attach(workerAssembly.create(host(workerAssembly.type().id()), new MachineLifecycleRegistry()));
        }

        private Worker attach(MachineInstance owner) {
            var input = new EnergyBuffer(100, () -> {
            });
            var output = new EnergyBuffer(100, () -> {
            });
            input.receiveEnergy(100, Actionable.MODULATE);
            var items = new BufferedResourceHatch(HatchMode.MIXED, 1, Reference2LongMaps.singleton(ITEMS, 100L), () -> {
            });
            assertEquals(100, Objects.requireNonNull(items.input()).insert(INPUT, 100, Actionable.MODULATE));
            Objects.requireNonNull(owner.lifecycle()).form(new MachineHatches(List.of(items), List.of(input), List.of(output)));
            machines.add(owner);
            return new Worker(owner, input, output, items);
        }

        private NetworkRuntime.WorkPermit permit(Worker worker) {
            return Objects.requireNonNull(Objects.requireNonNull(worker.owner.components().get(workerRole)).workPermit());
        }

        private Host host(ResourceLocation type) {
            return new Host(world, type, new BlockPos(positions++, 0, 0), player);
        }

        private void requireOwner() {
            if (Thread.currentThread() != ownerThread) throw new IllegalStateException("Wrong owner thread");
        }

        @Override
        public void close() {
            for (var machine : machines)
                Objects.requireNonNull(machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            network.close();
            identities.close();
        }
    }

    private record Worker(MachineInstance owner, EnergyBuffer input, EnergyBuffer output, BufferedResourceHatch items) {
    }

    private record Host(Object worldIdentity, ResourceLocation type, BlockPos position,
                        UUID placementPlayer) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, position);
        }

        @Override
        public World world() {
            throw new AssertionError("No Minecraft world in network controller tests");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
