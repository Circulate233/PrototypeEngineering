package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadContext;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadEvent;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.item.ItemStack;

import java.util.Objects;

/**
 * Adapts a registered material's item-download event to one research-bearing disk output template.
 * The research's required tier must fit the disk. The encoding data center retains the ordinary
 * item-download research-tier requirement; only importing a disk requires a database at its tier.
 * No source level, inventory or database is changed by this callback.
 */
@SuppressWarnings("unused") // Addons bind this event to their own blank disk material and output item.
public final class ResearchDiskEncodingEvent implements ResearchItemDownloadEvent {
    private final ResearchRegistry registry;
    private final int diskTier;
    private final ItemStack outputTemplate;

    public ResearchDiskEncodingEvent(ResearchRegistry registry, int diskTier, ItemStack outputTemplate) {
        this.registry = Objects.requireNonNull(registry, "Research registry");
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research disk event requires frozen research");
        }
        if (diskTier < 0) {
            throw new IllegalArgumentException("Research disk tier must be non-negative");
        }
        this.diskTier = diskTier;
        this.outputTemplate = ResearchDiskNbtCodec.copyStack(outputTemplate);
    }

    @Override
    public ItemStack createOutput(ResearchItemDownloadContext context) {
        Objects.requireNonNull(context, "Research download context");
        var definition = context.research();
        if (registry.require(definition.id()) != definition) {
            throw new IllegalArgumentException("Research disk event requires its canonical registered research");
        }
        if (definition.researchTierForLevel(context.downloadLevel()) > diskTier) {
            return ItemStack.EMPTY;
        }
        var data = new ResearchDiskData(registry, diskTier, Object2IntMaps.singleton(definition.id(), context.downloadLevel()));
        return ResearchDiskNbtCodec.writeCopy(outputTemplate, data);
    }
}
