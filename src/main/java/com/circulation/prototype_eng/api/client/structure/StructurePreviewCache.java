package com.circulation.prototype_eng.api.client.structure;

import com.circulation.prototype_eng.api.structure.StructureCatalogView;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;
import java.util.Objects;
import java.util.function.BiFunction;

/** Public renderer/GUI cache lifecycle; no preview value can outlive its authoritative catalog view. */
public final class StructurePreviewCache<T> implements AutoCloseable {
    private final ClientStructureCatalog catalog;
    private final Object2ObjectOpenHashMap<Key, T> values = new Object2ObjectOpenHashMap<>();
    private final AutoCloseable subscription;
    @Nullable private StructureCatalogView generation;
    private boolean closed;
    public StructurePreviewCache(ClientStructureCatalog catalog) {
        this.catalog = Objects.requireNonNull(catalog);
        subscription = catalog.onInvalidated(this::clear);
    }
    public @Nullable T get(ResourceLocation id, Object settings, BiFunction<StructureCatalogView, StructureCatalogView.Entry, T> build) {
        var view = catalog.view();
        if (closed) throw new IllegalStateException("Structure preview cache is closed");
        if (view != generation) { clear(); generation = view; }
        if (view == null || !view.entries().containsKey(id)) return null;
        var key = new Key(Objects.requireNonNull(id), Objects.requireNonNull(settings));
        var existing = values.get(key); if (existing != null) return existing;
        var result = Objects.requireNonNull(build.apply(view, view.entries().get(id)));
        if (!catalog.current(view)) throw new IllegalStateException("Structure catalog changed while building a preview");
        values.put(key, result); return result;
    }
    public void clear() { catalog.view(); values.clear(); generation = null; }
    @Override public void close() {
        if (closed) return; clear(); closed = true;
        try { subscription.close(); } catch (Exception failure) { throw new IllegalStateException("Cannot release preview invalidation subscription", failure); }
    }
    private record Key(ResourceLocation id, Object settings) {}
}
