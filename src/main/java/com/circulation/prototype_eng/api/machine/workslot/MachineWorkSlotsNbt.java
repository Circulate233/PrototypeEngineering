package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.api.persistence.ProcessResultNbt;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessCodecs;
import com.circulation.prototype_eng.api.recipe.persistence.RecipeProcessNbt;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLinkedOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Collection;
import java.util.Objects;

/**
 * All-or-nothing work-slot loading using the host's live layout and executor. Explicit layout revision
 * also covers compatible executor/host behavior. No saved field grants scheduler, world or PN authority.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class MachineWorkSlotsNbt {
    private final String revision;
    private final RecipeProcessCodecs recipes;

    public MachineWorkSlotsNbt(String revision, Collection<RecipeProcessNbt> recipes) {
        this(revision, RecipeProcessCodecs.fixed(recipes));
    }

    public MachineWorkSlotsNbt(String revision, RecipeProcessCodecs recipes) {
        this.revision = Objects.requireNonNull(revision);
        this.recipes = Objects.requireNonNull(recipes);
        if (revision.isBlank()) {
            throw new IllegalArgumentException("Work-slot layout revision must not be blank");
        }
    }

    private static NBTTagCompound writeDefinition(WorkSlotDefinition definition) {
        var entry = new NBTTagCompound();
        entry.setString("Name", definition.name());
        var groups = new NBTTagList();
        for (var group : definition.allowedRecipeGroups()) {
            groups.appendTag(new NBTTagString(group.toString()));
        }
        entry.setTag("Groups", groups);
        entry.setBoolean("Core", definition.isCore());
        entry.setString("RestartPolicy", definition.restartPolicy().name());
        return entry;
    }

    private static WorkSlotDefinition readDefinition(NBTTagCompound entry) {
        var savedGroups = entry.getTagList("Groups", Constants.NBT.TAG_STRING);
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>(savedGroups.tagCount());
        for (int i = 0; i < savedGroups.tagCount(); i++) {
            if (!groups.add(new ResourceLocation(savedGroups.getStringTagAt(i)))) {
                throw new IllegalArgumentException("Duplicate work-slot recipe group");
            }
        }
        return new WorkSlotDefinition(entry.getString("Name"), groups, entry.getBoolean("Core"),
            WorkSlotRestartPolicy.valueOf(entry.getString("RestartPolicy")));
    }

    public RecipeProcessCodecs processCodecs() {
        return recipes;
    }

    public NBTTagCompound encode(MachineWorkSlots host) {
        host.owner().requirePersistenceBoundary();
        var codecs = recipes.snapshot();
        var snapshot = host.snapshot();
        var tag = new NBTTagCompound();
        tag.setInteger("Version", host.dynamicRoster() ? 2 : 1);
        if (host.dynamicRoster()) {
            tag.setBoolean("Dynamic", true);
            tag.setLong("NextSlot", host.nextRuntimeSlot());
        }
        tag.setString("Revision", revision);
        var slots = new NBTTagList();
        for (int index = 0; index < snapshot.size(); index++) {
            var slot = snapshot.get(index);
            var entry = writeDefinition(slot.definition());
            entry.setTag("Result", ProcessResultNbt.INSTANCE.encode(slot.result()));
            if (slot.process() != null) {
                entry.setTag("Process", codecs.require(slot.process().recipe()).encode(slot.process()));
            }
            slots.appendTag(entry);
        }
        tag.setTag("Slots", slots);
        return tag;
    }

    /**
     * Parse every entry into new processes before the host changes any old attachment or slot.
     */
    public void restore(MachineWorkSlots host, NBTTagCompound tag) {
        host.owner().requirePersistenceBoundary();
        var codecs = recipes.snapshot();
        var old = host.snapshot();
        int version = tag.getInteger("Version");
        if (version != (host.dynamicRoster() ? 2 : 1) || !tag.getString("Revision").equals(revision)
            || host.dynamicRoster() && !tag.getBoolean("Dynamic")
            || !host.dynamicRoster() && (tag.hasKey("Dynamic") || tag.hasKey("NextSlot"))) {
            throw new IllegalArgumentException("Unsupported work-slot save or incompatible layout revision");
        }
        var slots = tag.getTagList("Slots", Constants.NBT.TAG_COMPOUND);
        if (!host.dynamicRoster() && slots.tagCount() != old.size()) {
            throw new IllegalArgumentException("Work slot layout size changed");
        }
        var decoded = new ObjectArrayList<MachineWorkSlots.SlotSnapshot>(slots.tagCount());
        var names = new ObjectLinkedOpenHashSet<String>(slots.tagCount());
        for (int i = 0; i < slots.tagCount(); i++) {
            var entry = slots.getCompoundTagAt(i);
            var definition = readDefinition(entry);
            if (!names.add(definition.name()) || !host.acceptsRestoredDefinition(i, definition)) {
                throw new IllegalArgumentException("Unknown, duplicate or changed work slot: " + definition.name());
            }
            var result = ProcessResultNbt.INSTANCE.decode(entry.getCompoundTag("Result"));
            var processTag = entry.hasKey("Process") ? entry.getCompoundTag("Process") : null;
            var process = processTag == null ? null : codecs.require(new ResourceLocation(processTag.getString("Recipe")),
                processTag.getString("Revision")).decode(host.owner(), processTag);
            decoded.add(new MachineWorkSlots.SlotSnapshot(definition, process, result));
        }
        if (host.dynamicRoster()) host.restore(decoded, tag.getLong("NextSlot"));
        else host.restore(decoded);
    }

}
