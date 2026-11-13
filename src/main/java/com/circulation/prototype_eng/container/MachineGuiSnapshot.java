package com.circulation.prototype_eng.container;

import ae2.container.guisync.PacketWritable;
import com.circulation.prototype_eng.api.machine.CrossRecipeController;
import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.attribute.StandardMachineAttributes;
import com.circulation.prototype_eng.api.machine.workslot.MachineWorkSlots;
import com.circulation.prototype_eng.api.machine.workslot.WorkSlotStatus;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeParallelism;
import com.circulation.prototype_eng.api.scheduler.ControllerExecutionPolicy;
import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import io.netty.buffer.ByteBuf;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.network.PacketBuffer;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded display data. Capture only after scheduler join; never retain runtime objects.
 * Entry lists are borrowed read-only for the snapshot's lifetime; callers must not mutate them.
 */
public record MachineGuiSnapshot(boolean formed, boolean active, int totalSlots, int offset,
                                 List<Entry> entries, WorkSlotStatus overallStatus,
                                 Details details) implements PacketWritable {
    public static final int MAX_ROWS = 7;
    public static final MachineGuiSnapshot EMPTY = new MachineGuiSnapshot(false, false, 0, 0, List.of());
    private static final int MAX_TEXT = 1024;
    private static final int MAX_ARGUMENTS = 16;

    public MachineGuiSnapshot {
        Objects.requireNonNull(overallStatus);
        Objects.requireNonNull(details);
        if (offset < 0 || offset > totalSlots || entries.size() > MAX_ROWS || entries.size() > totalSlots - offset) {
            throw new IllegalArgumentException("Invalid machine GUI page");
        }
        entries.forEach(Objects::requireNonNull);
    }

    public MachineGuiSnapshot(boolean formed, boolean active, int totalSlots, int offset, List<Entry> entries) {
        this(formed, active, totalSlots, offset, entries, overall(entries), Details.EMPTY);
    }

    @SuppressWarnings("unused") // AE2 GuiSync invokes the public ByteBuf constructor.
    public MachineGuiSnapshot(ByteBuf data) {
        this(data.readBoolean(), data.readBoolean(), data.readInt(), data.readInt(), readEntries(new PacketBuffer(data)), readStatus(data), readDetails(new PacketBuffer(data)));
    }

    private static WorkSlotStatus overall(List<Entry> entries) {
        var status = WorkSlotStatus.IDLE;
        for (var entry : entries) status = combine(status, entry.status());
        return status;
    }

    public static MachineGuiSnapshot unavailable(boolean formed, String structure) {
        return new MachineGuiSnapshot(formed, false, 0, 0, List.of(), WorkSlotStatus.IDLE,
            new Details(false, false, 0, 0, 0, 0, ExecutionMode.MAIN_THREAD, structure));
    }

    public static MachineGuiSnapshot capture(MachineInstance machine, int requestedOffset, int rows) {
        machine.requirePersistenceBoundary();
        if (rows < 1 || rows > MAX_ROWS) {
            throw new IllegalArgumentException("Invalid visible row count");
        }
        var hosts = new ObjectArrayList<MachineWorkSlots>();
        boolean crossRecipe = machine instanceof CrossRecipeController;
        int total = 0;
        int running = 0;
        int threads = 0;
        long usedParallelism = 0;
        var overall = WorkSlotStatus.IDLE;
        for (var binding : machine.components().orderedBindings()) {
            if (binding.component() instanceof MachineWorkSlots slots) {
                slots.requireQuiescent();
                if (slots.owner() != machine) {
                    throw new IllegalArgumentException("Foreign work-slot host");
                }
                hosts.add(slots);
                if (!crossRecipe) threads = Math.addExact(threads, slots.capacity());
                for (var slot : slots.slots()) {
                    overall = combine(overall, slot.status());
                    if (!crossRecipe || slot.process() != null) total = Math.incrementExact(total);
                    if (slot.process() != null) {
                        running = Math.incrementExact(running);
                        usedParallelism = Math.addExact(usedParallelism, RecipeParallelism.effective(slot.process()));
                    }
                }
            }
        }
        int start = Math.clamp(requestedOffset, 0, Math.max(0, total - rows));
        var result = new ObjectArrayList<Entry>(Math.min(rows, total - start));
        int visibleIndex = 0;
        for (var host : hosts) {
            for (var slot : host.slots()) {
                var process = slot.process();
                if (crossRecipe && process == null) continue;
                if (visibleIndex++ < start) continue;
                if (result.size() == rows) break;
                int percent = process == null ? 0 : process.durationTicks() == 0 ? 100
                    : Math.clamp((int) (process.state().progress() / process.durationTicks() * 100), 0, 100);
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
                result.add(new Entry(slot.name(), process == null ? "" : process.recipe().id().toString(), slot.status(), percent, reason, arguments,
                    process == null ? 0 : RecipeParallelism.effective(process)));
            }
            if (result.size() == rows) {
                break;
            }
        }
        var lifecycle = machine.lifecycle();
        return new MachineGuiSnapshot(lifecycle == null || lifecycle.formed(), machine.active(), total, start, result, overall,
            new Details(crossRecipe, lifecycle == null ? machine.active() : lifecycle.processingAllowed(), running, crossRecipe ? 0 : threads,
                usedParallelism, Math.max(0L, (long) Math.floor(machine.attributes().value(StandardMachineAttributes.PROCESS_PARALLELISM, 1))),
                machine.executionMode(), machine.type() == null ? "" : Objects.requireNonNull(machine.type()).structureId().toString(), machine.executionPolicy()));
    }

    private static WorkSlotStatus combine(WorkSlotStatus first, WorkSlotStatus second) {
        if (first == WorkSlotStatus.FAILED || second == WorkSlotStatus.FAILED) return WorkSlotStatus.FAILED;
        if (first == WorkSlotStatus.BLOCKED || second == WorkSlotStatus.BLOCKED) return WorkSlotStatus.BLOCKED;
        return first == WorkSlotStatus.RUNNING || second == WorkSlotStatus.RUNNING ? WorkSlotStatus.RUNNING : WorkSlotStatus.IDLE;
    }

    private static WorkSlotStatus readStatus(ByteBuf data) {
        int status = data.readUnsignedByte();
        if (status >= WorkSlotStatus.VALUES.length) throw new IllegalArgumentException("Invalid machine GUI status");
        return WorkSlotStatus.VALUES[status];
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
            if (status >= WorkSlotStatus.VALUES.length) {
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
            entries.add(new Entry(name, recipe, WorkSlotStatus.VALUES[status], percent, reason, arguments, packet.readInt()));
        }
        return entries;
    }

    private static String bounded(String text) {
        return text.substring(0, Math.min(text.length(), MAX_TEXT));
    }

    private static Details readDetails(PacketBuffer packet) {
        boolean cross = packet.readBoolean(), processing = packet.readBoolean();
        int running = packet.readInt(), threads = packet.readInt();
        long used = packet.readLong(), limit = packet.readLong();
        int mode = packet.readUnsignedByte();
        if (mode >= ExecutionMode.VALUES.length) throw new IllegalArgumentException("Invalid execution mode");
        int policy = packet.readByte();
        if (policy < -1 || policy >= ControllerExecutionPolicy.VALUES.length)
            throw new IllegalArgumentException("Invalid controller execution policy");
        return new Details(cross, processing, running, threads, used, limit, ExecutionMode.VALUES[mode], packet.readString(MAX_TEXT),
            policy == -1 ? null : ControllerExecutionPolicy.VALUES[policy]);
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
            packet.writeInt(entry.parallelism);
        }
        packet.writeByte(overallStatus.ordinal());
        packet.writeBoolean(details.crossRecipe).writeBoolean(details.processing).writeInt(details.running).writeInt(details.threadLimit)
              .writeLong(details.usedParallelism).writeLong(details.parallelismLimit).writeByte(details.mode.ordinal());
        packet.writeByte(details.policy == null ? -1 : details.policy.ordinal());
        packet.writeString(details.structure);
    }

    public record Details(boolean crossRecipe, boolean processing, int running, int threadLimit,
                          long usedParallelism, long parallelismLimit, ExecutionMode mode, String structure,
                          @Nullable ControllerExecutionPolicy policy) {
        private static final Details EMPTY = new Details(false, false, 0, 0, 0, 0, ExecutionMode.MAIN_THREAD, "");

        public Details(boolean crossRecipe, boolean processing, int running, int threadLimit,
                       long usedParallelism, long parallelismLimit, ExecutionMode mode, String structure) {
            this(crossRecipe, processing, running, threadLimit, usedParallelism, parallelismLimit, mode, structure, null);
        }

        public Details {
            if (running < 0 || threadLimit < 0 || usedParallelism < 0 || parallelismLimit < 0)
                throw new IllegalArgumentException("Negative machine GUI count");
            Objects.requireNonNull(mode);
            structure = bounded(Objects.requireNonNull(structure));
        }
    }

    public record Entry(String name, String recipe, WorkSlotStatus status, int percent, String reason,
                        List<String> arguments, int parallelism) {
        public Entry(String name, String recipe, WorkSlotStatus status, int percent, String reason, List<String> arguments) {
            this(name, recipe, status, percent, reason, arguments, recipe.isEmpty() ? 0 : 1);
        }

        public Entry {
            name = bounded(name);
            recipe = bounded(recipe);
            reason = bounded(reason);
            Objects.requireNonNull(status);
            if (percent < 0 || percent > 100 || parallelism < 0) {
                throw new IllegalArgumentException("Invalid recipe progress");
            }
            var copy = new ObjectArrayList<String>(Math.min(MAX_ARGUMENTS, arguments.size()));
            for (int i = 0; i < Math.min(MAX_ARGUMENTS, arguments.size()); i++) {
                copy.add(bounded(arguments.get(i)));
            }
            arguments = copy;
        }
    }
}
