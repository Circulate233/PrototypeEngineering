package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.GenericStack;
import ae2.api.util.AEColor;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.BudgetResult;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeWorkBudget;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ReservationResult;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceReservation;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.circulation.prototype_eng.storage.MEPatternAssemblyBlockEntity.Mode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MEPatternMirrorHatchTest {
    private static final KeyType TYPE = new KeyType("pattern_mirror");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key CATALYST = new Key(TYPE, "catalyst");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(id -> id.equals(TYPE.getId()) ? TYPE : null);

    private static MEPatternAssemblyStorage storage() {
        return new MEPatternAssemblyStorage(2, () -> AEColor.RED, () -> {
        }, KEYS);
    }

    private static ResourceStorage input(ResourceHatch hatch) {
        var input = hatch.input();
        assertNotNull(input);
        return input;
    }

    private static AEKey2LongMap amounts(long amount) {
        var result = new AEKey2LongMap.OpenHashMap();
        result.addTo(MATERIAL, amount);
        return result;
    }

    private static MachineRecipe recipe(String path, List<GenericStack> inputs) {
        return new MachineRecipe(id(path), 1, List.of(RecipeRequirements.RESOURCES.requirement(id(path + "_io"),
            new ResourceRequirement(inputs, List.of(new GenericStack(PRODUCT, 2))))));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("pattern_mirror_test", path);
    }

    private static RecipeScanner.Result scan(Fixture fixture, MachineRecipe recipe) {
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool_" + recipe.id().getPath()), "pool", List.of(recipe))));
        var scanner = new RecipeScanner(fixture.host, catalog, () -> 1L);
        for (int attempt = 0; attempt < 100; attempt++) {
            var result = scanner.scan(new ScanLimits(20, 20));
            if (result.status() != RecipeScanner.Status.YIELDED) return result;
        }
        throw new AssertionError("Mirror scanner failed to settle");
    }

    @Test
    void assemblyAndTwoMirrorsShareOneBalanceAndReleaseTheOriginalCraftingLock() {
        var storage = storage();
        assertTrue(storage.setMode(Mode.CRAFTING_LOCK));
        assertTrue(storage.push(0, amounts(4)));
        var first = new MEPatternMirrorHatch(storage);
        var second = new MEPatternMirrorHatch(storage);
        var fixture = new Fixture(first, second, storage);

        var duplicate = recipe("duplicate", List.of(new GenericStack(MATERIAL, 8)));
        assertEquals(RecipeScanner.Status.EXHAUSTED, scan(fixture, duplicate).status());
        var actual = recipe("actual", List.of(new GenericStack(MATERIAL, 4)));
        var admitted = scan(fixture, actual);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = admitted.process();
        assertNotNull(process);
        assertTrue(storage.busy());
        process.tick();

        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, input(storage).amount(MATERIAL));
        assertEquals(2, storage.output().amount(PRODUCT));
        assertFalse(storage.busy());
    }

    @Test
    void mirrorPortsRetainPatternIsolationAndTheAssembliesSharedCatalyst() {
        var storage = storage();
        assertTrue(storage.setMode(Mode.PATTERN_ISOLATION));
        assertTrue(storage.push(0, amounts(2)));
        assertTrue(storage.push(1, amounts(2)));
        storage.catalyst().insert(CATALYST, 1, Actionable.MODULATE);
        var first = new MEPatternMirrorHatch(storage);
        var second = new MEPatternMirrorHatch(storage);
        var fixture = new Fixture(first, second);

        var combined = recipe("combined", List.of(new GenericStack(MATERIAL, 4), new GenericStack(CATALYST, 1)));
        assertEquals(RecipeScanner.Status.EXHAUSTED, scan(fixture, combined).status());
        var single = recipe("single", List.of(new GenericStack(MATERIAL, 2), new GenericStack(CATALYST, 1)));
        var admitted = scan(fixture, single);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = admitted.process();
        assertNotNull(process);
        assertEquals(storage.inputPartitions().getFirst().inputGroup(), process.selectedInputGroup());
        process.tick();

        assertEquals(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
        assertEquals(0, input(storage.inputPartitions().get(0)).amount(MATERIAL));
        assertEquals(2, input(storage.inputPartitions().get(1)).amount(MATERIAL));
        assertEquals(0, storage.catalyst().amount(CATALYST));
        assertEquals(2, storage.output().amount(PRODUCT));
    }

    @Test
    void revocationRejectsCapturedInputAndOutputWithoutInvalidatingTheOriginalAssembly() {
        var storage = storage();
        assertTrue(storage.push(0, amounts(4)));
        var mirror = new MEPatternMirrorHatch(storage);
        var input = input(mirror);
        var output = mirror.output();
        var take = input.reserveExtract(input.snapshot(), MATERIAL, 3).reservation();
        var give = output.reserveInsert(output.snapshot(), PRODUCT, 2).reservation();
        assertNotNull(take);
        assertNotNull(give);
        mirror.revoke();

        take.context().lock();
        try {
            assertEquals(BudgetResult.REJECTED, take.canApplyWithinBudget(new RecipeWorkBudget(1)));
        } finally {
            take.context().unlock();
        }
        assertFalse(ResourceTransactions.commit(List.of(take, give)));
        assertEquals(4, input(storage).availableAmount(MATERIAL));
        assertEquals(0, storage.output().amount(PRODUCT));
        assertTrue(storage.isValid());
        assertEquals(0, input.extract(MATERIAL, 1, Actionable.MODULATE));
        assertEquals(ReservationResult.Status.INVALID_STORAGE, output.reserveInsert(output.snapshot(), PRODUCT, 1).status());

        var next = new MEPatternMirrorHatch(storage);
        assertEquals(1, input(next).extract(MATERIAL, 1, Actionable.MODULATE));
        assertEquals(3, input(storage).amount(MATERIAL));
    }

    @Test
    void revokedAppliedTokensCompleteOrRollbackOnTheirOriginalLedgers() {
        var storage = storage();
        assertTrue(storage.push(0, amounts(4)));
        var mirror = new MEPatternMirrorHatch(storage);
        var input = input(mirror);
        var take = input.reserveExtract(input.snapshot(), MATERIAL, 3).reservation();
        var give = mirror.output().reserveInsert(mirror.output().snapshot(), PRODUCT, 2).reservation();
        assertNotNull(take);
        assertNotNull(give);
        take.context().lock();
        try {
            assertTrue(take.canApply());
            take.apply();
        } finally {
            take.context().unlock();
        }
        give.context().lock();
        try {
            assertTrue(give.canApply());
            give.apply();
        } finally {
            give.context().unlock();
        }
        mirror.revoke();
        var replacement = storage();
        assertTrue(replacement.push(0, amounts(9)));
        var next = new MEPatternMirrorHatch(replacement);

        take.context().lock();
        try {
            assertTrue(take.rollbackWithinBudget(new RecipeWorkBudget(1)));
            assertEquals(ResourceReservation.Resolution.ROLLED_BACK, take.resolution());
        } finally {
            take.context().unlock();
        }
        give.context().lock();
        try {
            assertTrue(give.completeWithinBudget(new RecipeWorkBudget(1)));
        } finally {
            give.context().unlock();
        }

        assertEquals(4, input(storage).amount(MATERIAL));
        assertEquals(2, storage.output().amount(PRODUCT));
        assertEquals(9, input(next).amount(MATERIAL));
        assertEquals(0, replacement.output().amount(PRODUCT));
    }

    @Test
    void revocationBeforeCaptureTransferRetainsNativeCancellationObligations() {
        var storage = storage();
        assertTrue(storage.push(0, amounts(4)));
        var mirror = new MEPatternMirrorHatch(storage);
        var input = input(mirror);
        var capture = input.beginReserveExtract(input.snapshot(), MATERIAL, 3);
        assertFalse(capture.advance(new RecipeWorkBudget(1)));
        assertTrue(capture.advance(new RecipeWorkBudget(1)));
        assertTrue(capture.hasObligations());
        mirror.revoke();

        assertEquals(ReservationResult.Status.INVALID_STORAGE, capture.result().status());
        assertEquals(0, capture.amount());
        assertTrue(capture.hasObligations());
        input.context().lock();
        try {
            assertTrue(capture.cancel(new RecipeWorkBudget(1)));
        } finally {
            input.context().unlock();
        }
        assertEquals(4, input(storage).availableAmount(MATERIAL));
    }

    @Test
    void colorAndIsolationChangesInvalidatePublishedPortsAndTheirQuantityProofs() {
        var color = new AEColor[]{AEColor.RED};
        var storage = new MEPatternAssemblyStorage(2, () -> color[0], () -> {
        }, KEYS);
        var mirror = new MEPatternMirrorHatch(storage);
        var old = mirror.inputPartitions().getFirst();
        var quantities = input(old).beginQuantityCapture();
        assertTrue(quantities.advance(new RecipeWorkBudget(4)));
        assertTrue(quantities.current());
        color[0] = AEColor.BLUE;

        assertFalse(old.isValid());
        assertFalse(quantities.current());
        var colored = mirror.inputPartitions().getFirst();
        assertEquals(AEColor.BLUE, colored.inputGroup().color());
        assertTrue(colored.isValid());
        assertTrue(storage.setMode(Mode.PATTERN_ISOLATION));
        assertFalse(colored.isValid());
        assertNull(mirror.input());
        assertEquals(storage.inputPartitions().getFirst().inputGroup(), mirror.inputPartitions().getFirst().inputGroup());
        storage.invalidate();
        assertFalse(mirror.isValid());
        assertFalse(mirror.inputPartitions().getFirst().isValid());
    }

    private static final class Fixture {
        private final MachineWorkSlots host;

        private Fixture(ResourceHatch... hatches) {
            var machine = new MachineInstance();
            machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatches), List.of(), List.of()));
            host = new MachineWorkSlots(machine, List.of());
            machine.components().put(MachineWorkSlots.TYPE, host);
        }
    }
}
