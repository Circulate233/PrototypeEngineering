package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Stable panel splitting; no resource or explanation is discarded when a pool exceeds JEI's viewport.
 * Panel groups and candidate cycles are shared read-only for this layout's lifetime.
 */
public class RecipePanelLayout {
    private static final JeiRenderOptions EXPANDED = new JeiRenderOptions(true, JeiRenderOptions.CandidateMode.EXPANDED, 0);
    public static final int COLUMNS = 4;
    public static final int RESOURCE_CAPACITY = 16;
    /**
     * Retained for source compatibility; explanations no longer paginate.
     */
    @SuppressWarnings("unused")
    public static final int INFORMATION_CAPACITY = 2;
    private final List<JeiRecipeDisplay.Text> information;
    private final int pageCount;
    private final List<List<JeiRecipeDisplay.Resource>> inputGroups;
    private final List<List<JeiRecipeDisplay.Resource>> outputGroups;
    private final int inputCapacity;
    private final int outputCapacity;
    private final boolean repeatInput;

    public RecipePanelLayout(JeiRecipeDisplay display) {
        this(display, EXPANDED, null);
    }

    public RecipePanelLayout(JeiRecipeDisplay display, JeiRenderOptions options, @Nullable JeiRecipeIngredients ingredients) {
        this(display, options, ingredients, RESOURCE_CAPACITY, RESOURCE_CAPACITY, false);
    }

    public RecipePanelLayout(JeiRecipeDisplay display, JeiRenderOptions options, @Nullable JeiRecipeIngredients ingredients,
                             int inputCapacity, int outputCapacity, boolean repeatInput) {
        if (inputCapacity <= 0 || outputCapacity <= 0)
            throw new IllegalArgumentException("Positive panel capacities required");
        this.inputCapacity = inputCapacity;
        this.outputCapacity = outputCapacity;
        this.repeatInput = repeatInput;
        var inputRows = new ObjectArrayList<JeiRecipeDisplay.Resource>(6);
        var outputRows = new ObjectArrayList<JeiRecipeDisplay.Resource>(6);
        var elements = display.resources();
        for (int index2 = 0; index2 < elements.size(); index2++) {
            var resource = elements.get(index2);
            (resource.role() == JeiRecipeDisplay.Role.INPUT ? inputRows : outputRows).add(resource);
        }
        information = display.information();
        inputGroups = groups(inputRows, options, ingredients);
        outputGroups = groups(outputRows, options, ingredients);
        if (repeatInput && inputGroups.size() > inputCapacity)
            throw new IllegalArgumentException("Repeated inputs must fit one panel");
        pageCount = Math.max(1, Math.max(pages(inputGroups.size(), inputCapacity),
            pages(outputGroups.size(), outputCapacity)));
    }

    @SuppressWarnings("DataFlowIssue") // A retained candidate group was indexed only from a non-null group descriptor.
    private static List<List<JeiRecipeDisplay.Resource>> groups(List<JeiRecipeDisplay.Resource> rows,
                                                                JeiRenderOptions options, @Nullable JeiRecipeIngredients ingredients) {
        Reference2ObjectOpenHashMap<JeiRecipeDisplay.CandidateGroup, ObjectArrayList<JeiRecipeDisplay.Resource>> grouped = null;
        if (options.candidates() == JeiRenderOptions.CandidateMode.ALTERNATIVES && ingredients != null) {
            grouped = new Reference2ObjectOpenHashMap<>(rows.size());
            for (int index3 = 0; index3 < rows.size(); index3++) {
                var row = rows.get(index3);
                if (row.candidates() != null)
                    grouped.computeIfAbsent(row.candidates(), _ -> new ObjectArrayList<>()).add(row);
            }
            for (var entries = grouped.reference2ObjectEntrySet().fastIterator(); entries.hasNext(); )
                if (!ingredients.canCombine(entries.next().getValue())) entries.remove();
        }
        var result = new ObjectArrayList<List<JeiRecipeDisplay.Resource>>(rows.size());
        for (int index5 = 0; index5 < rows.size(); index5++) {
            var row = rows.get(index5);
            var group = row.candidates();
            var candidates = grouped == null ? null : grouped.get(group);
            if (candidates != null) {
                if (candidates.getFirst() == row) {
                    for (int pick = 0; pick < group.alternativeSlots(); pick++) result.add(candidates);
                }
            } else {
                result.add(List.of(row));
            }
        }
        return result;
    }

    private static int pages(int count, int capacity) {
        return count / capacity + (count % capacity == 0 ? 0 : 1);
    }

    /**
     * Safe pool-wide upper bound before exact converters are available during category registration.
     */
    @SuppressWarnings("unused") // Public helper retained for custom page layouts.
    public static int maximumSlots(JeiRecipeDisplay display, JeiRecipeDisplay.Role role, JeiRenderOptions options) {
        return maximumSlots(display, role, options, RESOURCE_CAPACITY);
    }

    public static int maximumSlots(JeiRecipeDisplay display, JeiRecipeDisplay.Role role, JeiRenderOptions options, int capacity) {
        long count = 0;
        var seen = new ReferenceOpenHashSet<JeiRecipeDisplay.CandidateGroup>();
        var elements4 = display.resources();
        for (int index4 = 0; index4 < elements4.size(); index4++) {
            var row = elements4.get(index4);
            if (row.role() != role) continue;
            count++;
            var group = row.candidates();
            if (options.candidates() == JeiRenderOptions.CandidateMode.ALTERNATIVES && group != null && seen.add(group)) {
                count += group.alternativeSlots() - 1L;
            }
        }
        return (int) Math.min(count, capacity);
    }

    private static List<JeiRecipeDisplay.Resource> flattened(List<List<JeiRecipeDisplay.Resource>> groups) {
        var result = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        for (int index = 0; index < groups.size(); index++) {
            var group = groups.get(index);
            result.addAll(group);
        }
        return result;
    }

    private static <T> List<T> panel(List<T> values, int page, int capacity) {
        long start = (long) page * capacity;
        if (start >= values.size()) {
            return ObjectLists.emptyList();
        }
        int from = (int) start;
        int to = (int) Math.min(values.size(), start + capacity);
        return values.subList(from, to);
    }

    public int pageCount() {
        return pageCount;
    }

    public JeiPatternRecipe patternRecipe(long durationTicks) {
        return JeiPatternRecipe.fromGroups(inputGroups, outputGroups, durationTicks);
    }

    public int inputSlots() {
        return Math.min(inputCapacity, inputGroups.size());
    }

    public int outputSlots() {
        return Math.min(outputCapacity, outputGroups.size());
    }

    public List<List<JeiRecipeDisplay.Resource>> inputGroups(int page) {
        checkPage(page);
        return panel(inputGroups, repeatInput ? 0 : page, inputCapacity);
    }

    public List<List<JeiRecipeDisplay.Resource>> outputGroups(int page) {
        checkPage(page);
        return panel(outputGroups, page, outputCapacity);
    }

    public List<JeiRecipeDisplay.Resource> inputs(int page) {
        return flattened(inputGroups(page));
    }

    public List<JeiRecipeDisplay.Resource> outputs(int page) {
        return flattened(outputGroups(page));
    }

    public List<JeiRecipeDisplay.Text> information(int page) {
        if (page < 0 || page >= pageCount) throw new IllegalArgumentException("Recipe panel out of range");
        return information;
    }

    private void checkPage(int page) {
        if (page < 0 || page >= pageCount) {
            throw new IllegalArgumentException("Recipe panel out of range");
        }
    }
}
