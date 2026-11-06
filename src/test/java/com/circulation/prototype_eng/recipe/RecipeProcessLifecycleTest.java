package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.component.ComponentSystem;
import com.circulation.prototype_eng.api.component.ComponentType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.NumericAttributeModifier;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.AttributeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.function.BiFunction;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeProcessLifecycleTest {
    private static final KeyType TYPE = new KeyType("lifecycle");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");

    @Test
    void blockedStagesPreserveAttributesBalancesAndBothTimelines() {
        var input = hatch(HatchMode.INPUT, 20);
        var output = hatch(HatchMode.OUTPUT, 4);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        materials.insert(MATERIAL, 3, Actionable.MODULATE);
        products.insert(PRODUCT, 1, Actionable.MODULATE);
        var energy = new EnergyBuffer(100, () -> { });
        var generated = new EnergyBuffer(100, () -> { });
        energy.receiveEnergy(20, Actionable.MODULATE);
        var machine = machine(new MachineHatches(List.of(input, output), List.of(energy), List.of(generated)));
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2.8);
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 0.5);
        var process = process(machine, 2,
            RecipeRequirements.ATTRIBUTES.requirement(id("speed"), new AttributeRequirement(
                StandardMachineAttributes.PROCESS_SPEED,
                new NumericAttributeModifier("boost", 0.5, AttributeModifier.Operation.MULTIPLY_TOTAL))),
            RecipeRequirements.RESOURCES.requirement(id("resource"), new ResourceRequirement(
                List.of(new GenericStack(MATERIAL, 2)), List.of(new GenericStack(MATERIAL, 1)),
                List.of(), List.of(new GenericStack(PRODUCT, 2)), false)),
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(1, 1, 0, 3, false)));

        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
        assertEquals(20, energy.energySnapshot().availableEnergy());
        assertEquals(3, materials.availableAmount(MATERIAL));
        assertTrue(process.attributes().localSnapshot().isEmpty());

        materials.insert(MATERIAL, 1, Actionable.MODULATE);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
        assertEquals(0, process.state().progress());
        assertEquals(0.75, process.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        assertEquals(0, materials.amount(MATERIAL));
        assertEquals(18, energy.storedEnergy());

        materials.insert(MATERIAL, 6, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0.75, process.state().progress());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1.5, process.state().progress());
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(2.25, process.state().progress());
        assertEquals(3, process.state().successfulTicks());
        assertEquals(0.75, process.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        assertEquals(12, energy.storedEnergy());
        assertEquals(0, generated.storedEnergy());
        assertEquals(0, materials.amount(MATERIAL));

        products.extract(PRODUCT, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(3, process.state().successfulTicks());
        assertEquals(12, energy.storedEnergy());
        assertEquals(6, generated.storedEnergy());
        assertEquals(4, products.amount(PRODUCT));
        assertEquals(0.5, process.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(4, products.amount(PRODUCT));
    }

    @Test
    void parallelismModifierRemainsEffectiveUntilAllEndOutputsSettle() {
        var input = hatch(HatchMode.INPUT, 6);
        var output = hatch(HatchMode.OUTPUT, 6);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        materials.insert(MATERIAL, 6, Actionable.MODULATE);
        products.insert(PRODUCT, 1, Actionable.MODULATE);
        var process = process(machine(new MachineHatches(List.of(input, output), List.of(), List.of())), 0,
            RecipeRequirements.ATTRIBUTES.requirement(id("parallel"), new AttributeRequirement(
                StandardMachineAttributes.PROCESS_PARALLELISM,
                new NumericAttributeModifier("bonus", 2, AttributeModifier.Operation.ADDITION))),
            RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
                List.of(new GenericStack(MATERIAL, 2)), List.of(new GenericStack(PRODUCT, 2)))));

        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(0, materials.amount(MATERIAL));
        assertEquals(3, process.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM));
        assertEquals(0, process.state().successfulTicks());
        products.extract(PRODUCT, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(6, products.amount(PRODUCT));
        assertTrue(process.attributes().localSnapshot().isEmpty());
    }

    @Test
    void commitFaultRefundsTickAndPermanentlyStopsItsDeterministicClock() {
        var energy = new EnergyBuffer(10, () -> { });
        energy.receiveEnergy(5, Actionable.MODULATE);
        boolean[] fail = {true};
        var ticks = new ObjectArrayList<RecipeProcess.Tick>();
        var primary = new IllegalStateException("external tick commit failed");
        var process = process(machine(new MachineHatches(List.of(), List.of(energy), List.of())), 1,
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(1, 1, 0, 0, false)),
            observer((current, stage) -> {
                if (stage != RecipeStage.TICK) { return RequirementTransaction.SUCCESS; }
                ticks.add(current.tickContext());
                return new RequirementTransaction() {
                    @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                    @Override public void commit() { if (fail[0]) { throw primary; } }
                    @Override public void rollback() { }
                };
            }));

        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
        assertEquals(4, energy.energySnapshot().availableEnergy());
        var committedState = process.state();
        var observedTicks = List.copyOf(ticks);
        fail[0] = false;
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(new RecipeProcess.Tick(0, 1, 0, 1), ticks.getFirst());
        assertEquals(observedTicks, ticks);
        assertEquals(committedState, process.state());
        assertEquals(0, process.state().successfulTicks());
        assertEquals(4, energy.storedEnergy());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertSame(ProcessResult.SUCCESS, process.state().result());
    }

    @Test
    void postCompletionNotificationFaultNeverReplaysAnyCommittedStage() {
        for (var failingStage : RecipeStage.values()) {
            boolean[] armed = {false};
            var failure = new IllegalStateException("notification at " + failingStage);
            var energy = new EnergyBuffer(100, () -> {
                if (armed[0]) {
                    armed[0] = false;
                    throw failure;
                }
            });
            energy.receiveEnergy(10, Actionable.MODULATE);
            var visits = new ObjectArrayList<RecipeStage>();
            var process = process(machine(new MachineHatches(List.of(), List.of(energy), List.of(energy))), 1,
                RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(2, 3, 0, 1, false)),
                observer((_, stage) -> {
                    visits.add(stage);
                    return new RequirementTransaction() {
                        @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                        @Override public void commit() { armed[0] = stage == failingStage; }
                        @Override public void rollback() { armed[0] = false; }
                    };
                }));
            assertSame(failure, assertThrows(IllegalStateException.class, process::tick));
            assertEquals(failingStage == RecipeStage.START ? 0 : 1, process.state().successfulTicks());
            var committedState = process.state();
            var expectedVisits = switch (failingStage) {
                case START -> List.of(RecipeStage.START);
                case TICK -> List.of(RecipeStage.START, RecipeStage.TICK);
                case END -> List.of(RecipeStage.START, RecipeStage.TICK, RecipeStage.END);
            };
            long expectedEnergy = switch (failingStage) { case START -> 8; case TICK -> 5; case END -> 6; };
            assertEquals(expectedVisits, visits);
            assertEquals(expectedEnergy, energy.storedEnergy());
            assertThrows(IllegalStateException.class, process::tick);
            assertEquals(committedState, process.state());
            assertEquals(expectedVisits, visits);
            assertEquals(expectedEnergy, energy.storedEnergy());
            assertEquals(failingStage == RecipeStage.END ? RecipeProcess.Lifecycle.COMPLETED : RecipeProcess.Lifecycle.RUNNING,
                process.state().lifecycle());
            assertSame(ProcessResult.SUCCESS, process.state().result());
        }
    }

    @Test
    void frozenTickAdvanceMatchesRequirementsEvenWhenThatStageChangesSpeed() {
        var timeline = new ObjectArrayList<RecipeProcess.Tick>();
        var process = process(new MachineInstance(), 3, observer((current, stage) -> {
            if (stage != RecipeStage.TICK) { return RequirementTransaction.SUCCESS; }
            timeline.add(current.tickContext());
            var before = current.attributes().localSnapshot();
            current.attributes().setBase(StandardMachineAttributes.PROCESS_SPEED, 2);
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() { }
                @Override public void rollback() { current.attributes().restoreLocal(before); }
            };
        }));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, process.state().progress());
        assertEquals(new RecipeProcess.Tick(0, 1, 0, 1), timeline.getFirst());
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(new RecipeProcess.Tick(1, 3, 1, 2), timeline.getLast());
        assertEquals(3, process.state().progress());
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
    }

    @Test
    void attributeCompensationDoesNotUndoAnotherRequirementsBusinessChangeTwice() {
        var value = id("external_value");
        var primary = new IllegalStateException("late commit failed");
        var process = process(new MachineInstance(), 1,
            observer(new ResourceLocation("aaa_external", "change_base"), (current, stage) -> {
                if (stage != RecipeStage.START) { return RequirementTransaction.SUCCESS; }
                return new RequirementTransaction() {
                    private boolean applied;
                    @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                    @Override public void commit() {
                        current.attributes().setBase(value, current.attributes().value(value) + 5);
                        applied = true;
                    }
                    @Override public void rollback() {
                        if (applied) {
                            current.attributes().setBase(value, current.attributes().value(value) - 5);
                        }
                    }
                };
            }),
            RecipeRequirements.ATTRIBUTES.requirement(id("boost"), new AttributeRequirement(
                StandardMachineAttributes.PROCESS_SPEED,
                new NumericAttributeModifier("speed", 1, AttributeModifier.Operation.ADDITION))),
            observer((_, _) -> new RequirementTransaction() {
                @Override public ProcessResult result() { return ProcessResult.SUCCESS; }
                @Override public void commit() { throw primary; }
                @Override public void rollback() { }
            }));
        process.attributes().setBase(value, 10);
        assertSame(primary, assertThrows(IllegalStateException.class, process::tick));
        assertEquals(10, process.attributes().value(value));
        assertEquals(1, process.attributes().value(StandardMachineAttributes.PROCESS_SPEED));
        assertEquals(1, process.attributes().localSnapshot().size());
        assertEquals(RecipeProcess.Lifecycle.NEW, process.state().lifecycle());
    }

    @Test
    void saturatedParallelQuantitiesCannotWrapOrLeavePartialReservations() {
        var input = hatch(HatchMode.INPUT, Long.MAX_VALUE);
        var output = hatch(HatchMode.OUTPUT, Long.MAX_VALUE);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        materials.insert(MATERIAL, Long.MAX_VALUE - 1, Actionable.MODULATE);
        var energy = new EnergyBuffer(Long.MAX_VALUE, () -> { });
        energy.receiveEnergy(Long.MAX_VALUE, Actionable.MODULATE);
        var machine = machine(new MachineHatches(List.of(input, output), List.of(energy), List.of()));
        machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 2);
        long amount = Long.MAX_VALUE / 2 + 1;
        var process = process(machine, 0,
            RecipeRequirements.RESOURCES.requirement(id("resource"), new ResourceRequirement(
                List.of(new GenericStack(MATERIAL, amount)), List.of(new GenericStack(PRODUCT, amount)))),
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(amount, 0, 0, 0, false)));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(Long.MAX_VALUE, energy.energySnapshot().availableEnergy());
        assertEquals(Long.MAX_VALUE - 1, materials.availableAmount(MATERIAL));
        materials.insert(MATERIAL, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, energy.storedEnergy());
        assertEquals(0, materials.amount(MATERIAL));
        assertEquals(Long.MAX_VALUE, products.amount(PRODUCT));
    }

    @Test
    void failureIsTerminalAndProcessCallbacksWrapTheFinalAttempt() {
        var events = new ObjectArrayList<String>();
        var failure = new ProcessResult.Failure("external.reason", List.of("detail"));
        var process = process(new MachineInstance(), 1, observer((_, stage) -> {
            events.add(stage.name());
            return new RequirementTransaction() {
                @Override public ProcessResult result() { return failure; }
                @Override public void commit() { throw new AssertionError("Failed stage committed"); }
                @Override public void rollback() { events.add("rollback"); }
            };
        }));
        var component = new ComponentType<RecipeProcess, String>(id("component"), _ -> "state",
            List.of(new ComponentSystem<>() {
                @Override public ResourceLocation id() { return RecipeProcessLifecycleTest.id("callbacks"); }
                @Override public void onPreTick(RecipeProcess owner, String value) { events.add("pre"); }
                @Override public void onTick(RecipeProcess owner, String value) { events.add("component"); }
                @Override public void onPostTick(RecipeProcess owner, String value) {
                    events.add("post:" + owner.state().lifecycle());
                }
            }));
        process.components().create(component, process);
        assertSame(failure, process.tick());
        assertSame(failure, process.tick());
        assertEquals(List.of("pre", "component", "START", "rollback", "post:FAILED"), events);
        assertEquals(0, process.state().successfulTicks());
    }

    private static RecipeRequirement<String> observer(BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquire) {
        return observer(new ResourceLocation("zz_external", "observer"), acquire);
    }

    private static RecipeRequirement<String> observer(ResourceLocation location,
                                                      BiFunction<RecipeProcess, RecipeStage, RequirementTransaction> acquire) {
        var system = new RecipeRequirementSystem<String>() {
            @Override public RequirementTransaction start(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.START);
            }
            @Override public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.TICK);
            }
            @Override public RequirementTransaction end(RecipeProcess process, RecipeRequirement<String> requirement) {
                return acquire.apply(process, RecipeStage.END);
            }
        };
        return new RecipeRequirementType<>(location, system).requirement(location, "payload");
    }

    private static RecipeProcess process(MachineInstance machine, int duration, RecipeRequirement<?>... requirements) {
        return new RecipeProcess(machine, new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 17);
    }

    private static MachineInstance machine(MachineHatches hatches) {
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, hatches);
        return machine;
    }

    private static BufferedResourceHatch hatch(HatchMode mode, long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, capacity);
        return new BufferedResourceHatch(mode, 1, capacities, () -> { });
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("external_process", path); }
}
