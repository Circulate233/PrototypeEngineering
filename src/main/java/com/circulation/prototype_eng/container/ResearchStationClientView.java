package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Updates rows by index and formats names and amounts only when their definitions or values change.
 */
public final class ResearchStationClientView {
    public @Nullable ResearchStationPacket.Definition definition;
    public List<ResearchStationPacket.QueueEntry> queue = List.of();
    public final ObjectArrayList<Material> materials = new ObjectArrayList<>();
    public final ObjectArrayList<Resource> resources = new ObjectArrayList<>();
    private String name;
    private final ObjectArrayList<String> queueNames = new ObjectArrayList<>();

    public void receive(ResearchStationPacket packet) {
        if (packet.reset()) {
            definition = packet.definition();
            name = null;
            materials.clear();
            resources.clear();
            if (definition != null) {
                for (var key : definition.materials()) materials.add(new Material(key));
                for (var resource : definition.resources())
                    resources.add(new Resource(resource.type(), resource.total()));
            }
        }
        if (packet.queue() != null) {
            queue = packet.queue();
            queueNames.clear();
        }
        for (var change : packet.materials()) {
            var row = materials.get(change.index());
            if ((change.fields() & ResearchStationPacket.FIRST) != 0) row.available = change.available();
            if ((change.fields() & ResearchStationPacket.SECOND) != 0) row.remaining = change.remaining();
            row.amounts = null;
        }
        for (var change : packet.resources()) {
            var row = resources.get(change.index());
            if ((change.fields() & ResearchStationPacket.FIRST) != 0) row.occupied = change.occupied();
            if ((change.fields() & ResearchStationPacket.SECOND) != 0) row.capacity = change.capacity();
            if ((change.fields() & ResearchStationPacket.THIRD) != 0) row.accumulated = change.accumulated();
            row.amounts = null;
            row.progress = null;
        }
    }

    public void invalidateText() {
        name = null;
        queueNames.clear();
        for (var row : materials) {
            row.name = null;
            row.amounts = null;
        }
        for (var row : resources) {
            row.name = null;
            row.amounts = null;
            row.progress = null;
        }
    }

    /**
     * Requires a non-null current research definition.
     */
    @SuppressWarnings("DataFlowIssue")
    @SideOnly(Side.CLIENT)
    public String name() {
        if (name == null) {
            LocalizationEnum localized = () -> definition.name();
            name = localized.getLocal();
        }
        return name;
    }

    @SideOnly(Side.CLIENT)
    public String queueName(int index) {
        if (queueNames.isEmpty()) for (var entry : queue) {
            LocalizationEnum localized = entry::name;
            queueNames.add(localized.getLocal());
        }
        return queueNames.get(index);
    }

    public static final class Material {
        public final AEKey key;
        public long available, remaining;
        private String name, amounts;

        private Material(AEKey key) {
            this.key = key;
        }

        @SideOnly(Side.CLIENT)
        public String name() {
            if (name == null) name = key.getDisplayName().getFormattedText();
            return name;
        }

        @SideOnly(Side.CLIENT)
        public String amounts() {
            if (amounts == null)
                amounts = key.formatAmount(available, AmountFormat.FULL) + " / " + key.formatAmount(remaining, AmountFormat.FULL);
            return amounts;
        }
    }

    public static final class Resource {
        public final NetworkResourceType type;
        public final double total;
        public double occupied, capacity, accumulated;
        private String name, amounts, progress;

        private Resource(NetworkResourceType type, double total) {
            this.type = type;
            this.total = total;
        }

        @SideOnly(Side.CLIENT)
        public String name() {
            if (name == null) name = type.displayName();
            return name;
        }

        @SideOnly(Side.CLIENT)
        public String amounts() {
            if (amounts == null) amounts = type.format(occupied) + " / " + type.format(capacity);
            return amounts;
        }

        @SideOnly(Side.CLIENT)
        public String progress() {
            if (progress == null) progress = type.format(accumulated) + " / " + type.format(total);
            return progress;
        }
    }
}
