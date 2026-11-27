package com.circulation.prototype_eng.recipe;

import ae2.api.config.Actionable;
import ae2.api.stacks.AEKeyType;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.RecipeJeiDisplays;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.recipe.RecipeBuilder;
import com.circulation.prototype_eng.api.recipe.matching.NamedResourceMatcher;
import com.circulation.prototype_eng.api.recipe.probability.ResourceChance;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.DynamicInputGroup;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.recipe.requirement.ResourcePresenceRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.ResourceRequirement;
import com.circulation.prototype_eng.api.storage.HatchMode;
import com.circulation.prototype_eng.localization.JeiText;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourcePresenceRequirementTest {
    private static final KeyType TYPE = new KeyType("resource_presence");
    private static final Key CATALYST = new Key(TYPE, "catalyst");
    private static final Key MATERIAL = new Key(TYPE, "material");
    private static final Key OUTPUT = new Key(TYPE, "output");

    @Test
    void probesAndExecutionRecheckSharedCatalystsWithoutConsumingOrMultiplyingThem() {
        var input = hatch(HatchMode.INPUT);
        var output = hatch(HatchMode.OUTPUT);
        var stock = Objects.requireNonNull(input.input());
        var product = Objects.requireNonNull(output.output());
        var machine = machine(input, output);
        var recipe = new RecipeBuilder(id("recipe"), 1)
            .addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(id("presence"),
                new ResourcePresenceRequirement(List.of(new GenericStack(CATALYST, 1)))))
            .addInput(id("materials"), new GenericStack(MATERIAL, 1))
            .addOutput(id("materials"), new GenericStack(OUTPUT, 1)).build();
        stock.insert(CATALYST, 1, Actionable.MODULATE);
        stock.insert(MATERIAL, 3, Actionable.MODULATE);
        var process = new RecipeProcess(machine, recipe, 17);
        process.attributes().setResolvedBase(StandardMachineAttributes.PROCESS_PARALLELISM, 3);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertEquals(1, stock.availableAmount(CATALYST));
        assertEquals(3, stock.availableAmount(MATERIAL));
        stock.extract(CATALYST, 1, Actionable.MODULATE);
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(3, stock.availableAmount(MATERIAL));
        stock.insert(CATALYST, 1, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, stock.availableAmount(CATALYST));
        assertEquals(0, stock.availableAmount(MATERIAL));
        assertEquals(3, product.amount(OUTPUT));
    }

    @Test
    void failedMultiResourceChecksReleaseAllTemporaryReservations() {
        var input = hatch(HatchMode.INPUT);
        var stock = Objects.requireNonNull(input.input());
        stock.insert(CATALYST, 7, Actionable.MODULATE);
        var recipe = new RecipeBuilder(id("missing"), 1)
            .addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(id("presence"),
                new ResourcePresenceRequirement(List.of(new GenericStack(CATALYST, 7), new GenericStack(MATERIAL, 2))))).build();
        var process = new RecipeProcess(machine(input), recipe, 19);
        assertInstanceOf(ProcessResult.Blocked.class, RecipeStageExecutor.probeStart(process));
        assertEquals(7, stock.availableAmount(CATALYST));
        assertInstanceOf(ProcessResult.Blocked.class, process.tick());
        assertEquals(7, stock.availableAmount(CATALYST));
        stock.insert(MATERIAL, 2, Actionable.MODULATE);
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(7, stock.availableAmount(CATALYST));
        assertEquals(2, stock.availableAmount(MATERIAL));
    }

    @Test
    void dynamicCatalystsMatchRealInventoryAndDisplayTheirNonconsumingCondition() {
        var input = hatch(HatchMode.INPUT);
        var stock = Objects.requireNonNull(input.input());
        stock.insert(CATALYST, 1, Actionable.MODULATE);
        var matcher = new NamedResourceMatcher(id("matcher"), (candidate, _) -> candidate.equals(CATALYST));
        var dynamic = new DynamicInputGroup(matcher, MATERIAL, 1, List.of(MATERIAL), 8);
        var payload = new ResourceRequirement(List.of(), List.of(), List.of(), List.of(), false,
            ResourceChance.ALWAYS, null, List.of(), List.of(dynamic));
        var recipe = new RecipeBuilder(id("dynamic"), 1)
            .addRequirement(RecipeRequirements.RESOURCE_PRESENCE.requirement(id("presence"),
                new ResourcePresenceRequirement(payload))).build();
        var process = new RecipeProcess(machine(input), recipe, 23);
        assertSame(ProcessResult.SUCCESS, RecipeStageExecutor.probeStart(process));
        assertSame(ProcessResult.SUCCESS, process.tick());
        assertEquals(1, stock.availableAmount(CATALYST));
        var displays = new RecipeJeiDisplays();
        displays.freeze();
        var display = displays.describe(recipe);
        assertTrue(display.resources().stream().flatMap(row -> row.details().stream())
            .anyMatch(text -> text.text() == JeiText.NotConsumed));
    }

    private static MachineInstance machine(BufferedResourceHatch... hatches) {
        var machine = new MachineInstance();
        machine.components().put(MachineHatches.TYPE, new MachineHatches(List.of(hatches), List.of(), List.of()));
        return machine;
    }

    private static BufferedResourceHatch hatch(HatchMode mode) {
        var capacities = new Reference2LongArrayMap<AEKeyType>();
        capacities.put(TYPE, 10_000);
        return new BufferedResourceHatch(mode, 3, capacities, () -> {});
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("presence_test", path);
    }
}
