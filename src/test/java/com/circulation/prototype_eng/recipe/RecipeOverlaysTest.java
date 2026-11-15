package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.matching.ResourceMatchers;
import com.circulation.prototype_eng.api.recipe.overlay.EnergyRequirementPatch;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayTypes;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.overlay.ResourceRequirementPatch;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.probability.WeightedSampling;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.CheckpointRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.FuzzyInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RandomOutputPool;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class RecipeOverlaysTest {
    private static final MachineHatches NO_HATCHES = new MachineHatches(List.of(), List.of(), List.of());
    private static final KeyType FIRST = new KeyType("overlay_first");
    private static final KeyType SECOND = new KeyType("overlay_second");
    private static final Key MATERIAL = new Key(FIRST, "material");
    private static final Key LIQUID = new Key(SECOND, "liquid");
    private static final Key PRODUCT = new Key(FIRST, "product");
    private static final RecipeRequirementType<Integer> NUMBER = new RecipeRequirementType<>(id("number"), new RecipeRequirementSystem<>() {
    });
    private static final RecipeOverlayType<Integer, Integer> ADD = new RecipeOverlayType<>(id("add"), NUMBER, Math::addExact);
    private static final RecipeOverlayType<Integer, Integer> MULTIPLY = new RecipeOverlayType<>(id("multiply"), NUMBER, Math::multiplyExact);

    private static RecipeProcess process(int duration, MachineHatches hatches, RecipeRequirement<?>... requirements) {
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, hatches);
        return new RecipeProcess(owner, new MachineRecipe(id("recipe"), duration, ObjectArrayList.of(requirements)), 1);
    }

    private static BufferedResourceHatch hatch(HatchMode mode, int slots) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(FIRST, 100);
        capacities.put(SECOND, 100);
        return new BufferedResourceHatch(mode, slots, capacities, () -> {
        });
    }

    private static GenericStack stack(Key key, long amount) {
        return new GenericStack(key, amount);
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("overlay_test", path);
    }

    @Test
    void partialLayersOverrideAllResourceStagesWithoutChangingAnotherProcessUsingTheSameRecipe() {
        var inputHatch = hatch(HatchMode.INPUT, 3);
        var outputHatch = hatch(HatchMode.OUTPUT, 3);
        var input = Objects.requireNonNull(inputHatch.input());
        var output = Objects.requireNonNull(outputHatch.output());
        input.insert(MATERIAL, 10, Actionable.MODULATE);
        input.insert(LIQUID, 20, Actionable.MODULATE);
        var target = RecipeRequirements.RESOURCES.requirement(id("io"), new ResourceRequirement(
            List.of(stack(MATERIAL, 3)), List.of(stack(LIQUID, 2)), List.of(stack(PRODUCT, 1)), List.of(stack(PRODUCT, 2)), false));
        var process = process(1, new MachineHatches(List.of(inputHatch, outputHatch), List.of(), List.of()), target);
        var overlays = RecipeOverlays.getOrCreate(process);
        var replacement = new ObjectArrayList<>(List.of(stack(LIQUID, 4)));
        overlays.put(target, "start_end", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(replacement, null, null, List.of(stack(PRODUCT, 6)), -1)).complete();
        overlays.put(target, "tick", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(null, List.of(stack(LIQUID, 3)), List.of(stack(MATERIAL, 2)), null, -1)).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(10, input.amount(MATERIAL));
        assertEquals(13, input.amount(LIQUID));
        assertEquals(2, output.amount(MATERIAL));
        assertEquals(6, output.amount(PRODUCT));

        var otherInputHatch = hatch(HatchMode.INPUT, 2);
        var otherOutputHatch = hatch(HatchMode.OUTPUT, 1);
        var otherInput = Objects.requireNonNull(otherInputHatch.input());
        otherInput.insert(MATERIAL, 3, Actionable.MODULATE);
        otherInput.insert(LIQUID, 2, Actionable.MODULATE);
        var other = process(1, new MachineHatches(List.of(otherInputHatch, otherOutputHatch), List.of(), List.of()), target);
        assertSame(ProcessResult.SUCCESS, other.tick());
        assertEquals(0, otherInput.amount(MATERIAL));
        assertEquals(0, otherInput.amount(LIQUID));
        assertEquals(3, Objects.requireNonNull(otherOutputHatch.output()).amount(PRODUCT));
    }

    @Test
    void clearingFixedIoKeepsProbabilityGroupsAndRandomOutputBehavior() {
        var inputHatch = hatch(HatchMode.INPUT, 2);
        var outputHatch = hatch(HatchMode.OUTPUT, 1);
        var input = Objects.requireNonNull(inputHatch.input());
        input.insert(LIQUID, 4, Actionable.MODULATE);
        input.insert(MATERIAL, 2, Actionable.MODULATE);
        var fixed = List.of(stack(PRODUCT, 99));
        var payload = new ResourceRequirement(fixed, fixed, fixed, fixed, false, new ResourceChance(200, null),
            new RandomOutputPool(List.of(new WeightedSampling.Weighted<>(stack(PRODUCT, 1), 1)), 1),
            List.of(new FuzzyInputGroup(List.of(LIQUID), 2)), List.of(new DynamicInputGroup(ResourceMatchers.EXACT, MATERIAL, 1)));
        var target = RecipeRequirements.RESOURCES.requirement(id("advanced"), payload);
        var process = process(1, new MachineHatches(List.of(inputHatch, outputHatch), List.of(), List.of()), target);
        RecipeOverlays.getOrCreate(process).put(target, "clear_fixed", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(List.of(), List.of(), List.of(), List.of(), -1)).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.amount(LIQUID));
        assertEquals(0, input.amount(MATERIAL));
        assertEquals(2, Objects.requireNonNull(outputHatch.output()).amount(PRODUCT));
        assertEquals(2, ResourceInputLocks.getOrCreate(process).snapshot().size());
    }

    @Test
    void energyTickPatchAcceptsZeroAndVoidWithoutReplacingStartAndEndQuantities() {
        var input = new EnergyBuffer(100, () -> {
        });
        var output = new EnergyBuffer(10, () -> {
        });
        input.receiveEnergy(3, Actionable.MODULATE);
        var target = RecipeRequirements.ENERGY.requirement(id("energy"), new EnergyRequirement(3, 20, 30, 8, false));
        var process = process(1, new MachineHatches(List.of(), List.of(input), List.of(output)), target);
        RecipeOverlays.getOrCreate(process).put(target, "rates", RecipeOverlayTypes.ENERGY_RATES,
            new EnergyRequirementPatch(0L, 4L, 1)).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.storedEnergy());
        assertEquals(10, output.storedEnergy());
    }

    @Test
    void replacingAndUndoingOneLayerPreservesInsertionOrderAndAnotherOwnersPermanentLayer() {
        var target = NUMBER.requirement(id("number"), 10);
        var overlays = RecipeOverlays.getOrCreate(process(0, NO_HATCHES, target));
        var initial = overlays.put(target, "first", ADD, 4);
        overlays.put(target, "other_owner", MULTIPLY, 3).complete();
        var replacement = overlays.put(target, "first", ADD, 2);
        assertEquals(36, overlays.resolve(target).payload());
        replacement.rollback();
        assertEquals(42, overlays.resolve(target).payload());
        initial.rollback();
        assertEquals(30, overlays.resolve(target).payload());
        var removed = overlays.remove(target, "other_owner");
        overlays.put(target, "last", ADD, 5).complete();
        assertEquals(15, overlays.resolve(target).payload());
        removed.rollback();
        assertEquals(35, overlays.resolve(target).payload());
        overlays.clear();
        assertSame(target, overlays.resolve(target));
    }

    @Test
    void failedPureApplicationAndIncorrectBindingsCannotPublishPartialState() {
        var target = NUMBER.requirement(id("number"), 10);
        var overlays = RecipeOverlays.getOrCreate(process(0, NO_HATCHES, target));
        var pending = overlays.put(target, "own", ADD, 4);
        assertThrows(IllegalStateException.class, overlays::clear);
        var broken = new RecipeOverlayType<Integer, Integer>(id("broken"), NUMBER, (_, _) -> {
            throw new IllegalArgumentException("bad patch");
        });
        assertThrows(IllegalArgumentException.class, () -> overlays.put(target, "own", broken, 1));
        assertEquals(14, overlays.resolve(target).payload());
        var counterfeit = new RecipeRequirementType<>(NUMBER.id(), NUMBER.system());
        var wrongType = new RecipeOverlayType<>(id("wrong"), counterfeit, Integer::sum);
        assertThrows(IllegalArgumentException.class, () -> overlays.put(target, "other", wrongType, 1));
        assertThrows(IllegalArgumentException.class, () -> overlays.resolve(NUMBER.requirement(target.id(), 99)));
        pending.rollback();
        overlays.clear();
        assertSame(target, overlays.resolve(target));
    }

    @Test
    void effectiveCallbackViewsCanTargetTheSameDefinitionWithoutApplyingItsOldLayersTwice() {
        var target = NUMBER.requirement(id("number"), 10);
        var overlays = RecipeOverlays.getOrCreate(process(0, NO_HATCHES, target));
        overlays.put(target, "first", ADD, 4).complete();
        var effective = overlays.resolve(target);
        var next = overlays.put(effective, "second", MULTIPLY, 3);
        assertEquals(42, overlays.resolve(target).payload());
        assertEquals(14, effective.payload());
        next.rollback();
        assertEquals(14, overlays.resolve(target).payload());
        assertSame(target, effective.definition());
    }

    @Test
    void recursiveApplierMutationFailsBeforeChangingEitherLayer() {
        var target = NUMBER.requirement(id("number"), 10);
        var overlays = RecipeOverlays.getOrCreate(process(0, NO_HATCHES, target));
        overlays.put(target, "existing", ADD, 4).complete();
        var recursive = new RecipeOverlayType<Integer, Integer>(id("recursive"), NUMBER, (base, value) -> {
            overlays.put(target, "side_effect", ADD, value).complete();
            return base;
        });
        assertThrows(IllegalStateException.class, () -> overlays.put(target, "broken", recursive, 1));
        assertEquals(14, overlays.resolve(target).payload());
        overlays.remove(target, "existing").complete();
        assertSame(target, overlays.resolve(target));
        overlays.clear();
    }

    @Test
    void twoCheckpointsSharingOneChildResolveIndependentPayloadsByFullPath() {
        var hatch = hatch(HatchMode.INPUT, 1);
        var input = Objects.requireNonNull(hatch.input());
        input.insert(MATERIAL, 5, Actionable.MODULATE);
        var child = RecipeRequirements.RESOURCES.requirement(id("child"), new ResourceRequirement(List.of(stack(MATERIAL, 3)), List.of()));
        var first = RecipeRequirements.CHECKPOINT.requirement(id("first"), new CheckpointRequirement(0, child));
        var second = RecipeRequirements.CHECKPOINT.requirement(id("second"), new CheckpointRequirement(0, child));
        var process = process(0, new MachineHatches(List.of(hatch), List.of(), List.of()), first, second);
        var overlays = RecipeOverlays.getOrCreate(process);
        overlays.put(child, child.path(first.path(null)), "amount", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(List.of(stack(MATERIAL, 4)), null, null, null, -1)).complete();
        overlays.put(child, child.path(second.path(null)), "amount", RecipeOverlayTypes.RESOURCE_IO,
            new ResourceRequirementPatch(List.of(stack(MATERIAL, 1)), null, null, null, -1)).complete();
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(0, input.amount(MATERIAL));
    }
}
