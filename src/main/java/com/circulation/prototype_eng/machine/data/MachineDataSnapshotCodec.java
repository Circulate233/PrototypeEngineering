package com.circulation.prototype_eng.machine.data;

import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.machine.render.ControllerRenderSnapshotCodec;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

/**
 * Full replacement schema; absence removes prior data, hosts and processes.
 */
public final class MachineDataSnapshotCodec {
    private MachineDataSnapshotCodec() {
    }

    public static byte[] encode(MachineDataSnapshot snapshot) {
        var tag = new NBTTagCompound();
        tag.setInteger("Version", 1);
        tag.setLong("Generation", snapshot.generation());
        tag.setLong("Revision", snapshot.revision());
        component(tag, snapshot.machine());
        var hosts = new NBTTagList();
        for (var host : snapshot.hosts()) {
            var entry = new NBTTagCompound();
            entry.setLong("Identity", host.identity());
            var slots = new NBTTagList();
            for (var slot : host.slots()) {
                var item = new NBTTagCompound();
                item.setString("Name", slot.name());
                if (slot.process() != null) {
                    var process = new NBTTagCompound();
                    process.setLong("Identity", slot.process().identity());
                    process.setString("Recipe", slot.process().recipe().toString());
                    component(process, slot.process().data());
                    item.setTag("Process", process);
                }
                slots.appendTag(item);
            }
            entry.setTag("Slots", slots);
            hosts.appendTag(entry);
        }
        tag.setTag("Hosts", hosts);
        return ControllerRenderSnapshotCodec.encodeCompound(tag);
    }

    public static MachineDataSnapshot decode(byte[] bytes) {
        var tag = ControllerRenderSnapshotCodec.decodeCompound(bytes);
        StrictNbt.requireKeys(tag, "Version", "Generation", "Revision", "ComponentRevision", "Data", "Hosts");
        if (StrictNbt.requireInt(tag, "Version") != 1)
            throw new IllegalArgumentException("Unknown machine data schema");
        var entries = StrictNbt.requireList(tag, "Hosts", Constants.NBT.TAG_COMPOUND);
        var hosts = new ObjectArrayList<MachineDataSnapshot.Host>(entries.tagCount());
        var hostIds = new LongOpenHashSet(entries.tagCount());
        var processIds = new LongOpenHashSet();
        for (int h = 0; h < entries.tagCount(); h++) {
            var entry = entries.getCompoundTagAt(h);
            StrictNbt.requireKeys(entry, "Identity", "Slots");
            var items = StrictNbt.requireList(entry, "Slots", Constants.NBT.TAG_COMPOUND);
            long hostId = StrictNbt.requireLong(entry, "Identity");
            if (!hostIds.add(hostId)) throw new IllegalArgumentException("Duplicate data host identity");
            var slots = new ObjectArrayList<MachineDataSnapshot.Slot>(items.tagCount());
            var names = new ObjectOpenHashSet<String>();
            for (int s = 0; s < items.tagCount(); s++) {
                var item = items.getCompoundTagAt(s);
                StrictNbt.requireKeys(item, "Name", "Process");
                MachineDataSnapshot.Process process = null;
                if (item.hasKey("Process")) {
                    var saved = StrictNbt.requireCompound(item, "Process");
                    StrictNbt.requireKeys(saved, "Identity", "Recipe", "ComponentRevision", "Data");
                    long processId = StrictNbt.requireLong(saved, "Identity");
                    if (!processIds.add(processId))
                        throw new IllegalArgumentException("Duplicate data process identity");
                    process = new MachineDataSnapshot.Process(processId, StrictNbt.requireId(saved, "Recipe"), component(saved));
                }
                var name = StrictNbt.requireString(item, "Name");
                if (!names.add(name)) throw new IllegalArgumentException("Duplicate data slot name");
                slots.add(new MachineDataSnapshot.Slot(name, process));
            }
            hosts.add(new MachineDataSnapshot.Host(hostId, slots));
        }
        return new MachineDataSnapshot(StrictNbt.requireLong(tag, "Generation"), StrictNbt.requireLong(tag, "Revision"), component(tag), hosts);
    }

    private static void component(NBTTagCompound tag, @Nullable ComponentData.Snapshot data) {
        if (data == null) return;
        tag.setLong("ComponentRevision", data.revision());
        tag.setTag("Data", data.data());
    }

    private static @Nullable ComponentData.Snapshot component(NBTTagCompound tag) {
        if (!tag.hasKey("Data") && !tag.hasKey("ComponentRevision")) return null;
        return new ComponentData.Snapshot(StrictNbt.requireLong(tag, "ComponentRevision"), StrictNbt.requireCompound(tag, "Data"));
    }
}
