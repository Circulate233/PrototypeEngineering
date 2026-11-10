package com.circulation.prototype_eng.integration.jei;

import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.localization.JeiText;
import com.circulation.prototype_eng.testsupport.TestResources.Key;
import com.circulation.prototype_eng.testsupport.TestResources.KeyType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Objects;

import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role.INPUT;
import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Role.OUTPUT;
import static com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay.Text.of;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PatternRecipeTransferTest {
    private static final KeyType TYPE = new KeyType("pattern_transfer");

    private static GenericStack stack(String name, long amount) {
        return new GenericStack(new Key(TYPE, name), amount);
    }

    @Test
    void completePatternKeepsEveryPageAndOriginalLongQuantities() {
        var display = new JeiRecipeDisplay.Builder();
        for (int i = 0; i < 35; i++)
            display.resource(stack("input_" + i, i == 20 ? Long.MAX_VALUE : i + 1), INPUT, of(JeiText.Start));
        for (int i = 0; i < 19; i++) display.resource(stack("output_" + i, i + 2), OUTPUT, of(JeiText.End));
        display.scoped(of(JeiText.Tick)).energy(100, INPUT, of(JeiText.EnergyInput, 100));
        var source = new RecipePanelLayout(display.build()).patternRecipe(80);
        var inputs = Objects.requireNonNull(PatternRecipeTransfer.plan(source.inputs(), List::getFirst, true));
        var outputs = Objects.requireNonNull(PatternRecipeTransfer.plan(source.outputs(), List::getFirst, true));
        assertEquals(35, inputs.size());
        assertEquals(19, outputs.size());
        assertEquals(stack("input_20", Long.MAX_VALUE), inputs.get(20).getStack());
        assertEquals(stack("output_18", 20), outputs.getLast().getStack());
    }

    @Test
    void fuzzyInputIsOneSlotAndMergeKeepsOnlySharedCandidates() {
        var display = new JeiRecipeDisplay.Builder();
        var candidates = display.candidates(new JeiRecipeDisplay.CandidateGroup("input"));
        candidates.resource(stack("a", 4), INPUT, of(JeiText.Fuzzy, "input"));
        candidates.resource(stack("b", 4), INPUT, of(JeiText.Fuzzy, "input"));
        display.resource(stack("b", 7), INPUT, of(JeiText.Start));
        display.resource(stack("out", 1), OUTPUT, of(JeiText.End));
        var rows = display.build().resources();
        var source = JeiPatternRecipe.fromGroups(List.of(rows.subList(0, 2), List.of(rows.get(2))), List.of(List.of(rows.get(3))), 80);
        assertEquals(2, source.inputs().size());
        var merged = Objects.requireNonNull(PatternRecipeTransfer.plan(source.inputs(), List::getLast, true));
        assertEquals(1, merged.size());
        assertEquals(stack("b", 11), merged.getFirst().getStack());
        assertEquals(List.of(stack("b", 1).what()), merged.getFirst().getCandidates());
        assertEquals(2, Objects.requireNonNull(PatternRecipeTransfer.plan(source.inputs(), List::getLast, false)).size());
    }

    @Test
    void overflowIsRejectedBeforeAnyTerminalWrite() {
        var groups = List.of(List.of(stack("a", Long.MAX_VALUE)), List.of(stack("a", 1)));
        assertNull(PatternRecipeTransfer.plan(groups, List::getFirst, true));
        var separate = Objects.requireNonNull(PatternRecipeTransfer.plan(groups, List::getFirst, false));
        assertEquals(Long.MAX_VALUE, Objects.requireNonNull(separate.getFirst().getStack()).amount());
        assertEquals(1, Objects.requireNonNull(separate.getLast().getStack()).amount());
        var display = new JeiRecipeDisplay.Builder();
        display.resource(stack("input", Long.MAX_VALUE), INPUT, of(JeiText.Tick));
        display.resource(stack("output", 1), OUTPUT);
        assertThrows(ArithmeticException.class, () -> new RecipePanelLayout(display.build()).patternRecipe(2));
    }

    @Test
    void displayedCandidatesAndChanceImportUnchangedWhilePerTickQuantitiesUseRecipeTime() {
        for (var detail : List.of(JeiText.Tick, JeiText.Alternative, JeiText.Dynamic, JeiText.Weighted, JeiText.CheckpointScaled)) {
            var display = new JeiRecipeDisplay.Builder();
            display.resource(stack("input", 2), INPUT, of(detail));
            display.resource(stack("output", 1), OUTPUT);
            var source = new RecipePanelLayout(display.build()).patternRecipe(80);
            assertEquals(stack("input", detail == JeiText.Tick ? 160 : 2), source.inputs().getFirst().getFirst());
            assertEquals(stack("output", 1), source.outputs().getFirst().getFirst());
        }
        var chance = new JeiRecipeDisplay.Builder();
        chance.resource(stack("input", 2), INPUT);
        chance.chance(50, false).resource(stack("output", 3), OUTPUT, of(JeiText.Tick));
        var source = new RecipePanelLayout(chance.build()).patternRecipe(80);
        assertEquals(stack("input", 2), source.inputs().getFirst().getFirst());
        assertEquals(stack("output", 240), source.outputs().getFirst().getFirst());
    }
}
