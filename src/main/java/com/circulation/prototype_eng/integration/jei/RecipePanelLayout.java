package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.client.jei.JeiRecipeDisplay;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;

import java.util.List;

/**
 * Stable panel splitting; no resource or explanation is discarded when a pool exceeds JEI's viewport.
 */
public class RecipePanelLayout {
    public static final int COLUMNS = 4;
    public static final int RESOURCE_CAPACITY = 16;
    public static final int INFORMATION_CAPACITY = 4;
    private final List<JeiRecipeDisplay.Resource> inputs;
    private final List<JeiRecipeDisplay.Resource> outputs;
    private final List<JeiRecipeDisplay.Text> information;
    private final int pageCount;

    public RecipePanelLayout(JeiRecipeDisplay display) {
        var inputRows = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        var outputRows = new ObjectArrayList<JeiRecipeDisplay.Resource>();
        for (var resource : display.resources()) {
            (resource.role() == JeiRecipeDisplay.Role.INPUT ? inputRows : outputRows).add(resource);
        }
        inputs = ObjectLists.unmodifiable(inputRows);
        outputs = ObjectLists.unmodifiable(outputRows);
        information = display.information();
        pageCount = Math.max(1, Math.max(pages(inputs.size(), RESOURCE_CAPACITY),
            Math.max(pages(outputs.size(), RESOURCE_CAPACITY), pages(information.size(), INFORMATION_CAPACITY))));
    }

    private static int pages(int count, int capacity) {
        return count / capacity + (count % capacity == 0 ? 0 : 1);
    }

    public int pageCount() {
        return pageCount;
    }

    public int inputSlots() {
        return Math.min(RESOURCE_CAPACITY, inputs.size());
    }

    public int outputSlots() {
        return Math.min(RESOURCE_CAPACITY, outputs.size());
    }

    public List<JeiRecipeDisplay.Resource> inputs(int page) {
        return panel(inputs, page, RESOURCE_CAPACITY);
    }

    public List<JeiRecipeDisplay.Resource> outputs(int page) {
        return panel(outputs, page, RESOURCE_CAPACITY);
    }

    public List<JeiRecipeDisplay.Text> information(int page) {
        return panel(information, page, INFORMATION_CAPACITY);
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
