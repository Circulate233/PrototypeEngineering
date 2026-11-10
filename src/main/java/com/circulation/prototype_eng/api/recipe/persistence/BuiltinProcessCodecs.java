package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.List;

final class BuiltinProcessCodecs {
    private BuiltinProcessCodecs() {
    }

    static ProcessComponentCodec<RecipeOverlays> overlays(List<RecipeOverlayNbt.Binding<?, ?>> bindings) {
        var frozen = ObjectLists.unmodifiable(new ObjectArrayList<>(bindings));
        var ids = new ObjectOpenHashSet<ResourceLocation>();
        for (var binding : frozen) {
            if (!ids.add(binding.type().id())) {
                throw new IllegalArgumentException("Duplicate overlay codec: " + binding.type().id());
            }
        }
        return new ProcessComponentCodec<>() {
            @Override
            public NBTTagCompound encode(RecipeProcess process, RecipeOverlays component) {
                return new RecipeOverlayNbt(new RecipeDefinitionIndex(process), frozen).encode(component.snapshot());
            }

            @Override
            public void restore(RecipeProcess process, RecipeOverlays component, NBTTagCompound data) {
                component.restore(new RecipeOverlayNbt(new RecipeDefinitionIndex(process), frozen).decode(data));
            }
        };
    }

    static ProcessComponentCodec<ResourceInputLocks> locks(ResourceKeyNbt resources) {
        return new ProcessComponentCodec<>() {
            @Override
            public NBTTagCompound encode(RecipeProcess process, ResourceInputLocks component) {
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

            @Override
            public void restore(RecipeProcess process, ResourceInputLocks component, NBTTagCompound data) {
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

    static ProcessComponentCodec<RequirementAttributeState> requirementAttributes() {
        return new ProcessComponentCodec<>() {
            @Override
            public NBTTagCompound encode(RecipeProcess process, RequirementAttributeState component) {
                var snapshot = component.snapshot();
                var tag = new NBTTagCompound();
                tag.setTag("Owned", attributeReferences(snapshot.owned()));
                tag.setTag("Aborted", attributeReferences(snapshot.aborted()));
                return tag;
            }

            @Override
            public void restore(RecipeProcess process, RequirementAttributeState component, NBTTagCompound data) {
                StrictNbt.requireKeys(data, "Owned", "Aborted");
                component.restore(new RequirementAttributeState.Snapshot(attributeReferences(data, "Owned"), attributeReferences(data, "Aborted")));
            }
        };
    }

    private static NBTTagList attributeReferences(List<RequirementAttributeState.Reference> references) {
        var list = new NBTTagList();
        for (var reference : references) {
            var tag = new NBTTagCompound();
            tag.setTag("Path", RequirementPathNbt.encode(reference.path()));
            tag.setString("Attribute", reference.attribute().toString());
            list.appendTag(tag);
        }
        return list;
    }

    private static List<RequirementAttributeState.Reference> attributeReferences(NBTTagCompound data, String name) {
        var result = new ObjectArrayList<RequirementAttributeState.Reference>();
        var list = StrictNbt.requireList(data, name, Constants.NBT.TAG_COMPOUND);
        for (int index = 0; index < list.tagCount(); index++) {
            var tag = list.getCompoundTagAt(index);
            StrictNbt.requireKeys(tag, "Path", "Attribute");
            result.add(new RequirementAttributeState.Reference(RequirementPathNbt.read(tag), StrictNbt.requireId(tag, "Attribute")));
        }
        return result;
    }

    static ProcessComponentCodec<SelectiveState> selections() {
        return new ProcessComponentCodec<>() {
            @Override
            public NBTTagCompound encode(RecipeProcess process, SelectiveState component) {
                var index = new RecipeDefinitionIndex(process);
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
                var receipts = new NBTTagList();
                for (var receipt : component.abortReceipts()) {
                    var entry = new NBTTagCompound();
                    entry.setTag("Path", RequirementPathNbt.encode(receipt.selectionPath()));
                    var modifiers = new NBTTagList();
                    for (var reference : receipt.modifiers()) {
                        var modifier = new NBTTagCompound();
                        modifier.setString("Attribute", reference.attribute().toString());
                        modifier.setString("Id", reference.id());
                        modifiers.appendTag(modifier);
                    }
                    entry.setTag("Modifiers", modifiers);
                    var overlays = new NBTTagList();
                    for (var reference : receipt.overlays()) {
                        if (index.require(reference.path()) != reference.target()) {
                            throw new IllegalArgumentException("Aborted overlay has a foreign canonical target");
                        }
                        var overlay = new NBTTagCompound();
                        overlay.setTag("Path", RequirementPathNbt.encode(reference.path()));
                        overlay.setString("Layer", reference.layerId());
                        overlays.appendTag(overlay);
                    }
                    entry.setTag("Overlays", overlays);
                    receipts.appendTag(entry);
                }
                tag.setTag("AbortReceipts", receipts);
                return tag;
            }

            @Override
            public void restore(RecipeProcess process, SelectiveState component, NBTTagCompound data) {
                var index = new RecipeDefinitionIndex(process);
                StrictNbt.requireKeys(data, "Selections", "AbortReceipts");
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
                var receipts = new ObjectArrayList<SelectiveState.AbortReceipt>();
                var savedReceipts = StrictNbt.requireList(data, "AbortReceipts", Constants.NBT.TAG_COMPOUND);
                for (int i = 0; i < savedReceipts.tagCount(); i++) {
                    var entry = savedReceipts.getCompoundTagAt(i);
                    StrictNbt.requireKeys(entry, "Path", "Modifiers", "Overlays");
                    var modifiers = new ObjectArrayList<SelectiveState.ModifierReference>();
                    var savedModifiers = StrictNbt.requireList(entry, "Modifiers", Constants.NBT.TAG_COMPOUND);
                    for (int j = 0; j < savedModifiers.tagCount(); j++) {
                        var modifier = savedModifiers.getCompoundTagAt(j);
                        StrictNbt.requireKeys(modifier, "Attribute", "Id");
                        modifiers.add(new SelectiveState.ModifierReference(StrictNbt.requireId(modifier, "Attribute"), StrictNbt.requireString(modifier, "Id")));
                    }
                    var overlays = new ObjectArrayList<SelectiveState.OverlayReference>();
                    var savedOverlays = StrictNbt.requireList(entry, "Overlays", Constants.NBT.TAG_COMPOUND);
                    for (int j = 0; j < savedOverlays.tagCount(); j++) {
                        var overlay = savedOverlays.getCompoundTagAt(j);
                        StrictNbt.requireKeys(overlay, "Path", "Layer");
                        var path = RequirementPathNbt.read(overlay);
                        overlays.add(new SelectiveState.OverlayReference(path, index.require(path), StrictNbt.requireString(overlay, "Layer")));
                    }
                    receipts.add(new SelectiveState.AbortReceipt(RequirementPathNbt.read(entry), modifiers, overlays));
                }
                if (!selections.isEmpty() && !receipts.isEmpty()) {
                    throw new IllegalArgumentException("Saved selections mix active ownership with abort receipts");
                }
                component.restore(selections);
                if (!receipts.isEmpty()) {
                    component.restoreAbortReceipts(receipts);
                }
            }
        };
    }
}
