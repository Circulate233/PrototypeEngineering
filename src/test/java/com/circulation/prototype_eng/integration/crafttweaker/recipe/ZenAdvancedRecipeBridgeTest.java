package com.circulation.prototype_eng.integration.crafttweaker.recipe;

import ae2.api.config.Actionable;
import com.circulation.prototype_eng.api.data.DataComponents;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.recipe.MachineRecipe;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayPipeline;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementSystem;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirementType;
import com.circulation.prototype_eng.api.recipe.requirement.RequirementTransaction;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.energy.EnergyBuffer;
import com.circulation.prototype_eng.storage.BufferedResourceHatch;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2LongMaps;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagInt;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ZenAdvancedRecipeBridgeTest {
    private static final KeyType TYPE = new KeyType("advanced_bridge");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key PRODUCT = new Key(TYPE, "product");

    private static ResourceLocation id(String name) {
        return new ResourceLocation("advanced_bridge", name);
    }

    private static BufferedResourceHatch hatch(HatchMode mode) {
        return new BufferedResourceHatch(mode, 2, Reference2LongMaps.singleton(TYPE, 100L), () -> {
        });
    }

    private static MachineInstance owner(MachineHatches hatches) {
        var owner = new MachineInstance();
        owner.components().put(MachineHatches.TYPE, hatches);
        return owner;
    }

    private static void compileAndRun(String script, String name) throws Exception {
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenResource.class, ZenRequirement.class, ZenRecipe.class, ZenInputAllocation.class,
            ZenAllocationCallback.class, ZenAllocationContext.class, ZenAllocationSource.class, ZenAllocationPortion.class,
            ZenSelectiveContext.class, ZenSelectiveModifier.class, ZenSelectiveCallback.class,
            ZenOverlayContext.class, ZenOverlayModifier.class, ZenOverlayCallback.class, ZenRequirementOverlay.class, IData.class, Harness.class))
            registry.registerNativeClass(type);
        // CT 718 caches primitive casting rules; every compiler fixture must include the same real data expansions.
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        var parsed = new ZenParsedFile(name, "AdvancedRecipeScript_" + name.replace('.', '_'),
            new ZenTokener(new StringReader(script), environment, name, false), global);
        ZenModule.compileScripts(name, List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        Objects.requireNonNull(new ZenModule(classes, ZenAdvancedRecipeBridgeTest.class.getClassLoader()).getMain()).run();
    }

    @Test
    void realSamUsesDetachedResourceAndFeFactsForActualSourcePaymentsAndRejectsDuplicateProposal() throws Exception {
        compileAndRun("""
            import mods.prototype_eng.recipe.InputAllocation;
            import mods.prototype_eng.recipe.AllocationPortion;
            import mods.prototype_eng.recipe.Requirement;
            import mods.prototype_eng.recipe.Recipe;
            import tests.prototype_eng.AdvancedHarness;
            var allocation = InputAllocation.create("advanced_bridge:allocation", function(context) {
                if (context.isEnergy) {
                    return [AllocationPortion.create(context.sources[1].index, context.amount)] as AllocationPortion[];
                }
                return [AllocationPortion.create(context.sources[0].index, context.sources[0].resources[0].amount - 2),
                        AllocationPortion.create(context.sources[1].index, context.amount - 7)] as AllocationPortion[];
            });
            var input = Requirement.resources("advanced_bridge:input", [AdvancedHarness.material(10)], [])
                .customInputAllocation(allocation);
            var energy = Requirement.energy("advanced_bridge:energy", 3, 0, 0, 0, false)
                .customInputAllocation(allocation);
            AdvancedHarness.captureRecipe(Recipe.create("advanced_bridge:recipe", "v1", 0, [input, energy]));
            var duplicate = InputAllocation.create("advanced_bridge:duplicate", function(context) {
                return [AllocationPortion.create(context.sources[0].index, 5),
                        AllocationPortion.create(context.sources[0].index, 5)] as AllocationPortion[];
            });
            AdvancedHarness.captureInvalid(Recipe.create("advanced_bridge:bad", "v1", 0,
                [Requirement.resources("advanced_bridge:input", [AdvancedHarness.material(10)], [])
                    .customInputAllocation(duplicate)]));
            """, "allocation.zs");
        var first = hatch(HatchMode.INPUT);
        var second = hatch(HatchMode.INPUT);
        var firstInput = Objects.requireNonNull(first.input());
        var secondInput = Objects.requireNonNull(second.input());
        firstInput.insert(MATERIAL, 9, Actionable.MODULATE);
        secondInput.insert(MATERIAL, 9, Actionable.MODULATE);
        var firstEnergy = new EnergyBuffer(100, () -> {
        });
        var secondEnergy = new EnergyBuffer(100, () -> {
        });
        firstEnergy.receiveEnergy(6, Actionable.MODULATE);
        secondEnergy.receiveEnergy(6, Actionable.MODULATE);
        var machine = owner(new MachineHatches(List.of(first, first, second), List.of(firstEnergy, secondEnergy), List.of()));
        assertSame(ProcessResult.SUCCESS, new RecipeProcess(machine, Harness.recipe.recipe(), 17).tick());
        assertEquals(2, firstInput.amount(MATERIAL));
        assertEquals(6, secondInput.amount(MATERIAL));
        assertEquals(6, firstEnergy.storedEnergy());
        assertEquals(3, secondEnergy.storedEnergy());
        firstInput.insert(MATERIAL, 7, Actionable.MODULATE);
        secondInput.insert(MATERIAL, 3, Actionable.MODULATE);
        var invalid = new RecipeProcess(machine, Harness.invalid.recipe(), 17);
        assertThrows(IllegalArgumentException.class, invalid::tick);
        assertEquals(9, firstInput.amount(MATERIAL));
        assertEquals(9, secondInput.amount(MATERIAL));
        assertEquals(9, firstInput.availableAmount(MATERIAL));
        assertEquals(9, secondInput.availableAmount(MATERIAL));
    }

    @Test
    void realScriptCombinesWeightedOutputsAttributeOperationsAndNullablePartialPatches() throws Exception {
        compileAndRun("""
            import mods.prototype_eng.recipe.Requirement;
            import mods.prototype_eng.recipe.Recipe;
            import mods.prototype_eng.recipe.SelectiveModifier;
            import mods.prototype_eng.recipe.OverlayModifier;
            import tests.prototype_eng.AdvancedHarness;
            var outputs = Requirement.resourceIO("advanced_bridge:outputs", [], [], [], [AdvancedHarness.product(99)], false)
                .randomOutputs([AdvancedHarness.product(99), AdvancedHarness.product(2), AdvancedHarness.product(3)], [0, 1, 2], 2)
                .chance(200.0, null);
            var energy = Requirement.energy("advanced_bridge:energy", 0, 2, 4, 5, false);
            var attribute = Requirement.attribute("advanced_bridge:attribute", "prototype_eng:process_parallelism",
                "base", 1.0, "MULTIPLY_BASE", true, true);
            var selection = Requirement.selective("advanced_bridge:selection",
                [Requirement.energy("advanced_bridge:optional", 0, 0, 0, 0, false)],
                [SelectiveModifier.create("advanced_bridge:select", function(context) {
                    context.putAttribute("prototype_eng:process_parallelism", "total", 1.0, "MULTIPLY_TOTAL");
                    context.putEnergyPatch(energy, "rates", 0, null, null);
                    context.putResourcePatch(outputs, "inherit", [], null, null, null, null);
                })]);
            AdvancedHarness.captureRecipe(Recipe.create("advanced_bridge:recipe", "advanced", 1,
                [attribute, selection, outputs, energy]));
            AdvancedHarness.captureOverlay(OverlayModifier.create("advanced_bridge:overlay", function(context) {
                context.putResourcePatch(outputs, "clear", null, null, null, [], null);
                context.putEnergyPatch(energy, "inherit", null, null, null);
            }));
            """, "weighted_partial.zs");
        var output = hatch(HatchMode.OUTPUT);
        var energy = new EnergyBuffer(100, () -> {
        });
        var process = new RecipeProcess(owner(new MachineHatches(List.of(output), List.of(), List.of(energy))), Harness.recipe.recipe(), 19);
        RecipeOverlayPipeline.apply(process, List.of(Harness.overlay.value()));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(40, Objects.requireNonNull(output.output()).amount(PRODUCT));
        assertEquals(36, energy.storedEnergy());
    }

    @Test
    void externalJavaRequirementAndTypedOverlayRetainAccurateCodecAfterOtherHandlesChange() throws Exception {
        var number = new RecipeRequirementType<Integer>(id("number"), new RecipeRequirementSystem<>() {
            @Override
            @SuppressWarnings("ReturnOfInnerClass")
            // Transaction retains exactly this process's data and prior value for compensation.
            public RequirementTransaction tick(RecipeProcess process, RecipeRequirement<Integer> requirement) {
                var data = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
                var before = data.get("number");
                int after = requirement.payload();
                return new RequirementTransaction() {
                    @Override
                    public ProcessResult result() {
                        return ProcessResult.SUCCESS;
                    }

                    @Override
                    public void commit() {
                        data.set("number", new NBTTagInt(after));
                    }

                    @Override
                    public void rollback() {
                        data.set("number", before);
                    }
                };
            }
        });
        var target = number.requirement(id("target"), 10);
        var add = new RecipeOverlayType<Integer, Integer>(id("external_overlay"), number, Math::addExact);
        var integerCodec = new NbtCodec<Integer>() {
            @Override
            public NBTTagCompound encode(Integer value) {
                var tag = new NBTTagCompound();
                tag.setInteger("value", value);
                return tag;
            }

            @Override
            public Integer decode(NBTTagCompound tag) {
                return tag.getInteger("value");
            }
        };
        Harness.external = new ZenRequirement(target);
        Harness.externalOverlay = new ZenRequirementOverlay(target, add, 5);
        var recipe = new MachineRecipe(id("external_recipe"), 2, List.of(target));
        var codec = RecipeProcessNbt.builder(recipe, "original")
                                    .component(DataComponents.PROCESS, DataComponents.PROCESS_CODEC).overlay(add, integerCodec)
                                    .validator(process -> {
                                        var data = Objects.requireNonNull(process.components().get(DataComponents.PROCESS));
                                        int expected = RecipeOverlays.getOrCreate(process).resolve(target).payload();
                                        assertEquals(expected, data.snapshot().data().getInteger("number"));
                                    }).build();
        Harness.javaRecipe = new ZenRecipe(codec);
        compileAndRun("""
            import mods.prototype_eng.recipe.Recipe;
            import mods.prototype_eng.recipe.OverlayModifier;
            import tests.prototype_eng.AdvancedHarness;
            var exactOverlay = AdvancedHarness.externalOverlay();
            var exactRequirement = AdvancedHarness.external();
            AdvancedHarness.captureInvalid(Recipe.create("advanced_bridge:script_external", "v1", 1, [exactRequirement]));
            AdvancedHarness.captureRecipe(AdvancedHarness.javaRecipe());
            AdvancedHarness.captureOverlay(OverlayModifier.create("advanced_bridge:old", function(context) {
                context.putOverlay(exactOverlay, "number");
            }));
            """, "external.zs");
        Harness.externalOverlay = new ZenRequirementOverlay(target,
            new RecipeOverlayType<>(add.id(), number, Math::multiplyExact), 10);
        Harness.javaRecipe = ZenRecipe.create("advanced_bridge:external_recipe", "new", 2, new ZenRequirement[]{});
        var machine = owner(new MachineHatches(List.of(), List.of(), List.of()));
        var scripted = new RecipeProcess(machine, Harness.invalid.recipe(), 23);
        scripted.components().create(DataComponents.PROCESS, scripted);
        assertSame(ProcessResult.SUCCESS, scripted.tick());
        assertEquals(10, Objects.requireNonNull(scripted.components().get(DataComponents.PROCESS)).snapshot().data().getInteger("number"));
        var process = new RecipeProcess(machine, Harness.recipe.recipe(), 23);
        process.components().create(DataComponents.PROCESS, process);
        RecipeOverlayPipeline.apply(process, List.of(Harness.overlay.value()));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(15, Objects.requireNonNull(process.components().get(DataComponents.PROCESS)).snapshot().data().getInteger("number"));
        var restored = Harness.recipe.codec().decode(machine, Harness.recipe.codec().encode(process));
        assertSame(recipe, restored.recipe());
        assertSame(ProcessResult.SUCCESS, restored.tick());
        assertEquals(15, Objects.requireNonNull(restored.components().get(DataComponents.PROCESS)).snapshot().data().getInteger("number"));
    }

    @SuppressWarnings("unused")
    @ZenClass("tests.prototype_eng.AdvancedHarness")
    public static final class Harness {
        private static ZenRecipe recipe;
        private static ZenRecipe invalid;
        private static ZenOverlayModifier overlay;
        private static ZenRequirement external;
        private static ZenRequirementOverlay externalOverlay;
        private static ZenRecipe javaRecipe;

        @ZenMethod
        public static ZenResource material(long amount) {
            return new ZenResource(MATERIAL, amount);
        }

        @ZenMethod
        public static ZenResource product(long amount) {
            return new ZenResource(PRODUCT, amount);
        }

        @ZenMethod
        public static void captureRecipe(ZenRecipe value) {
            recipe = value;
        }

        @ZenMethod
        public static void captureInvalid(ZenRecipe value) {
            invalid = value;
        }

        @ZenMethod
        public static void captureOverlay(ZenOverlayModifier value) {
            overlay = value;
        }

        @ZenMethod
        public static ZenRequirement external() {
            return external;
        }

        @ZenMethod
        public static ZenRequirementOverlay externalOverlay() {
            return externalOverlay;
        }

        @ZenMethod
        public static ZenRecipe javaRecipe() {
            return javaRecipe;
        }
    }
}
