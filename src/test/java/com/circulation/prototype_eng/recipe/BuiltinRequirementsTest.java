package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.energy.EnergyInput;
import com.circulation.prototype_eng.api.energy.EnergySnapshot;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeRegistry;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;

class BuiltinRequirementsTest {
    private static final KeyType FIRST = new KeyType("recipe_first");
    private static final KeyType SECOND = new KeyType("recipe_second");
    private static final Key MATERIAL = new Key(FIRST, "material");
    private static final Key LIQUID = new Key(SECOND, "liquid");
    private static final Key PRODUCT = new Key(FIRST, "product");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("external_addon", name);
    }

    private static BufferedResourceHatch hatch(HatchMode mode, int slots, long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, capacity);
        capacities.put(SECOND, capacity);
        return new BufferedResourceHatch(mode, slots, capacities, () -> {
        });
    }

    private static RecipeProcess process(MachineHatches hatches, List<RecipeRequirement<?>> requirements) {
        var registry = new RecipeRegistry();
        registry.registerPool(id("pool"), "external.pool");
        registry.registerRecipe(id("pool"), new MachineRecipe(id("recipe"), 1, requirements));
        registry.freeze();
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, hatches);
        return new RecipeProcess(machine, Objects.requireNonNull(registry.get(id("pool"))).recipes().getFirst(), 17);
    }

    @Test
    void registeredResourceAndEnergyRequirementsRetryBlockedTickAndEndWithoutPartialCharges() {
        var input = hatch(HatchMode.INPUT, 2, 20);
        var output = hatch(HatchMode.OUTPUT, 1, 3);
        var materials = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        materials.insert(MATERIAL, 8, Actionable.MODULATE);
        materials.insert(LIQUID, 10, Actionable.MODULATE);
        products.insert(PRODUCT, 2, Actionable.MODULATE);
        var power = new EnergyBuffer(100, () -> {
        });
        var generated = new EnergyBuffer(100, () -> {
        });
        power.receiveEnergy(20, Actionable.MODULATE);
        var hatches = new MachineHatches(List.of(new Alias(input), new Alias(input), new Alias(output)),
            List.of(new EnergyAlias(power), new EnergyAlias(power)), List.of(generated));
        var resources = new ResourceRequirement(
            List.of(new GenericStack(MATERIAL, 3), new GenericStack(LIQUID, 4)),
            List.of(new GenericStack(MATERIAL, 1)), List.of(new GenericStack(PRODUCT, 2)),
            List.of(new GenericStack(PRODUCT, 3)), false);
        var process = process(hatches, List.of(
            RecipeRequirements.RESOURCES.requirement(id("resources"), resources),
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(5, 2, 0, 3, false))));

        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(5, materials.amount(MATERIAL));
        assertEquals(6, materials.amount(LIQUID));
        assertEquals(15, power.storedEnergy());
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.TICK));
        assertEquals(5, materials.availableAmount(MATERIAL));
        assertEquals(15, power.energySnapshot().availableEnergy());
        assertEquals(2, products.amount(PRODUCT));

        products.extract(PRODUCT, 2, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.TICK));
        assertEquals(4, materials.amount(MATERIAL));
        assertEquals(13, power.storedEnergy());
        assertEquals(2, products.amount(PRODUCT));
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.END));
        assertEquals(0, generated.storedEnergy());
        assertEquals(100, generated.energySnapshot().availableCapacity());
        assertEquals(2, products.amount(PRODUCT));
        products.extract(PRODUCT, 2, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.END));
        assertEquals(3, generated.storedEnergy());
        assertEquals(3, products.amount(PRODUCT));
        assertEquals(4, materials.amount(MATERIAL));
        assertEquals(13, power.storedEnergy());
    }

    @Test
    void separateRequirementsAndAliasViewsCannotReserveTheSameMaterialTwice() {
        var input = hatch(HatchMode.INPUT, 1, 10);
        var storage = Objects.requireNonNull(input.input());
        storage.insert(MATERIAL, 5, Actionable.MODULATE);
        var hatches = new MachineHatches(List.of(new Alias(input), new Alias(input)), List.of(), List.of());
        var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 3)), List.of());
        var process = process(hatches, List.of(
            RecipeRequirements.RESOURCES.requirement(id("first"), payload),
            RecipeRequirements.RESOURCES.requirement(id("second"), payload)));
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(5, storage.amount(MATERIAL));
        assertEquals(5, storage.availableAmount(MATERIAL));
        storage.insert(MATERIAL, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(0, storage.amount(MATERIAL));
    }

    @Test
    void oneEnergySourceContributesOnlyItsOperationLimitEvenThroughMultipleAliases() {
        var first = new EnergyBuffer(100, 100, 6, () -> {
        });
        var second = new EnergyBuffer(100, () -> {
        });
        first.receiveEnergy(10, Actionable.MODULATE);
        var hatches = new MachineHatches(List.of(),
            List.of(new EnergyAlias(first), new EnergyAlias(first), second), List.of());
        var process = process(hatches, List.of(RecipeRequirements.ENERGY.requirement(id("power"),
            new EnergyRequirement(7, 0, 0, 0, false))));
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(10, first.energySnapshot().availableEnergy());
        second.receiveEnergy(1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process, RecipeStage.START));
        assertEquals(4, first.storedEnergy());
        assertEquals(0, second.storedEnergy());
    }

    @Test
    void explicitVoidExcessRetainsOnlyFittingResourceAndEnergyOutputsAndStillRequiresTargets() {
        var output = hatch(HatchMode.OUTPUT, 1, 2);
        var products = Objects.requireNonNull(output.output());
        products.insert(PRODUCT, 1, Actionable.MODULATE);
        var power = new EnergyBuffer(2, () -> {
        });
        power.receiveEnergy(1, Actionable.MODULATE);
        var hatches = new MachineHatches(List.of(new Alias(output)), List.of(), List.of(power));
        var resourceOutputs = List.of(new GenericStack(PRODUCT, 5));
        var requirements = List.<RecipeRequirement<?>>of(
            RecipeRequirements.RESOURCES.requirement(id("resources"),
                new ResourceRequirement(List.of(), List.of(), List.of(), resourceOutputs, true)),
            RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(0, 0, 0, 5, true)));
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.execute(process(hatches, requirements), RecipeStage.END));
        assertEquals(2, products.amount(PRODUCT));
        assertEquals(2, power.storedEnergy());
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.execute(
            process(new MachineHatches(List.of(), List.of(), List.of()), requirements), RecipeStage.END));
    }

    private record Alias(ResourceHatch backing) implements ResourceHatch {
        @Override
        public ResourceStorage input() {
            return backing.input();
        }

        @Override
        public ResourceStorage output() {
            return backing.output();
        }

        @Override
        public boolean isValid() {
            return backing.isValid();
        }
    }

    private record EnergyAlias(EnergyInput backing) implements EnergyInput {
        @Override
        public StorageContext context() {
            return backing.context();
        }

        @Override
        public EnergySnapshot energySnapshot() {
            return backing.energySnapshot();
        }

        @Override
        public ReservationResult reserveExtractEnergy(EnergySnapshot snapshot, long amount) {
            return backing.reserveExtractEnergy(snapshot, amount);
        }

        @Override
        public long storedEnergy() {
            return backing.storedEnergy();
        }

        @Override
        public long extractEnergy(long amount, Actionable mode) {
            return backing.extractEnergy(amount, mode);
        }

        @Override
        public boolean isValid() {
            return backing.isValid();
        }
    }
}
