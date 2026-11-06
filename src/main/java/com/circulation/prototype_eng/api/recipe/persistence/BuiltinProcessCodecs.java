package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraftforge.common.util.Constants;

final class BuiltinProcessCodecs {
    private BuiltinProcessCodecs() { }

    static ProcessComponentCodec<RecipeOverlays> overlays(RecipeOverlayNbt codec) {
        return new ProcessComponentCodec<>() {
            @Override public NBTTagCompound encode(RecipeProcess process, RecipeOverlays component) {
                return codec.encode(component.snapshot());
            }
            @Override public void restore(RecipeProcess process, RecipeOverlays component, NBTTagCompound data) {
                component.restore(codec.decode(data));
            }
        };
    }

    static ProcessComponentCodec<ResourceInputLocks> locks(ResourceKeyNbt resources) {
        return new ProcessComponentCodec<>() {
            @Override public NBTTagCompound encode(RecipeProcess process, ResourceInputLocks component) {
                var list = new NBTTagList();
                component.snapshot().forEach((key, value) -> {
                    var entry = new NBTTagCompound();
                    entry.setTag("Path", RequirementPathNbt.encode(key.requirement()));
                    entry.setString("Stage", key.stage().name());
                    entry.setString("Kind", key.kind().name());
                    entry.setInteger("Group", key.groupIndex());
                    entry.setTag("Key", resources.encode(value));
                    list.appendTag(entry);
                });
                var tag = new NBTTagCompound();
                tag.setTag("Choices", list);
                return tag;
            }

            @Override public void restore(RecipeProcess process, ResourceInputLocks component, NBTTagCompound data) {
                StrictNbt.requireKeys(data, "Choices");
                var list = StrictNbt.requireList(data, "Choices", Constants.NBT.TAG_COMPOUND);
                var choices = new Object2ObjectLinkedOpenHashMap<ResourceInputLocks.Key, AEKey>();
                for (int i = 0; i < list.tagCount(); i++) {
                    var entry = list.getCompoundTagAt(i);
                    StrictNbt.requireKeys(entry, "Path", "Stage", "Kind", "Group", "Key");
                    var key = new ResourceInputLocks.Key(RequirementPathNbt.read(entry),
                        RecipeStage.valueOf(StrictNbt.requireString(entry, "Stage")),
                        ResourceInputLocks.Kind.valueOf(StrictNbt.requireString(entry, "Kind")), StrictNbt.requireInt(entry, "Group"));
                    if (choices.putIfAbsent(key, resources.decode(StrictNbt.requireCompound(entry, "Key"))) != null) {
                        throw new IllegalArgumentException("Duplicate saved input choice");
                    }
                }
                component.restore(choices);
            }
        };
    }

    static ProcessComponentCodec<SelectiveState> selections(RecipeDefinitionIndex index) {
        return new ProcessComponentCodec<>() {
            @Override public NBTTagCompound encode(RecipeProcess process, SelectiveState component) {
                var list = new NBTTagList();
                component.snapshot().forEach((path, selection) -> {
                    var entry = new NBTTagCompound();
                    entry.setTag("Path", RequirementPathNbt.encode(path));
                    entry.setInteger("Index", selection.index());
                    entry.setBoolean("Committed", selection.committed());
                    entry.setBoolean("Completed", selection.completed());
                    var modifiers = new NBTTagList();
                    for (var reference : selection.modifiers()) {
                        var modifier = new NBTTagCompound();
                        modifier.setString("Attribute", reference.attribute().toString());
                        modifier.setString("Id", reference.id());
                        modifiers.appendTag(modifier);
                    }
                    entry.setTag("Modifiers", modifiers);
                    var overlays = new NBTTagList();
                    for (var reference : selection.overlays()) {
                        if (index.require(reference.path()) != reference.target()) {
                            throw new IllegalArgumentException("Owned overlay has a foreign canonical target");
                        }
                        var overlay = new NBTTagCompound();
                        overlay.setTag("Path", RequirementPathNbt.encode(reference.path()));
                        overlay.setString("Layer", reference.layerId());
                        overlays.appendTag(overlay);
                    }
                    entry.setTag("Overlays", overlays);
                    list.appendTag(entry);
                });
                var tag = new NBTTagCompound();
                tag.setTag("Selections", list);
                return tag;
            }

            @Override public void restore(RecipeProcess process, SelectiveState component, NBTTagCompound data) {
                StrictNbt.requireKeys(data, "Selections");
                var list = StrictNbt.requireList(data, "Selections", Constants.NBT.TAG_COMPOUND);
                var selections = new Object2ObjectLinkedOpenHashMap<RequirementPath, SelectiveState.SelectionSnapshot>();
                for (int i = 0; i < list.tagCount(); i++) {
                    var entry = list.getCompoundTagAt(i);
                    StrictNbt.requireKeys(entry, "Path", "Index", "Committed", "Completed", "Modifiers", "Overlays");
                    var modifiers = new ObjectArrayList<SelectiveState.ModifierReference>();
                    var savedModifiers = StrictNbt.requireList(entry, "Modifiers", Constants.NBT.TAG_COMPOUND);
                    for (int j = 0; j < savedModifiers.tagCount(); j++) {
                        var modifier = savedModifiers.getCompoundTagAt(j);
                        StrictNbt.requireKeys(modifier, "Attribute", "Id");
                        modifiers.add(new SelectiveState.ModifierReference(StrictNbt.requireId(modifier, "Attribute"),
                            StrictNbt.requireString(modifier, "Id")));
                    }
                    var overlays = new ObjectArrayList<SelectiveState.OverlayReference>();
                    var savedOverlays = StrictNbt.requireList(entry, "Overlays", Constants.NBT.TAG_COMPOUND);
                    for (int j = 0; j < savedOverlays.tagCount(); j++) {
                        var overlay = savedOverlays.getCompoundTagAt(j);
                        StrictNbt.requireKeys(overlay, "Path", "Layer");
                        var path = RequirementPathNbt.read(overlay);
                        overlays.add(new SelectiveState.OverlayReference(path, index.require(path), StrictNbt.requireString(overlay, "Layer")));
                    }
                    var selection = new SelectiveState.SelectionSnapshot(StrictNbt.requireBoolean(entry, "Committed"),
                        StrictNbt.requireInt(entry, "Index"), StrictNbt.requireBoolean(entry, "Completed"), modifiers, overlays);
                    if (selections.putIfAbsent(RequirementPathNbt.read(entry), selection) != null) {
                        throw new IllegalArgumentException("Duplicate saved selection");
                    }
                }
                component.restore(selections);
            }
        };
    }
}
