package com.circulation.prototype_eng.container;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AmountFormat;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.client.research.ClientResearchContents;
import com.circulation.prototype_eng.localization.ResearchStationGuiText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;
import org.jetbrains.annotations.Nullable;

import java.text.DecimalFormat;
import java.util.List;

/**
 * Updates rows by index and formats names and amounts only when their definitions or values change.
 */
public final class ResearchStationClientView {
    private static final DecimalFormat PERCENT_FORMAT = new DecimalFormat("0.#'%'");
    public @Nullable ResearchStationPacket.Definition definition;
    public List<ResearchStationPacket.QueueEntry> queue = List.of();
    public final ObjectArrayList<Material> materials = new ObjectArrayList<>();
    public final ObjectArrayList<Resource> resources = new ObjectArrayList<>();
    private String name;
    private final ObjectArrayList<String> queueNames = new ObjectArrayList<>();
    private final ObjectArrayList<String> queueLevels = new ObjectArrayList<>();

    public void receive(ResearchStationPacket packet) {
        if (packet.reset()) {
            definition = packet.definition();
            name = null;
            materials.clear();
            resources.clear();
            if (definition != null) {
                var elements = definition.materials();
                for (int index4 = 0; index4 < elements.size(); index4++) {
                    var key = elements.get(index4);
                    materials.add(new Material(key));
                }
                var elements5 = definition.resources();
                for (int index5 = 0; index5 < elements5.size(); index5++) {
                    var resource = elements5.get(index5);
                    resources.add(new Resource(resource.type(), resource.total()));
                }
            }
        }
        if (packet.queue() != null) {
            queue = packet.queue();
            queueNames.clear();
            queueLevels.clear();
        }
        var elements6 = packet.materials();
        for (int index6 = 0; index6 < elements6.size(); index6++) {
            var change = elements6.get(index6);
            var row = materials.get(change.index());
            if ((change.fields() & ResearchStationPacket.FIRST) != 0) row.available = change.available();
            if ((change.fields() & ResearchStationPacket.SECOND) != 0) row.remaining = change.remaining();
            row.amounts = null;
            row.slotAmounts = null;
        }
        var elements7 = packet.resources();
        for (int index7 = 0; index7 < elements7.size(); index7++) {
            var change = elements7.get(index7);
            var row = resources.get(change.index());
            if ((change.fields() & ResearchStationPacket.FIRST) != 0) row.occupied = change.occupied();
            if ((change.fields() & ResearchStationPacket.SECOND) != 0) row.capacity = change.capacity();
            if ((change.fields() & ResearchStationPacket.THIRD) != 0) {
                row.accumulated = change.accumulated();
                row.progress = null;
                row.percentage = null;
            }
            if ((change.fields() & (ResearchStationPacket.FIRST | ResearchStationPacket.SECOND)) != 0)
                row.amounts = null;
        }
    }

    public void invalidateText() {
        name = null;
        queueNames.clear();
        queueLevels.clear();
        for (int index1 = 0; index1 < materials.size(); index1++) {
            var row = materials.get(index1);
            row.name = null;
            row.amounts = null;
            row.slotAmounts = null;
        }
        for (int index2 = 0; index2 < resources.size(); index2++) {
            var row = resources.get(index2);
            row.name = null;
            row.amounts = null;
            row.progress = null;
            row.percentage = null;
        }
    }

    /**
     * Requires a non-null current research definition.
     */
    @SuppressWarnings("DataFlowIssue")
    @SideOnly(Side.CLIENT)
    public String name() {
        if (name == null) {
            name = ClientResearchContents.name(definition.id());
        }
        return name;
    }

    @SideOnly(Side.CLIENT)
    public String queueName(int index) {
        if (queueNames.isEmpty()) for (int index3 = 0; index3 < queue.size(); index3++) {
            var entry = queue.get(index3);
            queueNames.add(ClientResearchContents.name(entry.id()));
            queueLevels.add((index3 == 0 ? ResearchStationGuiText.InProgress.getLocal() + " · " : "") + "Lv." + entry.level());
        }
        return queueNames.get(index);
    }

    @SideOnly(Side.CLIENT)
    public String queueLevel(int index) {
        if (queueNames.isEmpty()) queueName(index);
        return queueLevels.get(index);
    }

    public static final class Material {
        public final AEKey key;
        public long available, remaining;
        private String name, amounts, slotAmounts;

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

        @SideOnly(Side.CLIENT)
        public String slotAmounts() {
            if (slotAmounts == null)
                slotAmounts = key.formatAmount(available, AmountFormat.SLOT) + "/" + key.formatAmount(remaining, AmountFormat.SLOT);
            return slotAmounts;
        }
    }

    public static final class Resource {
        public final NetworkResourceType type;
        public final double total;
        public double occupied, capacity, accumulated;
        private String name, amounts, progress, percentage;

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

        @SideOnly(Side.CLIENT)
        public String percentage() {
            if (percentage == null) percentage = PERCENT_FORMAT.format(100 * accumulated / total);
            return percentage;
        }
    }
}
