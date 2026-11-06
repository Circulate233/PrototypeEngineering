package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.StorageContext;
import com.circulation.prototype_eng.api.storage.StorageSnapshot;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProbabilisticResourceRequirementTest {
    private static final KeyType FIRST = new KeyType("probability_first");
    private static final KeyType SECOND = new KeyType("probability_second");
    private static final Key MATERIAL = new Key(FIRST, "material");
    private static final Key PRODUCT = new Key(FIRST, "product");
    private static final Key ALTERNATE = new Key(SECOND, "alternate");

    @Test
    void zeroChanceStillChecksCapacityButDoesNotMonopolizeSharedCheckResources() {
        var input = hatch(HatchMode.INPUT, 1, 10);
        var material = Objects.requireNonNull(input.input());
        material.insert(MATERIAL, 1, Actionable.MODULATE);
        var check = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)), List.of(), List.of(),
            List.of(), false, new ResourceChance(0, null), null);
        var consume = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1)), List.of());
        var process = process(List.of(input), 0, 2,
            RecipeRequirements.RESOURCES.requirement(id("check_first"), check),
            RecipeRequirements.RESOURCES.requirement(id("check_second"), check),
            RecipeRequirements.RESOURCES.requirement(id("consume"), consume));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(1, material.availableAmount(MATERIAL));
        material.insert(MATERIAL, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, material.amount(MATERIAL));
        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
    }

    @Test
    void chanceAboveOneHundredUsesAttributeMultiplierAndAggregatesKeysBeforeScaling() {
        var input = hatch(HatchMode.INPUT, 1, 30);
        var output = hatch(HatchMode.OUTPUT, 2, 30);
        var material = Objects.requireNonNull(input.input());
        var products = Objects.requireNonNull(output.output());
        material.insert(MATERIAL, 23, Actionable.MODULATE);
        var multiplier = id("chance_multiplier");
        var payload = new ResourceRequirement(List.of(new GenericStack(MATERIAL, 1), new GenericStack(MATERIAL, 3)),
            List.of(), List.of(), List.of(new GenericStack(PRODUCT, 2), new GenericStack(ALTERNATE, 1)),
            false, new ResourceChance(75, multiplier), null);
        var process = process(List.of(input, output), 0, 2, RecipeRequirements.RESOURCES.requirement(id("io"), payload));
        process.attributes().setBase(multiplier, 4);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(23, material.availableAmount(MATERIAL));
        material.insert(MATERIAL, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, material.amount(MATERIAL));
        assertEquals(12, products.amount(PRODUCT));
        assertEquals(6, products.amount(ALTERNATE));
    }

    @Test
    void blockedTickRetryMatchesUninterruptedProcessAndSuccessfulTicksUseDifferentSamples() {
        var baselineInput = hatch(HatchMode.INPUT, 1, 100);
        var baselineOutput = hatch(HatchMode.OUTPUT, 1, 100);
        var retryInput = hatch(HatchMode.INPUT, 1, 100);
        var retryOutput = hatch(HatchMode.OUTPUT, 1, 100);
        var original = Objects.requireNonNull(baselineInput.input());
        var retried = Objects.requireNonNull(retryInput.input());
        var originalProducts = Objects.requireNonNull(baselineOutput.output());
        var retryProducts = Objects.requireNonNull(retryOutput.output());
        original.insert(MATERIAL, 100, Actionable.MODULATE);
        retried.insert(MATERIAL, 100, Actionable.MODULATE);
        var payload = new ResourceRequirement(List.of(), List.of(new GenericStack(MATERIAL, 1)),
            List.of(new GenericStack(PRODUCT, 1)), List.of(), false, new ResourceChance(50, null), null);
        var resource = RecipeRequirements.RESOURCES.requirement(id("io"), payload);
        var baseline = process(List.of(baselineInput, baselineOutput), 12, 4, resource);
        boolean[] blocked = {true};
        var gateId = new ResourceLocation("zz_external", "gate");
        var gateType = new RecipeRequirementType<>(gateId, new RecipeRequirementSystem<String>() {
            @Override
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<String> requirement) {
                return new Decision(blocked[0] ? new ProcessResult.Blocked("external.wait") : ProcessResult.SUCCESS);
            }
        });
        var retry = process(List.of(retryInput, retryOutput), 12, 4, resource, gateType.requirement(gateId, "payload"));
        var distinctAmounts = new LongOpenHashSet();
        for (int tick = 0; tick < 12; tick++) {
            long before = retried.amount(MATERIAL);
            blocked[0] = true;
            assertInstanceOf(ProcessResult.Blocked.class, retry.tick());
            assertEquals(before, retried.availableAmount(MATERIAL));
            blocked[0] = false;
            assertSame(ProcessResult.SUCCESS, retry.tick());
            assertSame(ProcessResult.SUCCESS, baseline.tick());
            assertEquals(original.amount(MATERIAL), retried.amount(MATERIAL));
            assertEquals(originalProducts.amount(PRODUCT), retryProducts.amount(PRODUCT));
            assertEquals(tick + 1, retry.state().successfulTicks());
            distinctAmounts.add(before - retried.amount(MATERIAL));
        }
        assertTrue(distinctAmounts.size() > 1, "Successive successful ticks must not reuse the frozen old salt");
    }

    @Test
    void weightedOutputChecksTheSelectedKeyAndRollsBackFixedOutputsUntilRetryFits() {
        var restricted = hatch(HatchMode.OUTPUT, 1, 100);
        var blockedProducts = Objects.requireNonNull(restricted.output());
        blockedProducts.insert(PRODUCT, 1, Actionable.MODULATE);
        var pool = new RandomOutputPool(List.of(
            new WeightedSampling.Weighted<>(new GenericStack(PRODUCT, 20), 1),
            new WeightedSampling.Weighted<>(new GenericStack(ALTERNATE, 1), Integer.MAX_VALUE)), 1);
        var payload = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(new GenericStack(PRODUCT, 5)),
            false, ResourceChance.ALWAYS, pool);
        var process = process(List.of(restricted), 0, 1, RecipeRequirements.RESOURCES.requirement(id("output"), payload));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(1, blockedProducts.amount(PRODUCT));
        assertEquals(0, blockedProducts.amount(ALTERNATE));
        assertEquals(99, blockedProducts.insert(PRODUCT, 99, Actionable.SIMULATE));

        var expanded = hatch(HatchMode.OUTPUT, 2, 100);
        process.owner().components().put(MachineHatches.TYPE, new MachineHatches(List.of(expanded), List.of(), List.of()));
        assertSame(ProcessResult.SUCCESS, process.tick());
        var products = Objects.requireNonNull(expanded.output());
        assertEquals(5, products.amount(PRODUCT));
        assertEquals(1, products.amount(ALTERNATE));
    }

    @Test
    void preflightOutputFaultReleasesAlreadyHeldInputAndRejectsFurtherTicks() {
        var input = Objects.requireNonNull(hatch(HatchMode.INPUT, 1, 10).input());
        var output = Objects.requireNonNull(hatch(HatchMode.OUTPUT, 1, 10).output());
        input.insert(MATERIAL, 4, Actionable.MODULATE);
        boolean[] fail = {true};
        var payload = new ResourceRequirement(List.of(), List.of(new GenericStack(MATERIAL, 1)),
            List.of(new GenericStack(PRODUCT, 1)), List.of(), false, new ResourceChance(0, null), null);
        var process = process(List.of(new Ports(input, new FaultingOutput(output, fail))), 1, 4,
            RecipeRequirements.RESOURCES.requirement(id("preflight"), payload));
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(4, input.availableAmount(MATERIAL));
        assertEquals(10, output.insert(PRODUCT, 10, Actionable.SIMULATE));
        assertEquals(0, process.state().successfulTicks());
        fail[0] = false;
        assertThrows(IllegalStateException.class, process::tick);
        assertEquals(4, input.availableAmount(MATERIAL));
        assertEquals(0, output.amount(PRODUCT));
        assertEquals(RecipeProcess.Lifecycle.RUNNING, process.state().lifecycle());
        assertEquals(0, process.state().successfulTicks());
    }

    @Test
    void allEntrySelectionScalesDuplicateKeysAndExplicitVoidDiscardsOnlyUnfittingOutput() {
        var output = hatch(HatchMode.OUTPUT, 2, 10);
        var pool = new RandomOutputPool(List.of(
            new WeightedSampling.Weighted<>(new GenericStack(PRODUCT, 1), 2),
            new WeightedSampling.Weighted<>(new GenericStack(PRODUCT, 2), 3),
            new WeightedSampling.Weighted<>(new GenericStack(ALTERNATE, 4), 1)), 3);
        var payload = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), true, ResourceChance.ALWAYS, pool);
        var definition = RecipeRequirements.RESOURCES.requirement(id("random"), payload);
        var process = process(List.of(output), 0, 3, definition);
        assertSame(ProcessResult.SUCCESS, process.tick());
        var products = Objects.requireNonNull(output.output());
        assertEquals(9, products.amount(PRODUCT));
        assertEquals(10, products.amount(ALTERNATE));
        assertInstanceOf(ProcessResult.Blocked.class, process(List.of(), 0, 3, definition).tick());
    }

    private static RecipeProcess process(List<? extends ResourceHatch> hatches, int duration, double parallelism,
                                          RecipeRequirement<?>... requirements) {
        var owner = new MachineInstance();
        owner.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, parallelism);
        owner.components().put(MachineHatches.TYPE, new MachineHatches(new ObjectArrayList<>(hatches), List.of(), List.of()));
        return new RecipeProcess(owner, new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 17);
    }

    private static BufferedResourceHatch hatch(HatchMode mode, int slots, long capacity) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, capacity);
        capacities.put(SECOND, capacity);
        return new BufferedResourceHatch(mode, slots, capacities, () -> { });
    }

    private static ResourceLocation id(String path) { return new ResourceLocation("probability_test", path); }

    private record Decision(ProcessResult result) implements RequirementTransaction {
        @Override public void commit() { }
        @Override public void rollback() { }
    }

    private record Ports(ResourceStorage input, ResourceStorage output) implements ResourceHatch {
        @Override public boolean isValid() { return input.isValid() && output.isValid(); }
    }

    private record FaultingOutput(ResourceStorage backing, boolean[] fail) implements ResourceStorage {
        @Override public StorageContext context() { return backing.context(); }
        @Override public StorageSnapshot snapshot() { return backing.snapshot(); }
        @Override public long amount(AEKey key) { return backing.amount(key); }
        @Override public long availableAmount(AEKey key) { return backing.availableAmount(key); }
        @Override public long insert(AEKey key, long amount, Actionable mode) {
            if (fail[0]) { throw new IllegalStateException("External output preflight failed"); }
            return backing.insert(key, amount, mode);
        }
        @Override public long extract(AEKey key, long amount, Actionable mode) { return backing.extract(key, amount, mode); }
        @Override public ReservationResult reserveExtract(StorageSnapshot snapshot, AEKey key, long amount) {
            return backing.reserveExtract(snapshot, key, amount);
        }
        @Override public ReservationResult reserveInsert(StorageSnapshot snapshot, AEKey key, long amount) {
            return backing.reserveInsert(snapshot, key, amount);
        }
    }
}
