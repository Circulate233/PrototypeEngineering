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
     * Full item, metadata and NBT identity. Keys are immutable and retained directly.
     */
    public void register(AEItemKey material, ResearchItemDownloadEvent event) {
        requireLoading();
        registerKey(material, event);
    }

    private void registerKey(AEItemKey key, ResearchItemDownloadEvent event) {
        if (registrations.putIfAbsent(key, Objects.requireNonNull(event, "Download event")) != null) {
            throw new IllegalArgumentException("Duplicate research item download material");
        }
    }

    /**
     * Stack count does not form part of registration identity.
     */
    public void register(ItemStack material, ResearchItemDownloadEvent event) {
        requireLoading();
        var key = AEItemKey.of(material);
        if (key == null) throw new IllegalArgumentException("Download material must be nonempty");
        registerKey(key, event);
    }

    public boolean isFrozen() {
        return frozen != null;
    }

    public void freeze(ResearchRegistry research) {
        requireLoading();
        if (!Objects.requireNonNull(research, "Research registry").isFrozen()) {
            throw new IllegalStateException("Research must freeze before item download events");
        }
        frozen = new Frozen(registrations);
    }

    /**
     * Invokes one matching callback on the server owner thread; failures propagate.
     */
    public ResearchItemDownloadEvaluation evaluate(ResearchItemDownloadContext context) {
        var current = requireFrozen();
        var event = current.events.get(context.materialKey());
        if (event == null) {
            return ResearchItemDownloadEvaluation.noEvent();
        }
        var output = Objects.requireNonNull(event.createOutput(context), "Download event output");
        if (output.isEmpty()) {
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

    private record Frozen(Object2ObjectOpenHashMap<AEItemKey, ResearchItemDownloadEvent> events) {
    }
}
