package com.circulation.prototype_eng.storage;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKey;
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
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeScanner;
import com.circulation.prototype_eng.api.recipe.scanning.ScanLimits;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.api.storage.ResourceTransactions;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static com.circulation.prototype_eng.storage.MePatternAssemblyBlockEntity.Mode;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MePatternAssemblyStorageTest {
    private static final KeyType TYPE = new KeyType("pattern_assembly");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key CATALYST = new Key(TYPE, "catalyst");
    private static final Key PRODUCT = new Key(TYPE, "product");
    private static final ResourceKeyNbt KEYS = new ResourceKeyNbt(id -> id.equals(TYPE.getId()) ? TYPE : null);

    private static MePatternAssemblyStorage storage() {
        return new MePatternAssemblyStorage(2, () -> AEColor.RED, () -> {
        }, KEYS);
    }

    private static AEKey2LongMap amounts(AEKey key, long amount) {
        var result = new AEKey2LongMap.OpenHashMap();
        result.addTo(key, amount);
        return result;
    }

    private static MachineRecipe recipe(String path, int duration, List<GenericStack> inputs) {
        return new MachineRecipe(id(path), duration, List.of(RecipeRequirements.RESOURCES.requirement(id(path + "_io"),
            new ResourceRequirement(inputs, List.of(new GenericStack(PRODUCT, 2))))));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("pattern_assembly_test", path);
    }

    private static RecipeScanner.Result scan(Fixture fixture, MachineRecipe recipe) {
        var catalog = new RecipeCatalog(List.of(new DefaultRecipePool(id("pool_" + recipe.id().getPath()), "pool", List.of(recipe))));
        var scanner = new RecipeScanner(fixture.host, catalog, () -> 1L);
        for (int attempt = 0; attempt < 100; attempt++) {
            var result = scanner.scan(new ScanLimits(20, 20));
            if (result.status() != RecipeScanner.Status.YIELDED) return result;
        }
        throw new AssertionError("Pattern scanner failed to settle");
    }

    @Test
    void realRecipeCannotCombineIsolatedPatternSlotsButCanUseSharedCatalystAndReturnRemainingInput() {
        var storage = storage();
        storage.bindIdentity("isolation");
        assertTrue(storage.setMode(Mode.PATTERN_ISOLATION));
        assertTrue(storage.push(0, amounts(MATERIAL, 2)));
        assertTrue(storage.push(1, amounts(MATERIAL, 2)));
        storage.catalyst().insert(CATALYST, 1, Actionable.MODULATE);
        var recipe = recipe("isolation", 2, List.of(new GenericStack(MATERIAL, 4), new GenericStack(CATALYST, 1)));
        var fixture = new Fixture(storage);
        assertEquals(RecipeScanner.Status.EXHAUSTED, scan(fixture, recipe).status());
        assertTrue(storage.push(0, amounts(MATERIAL, 2)));
        var admitted = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        process.tick();
        assertEquals(0, storage.catalyst().amount(CATALYST));
        assertFalse(storage.setMode(Mode.DEFAULT));
        process.tick();
        assertEquals(2, storage.output().amount(PRODUCT));
        var network = new Network();
        assertTrue(storage.returnInputs(network));
        assertEquals(2, network.amounts.getLong(MATERIAL));
        assertTrue(storage.setMode(Mode.DEFAULT));
    }

    @Test
    void craftingLockTracksActualPayingProcessAndDoesNotUnlockOtherSameColorProvider() {
        var paying = storage();
        var unused = storage();
        assertTrue(paying.setMode(Mode.CRAFTING_LOCK));
        assertTrue(unused.setMode(Mode.CRAFTING_LOCK));
        assertTrue(paying.push(0, amounts(MATERIAL, 4)));
        assertTrue(unused.push(0, amounts(MATERIAL, 4)));
        var recipe = recipe("locking", 3, List.of(new GenericStack(MATERIAL, 4)));
        var admitted = scan(new Fixture(paying, unused), recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        assertTrue(paying.busy());
        process.tick();
        assertTrue(paying.busy());
        assertEquals(0, Objects.requireNonNull(paying.input()).amount(MATERIAL));
        assertEquals(4, Objects.requireNonNull(unused.input()).amount(MATERIAL));
        assertFalse(paying.push(0, amounts(MATERIAL, 1)));
        process.tick();
        assertTrue(paying.busy());
        process.tick();
        assertFalse(paying.busy());
        assertTrue(unused.busy());
        assertTrue(paying.push(0, amounts(MATERIAL, 1)));
    }

    @Test
    void recipeBlockingUsesOnlyActualCommittedInputReceiptsAndForgetsCompletedRecipe() {
        var storage = storage();
        assertTrue(storage.setMode(Mode.RECIPE_BLOCKING));
        assertTrue(storage.push(0, amounts(MATERIAL, 8)));
        var recipe = recipe("recipe_blocking", 2, List.of(new GenericStack(MATERIAL, 4)));
        var admitted = scan(new Fixture(storage), recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        assertFalse(storage.push(0, amounts(MATERIAL, 2)), "A successful scanner probe is not a running input receipt");
        var process = Objects.requireNonNull(admitted.process());
        process.tick();
        assertTrue(storage.push(0, amounts(MATERIAL, 2)));
        assertFalse(storage.push(0, amounts(CATALYST, 1)));
        process.tick();
        assertFalse(storage.push(0, amounts(MATERIAL, 2)), "Completed recipes cannot bypass a nonempty blocking cache");
    }

    @Test
    void recipeBlockingMatchesFullCommittedInputsAcrossPlainSameColorHatchPatternBufferAndCatalyst() {
        var storage = storage();
        assertTrue(storage.setMode(Mode.RECIPE_BLOCKING));
        assertTrue(storage.push(0, amounts(MATERIAL, 2)));
        storage.catalyst().insert(CATALYST, 1, Actionable.MODULATE);
        var plain = new PlainHatch();
        plain.input.insert(MATERIAL, 2, Actionable.MODULATE);
        var recipe = recipe("complete_receipt", 2, List.of(new GenericStack(MATERIAL, 4), new GenericStack(CATALYST, 1)));
        var admitted = scan(new Fixture(storage, plain), recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        process.tick();
        assertEquals(0, Objects.requireNonNull(storage.input()).amount(MATERIAL));
        assertEquals(0, plain.input.amount(MATERIAL));
        assertEquals(0, storage.catalyst().amount(CATALYST));
        var fullPattern = amounts(MATERIAL, 4);
        fullPattern.putAll(amounts(CATALYST, 1));
        assertTrue(storage.push(0, fullPattern));
        assertTrue(storage.push(0, fullPattern), "A nonempty cache accepts the running recipe's complete actual ingredients");
        assertFalse(storage.push(0, amounts(MATERIAL, 2)), "A partial source receipt must not become the full running-recipe signature");
        process.tick();
        assertFalse(storage.push(0, fullPattern));
    }

    @Test
    void receiptAndProcessIdentitySurviveSaveAndDifferentIdentityCannotReleaseRestoredLock() {
        var storage = storage();
        assertTrue(storage.setMode(Mode.CRAFTING_LOCK));
        assertTrue(storage.push(0, amounts(MATERIAL, 4)));
        var recipe = recipe("restore", 3, List.of(new GenericStack(MATERIAL, 4)));
        var fixture = new Fixture(storage);
        var admitted = scan(fixture, recipe);
        assertEquals(RecipeScanner.Status.ADMITTED, admitted.status());
        var process = Objects.requireNonNull(admitted.process());
        process.tick();
        var codec = RecipeProcessNbt.builder(recipe, "receipt").resources(KEYS).build();
        var savedProcess = codec.encode(process);
        var savedStorage = new NBTTagCompound();
        storage.save(savedStorage);
        var restored = MePatternAssemblyStorage.load(savedStorage, 2, () -> AEColor.RED, () -> {
        }, KEYS);
        var restoredFixture = new Fixture(restored);
        var resumed = codec.decode(restoredFixture.machine, savedProcess);
        assertEquals(process.identity(), resumed.identity());
        var wrong = new RecipeProcess(restoredFixture.machine, recipe, process.seed());
        assertNotEquals(resumed.identity(), wrong.identity());
        restored.stageCommitted(wrong, RecipeStage.END);
        assertTrue(restored.busy());
        restored.stageCommitted(resumed, RecipeStage.END);
        assertFalse(restored.busy());
        var malformed = savedStorage.copy();
        malformed.getTagList("Running", 10).appendTag(malformed.getTagList("Running", 10).get(0).copy());
        assertThrows(IllegalArgumentException.class, () -> MePatternAssemblyStorage.load(malformed, 2, () -> AEColor.RED, () -> {
        }, KEYS));
    }

    @Test
    void multiKeyPushIsAtomicAtLongCapacityAndDestructionDefersReservedResourcesExactlyOnce() {
        var storage = storage();
        assertTrue(storage.push(0, amounts(MATERIAL, Long.MAX_VALUE - 1)));
        var batch = amounts(MATERIAL, 2);
        batch.putAll(amounts(CATALYST, 3));
        assertFalse(storage.push(0, batch));
        assertEquals(0, Objects.requireNonNull(storage.input()).amount(CATALYST));
        assertEquals(1, storage.maxMultiplier(0, amounts(MATERIAL, 1), 100));
        var source = Objects.requireNonNull(storage.input());
        var token = Objects.requireNonNull(source.reserveExtract(source.snapshot(), MATERIAL, 1).reservation());
        var deferred = new ObjectArrayList<GenericStack>();
        assertTrue(storage.removeForDrops(deferred::addAll).isEmpty());
        assertTrue(deferred.isEmpty());
        ResourceTransactions.rollback(List.of(token));
        assertEquals(List.of(new GenericStack(MATERIAL, Long.MAX_VALUE - 1)), deferred);
        assertTrue(storage.removeForDrops(deferred::addAll).isEmpty());
        assertFalse(storage.push(0, amounts(MATERIAL, 1)));
    }

    private static final class Fixture {
        private final MachineInstance machine = new MachineInstance();
        private final MachineWorkSlots host;

        private Fixture(ResourceHatch... hatches) {
            machine.attributes().setBase(StandardMachineAttributes.MAX_CONCURRENT_PROCESSES, 1);
            machine.attributes().setBase(StandardMachineAttributes.PROCESS_PARALLELISM, 1);
            machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatches), List.of(), List.of()));
            host = new MachineWorkSlots(machine, List.of());
            machine.components().put(MachineWorkSlots.TYPE, host);
        }
    }

    private static final class Network implements MeResourceHatch.MeTransfer {
        private final AEKey2LongMap amounts = new AEKey2LongMap.OpenHashMap();

        @Override
        public long insert(AEKey key, long amount) {
            amounts.addTo(key, amount);
            return amount;
        }

        @Override
        public long extract(AEKey key, long amount) {
            return 0;
        }
    }

    private static final class PlainHatch implements ResourceHatch {
        private final UnboundedResourceBuffer input = new UnboundedResourceBuffer(() -> {});
        @Override public ResourceStorage input() { return input; }
        @Override public ResourceStorage output() { return null; }
        @Override public boolean isValid() { return true; }
    }
}
