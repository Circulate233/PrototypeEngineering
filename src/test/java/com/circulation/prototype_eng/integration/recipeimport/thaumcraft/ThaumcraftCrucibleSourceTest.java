package com.circulation.prototype_eng.integration.recipeimport.thaumcraft;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import thaumcraft.api.aspects.Aspect;
import thaumcraft.api.aspects.AspectList;
import thaumcraft.api.crafting.CrucibleRecipe;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ThaumcraftCrucibleSourceTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void catalystPredicateAndFullProductAreKeptWithIndependentAspectMetadata() {
        var source = new CrucibleRecipe("CRUCIBLE_RESEARCH", new ItemStack(Items.GOLD_INGOT, 3), new ItemStack(Items.IRON_INGOT),
            new AspectList().add(Aspect.FIRE, 9));
        var captured = new AtomicReference<AspectList>();
        var converted = ThaumcraftCrucibleSource.convert(id("recipe"), id("source"), source, (_, sourceId, aspects, research) -> {
            assertEquals(id("source"), sourceId);
            assertEquals(source.getResearch(), research);
            assertNotSame(source.getAspects(), aspects);
            captured.set(aspects);
            return true;
        }).getFirst();
        var resources = (ResourceRequirement) Objects.requireNonNull(converted.requirements().get(RecipeRequirements.RESOURCES)).getFirst().payload();
        var dynamic = resources.dynamicInputs().getFirst();
        assertTrue(dynamic.matcher().matches(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), dynamic.pattern()));
        assertFalse(dynamic.matcher().matches(AEItemKey.of(new ItemStack(Items.COAL)), dynamic.pattern()));
        assertEquals(source.getRecipeOutput().getCount(), resources.outputs().getFirst().amount());
        int copiedAmount = captured.get().getAmount(Aspect.FIRE);
        source.getAspects().add(Aspect.FIRE, 1);
        assertEquals(copiedAmount, captured.get().getAmount(Aspect.FIRE));
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 64);
        var hatch = new BufferedResourceHatch(HatchMode.MIXED, 2, capacities, () -> {
        });
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var inputKey = Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.IRON_INGOT)));
        input.insert(inputKey, 1, Actionable.MODULATE);
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        assertSame(ProcessResult.SUCCESS, new RecipeProcess(owner, converted, 47).tick());
        assertEquals(0, input.availableAmount(inputKey));
        assertEquals(source.getRecipeOutput().getCount(), output.availableAmount(AEItemKey.of(source.getRecipeOutput())));
    }

    @Test
    void nullHandlerSkipsAspectsAndResearchAndConfiguredHandlersCanRejectOrFail() {
        var failMetadataReads = new boolean[1];
        var source = new CrucibleRecipe("CRUCIBLE_RESEARCH", new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT), new AspectList()) {
            @Override
            public AspectList getAspects() {
                if (failMetadataReads[0]) throw new AssertionError("Null handler must not request aspects");
                return super.getAspects();
            }

            @Override
            public String getResearch() {
                if (failMetadataReads[0]) throw new AssertionError("Null handler must not request research");
                return super.getResearch();
            }
        };
        failMetadataReads[0] = true;
        assertEquals(1, ThaumcraftCrucibleSource.convert(id("no_handler"), id("source"), source, null).size());
        var ordinary = new CrucibleRecipe("CRUCIBLE_RESEARCH", new ItemStack(Items.GOLD_INGOT), new ItemStack(Items.IRON_INGOT), new AspectList());
        assertTrue(ThaumcraftCrucibleSource.convert(id("reject"), id("source"), ordinary, (_, _, _, _) -> false).isEmpty());
        var failure = new IllegalStateException("Crucible handler failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> ThaumcraftCrucibleSource.convert(id("failure"), id("source"), ordinary, (_, _, _, _) -> {
                throw failure;
            })));
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("crucible_import_test", path);
    }
}
