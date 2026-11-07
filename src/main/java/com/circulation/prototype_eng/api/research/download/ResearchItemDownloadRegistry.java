package com.circulation.prototype_eng.api.research.download;

import ae2.api.stacks.AEItemKey;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.item.ItemStack;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * Loading-thread exact material registration, atomically frozen before runtime download evaluation.
 */
@SuppressWarnings("unused") // Addons register keys; server consumers evaluate the frozen registry.
public final class ResearchItemDownloadRegistry {
    public static final ResearchItemDownloadRegistry INSTANCE = new ResearchItemDownloadRegistry();
    private final Object2ObjectOpenHashMap<AEItemKey, ResearchItemDownloadEvent> registrations = new Object2ObjectOpenHashMap<>();
    @Nullable
    private volatile Frozen frozen;

    /**
     * Full item, metadata and NBT identity. The supplied key is copied to isolate mutable AE key views.
     */
    public void register(AEItemKey material, ResearchItemDownloadEvent event) {
        requireLoading();
        var key = DownloadValidation.key(Objects.requireNonNull(material, "Download material key").toStack());
        if (registrations.putIfAbsent(key, Objects.requireNonNull(event, "Download event")) != null) {
            throw new IllegalArgumentException("Duplicate research item download material");
        }
    }

    /**
     * Stack count does not form part of registration identity.
     */
    public void register(ItemStack material, ResearchItemDownloadEvent event) {
        requireLoading();
        register(DownloadValidation.key(material), event);
    }

    public boolean isFrozen() {
        return frozen != null;
    }

    public void freeze(ResearchRegistry research) {
        requireLoading();
        if (!Objects.requireNonNull(research, "Research registry").isFrozen()) {
            throw new IllegalStateException("Research must freeze before item download events");
        }
        var events = new Object2ObjectOpenHashMap<AEItemKey, ResearchItemDownloadEvent>();
        for (var entry : registrations.object2ObjectEntrySet()) {
            events.put(DownloadValidation.key(entry.getKey().toStack()), entry.getValue());
        }
        frozen = new Frozen(research, events);
        registrations.clear();
    }

    /**
     * Revalidates real capabilities before invoking one matching callback; failures propagate.
     */
    public ResearchItemDownloadEvaluation evaluate(ResearchItemDownloadContext context) {
        var current = requireFrozen();
        Objects.requireNonNull(context, "Download context").validateFor(current.research);
        var event = current.events.get(DownloadValidation.key(context.material()));
        if (event == null) {
            return ResearchItemDownloadEvaluation.noEvent();
        }
        var output = Objects.requireNonNull(event.createOutput(context), "Download event output");
        if (output == ItemStack.EMPTY) {
            return ResearchItemDownloadEvaluation.rejected();
        }
        return ResearchItemDownloadEvaluation.success(output);
    }

    private void requireLoading() {
        if (frozen != null) {
            throw new IllegalStateException("Research item download registration has ended");
        }
    }

    private Frozen requireFrozen() {
        var current = frozen;
        if (current == null) {
            throw new IllegalStateException("Research item download registry is not ready");
        }
        return current;
    }

    private record Frozen(ResearchRegistry research,
                          Object2ObjectOpenHashMap<AEItemKey, ResearchItemDownloadEvent> events) {
    }
}
