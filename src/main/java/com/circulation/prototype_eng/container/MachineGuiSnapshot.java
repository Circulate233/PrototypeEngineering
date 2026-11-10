package com.circulation.prototype_eng.container;

import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.network.PacketBuffer;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded display data. Capture only after scheduler join; never retain runtime objects.
 */
public record MachineGuiSnapshot(boolean formed, boolean active, int totalSlots, int offset,
                                 List<Entry> entries, WorkSlotStatus overallStatus) implements PacketWritable {
    public static final int MAX_ROWS = 7;
    public static final MachineGuiSnapshot EMPTY = new MachineGuiSnapshot(false, false, 0, 0, List.of());
    private static final int MAX_TEXT = 1024;
    private static final int MAX_ARGUMENTS = 16;

    public MachineGuiSnapshot {
        Objects.requireNonNull(overallStatus);
        if (offset < 0 || offset > totalSlots || entries.size() > MAX_ROWS || entries.size() > totalSlots - offset) {
            throw new IllegalArgumentException("Invalid machine GUI page");
        }
        entries = ObjectLists.unmodifiable(new ObjectArrayList<>(entries));
        entries.forEach(Objects::requireNonNull);
    }

    public MachineGuiSnapshot(boolean formed, boolean active, int totalSlots, int offset, List<Entry> entries) {
        this(formed, active, totalSlots, offset, entries, entries.stream().map(Entry::status).reduce(WorkSlotStatus.IDLE, MachineGuiSnapshot::combine));
    }

    @SuppressWarnings("unused") // AE2 GuiSync invokes the public ByteBuf constructor.
    public MachineGuiSnapshot(ByteBuf data) {
        this(data.readBoolean(), data.readBoolean(), data.readInt(), data.readInt(), readEntries(new PacketBuffer(data)), readStatus(data));
    }

    public static MachineGuiSnapshot capture(MachineInstance machine, int requestedOffset, int rows) {
        machine.requirePersistenceBoundary();
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("Invalid visible row count");
        }
        var hosts = new ObjectArrayList<MachineWorkSlots>();
        int total = 0;
        var overall = WorkSlotStatus.IDLE;
        for (var binding : machine.components().orderedBindings()) {
            if (binding.component() instanceof MachineWorkSlots slots) {
                slots.requireQuiescent();
                if (slots.owner() != machine) {
                    throw new IllegalArgumentException("Foreign work-slot host");
                }
                hosts.add(slots);
                total = Math.addExact(total, slots.slots().size());
                for (var slot : slots.slots()) overall = combine(overall, slot.status());
            }
        }
        int start = Math.clamp(requestedOffset, 0, Math.max(0, total - rows));
        var result = new ObjectArrayList<Entry>();
        int base = 0;
        for (var host : hosts) {
            int first = Math.max(0, start - base);
            for (int i = first; i < host.slots().size() && result.size() < rows; i++) {
                var slot = host.slots().get(i);
                var process = slot.process();
                int percent = process == null ? 0 : process.recipe().durationTicks() == 0 ? 100
                    : Math.clamp((int) (process.state().progress() / process.recipe().durationTicks() * 100), 0, 100);
                var reason = switch (slot.result()) {
                    case ProcessResult.Blocked blocked -> blocked.reason();
                    case ProcessResult.Failure failure -> failure.reason();
                    case ProcessResult.Success ignored -> "";
                };
                var arguments = switch (slot.result()) {
                    case ProcessResult.Blocked blocked -> blocked.arguments();
                    case ProcessResult.Failure failure -> failure.arguments();
                    case ProcessResult.Success ignored -> List.<String>of();
                };
                result.add(new Entry(slot.name(), process == null ? "" : process.recipe().id().toString(), slot.status(), percent, reason, arguments));
            }
            base = Math.addExact(base, host.slots().size());
            if (result.size() == rows) {
                break;
            }
        }
        var lifecycle = machine.lifecycle();
        return new MachineGuiSnapshot(lifecycle == null || lifecycle.formed(), machine.active(), total, start, result, overall);
    }

    private static WorkSlotStatus combine(WorkSlotStatus first, WorkSlotStatus second) {
        if (first == WorkSlotStatus.FAILED || second == WorkSlotStatus.FAILED) return WorkSlotStatus.FAILED;
        if (first == WorkSlotStatus.BLOCKED || second == WorkSlotStatus.BLOCKED) return WorkSlotStatus.BLOCKED;
        return first == WorkSlotStatus.RUNNING || second == WorkSlotStatus.RUNNING ? WorkSlotStatus.RUNNING : WorkSlotStatus.IDLE;
    }

    private static WorkSlotStatus readStatus(ByteBuf data) {
        int status = data.readUnsignedByte();
        if (status >= WorkSlotStatus.values().length) throw new IllegalArgumentException("Invalid machine GUI status");
        return WorkSlotStatus.values()[status];
    }

    private static List<Entry> readEntries(PacketBuffer packet) {
        int count = packet.readUnsignedByte();
        if (count > MAX_ROWS) {
            throw new IllegalArgumentException("Too many machine GUI rows");
        }
        var entries = new ObjectArrayList<Entry>(count);
        for (int i = 0; i < count; i++) {
            String name = packet.readString(MAX_TEXT);
            String recipe = packet.readString(MAX_TEXT);
            int status = packet.readUnsignedByte();
            if (status >= WorkSlotStatus.values().length) {
                throw new IllegalArgumentException("Invalid work-slot status");
            }
            int percent = packet.readUnsignedByte();
            String reason = packet.readString(MAX_TEXT);
            int size = packet.readUnsignedByte();
            if (size > MAX_ARGUMENTS) {
                throw new IllegalArgumentException("Too many reason arguments");
            }
            var arguments = new ObjectArrayList<String>(size);
            for (int j = 0; j < size; j++) {
                arguments.add(packet.readString(MAX_TEXT));
            }
            entries.add(new Entry(name, recipe, WorkSlotStatus.values()[status], percent, reason, arguments));
        }
        return entries;
    }

    private static String bounded(String text) {
        return text.substring(0, Math.min(text.length(), MAX_TEXT));
    }

    @Override
    public void writeToPacket(ByteBuf data) {
        data.writeBoolean(formed).writeBoolean(active).writeInt(totalSlots).writeInt(offset).writeByte(entries.size());
        var packet = new PacketBuffer(data);
        for (var entry : entries) {
            packet.writeString(entry.name).writeString(entry.recipe);
            packet.writeByte(entry.status.ordinal()).writeByte(entry.percent);
            packet.writeString(entry.reason).writeByte(entry.arguments.size());
            for (var argument : entry.arguments) {
                packet.writeString(argument);
            }
        }
        packet.writeByte(overallStatus.ordinal());
    }

    public record Entry(String name, String recipe, WorkSlotStatus status, int percent, String reason,
                        List<String> arguments) {
        public Entry {
            name = bounded(name);
            recipe = bounded(recipe);
            reason = bounded(reason);
            Objects.requireNonNull(status);
            if (percent < 0 || percent > 100) {
                throw new IllegalArgumentException("Invalid recipe progress");
            }
            var copy = new ObjectArrayList<String>();
            for (int i = 0; i < Math.min(MAX_ARGUMENTS, arguments.size()); i++) {
                copy.add(bounded(arguments.get(i)));
            }
            arguments = ObjectLists.unmodifiable(copy);
        }
    }
}
