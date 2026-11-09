package com.circulation.prototype_eng.machine;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycle;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleEvent;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleHost;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineLifecycleRegistry;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlotsNbt;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotDefinition;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanPreparation;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.structure.StructureAssembly;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;

class MachineRetirementTest {
    private static final ResourceLocation GROUP = id("group");

    @Test
    void committedStartRetirementFinishesTheTransactionAndPausesBeforeTickWithoutRequiringFormation() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var recipe = recipe("start", new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                log.add("start:acquire");
                return transaction(log, "start", fixture.lifecycle::retire);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return fail("Retirement during START must pause before TICK");
            }
        });
        var process = new RecipeProcess(fixture.machine, recipe, 1);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(List.of("start:acquire", "start:commit", "start:complete"), log);
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
        assertDoesNotThrow(process::requirePersistenceBoundary);
        process.tick();
        assertEquals(3, log.size());
        assertFalse(fixture.machine.active());
        fixture.machine.onSchedule();
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }

    @Test
    void retirementDuringFinalTickRetainsCommittedProgressWithoutEnteringEnd() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var recipe = recipe("tick", new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return transaction(log, "tick", fixture.lifecycle::retire);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return fail("Retirement during TICK must pause before END");
            }
        });
        var process = new RecipeProcess(fixture.machine, recipe, 1);
        process.tick();
        assertEquals(List.of("tick:commit", "tick:complete"), log);
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(1, process.state().successfulTicks());
        assertEquals(recipe.durationTicks(), process.state().progress());
        assertDoesNotThrow(process::requirePersistenceBoundary);
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }

    @Test
    void firstWorkSlotRetirementDoesNotInvokeTheSecondExecutorOrAdmitNewWorkAndStillSavesAndRestores() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var recipe = recipe("slots", new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return transaction(log, "start", fixture.lifecycle::retire);
            }
        });
        var slots = new MachineWorkSlots(fixture.machine, List.of(new WorkSlotDefinition("first", Set.of(GROUP)),
            new WorkSlotDefinition("second", Set.of(GROUP))), Set.of(), process -> {
                log.add("executor:" + process.seed());
                process.tick();
            });
        fixture.machine.components().put(MachineWorkSlots.TYPE, slots);
        fixture.machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 2);
        assertTrue(slots.startIn("first", new RecipeProcess(fixture.machine, recipe, 1)));
        assertTrue(slots.startIn("second", new RecipeProcess(fixture.machine, recipe, 2)));
        slots.tickProcesses();
        assertEquals(List.of("executor:1", "start:commit", "start:complete"), log);
        assertEquals(RecipeProcess.Lifecycle.NEW, Objects.requireNonNull(slots.requireSlot("second").process()).state().lifecycle());
        var codec = new MachineWorkSlotsNbt("retirement", List.of(RecipeProcessNbt.builder(recipe, "v1").build()));
        var saved = fixture.lifecycle.withPersistence(() -> codec.encode(slots));
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
        assertEquals(saved, fixture.lifecycle.withPersistence(() -> codec.encode(slots)));
        fixture.lifecycle.withPersistence(() -> { codec.restore(slots, saved); return null; });
        assertEquals(2, slots.activeProcesses().size());
        assertFalse(slots.startIn("first", new RecipeProcess(fixture.machine, recipe, 3)));
        assertEquals(saved, fixture.lifecycle.withPersistence(() -> codec.encode(slots)));
    }

    @Test
    void retiringInsideAStartProbeRollsBackRealReservationsAndNeverAdmitsTheCandidate() {
        var fixture = new Fixture();
        var buffer = new EnergyBuffer(100, () -> {});
        buffer.receiveEnergy(20, Actionable.MODULATE);
        var log = new ObjectArrayList<String>();
        var recipe = recipe("probe", new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                var reservation = Objects.requireNonNull(buffer.reserveExtractEnergy(buffer.energySnapshot(), 5).reservation());
                log.add("reserve");
                fixture.lifecycle.retire();
                return probeTransaction(reservation, log);
            }
        });
        var slots = new MachineWorkSlots(fixture.machine, List.of(new WorkSlotDefinition("main", Set.of(GROUP))));
        var scanner = new RecipeScanner(slots, catalog(recipe), RecipeProcess::new, List.of(), List.of(), _ -> false, () -> 1);
        assertEquals(RecipeScanner.Status.PAUSED, scanner.scan(new ScanLimits(20, 20)).status());
        assertEquals(List.of("reserve", "rollback"), log);
        assertTrue(slots.activeProcesses().isEmpty());
        assertEquals(20, buffer.energySnapshot().availableEnergy());
        assertEquals(20, buffer.storedEnergy());
        assertEquals(RecipeScanner.Status.PAUSED, scanner.scan(new ScanLimits(20, 20)).status());
        assertEquals(2, log.size());
        assertFalse(slots.startIn("main", new RecipeProcess(fixture.machine, recipe, 2)));
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }

    @Test
    void retiringPreparationRollsBackLocalProjectionAndSkipsLaterPreparationAndResourceProbe() {
        var fixture = new Fixture();
        var log = new ObjectArrayList<String>();
        var recipe = recipe("preparation", new RecipeRequirementSystem<>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return fail("Retired preparation cannot begin a resource probe");
            }
        });
        RecipeScanPreparation preparation = _ -> {
            log.add("prepare");
            fixture.lifecycle.retire();
            return transaction(log, "preparation", () -> fail("A scan preparation cannot commit"));
        };
        RecipeScanPreparation forbidden = _ -> fail("A retired scan cannot enter another preparation");
        var slots = new MachineWorkSlots(fixture.machine, List.of(new WorkSlotDefinition("main", Set.of(GROUP))));
        var scanner = new RecipeScanner(slots, catalog(recipe), RecipeProcess::new, List.of(), List.of(), _ -> false, () -> 1,
            List.of(preparation, forbidden));
        assertEquals(RecipeScanner.Status.PAUSED, scanner.scan(new ScanLimits(20, 20)).status());
        assertEquals(List.of("prepare", "preparation:rollback"), log);
        assertTrue(slots.activeProcesses().isEmpty());
        fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }

    @Test
    void retirementRejectsLateStructurePublicationButAllowsClearingExistingBindingsAndUnloading() {
        var fixture = new Fixture();
        fixture.lifecycle.form(new MachineHatches(List.of(), List.of(), List.of()));
        fixture.lifecycle.retire();
        assertFalse(fixture.lifecycle.refreshStructure(() -> fail("Retired refresh must not inspect authority"),
            () -> fail("Retired refresh must not inspect the world")));
        assertThrows(IllegalStateException.class, () -> fixture.lifecycle.form(new MachineHatches(List.of(), List.of(), List.of())));
        fixture.lifecycle.invalidateStructure();
        assertNull(fixture.machine.components().get(MachineHatches.TYPE));
        assertTrue(fixture.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD));

        var duringPrepare = new Fixture();
        assertFalse(duringPrepare.lifecycle.refreshStructure(() -> true, () -> {
            duringPrepare.lifecycle.retire();
            return StructureAssembly.UNFORMED;
        }));
        assertNull(duringPrepare.machine.components().get(MachineHatches.TYPE));
        duringPrepare.lifecycle.unload(MachineLifecycleEvent.UnloadReason.CHUNK_UNLOAD);
    }

    private static RequirementTransaction probeTransaction(ResourceReservation reservation, List<String> log) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public List<? extends ResourceReservation> reservations() { return List.of(reservation); }
            @Override public void commit() { fail("An admission probe cannot commit"); }
            @Override public void rollback() { log.add("rollback"); }
        };
    }

    private static RequirementTransaction transaction(List<String> log, String name, Runnable commit) {
        return new RequirementTransaction() {
            @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
            @Override public void commit() { log.add(name + ":commit"); commit.run(); }
            @Override public void complete() { log.add(name + ":complete"); }
            @Override public void rollback() { log.add(name + ":rollback"); }
        };
    }

    private static MachineRecipe recipe(String name, RecipeRequirementSystem<String> system) {
        var requirement = new RecipeRequirementType<>(id(name + "_requirement"), system).requirement(id(name + "_part"), "value");
        return new MachineRecipe(id(name), 1, List.of(requirement), Set.of(GROUP));
    }
    private static RecipeCatalog catalog(MachineRecipe recipe) {
        return new RecipeCatalog(List.of(new DefaultRecipePool(id("pool"), "retirement.pool", List.of(recipe))));
    }
    private static ResourceLocation id(String path) { return new ResourceLocation("retirement_test", path); }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final MachineLifecycle lifecycle;

        private Fixture() {
            var owner = Thread.currentThread();
            lifecycle = new MachineLifecycle(machine, new MachineLifecycleHost() {
                @Override public MachineIdentity identity() { return new MachineIdentity(id("machine"), 0, BlockPos.ORIGIN); }
                @Override public World world() { return fail("Retirement tests do not create a game World"); }
                @Override public void requireServerThread() { assertSame(owner, Thread.currentThread()); }
            }, new MachineLifecycleRegistry());
            lifecycle.load(MachineLifecycleEvent.LoadBoundary.FRESH);
        }
    }
}
