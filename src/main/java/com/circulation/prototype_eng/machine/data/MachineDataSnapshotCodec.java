package com.circulation.prototype_eng.machine.data;

import com.circulation.prototype_eng.api.data.ComponentData;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.machine.render.ControllerRenderSnapshotCodec;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;
import org.jetbrains.annotations.Nullable;

/** Full replacement schema; absence removes prior data, hosts and processes. */
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
        if (StrictNbt.requireInt(tag, "Version") != 1) throw new IllegalArgumentException("Unknown machine data schema");
        var hosts = new ObjectArrayList<MachineDataSnapshot.Host>();
        var entries = StrictNbt.requireList(tag, "Hosts", Constants.NBT.TAG_COMPOUND);
        for (int h = 0; h < entries.tagCount(); h++) {
            var entry = entries.getCompoundTagAt(h);
            StrictNbt.requireKeys(entry, "Identity", "Slots");
            var slots = new ObjectArrayList<MachineDataSnapshot.Slot>();
            var items = StrictNbt.requireList(entry, "Slots", Constants.NBT.TAG_COMPOUND);
            for (int s = 0; s < items.tagCount(); s++) {
                var item = items.getCompoundTagAt(s);
                StrictNbt.requireKeys(item, "Name", "Process");
                MachineDataSnapshot.Process process = null;
                if (item.hasKey("Process")) {
                    var saved = StrictNbt.requireCompound(item, "Process");
                    StrictNbt.requireKeys(saved, "Identity", "Recipe", "ComponentRevision", "Data");
                    process = new MachineDataSnapshot.Process(StrictNbt.requireLong(saved, "Identity"), StrictNbt.requireId(saved, "Recipe"), component(saved));
                }
                slots.add(new MachineDataSnapshot.Slot(StrictNbt.requireString(item, "Name"), process));
            }
            hosts.add(new MachineDataSnapshot.Host(StrictNbt.requireLong(entry, "Identity"), slots));
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
