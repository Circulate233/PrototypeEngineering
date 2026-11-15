package com.circulation.prototype_eng.api.client.structure;

import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.function.BiFunction;

/**
 * Public renderer/GUI cache lifecycle; no preview value can outlive its authoritative catalog view.
 */
public final class StructurePreviewCache<T> implements AutoCloseable {
    private final ClientStructureCatalog catalog;
    private final Object2ObjectLinkedOpenHashMap<Key, T> values = new Object2ObjectLinkedOpenHashMap<>();
    private final int maxEntries;
    private final AutoCloseable subscription;
    @Nullable
    private StructureCatalogView generation;
    private boolean closed;

    public StructurePreviewCache(ClientStructureCatalog catalog) {
        this(catalog, Integer.MAX_VALUE);
    }

    public StructurePreviewCache(ClientStructureCatalog catalog, int maxEntries) {
        this.catalog = catalog;
        if (maxEntries <= 0) throw new IllegalArgumentException("Nonpositive preview cache capacity");
        this.maxEntries = maxEntries;
        subscription = catalog.onInvalidated(this::clear);
    }

    public @Nullable T get(ResourceLocation id, Object settings, BiFunction<StructureCatalogView, StructureCatalogView.Entry, T> build) {
        var view = catalog.view();
        if (closed) throw new IllegalStateException("Structure preview cache is closed");
        if (view != generation) {
            clear();
            generation = view;
        }
        if (view == null || !view.entries().containsKey(id)) return null;
        var key = new Key(id, settings);
        var existing = values.getAndMoveToLast(key);
        if (existing != null) return existing;
        var result = build.apply(view, view.entries().get(id));
        if (!catalog.current(view))
            throw new IllegalStateException("Structure catalog changed while building a preview");
        values.putAndMoveToLast(key, result);
        if (values.size() > maxEntries) values.removeFirst();
        return result;
    }

    public void clear() {
        catalog.view();
        values.clear();
        generation = null;
    }

    @Override
    public void close() {
        if (closed) return;
        clear();
        closed = true;
        try {
            subscription.close();
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot release preview invalidation subscription", failure);
        }
    }

    private record Key(ResourceLocation id, Object settings) {
    }
}
