package com.circulation.prototype_eng.api.research.disk;

import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadContext;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadEvent;
import com.circulation.prototype_eng.api.research.download.ResearchItemDownloadRegistry;
import it.unimi.dsi.fastutil.objects.Object2IntMaps;
import net.minecraft.item.ItemStack;


/**
 * Adapts a registered material's item-download event to one research-bearing disk output template.
 * The research's required tier must fit the disk. The encoding data center retains the ordinary
 * item-download research-tier requirement; only importing a disk requires a database at its tier.
 * No source level, inventory or database is changed by this callback. The output template is borrowed
 * read-only and must remain unchanged for the event registration lifetime.
 */
@SuppressWarnings("unused") // Addons bind this event to their own blank disk material and output item.
public final class ResearchDiskEncodingEvent implements ResearchItemDownloadEvent {
    private final ResearchRegistry registry;
    private final int diskTier;
    private final ItemStack outputTemplate;

    public ResearchDiskEncodingEvent(ResearchRegistry registry, int diskTier, ItemStack outputTemplate) {
        this.registry = registry;
        if (!registry.isFrozen()) {
            throw new IllegalStateException("Research disk event requires frozen research");
        }
        if (diskTier < 0) {
            throw new IllegalArgumentException("Research disk tier must be non-negative");
        }
        this.diskTier = diskTier;
        this.outputTemplate = outputTemplate;
    }

    /**
     * Bind a consumer-registered material and disk template in the existing item-download event.
     */
    @SuppressWarnings("unused") // Consumer registration through the existing Java item-download event.
    public static void register(ResearchItemDownloadRegistry downloads, ResearchRegistry research, ItemStack material,
                                ItemStack outputTemplate) {
        if (!(outputTemplate.getItem() instanceof ResearchDiskCarrier carrier)) {
            throw new IllegalArgumentException("Research disk template must expose its physical tier");
        }
        downloads.register(material,
            new ResearchDiskEncodingEvent(research, carrier.researchDiskTier(outputTemplate), outputTemplate));
    }

    @Override
    public ItemStack createOutput(ResearchItemDownloadContext context) {
        var definition = context.research();
        if (definition.researchTierForLevel(context.downloadLevel()) > diskTier) {
            return ItemStack.EMPTY;
        }
        var data = new ResearchDiskData(registry, diskTier, Object2IntMaps.singleton(definition.id(), context.downloadLevel()));
        return ResearchDiskNbtCodec.writeCopy(outputTemplate, data);
    }
}
