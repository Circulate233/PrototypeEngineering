package com.circulation.prototype_eng.integration.jei;

import com.circulation.prototype_eng.api.recipe.RecipePool;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectSets;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Client registration bridge used once after every HEI plugin has supplied its categories.
 */
public final class ExternalRecipePoolPages {
    private static @Nullable Pending pending;

    private ExternalRecipePoolPages() {
    }

    public static boolean requested(List<RecipePool> pools) {
        for (int index = 0; index < pools.size(); index++) {
            var pool = pools.get(index);
            var id = pool.existingJeiPageId();
            if (pool.hasExistingJeiPage() && id != null && !id.isBlank()) return true;
        }
        return false;
    }

    public static void reset() {
        pending = null;
    }

    public static void defer(Object registry, Consumer<Set<String>> registration) {
        if (pending != null) throw new IllegalStateException("Recipe pool registration is already pending");
        pending = new Pending(Objects.requireNonNull(registry), Objects.requireNonNull(registration));
    }

    public static void finish(Object registry, Set<String> categories) {
        var work = pending;
        if (work == null || work.registry != registry) return;
        pending = null;
        work.registration.accept(ObjectSets.unmodifiable(new ObjectOpenHashSet<>(categories)));
    }

    private record Pending(Object registry, Consumer<Set<String>> registration) {
    }
}
