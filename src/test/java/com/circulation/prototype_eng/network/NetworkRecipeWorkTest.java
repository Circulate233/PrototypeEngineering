package com.circulation.prototype_eng.network;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.MachineIdentityRegistry;
import com.circulation.prototype_eng.api.machine.identity.MachineOwnership;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.persistence.MachineAssembly;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.network.MachineComputerComponents;
import com.circulation.prototype_eng.api.network.MachineDataCenterStorage;
import com.circulation.prototype_eng.api.network.MachineNetworkDevice;
import com.circulation.prototype_eng.api.network.NetworkDeviceRole;
import com.circulation.prototype_eng.api.network.NetworkDeviceSnapshot;
import com.circulation.prototype_eng.api.network.NetworkRuntime;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeWork;
import com.circulation.prototype_eng.api.network.recipe.NetworkWorkRequirements;
import com.circulation.prototype_eng.api.network.resource.ComputerSupplyFixtures;
import com.circulation.prototype_eng.api.network.resource.ComputingResourceFixtures;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponent;
import com.circulation.prototype_eng.api.network.resource.NetworkComputerComponentRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceModifier;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.probability.ProbabilityTuning;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeProcessFactory;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.team.OwnerTeamResolution;
import com.circulation.prototype_eng.api.team.OwnerTeamType;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.localization.GuiText;
import com.circulation.prototype_eng.recipe.RecipeStageExecutor;
import com.circulation.prototype_eng.research.ResearchEligibility;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import it.unimi.dsi.fastutil.objects.Reference2DoubleMaps;
import it.unimi.dsi.fastutil.objects.Reference2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NetworkRecipeWorkTest {
    private static MachineRecipe recipe(String name, int duration, EnergyRequirement energy, Hooks hooks) {
        var system = new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return new Effect(hooks, RecipeStage.START);
            }

            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return new Effect(hooks, RecipeStage.TICK);
            }

            @Override
            public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return new Effect(hooks, RecipeStage.END);
            }
        };
        var effect = new RecipeRequirementType<>(new ResourceLocation("zz_network_work_test", "late_effect"), system).requirement(id(name + "_effect"), "business effect");
        return new MachineRecipe(id(name), duration, List.of(RecipeRequirements.ENERGY.requirement(id(name + "_energy"), energy), effect));
    }

    private static MachineType.Builder type(String name) {
        return MachineType.builder(id(name), GuiText.ResourceStorage, id("structure"));
    }

    private static RecipeCatalog catalog(List<MachineRecipe> recipes) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("recipes"), "network_work_test.recipes", recipes)));
    }

    private static Reference2DoubleOpenHashMap<NetworkResourceType> demand() {
        var demand = new Reference2DoubleOpenHashMap<NetworkResourceType>();
        demand.put(ComputingResourceFixtures.COMPUTE, 10);
        return demand;
    }

    private static ResearchRegistry research() {
        var registry = new ResearchRegistry();
        for (var name : List.of("gate", "filler_one", "filler_two")) {
            int max = name.equals("gate") ? 3 : 1;
            registry.register(ResearchDefinition.builder(id(name)).maxLevel(max).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, max).durationTicks(1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
        }
        registry.freeze();
        return registry;
    }

    private static ResourceLocation id(String name) {
        return new ResourceLocation("network_work_test", name);
    }

    @Test
    void parallelProcessesSpendSummedNumericDemandButShareOnlyOneMachineLease() {
        try (var fixture = new Fixture(1, 100, false)) {
            var first = fixture.worker();
            var other = fixture.worker();
            fixture.refresh();
            var three = fixture.process(first, fixture.normal, 3);
            var five = fixture.process(first, fixture.normal, 5);
            assertSame(ProcessResult.SUCCESS, three.tick());
            assertSame(ProcessResult.SUCCESS, five.tick());
            assertEquals(20, fixture.permit(first).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(92, first.input.storedEnergy());
            assertEquals(1, three.state().successfulTicks());
            assertEquals(1, five.state().successfulTicks());
            var rejected = fixture.process(other, fixture.normal, 1);
            assertInstanceOf(ProcessResult.Blocked.class, rejected.tick());
            assertEquals(RecipeProcess.Lifecycle.NEW, rejected.state().lifecycle());
            assertEquals(100, other.input.storedEnergy());
            assertEquals(20, fixture.permit(first).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void scannerZeroDurationStartAndIndependentTickProbesSpendNoLeaseNumericBudgetOrOperationSequence() {
        try (var fixture = new Fixture(1, 100, false)) {
            var candidate = fixture.worker();
            var actual = fixture.worker();
            fixture.refresh();
            var tickProbe = fixture.process(candidate, fixture.normal, 3);
            assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStage(tickProbe, RecipeStage.TICK));
            assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(fixture.process(candidate, fixture.zero, 2)));
            var slots = new MachineWorkSlots(candidate.owner, List.of());
            var catalog = catalog(List.of(fixture.zero));
            var scanner = new RecipeScanner(slots, catalog, fixture.factory, List.of(), List.of(), _ -> false, () -> 17);
            var scanned = scanner.scan(new ScanLimits(10, 10));
            assertEquals(RecipeScanner.Status.ADMITTED, scanned.status());
            assertEquals(RecipeProcess.Lifecycle.NEW, Objects.requireNonNull(scanned.process()).state().lifecycle());
            assertEquals(100, candidate.input.storedEnergy());
            assertEquals(0, candidate.output.storedEnergy());
            assertEquals(100, fixture.permit(candidate).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertSame(ProcessResult.SUCCESS, fixture.process(actual, fixture.normal, 1).tick(), "Probes on another machine do not claim its only lease");
            fixture.nextTick();
            var witness = Objects.requireNonNull(fixture.permit(candidate).reserve(id("operation_witness"), demand()));
            assertEquals(0, witness.operation().sequence(), "The candidate's first real operation follows all probes without an allocated probe sequence");
            assertTrue(witness.commit());
            witness.rollback();
            assertEquals(100, fixture.permit(candidate).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(0, fixture.hooks.committed.getInt(RecipeStage.END));
        }
    }

    @Test
    void defaultAndRecipeResearchTakeTheirMaximumAndUseOnlyTheSelectedPhysicalDatabase() {
        try (var fixture = new Fixture(1, 100, true)) {
            var machine = fixture.worker();
            var losing = fixture.dataCenter();
            fixture.player.completeAtLeast(id("gate"), 2);
            fixture.player.completeAtLeast(id("filler_one"), 1);
            fixture.player.completeAtLeast(id("filler_two"), 1);
            var active = fixture.storage(fixture.primaryDataCenter);
            active.downloadResearch(id("filler_one"), 1, fixture.cloud());
            active.downloadResearch(id("filler_two"), 1, fixture.cloud());
            fixture.storage(losing).downloadResearch(id("gate"), 1, fixture.cloud());
            fixture.refresh();
            var process = fixture.process(machine, fixture.normal, 1);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick(), "Cloud and losing database levels cannot satisfy a missing active database entry");
            assertEquals(0, process.state().successfulTicks());
            assertEquals(100, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            active.downloadResearch(id("gate"), 1, fixture.cloud());
            fixture.refresh();
            assertInstanceOf(ProcessResult.Blocked.class, process.tick(), "Recipe level two is insufficient for the machine's default level three");
            fixture.player.completeAtLeast(id("gate"), 3);
            active.synchronizeLoopingResearch(fixture.cloud());
            fixture.refresh();
            assertSame(ProcessResult.SUCCESS, process.tick(), "The combined minimum is three, so the actual downloaded level three passes");
            assertEquals(99, machine.input.storedEnergy());
            assertEquals(90, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void numericShortageBlocksProgressWithoutFaultAndTheNextTickCanResumeTheSameProcess() {
        try (var fixture = new Fixture(1, 20, false)) {
            var machine = fixture.worker();
            fixture.refresh();
            var process = fixture.process(machine, fixture.normal, 3);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            assertEquals(0, process.state().progress());
            assertEquals(0, process.state().successfulTicks());
            assertEquals(100, machine.input.storedEnergy());
            assertTrue(process.persistenceReady());
            fixture.supply.put(ComputingResourceFixtures.COMPUTE, 50);
            fixture.nextTick();
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(1, process.state().successfulTicks());
            assertEquals(97, machine.input.storedEnergy());
            assertEquals(20, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void lateContentChangeOrRevocationRollsBackThisStageAndPreservesThePreviouslyCompletedTick() {
        for (boolean contentChange : List.of(false, true)) {
            try (var fixture = new Fixture(1, 100, false)) {
                var machine = fixture.worker();
                fixture.player.completeAtLeast(id("gate"), 1);
                var storage = fixture.storage(fixture.primaryDataCenter);
                storage.downloadResearch(id("gate"), 1, fixture.cloud());
                fixture.refresh();
                var process = fixture.process(machine, fixture.normal, 3);
                assertSame(ProcessResult.SUCCESS, process.tick());
                var completedBook = fixture.permit(machine);
                fixture.nextTick();
                var attemptedBook = fixture.permit(machine);
                fixture.player.completeAtLeast(id("gate"), 2);
                fixture.hooks.onTickCommit = contentChange ? () -> storage.synchronizeLoopingResearch(fixture.cloud()) : fixture.network::revoke;
                assertInstanceOf(ProcessResult.Blocked.class, process.tick());
                assertEquals(1, process.state().progress());
                assertEquals(1, process.state().successfulTicks());
                assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
                assertEquals(97, machine.input.storedEnergy(), "Only the rejected tick's FE returns");
                assertEquals(70, completedBook.availableResources().getDouble(ComputingResourceFixtures.COMPUTE), "Earlier completed work stays paid");
                assertEquals(100, attemptedBook.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
                assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.TICK));
                assertTrue(process.persistenceReady(), "Expected authority loss remains resumable");
                fixture.hooks.onTickCommit = () -> {
                };
                fixture.refresh();
                assertSame(ProcessResult.SUCCESS, process.tick());
                assertEquals(2, process.state().successfulTicks());
                assertEquals(94, machine.input.storedEnergy());
                assertEquals(70, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            }
        }
    }

    @Test
    void anExceptionalLateCommitPreservesItsCauseAndCompensatesItsIssuingBookAfterTheOldTickCloses() {
        try (var fixture = new Fixture(1, 100, false)) {
            var machine = fixture.worker();
            fixture.refresh();
            var process = fixture.process(machine, fixture.normal, 1);
            assertSame(ProcessResult.SUCCESS, process.tick());
            fixture.nextTick();
            var original = fixture.permit(machine);
            var failure = new IllegalStateException("external requirement failed after network preparation");
            fixture.hooks.onTickCommit = () -> {
                fixture.advanceTickWithoutCapture();
                throw failure;
            };
            assertSame(failure, assertThrows(IllegalStateException.class, process::tick));
            assertEquals(100, original.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            fixture.refresh();
            assertEquals(100, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE), "Old compensation never enters the newly published book");
            assertEquals(99, machine.input.storedEnergy());
            assertEquals(1, process.state().successfulTicks());
            assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.TICK));
            assertTrue(process.quiescent());
        }
    }

    @Test
    void durationOneAndZeroDurationChargeExactlyOneWorkUnitAcrossTheirRealLifecycleStages() {
        try (var fixture = new Fixture(1, 100, false)) {
            var machine = fixture.worker();
            fixture.refresh();
            var fast = fixture.process(machine, fixture.fast, 1);
            assertSame(ProcessResult.SUCCESS, fast.tick());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, fast.state().lifecycle());
            assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.START));
            assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.TICK));
            assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.END));
            assertEquals(90, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(95, machine.input.storedEnergy());
            assertEquals(5, machine.output.storedEnergy());
            var zero = fixture.process(machine, fixture.zero, 1);
            assertSame(ProcessResult.SUCCESS, zero.tick());
            assertEquals(RecipeProcess.Lifecycle.COMPLETED, zero.state().lifecycle());
            assertEquals(0, zero.state().successfulTicks());
            assertEquals(80, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(2, fixture.hooks.committed.getInt(RecipeStage.START));
            assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.TICK));
            assertEquals(2, fixture.hooks.committed.getInt(RecipeStage.END));
            assertEquals(93, machine.input.storedEnergy());
            assertEquals(10, machine.output.storedEnergy());
        }
    }

    @Test
    void restoredNetworkProviderRootsResumeWithoutReplayingStartOrDeductingHistoricalWork() {
        try (var fixture = new Fixture(1, 100, false)) {
            var machine = fixture.worker();
            fixture.refresh();
            var original = fixture.process(machine, fixture.normal, 2);
            assertSame(ProcessResult.SUCCESS, original.tick());
            var paid = fixture.permit(machine);
            var codec = RecipeProcessNbt.builder(fixture.normal, "network-v1").build();
            var restored = codec.decode(machine.owner, codec.encode(original));
            assertEquals(original.state(), restored.state());
            assertEquals(80, paid.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            fixture.nextTick();
            assertSame(ProcessResult.SUCCESS, restored.tick());
            assertEquals(2, restored.state().successfulTicks());
            assertEquals(96, machine.input.storedEnergy());
            assertEquals(1, fixture.hooks.committed.getInt(RecipeStage.START), "Saved started state does not replay START");
            assertEquals(80, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(80, paid.availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
        }
    }

    @Test
    void unrepresentableParallelDemandBlocksNormallyAndCanResumeAtAnAffordableParallelism() {
        long capacity = 1_000_000_000_000L;
        try (var fixture = new Fixture(1, capacity, false, capacity - 4)) {
            var machine = fixture.worker();
            fixture.refresh();
            var process = fixture.process(machine, fixture.normal, 10_000_000);
            assertInstanceOf(ProcessResult.Blocked.class, process.tick());
            assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
            assertEquals(capacity, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(100, machine.input.storedEnergy());
            process.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            assertSame(ProcessResult.SUCCESS, process.tick());
            assertEquals(0, fixture.permit(machine).availableResources().getDouble(ComputingResourceFixtures.COMPUTE));
            assertEquals(99, machine.input.storedEnergy());
        }
    }

    private static final class Fixture implements AutoCloseable {
        private final Thread ownerThread = Thread.currentThread();
        private final Object world = new Object();
        private final List<MachineInstance> machines = new ObjectArrayList<>();
        private final Hooks hooks = new Hooks();
        private final ResearchRegistry research = research();
        private final PlayerResearchState player = new PlayerResearchState(new UUID(0, 1));
        private final MachineIdentityRegistry identities = new MachineIdentityRegistry(this::requireOwner);
        private final NetworkRuntime network = new NetworkRuntime(identities,
            owner -> OwnerTeamResolution.resolved(owner, "team", OwnerTeamType.SERVER, ObjectSets.singleton(owner), null), this::requireOwner);
        private final ComponentType<MachineInstance, MachineOwnership> ownership = new ComponentType<>(id("ownership"),
            owner -> new MachineOwnership(owner, _ -> identities), List.of());
        private final Reference2DoubleOpenHashMap<NetworkResourceType> supply = new Reference2DoubleOpenHashMap<>();
        private final ComponentType<MachineInstance, MachineDataCenterStorage> storage = MachineDataCenterStorage.component(id("storage"),
            ownership, 1, 3, research, _ -> {
            });
        private final ComponentType<MachineInstance, MachineNetworkDevice> workerRole = MachineNetworkDevice.component(id("worker_role"), ownership,
            (owner, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.DEVICE, 1,
                Objects.requireNonNull(owner.lifecycle()).formed(), identity.loadOrder(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
        private final MachineRecipe normal = recipe("normal", 4, new EnergyRequirement(0, 1, 0, 0, false), hooks);
        private final MachineRecipe fast = recipe("fast", 1, new EnergyRequirement(2, 3, 0, 5, false), hooks);
        private final MachineRecipe zero = recipe("zero", 0, new EnergyRequirement(2, 3, 0, 5, false), hooks);
        private final RecipeProcessFactory factory = RecipeProcess::new;
        private final MachineAssembly dataAssembly;
        private final MachineAssembly workerAssembly;
        private final MachineInstance primaryDataCenter;
        private int positions;
        private long tick;

        private Fixture(long threads, long compute, boolean researchGate) {
            this(threads, compute, researchGate, 6);
        }

        private Fixture(long threads, long compute, boolean researchGate, long recipeCompute) {
            supply.put(ComputingResourceFixtures.COMPUTE, compute);
            var extensions = new NetworkRecipeExtensions();
            extensions.register((_, builder) -> {
                builder.resource(ComputingResourceFixtures.COMPUTE, recipeCompute);
                if (researchGate) {
                    builder.research(id("gate"), 2);
                }
            });
            extensions.publish(catalog(List.of(normal, fast, zero)), research);
            var defaults = NetworkWorkRequirements.builder().resource(ComputingResourceFixtures.COMPUTE, 4);
            if (researchGate) {
                defaults.research(id("gate"), 3);
            }
            var work = NetworkRecipeWork.component(id("network_work"), workerRole, defaults.build(), extensions, research);
            var workerType = type("worker").component(ownership).component(workerRole).component(work).recipePool(id("recipes")).build();
            workerAssembly = MachineAssembly.builder(workerType, "worker-v1").component(ownership, MachineOwnership.CODEC).recreated(workerRole, work).build();
            var dataRole = MachineNetworkDevice.capturedComponent(id("data_role"), ownership,
                (owner, identity) -> Objects.requireNonNull(owner.components().get(storage)).networkCapture(identity,
                    Objects.requireNonNull(owner.lifecycle()).formed(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
            var dataType = type("data_center").component(ownership).component(storage).component(dataRole).build();
            dataAssembly = MachineAssembly.builder(dataType, "data-v1").component(ownership, MachineOwnership.CODEC)
                                          .component(storage, MachineDataCenterStorage.CODEC).recreated(dataRole).build();
            network.beginTick(tick);
            var centerRole = MachineNetworkDevice.component(id("center_role"), ownership,
                (owner, identity) -> new NetworkDeviceSnapshot(identity.identity(), NetworkDeviceRole.NETWORK_CENTER, 1,
                    Objects.requireNonNull(owner.lifecycle()).formed(), identity.loadOrder(), 2, Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY, 0, 0), _ -> network);
            var centerType = type("center").component(ownership).component(centerRole).build();
            var centerAssembly = MachineAssembly.builder(centerType, "center-v1").component(ownership, MachineOwnership.CODEC).recreated(centerRole).build();
            attach(centerAssembly.create(host(centerType.id()), new MachineLifecycleRegistry()));
            var card = ComputerSupplyFixtures.key("compute_card");
            var components = new NetworkComputerComponentRegistry();
            ComputerSupplyFixtures.register(components, card, _ -> {
                var captured = new Reference2DoubleOpenHashMap<>(supply);
                return new NetworkComputerComponent.Capture(threads, captured, Reference2DoubleMaps.emptyMap(), 0, () -> captured.equals(supply));
            });
            components.freeze();
            var installed = MachineComputerComponents.component(id("computer_supply"), components, ComputerSupplyFixtures::warehouses);
            var computerRole = MachineNetworkDevice.capturedComponent(id("computer_role"), ownership,
                (owner, identity) -> Objects.requireNonNull(owner.components().get(installed)).networkCapture(identity, 1,
                    Objects.requireNonNull(owner.lifecycle()).formed(), Reference2DoubleMaps.emptyMap(), NetworkResourceModifier.EMPTY), _ -> network);
            var computerType = type("computer").component(ownership).component(installed).component(computerRole).build();
            var computer = MachineAssembly.builder(computerType, "computer-v1").component(ownership, MachineOwnership.CODEC)
                                          .recreated(installed, computerRole).build().create(host(computerType.id()), new MachineLifecycleRegistry());
            var warehouse = new BufferedResourceHatch(HatchMode.INPUT, 1, Reference2LongMaps.singleton(ComputerSupplyFixtures.TYPE, 64L), () -> {
            });
            assertEquals(1, Objects.requireNonNull(warehouse.input()).insert(card, 1, Actionable.MODULATE));
            Objects.requireNonNull(computer.lifecycle()).form(new MachineHatches(List.of(warehouse), List.of(), List.of()));
            machines.add(computer);
            primaryDataCenter = dataCenter();
        }

        private Worker worker() {
            return attach(workerAssembly.create(host(workerAssembly.type().id()), new MachineLifecycleRegistry()));
        }

        private MachineInstance dataCenter() {
            return attach(dataAssembly.create(host(dataAssembly.type().id()), new MachineLifecycleRegistry())).owner;
        }

        private Worker attach(MachineInstance owner) {
            var input = new EnergyBuffer(100, () -> {
            });
            var output = new EnergyBuffer(100, () -> {
            });
            input.receiveEnergy(100, Actionable.MODULATE);
            Objects.requireNonNull(owner.lifecycle()).form(new MachineHatches(List.of(), List.of(input), List.of(output)));
            machines.add(owner);
            return new Worker(owner, input, output);
        }

        private RecipeProcess process(Worker worker, MachineRecipe recipe, int parallelism) {
            var process = factory.create(worker.owner, recipe, 17, ProbabilityTuning.defaultTuning());
            process.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
            return process;
        }

        private NetworkRuntime.WorkPermit permit(Worker worker) {
            return Objects.requireNonNull(Objects.requireNonNull(worker.owner.components().get(workerRole)).workPermit());
        }

        private MachineDataCenterStorage storage(MachineInstance owner) {
            return Objects.requireNonNull(owner.components().get(storage));
        }

        private TeamResearchView cloud() {
            return new ResearchEligibility(research).calculateStructural("team", List.of(player.playerUuid()),
                Map.of(player.playerUuid(), player), Object2IntMaps.emptyMap(), Set.of());
        }

        private Host host(ResourceLocation type) {
            return new Host(world, type, new BlockPos(positions++, 0, 0), player.playerUuid());
        }

        private void refresh() {
            assertTrue(network.refresh());
        }

        private void advanceTickWithoutCapture() {
            network.endTick();
            network.beginTick(++tick);
        }

        private void nextTick() {
            advanceTickWithoutCapture();
            refresh();
        }

        private void requireOwner() {
            if (Thread.currentThread() != ownerThread) {
                throw new IllegalStateException("Not the test server owner");
            }
        }

        @Override
        public void close() {
            for (var machine : machines) {
                Objects.requireNonNull(machine.lifecycle()).unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
            }
            network.close();
            identities.close();
        }
    }

    private static final class Hooks {
        private final Object2IntOpenHashMap<RecipeStage> committed = new Object2IntOpenHashMap<>();
        private Runnable onTickCommit = () -> {
        };
    }

    private static final class Effect implements RequirementTransaction {
        private final Hooks hooks;
        private final RecipeStage phase;
        private boolean committed;
        private Resolution resolution = Resolution.REVERSIBLE;

        private Effect(Hooks hooks, RecipeStage phase) {
            this.hooks = hooks;
            this.phase = phase;
        }

        @Override
        public ProcessResult result() {
            return ProcessResult.SUCCESS;
        }

        @Override
        public void commit() {
            committed = true;
            hooks.committed.addTo(phase, 1);
            if (phase == RecipeStage.TICK) {
                hooks.onTickCommit.run();
            }
        }

        @Override
        public void complete() {
            resolution = Resolution.COMPLETED;
        }

        @Override
        public void rollback() {
            if (committed) {
                hooks.committed.addTo(phase, -1);
                committed = false;
            }
            resolution = Resolution.ROLLED_BACK;
        }

        @Override
        public Resolution resolution() {
            return resolution;
        }
    }

    private record Worker(MachineInstance owner, EnergyBuffer input, EnergyBuffer output) {
    }

    private record Host(Object worldIdentity, ResourceLocation type, BlockPos position,
                        UUID placementPlayer) implements MachineLifecycleHost {
        @Override
        public MachineIdentity identity() {
            return new MachineIdentity(type, 0, position);
        }

        @Override
        public World world() {
            throw new AssertionError("Network recipe tests do not load a Minecraft world");
        }

        @Override
        public void requireServerThread() {
        }
    }
}
