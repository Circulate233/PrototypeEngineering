package com.circulation.prototype_eng.api.recipe.importing;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKey2LongMap;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.MachineType;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.SimpleRecipePool;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.ResourceHatch;
import com.circulation.prototype_eng.api.storage.ResourceStorage;
import com.circulation.prototype_eng.localization.RecipePoolTitle;
import com.circulation.prototype_eng.storage.SlottedResourceBuffer;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ImportedRecipeBackendTest {
    @Test
    void selectingImplementationDoesNotLoadSourcesAndFreezingRetainsCanonicalStrategyAndBindings() {
        Bootstrap.register();
        var reads = new AtomicInteger();
        var pool = new ImportedRecipePool(id("pool"), "backend.pool.title", () -> {
            reads.incrementAndGet();
            return List.of(fixed("recipe", 1).build());
        });
        assertSame(pool, pool.poolType(RecipePoolType.SINGLE_INPUT));
        assertEquals(0, reads.get());
        assertEquals(List.of(), pool.machines());
        assertSame(RecipePoolType.SINGLE_INPUT, pool.poolType());
        var implementation = assertInstanceOf(SimpleRecipePool.class, pool.implementation());
        assertEquals(1, reads.get());
        var canonical = pool.recipes().getFirst();
        assertSame(canonical, implementation.recipes().getFirst());
        assertThrows(IllegalStateException.class, () -> pool.poolType(RecipePoolType.GENERAL));
        var machines = List.of(MachineType.builder(id("machine"), new RecipePoolTitle("backend.machine.title"), id("structure")).build());
        var frozen = pool.freeze(pool.recipes(), machines);
        assertNotSame(pool, frozen);
        assertInstanceOf(SimpleRecipePool.class, frozen.implementation());
        assertSame(canonical, frozen.recipes().getFirst());
        assertSame(machines, frozen.machines());
        assertSame(RecipePoolType.SINGLE_INPUT, frozen.poolType());
        assertEquals(1, reads.get());
    }

    @Test
    void nullableCreationAndExplicitGeneralPoolAllowMultipleInputsBeforeFirstAccess() {
        Bootstrap.register();
        var general = ImportedRecipePool.withoutContext(id("general"), "backend.general.title",
            () -> List.of(fixed("two_inputs", 2)), null);
        assertInstanceOf(DefaultRecipePool.class, general.implementation());
        var single = ImportedRecipePool.withoutContext(id("single"), "backend.single.title",
            () -> List.of(fixed("edited", 2)), recipe -> {
                var resource = (ResourceRequirement) recipe.requirements().getFirst().payload();
                recipe.requirements(List.of(RecipeRequirements.RESOURCES.requirement(id("edited_io"),
                    new ResourceRequirement(List.of(resource.inputs().getFirst()), resource.outputs()))));
                return true;
            }).poolType(RecipePoolType.SINGLE_INPUT);
        assertInstanceOf(SimpleRecipePool.class, single.implementation());
        var invalid = ImportedRecipePool.withoutContext(id("invalid"), "backend.invalid.title",
            () -> List.of(fixed("multiple", 2))).poolType(RecipePoolType.SINGLE_INPUT);
        assertThrows(IllegalArgumentException.class, invalid::recipes);
        assertThrows(IllegalStateException.class, invalid::recipes);
    }

    @Test
    void dynamicSingleInputsRetainNbtMatchingAndUseTheOrdinaryTransaction() {
        Bootstrap.register();
        var displayed = item(Items.IRON_INGOT);
        var taggedStack = new ItemStack(Items.IRON_INGOT);
        var tag = new NBTTagCompound();
        tag.setString("Custom", "accepted");
        taggedStack.setTagCompound(tag);
        var tagged = Objects.requireNonNull(AEItemKey.of(taggedStack));
        var matcher = new NamedResourceMatcher(id("dynamic_matcher"),
            (candidate, _) -> candidate instanceof AEItemKey item && item.getItem() == Items.IRON_INGOT);
        var dynamic = new DynamicInputGroup(matcher, displayed, 1, List.of(displayed), Integer.MAX_VALUE);
        var recipe = new RecipeBuilder(id("dynamic"), 1).addRequirement(RecipeRequirements.RESOURCES.requirement(id("dynamic_io"),
            new ResourceRequirement(List.of(), List.of(), List.of(), List.of(new GenericStack(item(Items.GOLD_INGOT), 1)), false,
                ResourceChance.ALWAYS, null, List.of(), List.of(dynamic)))).build();
        var pool = new ImportedRecipePool(id("dynamic_pool"), "backend.dynamic.title", () -> List.of(recipe))
            .poolType(RecipePoolType.SINGLE_INPUT);
        assertInstanceOf(SimpleRecipePool.class, pool.implementation());
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(tagged, 1);
        assertSame(recipe, pool.match(inventory));
        var stock = storage();
        var machine = machine(stock);
        var process = new RecipeProcess(machine, recipe, 7);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        stock.insert(tagged, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, stock.availableAmount(tagged));
        assertEquals(1, stock.availableAmount(item(Items.GOLD_INGOT)));
        assertSame(RecipeProcess.Lifecycle.COMPLETED, process.state().lifecycle());
    }

    @Test
    void generalSingleInputAuxiliaryDeclarationsRemainIndependentAndTransactional() {
        Bootstrap.register();
        var primary = fixed("auxiliary", 1).build();
        var source = SimpleRecipePool.forRecipes(id("auxiliary_pool"), "backend.auxiliary.title", List.of(primary));
        var auxiliary = new GenericStack(item(Items.COAL), 2);
        var configured = source.withAuxiliaryInputs(List.of(auxiliary));
        var cleared = configured.withAuxiliaryInputs(List.of());
        assertEquals(1, source.recipes().getFirst().orderedRequirements().size());
        assertEquals(2, configured.recipes().getFirst().orderedRequirements().size());
        assertEquals(1, cleared.recipes().getFirst().orderedRequirements().size());
        var inventory = new AEKey2LongMap.OpenHashMap();
        inventory.put(item(Items.IRON_INGOT), 1);
        var recipe = configured.recipes().getFirst();
        assertSame(recipe, configured.match(inventory));
        var stock = storage();
        stock.insert(item(Items.IRON_INGOT), 1, Actionable.MODULATE);
        var process = new RecipeProcess(machine(stock), recipe, 11);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(1, stock.availableAmount(item(Items.IRON_INGOT)));
        stock.insert(auxiliary.what(), auxiliary.amount(), Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, stock.availableAmount(item(Items.COAL)));
        assertEquals(0, stock.availableAmount(item(Items.IRON_INGOT)));
        assertNotNull(cleared.match(inventory));
    }

    private static RecipeBuilder fixed(String name, int inputCount) {
        var inputs = inputCount == 1 ? List.of(new GenericStack(item(Items.IRON_INGOT), 1))
            : List.of(new GenericStack(item(Items.IRON_INGOT), 1), new GenericStack(item(Items.COAL), 1));
        return new RecipeBuilder(id(name), 1).addRequirement(RecipeRequirements.RESOURCES.requirement(id(name + "/io"),
            new ResourceRequirement(inputs, List.of(new GenericStack(item(Items.GOLD_INGOT), 1)))));
    }

    private static AEItemKey item(Item item) {
        return Objects.requireNonNull(AEItemKey.of(new ItemStack(item)));
    }

    private static SlottedResourceBuffer storage() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 64);
        return new SlottedResourceBuffer(3, capacities, () -> {});
    }

    private static MachineInstance machine(SlottedResourceBuffer stock) {
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(new ResourceHatch() {
            @Override
            public ResourceStorage input() {
                return stock;
            }

            @Override
            public ResourceStorage output() {
                return stock;
            }

            @Override
            public boolean isValid() {
                return true;
            }
        }), List.of(), List.of()));
        return machine;
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("import_backend_test", path);
    }
}
