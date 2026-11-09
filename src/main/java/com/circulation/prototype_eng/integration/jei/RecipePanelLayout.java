package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import java.util.List;
import com.circulation.prototype_eng.api.client.jei.JeiRecipeIngredients;
import com.circulation.prototype_eng.api.client.jei.JeiRenderOptions;
import it.unimi.dsi.fastutil.objects.Reference2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ReferenceOpenHashSet;
import org.jetbrains.annotations.Nullable;
import com.circulation.prototype_eng.api.client.jei.JeiPatternRecipe;
/**
 * Stable panel splitting; no resource or explanation is discarded when a pool exceeds JEI's viewport.
 */
public class RecipePanelLayout {
    public static final int COLUMNS = 4;
    public static final int RESOURCE_CAPACITY = 16;
    public static final int INFORMATION_CAPACITY = 2;
    private final List<JeiRecipeDisplay.Text> information;
    private final int pageCount;
    private final List<List<JeiRecipeDisplay.Resource>> inputGroups;
    private final List<List<JeiRecipeDisplay.Resource>> outputGroups;

    public RecipePanelLayout(JeiRecipeDisplay display) {
        this(display, new JeiRenderOptions(true, JeiRenderOptions.CandidateMode.EXPANDED, 0), null);
        }

    public RecipePanelLayout(JeiRecipeDisplay display, JeiRenderOptions options, @Nullable JeiRecipeIngredients ingredients) {
        var inputRows = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        var outputRows = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        for (var resource : display.resources()) {
            (resource.role() == JeiRecipeDisplay.Role.INPUT ? inputRows : outputRows).add(resource);
        }
        information = display.information();
        inputGroups = groups(inputRows, options, ingredients);
        outputGroups = groups(outputRows, options, ingredients);
        pageCount = Math.max(1, Math.max(pages(inputGroups.size(), RESOURCE_CAPACITY),
            Math.max(pages(outputGroups.size(), RESOURCE_CAPACITY), pages(information.size(), INFORMATION_CAPACITY))));
    }

    private static List<List<JeiRecipeDisplay.Resource>> groups(List<JeiRecipeDisplay.Resource> rows,
                                                               JeiRenderOptions options, @Nullable JeiRecipeIngredients ingredients) {
        var grouped = new Reference2ObjectOpenHashMap<JeiRecipeDisplay.CandidateGroup, ObjectArrayList<JeiRecipeDisplay.Resource>>();
        if (options.candidates() == JeiRenderOptions.CandidateMode.ALTERNATIVES && ingredients != null) {
            for (var row : rows) {
                if (row.candidates() != null) grouped.computeIfAbsent(row.candidates(), _ -> new ObjectArrayList<>()).add(row);
            }
        }
        var result = new ObjectArrayList<List<JeiRecipeDisplay.Resource>>();
        for (var row : rows) {
            var group = row.candidates();
            var candidates = grouped.get(group);
            if (group != null && candidates != null && ingredients != null && ingredients.canCombine(candidates)) {
                if (candidates.getFirst() == row) {
                    var cycle = ObjectLists.unmodifiable(candidates);
                    for (int pick = 0; pick < group.alternativeSlots(); pick++) result.add(cycle);
                }
            } else {
                result.add(ObjectArrayList.of(row));
            }
        }
        return ObjectLists.unmodifiable(result);
    }

    private static int pages(int count, int capacity) {
        return count / capacity + (count % capacity == 0 ? 0 : 1);
    }

    /** Safe pool-wide upper bound before exact converters are available during category registration. */
    public static int maximumSlots(JeiRecipeDisplay display, JeiRecipeDisplay.Role role, JeiRenderOptions options) {
        long count = 0;
        var seen = new ReferenceOpenHashSet<JeiRecipeDisplay.CandidateGroup>();
        for (var row : display.resources()) {
            if (row.role() != role) continue;
            count++;
            var group = row.candidates();
            if (options.candidates() == JeiRenderOptions.CandidateMode.ALTERNATIVES && group != null && seen.add(group)) {
                count += group.alternativeSlots() - 1L;
            }
        }
        return (int) Math.min(count, RESOURCE_CAPACITY);
    }

    public int pageCount() {
        return pageCount;
    }

    public JeiPatternRecipe patternRecipe(long durationTicks) {
        return JeiPatternRecipe.fromGroups(inputGroups, outputGroups, durationTicks);
    }

    public int inputSlots() {
        return Math.min(RESOURCE_CAPACITY, inputGroups.size());
    }

    public int outputSlots() {
        return Math.min(RESOURCE_CAPACITY, outputGroups.size());
    }

    public List<List<JeiRecipeDisplay.Resource>> inputGroups(int page) { return panel(inputGroups, page, RESOURCE_CAPACITY); }
    public List<List<JeiRecipeDisplay.Resource>> outputGroups(int page) { return panel(outputGroups, page, RESOURCE_CAPACITY); }

    public List<JeiRecipeDisplay.Resource> inputs(int page) {
        return flattened(inputGroups(page));
    }

    public List<JeiRecipeDisplay.Resource> outputs(int page) {
        return flattened(outputGroups(page));
    }

    public List<JeiRecipeDisplay.Text> information(int page) {
        return panel(information, page, INFORMATION_CAPACITY);
    }

    private static List<JeiRecipeDisplay.Resource> flattened(List<List<JeiRecipeDisplay.Resource>> groups) {
        var result = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        for (var group : groups) result.addAll(group);
        return ObjectLists.unmodifiable(result);
    }

    private <T> List<T> panel(List<T> values, int page, int capacity) {
        if (page < 0 || page >= pageCount) {
            throw new IllegalArgumentException("Recipe panel out of range");
        }
        long start = (long) page * capacity;
        if (start >= values.size()) {
            return ObjectLists.emptyList();
        }
        int from = (int) start;
        int to = (int) Math.min(values.size(), start + capacity);
        return values.subList(from, to);
    }
}
