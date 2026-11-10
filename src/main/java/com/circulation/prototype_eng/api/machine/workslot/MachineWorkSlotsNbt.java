package com.circulation.prototype_eng.api.machine.workslot;

import com.circulation.prototype_eng.api.persistence.ProcessResultNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
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

    public RecipeProcessCodecs processCodecs() {
        return recipes;
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
        var groups = new ObjectLinkedOpenHashSet<ResourceLocation>();
        var savedGroups = StrictNbt.requireList(entry, "Groups", Constants.NBT.TAG_STRING);
        for (int i = 0; i < savedGroups.tagCount(); i++) {
            if (!groups.add(StrictNbt.requireId(savedGroups.getStringTagAt(i)))) {
                throw new IllegalArgumentException("Duplicate work-slot recipe group");
            }
        }
        return new WorkSlotDefinition(StrictNbt.requireString(entry, "Name"), groups, StrictNbt.requireBoolean(entry, "Core"),
            WorkSlotRestartPolicy.valueOf(StrictNbt.requireString(entry, "RestartPolicy")));
    }

    public NBTTagCompound encode(MachineWorkSlots host) {
        host.owner().requirePersistenceBoundary();
        var codecs = recipes.snapshot();
        var snapshot = host.snapshot();
        long runtimeCounter = host.nextRuntimeSlot();
        var tag = new NBTTagCompound();
        tag.setInteger("Version", host.dynamicRoster() ? 2 : 1);
        if (host.dynamicRoster()) {
            tag.setBoolean("Dynamic", true);
            tag.setLong("NextSlot", runtimeCounter);
        }
        tag.setString("Revision", revision);
        var slots = new NBTTagList();
        for (var slot : snapshot) {
            var entry = writeDefinition(slot.definition());
            entry.setTag("Result", ProcessResultNbt.INSTANCE.encode(slot.result()));
            if (slot.process() != null) {
                entry.setTag("Process", codecs.require(slot.process().recipe()).encode(slot.process()));
            }
            slots.appendTag(entry);
        }
        tag.setTag("Slots", slots);
        if (!host.snapshot().equals(snapshot) || host.nextRuntimeSlot() != runtimeCounter) {
            throw new IllegalStateException("Codec changed work slots during serialization");
        }
        host.owner().requirePersistenceBoundary();
        return tag;
    }

    /**
     * Parse every entry into new processes before the host changes any old attachment or slot.
     */
    public void restore(MachineWorkSlots host, NBTTagCompound saved) {
        host.owner().requirePersistenceBoundary();
        var codecs = recipes.snapshot();
        var old = host.snapshot();
        long runtimeCounter = host.nextRuntimeSlot();
        var tag = Objects.requireNonNull(saved).copy();
        StrictNbt.requireKeys(tag, "Version", "Revision", "Slots", "Dynamic", "NextSlot");
        int version = StrictNbt.requireInt(tag, "Version");
        if (version != (host.dynamicRoster() ? 2 : 1) || !StrictNbt.requireString(tag, "Revision").equals(revision)
            || host.dynamicRoster() && !StrictNbt.requireBoolean(tag, "Dynamic")
            || !host.dynamicRoster() && (tag.hasKey("Dynamic") || tag.hasKey("NextSlot"))) {
            throw new IllegalArgumentException("Unsupported work-slot save or incompatible layout revision");
        }
        var slots = StrictNbt.requireList(tag, "Slots", Constants.NBT.TAG_COMPOUND);
        if (!host.dynamicRoster() && slots.tagCount() != old.size()) {
            throw new IllegalArgumentException("Work slot layout size changed");
        }
        var decoded = new ObjectArrayList<MachineWorkSlots.SlotSnapshot>();
        var names = new ObjectLinkedOpenHashSet<String>();
        for (int i = 0; i < slots.tagCount(); i++) {
            var entry = slots.getCompoundTagAt(i);
            StrictNbt.requireKeys(entry, "Name", "Groups", "Core", "RestartPolicy", "Result", "Process");
            var definition = readDefinition(entry);
            if (!names.add(definition.name()) || !host.acceptsRestoredDefinition(i, definition)) {
                throw new IllegalArgumentException("Unknown, duplicate or changed work slot: " + definition.name());
            }
            var result = ProcessResultNbt.INSTANCE.decode(StrictNbt.requireCompound(entry, "Result"));
            var processTag = entry.hasKey("Process") ? StrictNbt.requireCompound(entry, "Process") : null;
            var process = processTag == null ? null : codecs.require(StrictNbt.requireId(processTag, "Recipe"),
                StrictNbt.requireString(processTag, "Revision")).decode(host.owner(), processTag);
            decoded.add(new MachineWorkSlots.SlotSnapshot(definition, process, result));
        }
        if (!host.snapshot().equals(old) || host.nextRuntimeSlot() != runtimeCounter) {
            throw new IllegalStateException("Codec changed live work slots during restoration");
        }
        host.owner().requirePersistenceBoundary();
        if (host.dynamicRoster()) host.restore(decoded, StrictNbt.requireLong(tag, "NextSlot"));
        else host.restore(decoded);
    }

}
