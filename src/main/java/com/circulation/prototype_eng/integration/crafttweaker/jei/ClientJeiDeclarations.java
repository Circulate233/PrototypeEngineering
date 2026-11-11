package com.circulation.prototype_eng.integration.crafttweaker.jei;

import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/** Server-safe startup declarations; retained snapshots replay only the client category phase. */
public final class ClientJeiDeclarations {
    private static final Object2ObjectLinkedOpenHashMap<ResourceLocation, Binding> PENDING = new Object2ObjectLinkedOpenHashMap<>();
    @Nullable private static List<Binding> frozen;
    @Nullable private static ZenJeiOptions defaults;

    private ClientJeiDeclarations() {}

    static synchronized void defaults(ZenJeiOptions options) {
        requireOpen();
        defaults = Objects.requireNonNull(options);
    }

    public static synchronized void declare(ResourceLocation pool, @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page) {
        if (options == null && page == null) return;
        requireOpen();
        var binding = new Binding(Objects.requireNonNull(pool), options, page);
        var previous = PENDING.putIfAbsent(pool, binding);
        if (previous != null && !previous.equals(binding)) throw new IllegalArgumentException("Conflicting client page declaration: " + pool);
    }

    static synchronized List<Binding> freeze() {
        if (frozen == null) {
            frozen = ObjectLists.unmodifiable(new ObjectArrayList<>(PENDING.values()));
            PENDING.clear();
        }
        return frozen;
    }

    static synchronized @Nullable ZenJeiOptions defaults() { return defaults; }

    private static void requireOpen() {
        if (frozen != null) throw new IllegalStateException("Client page declarations have closed");
    }

    record Binding(ResourceLocation pool, @Nullable ZenJeiOptions options, @Nullable ZenJeiPage page) {}
}
