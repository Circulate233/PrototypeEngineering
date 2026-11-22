package com.circulation.prototype_eng.integration.recipeimport.tconstruct;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEFluidKey;
import ae2.api.stacks.AEItemKey;
import ae2.api.stacks.AEKeyType;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import it.unimi.dsi.fastutil.objects.Reference2LongArrayMap;
import net.minecraft.init.Bootstrap;
import net.minecraft.init.Items;
import net.minecraft.item.ItemStack;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.fluids.FluidRegistry;
import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import slimeknights.mantle.util.RecipeMatch;
import slimeknights.tconstruct.library.DryingRecipe;
import slimeknights.tconstruct.library.smeltery.CastingRecipe;
import slimeknights.tconstruct.library.smeltery.ICastingRecipe;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TConstructAdditionalSourcesTest {
    @BeforeAll
    static void bootstrap() {
        Bootstrap.register();
    }

    @Test
    void dryingRetainsOriginalInputPredicateQuantityAndTickDuration() {
        var source = new DryingRecipe(RecipeMatch.of(new ItemStack(Items.APPLE), 1, 2), new ItemStack(Items.GOLDEN_APPLE, 3), 71);
        var converted = TConstructDryingSource.convertAll(id("drying"), List.of(source), null).getFirst();
        assertEquals(source.getTime(), converted.durationTicks());
        var resources = resources(converted);
        var input = resources.dynamicInputs().getFirst();
        assertEquals(source.input.amountNeeded, input.amount());
        assertTrue(input.matcher().matches(AEItemKey.of(new ItemStack(Items.APPLE)), input.pattern()));
        assertFalse(input.matcher().matches(AEItemKey.of(new ItemStack(Items.COAL)), input.pattern()));
        assertEquals(source.getResult().getCount(), resources.outputs().getFirst().amount());
        assertTrue(TConstructDryingSource.convertAll(id("rejected_drying"), List.of(source), _ -> false).isEmpty());
        var failure = new IllegalStateException("Drying handler failed");
        assertSame(failure, assertThrows(IllegalStateException.class,
            () -> TConstructDryingSource.convertAll(id("failed_drying"), List.of(source), _ -> {
                throw failure;
            })));
    }

    @Test
    void reusableCastIsRequiredWithoutBeingConsumedAndFluidPaymentRetriesAtomically() {
        var source = casting(false);
        var converted = TConstructCastingSources.convertAll(id("table"), "table", List.of(source), null).getFirst();
        var presence = (ResourcePresenceRequirement) Objects.requireNonNull(converted.requirements().get(RecipeRequirements.RESOURCE_PRESENCE))
            .getFirst().payload();
        var cast = presence.resources().dynamicInputs().getFirst();
        assertTrue(cast.matcher().matches(AEItemKey.of(new ItemStack(Items.IRON_INGOT)), cast.pattern()));
        assertFalse(cast.matcher().matches(AEItemKey.of(new ItemStack(Items.COAL)), cast.pattern()));
        var materials = resources(converted);
        assertEquals(source.getTime(), converted.durationTicks());
        assertEquals(source.getFluidAmount(), materials.inputs().getFirst().amount());
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        var output = Objects.requireNonNull(hatch.output());
        var fluid = Objects.requireNonNull(AEFluidKey.of(source.getFluid()));
        input.insert(fluid, source.getFluidAmount(), Actionable.MODULATE);
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        var process = new RecipeProcess(owner, RecipeBuilder.from(converted).durationTicks(0).build(), 41);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(source.getFluidAmount(), input.availableAmount(fluid));
        var mold = Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.IRON_INGOT)));
        input.insert(mold, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, input.availableAmount(mold));
        assertEquals(0, input.availableAmount(fluid));
        assertEquals(source.getResult().getCount(), output.availableAmount(AEItemKey.of(source.getResult())));
    }

    @Test
    void consumedCastIsAnOrdinaryInputAndCallbacksRetainSourceFlags() {
        var source = casting(true);
        var recipe = TConstructCastingSources.convertAll(id("basin"), "basin", List.of(source), (draft, consumed, switched) -> {
            assertEquals(source.consumesCast(), consumed);
            assertEquals(source.switchOutputs(), switched);
            draft.durationTicks(0);
            return true;
        }).getFirst();
        var materials = resources(recipe);
        assertEquals(1, materials.dynamicInputs().size());
        var hatch = hatch();
        var input = Objects.requireNonNull(hatch.input());
        var fluid = Objects.requireNonNull(AEFluidKey.of(source.getFluid()));
        var mold = Objects.requireNonNull(AEItemKey.of(new ItemStack(Items.IRON_INGOT)));
        input.insert(fluid, source.getFluidAmount(), Actionable.MODULATE);
        input.insert(mold, 1, Actionable.MODULATE);
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatch), List.of(), List.of()));
        assertSame(ProcessResult.SUCCESS, new RecipeProcess(owner, recipe, 43).tick());
        assertEquals(0, input.availableAmount(mold));
        assertEquals(0, input.availableAmount(fluid));
        assertTrue(TConstructCastingSources.convertAll(id("rejected_casting"), "table", List.of(source), (_, _, _) -> false).isEmpty());
    }

    @Test
    void unenumerableCastingInterfacesAreRejectedBeforeAnyCreationCallback() {
        var source = new ICastingRecipe() {
            @Override
            public ItemStack getResult(@Nonnull ItemStack cast, @Nonnull Fluid fluid) {
                throw new AssertionError("Dynamic result must not be replaced with a guessed constant");
            }

            @Override
            public boolean matches(@Nonnull ItemStack cast, @Nonnull Fluid fluid) {
                return true;
            }

            @Override
            public boolean switchOutputs() {
                return false;
            }

            @Override
            public boolean consumesCast() {
                return false;
            }

            @Override
            public int getTime() {
                return 1;
            }

            @Override
            public int getFluidAmount() {
                return 1;
            }
        };
        assertTrue(TConstructCastingSources.convertAll(id("dynamic"), "table", List.of(source), (_, _, _) -> {
            throw new AssertionError("Rejected source must not invoke the creation callback");
        }).isEmpty());
    }

    private static CastingRecipe casting(boolean consumed) {
        return new CastingRecipe(new ItemStack(Items.GOLD_INGOT, 2), RecipeMatch.of(new ItemStack(Items.IRON_INGOT)),
            new FluidStack(FluidRegistry.WATER, 160), 53, consumed, true);
    }

    private static ResourceRequirement resources(MachineRecipe recipe) {
        return (ResourceRequirement) Objects.requireNonNull(recipe.requirements().get(RecipeRequirements.RESOURCES)).getFirst().payload();
    }

    private static BufferedResourceHatch hatch() {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(AEKeyType.items(), 64);
        capacities.put(AEKeyType.fluids(), 1000);
        return new BufferedResourceHatch(HatchMode.MIXED, 4, capacities, () -> {
        });
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("tconstruct_additional_test", path);
    }
}
