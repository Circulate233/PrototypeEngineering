package com.circulation.prototype_eng.api.recipe.persistence;

import ae2.api.stacks.AEKey;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.process.RequirementAttributeState;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.process.ResourceInputLocks;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeStage;
import com.circulation.prototype_eng.api.recipe.selective.SelectiveState;
import it.unimi.dsi.fastutil.objects.Object2ObjectLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.List;

final class BuiltinProcessCodecs {
    private BuiltinProcessCodecs() {
    }

    static ProcessComponentCodec<RecipeOverlays> overlays(List<RecipeOverlayNbt.Binding<?, ?>> bindings) {
        var codecs = new Object2ObjectOpenHashMap<ResourceLocation, RecipeOverlayNbt.Binding<?, ?>>(bindings.size());
        for (int index2 = 0; index2 < bindings.size(); index2++) {
            var binding = bindings.get(index2);
            if (codecs.putIfAbsent(binding.type().id(), binding) != null) {
                throw new IllegalArgumentException("Duplicate overlay codec: " + binding.type().id());
            }
        }
        return new ProcessComponentCodec<>() {
            @Override
            public NBTTagCompound encode(RecipeProcess process, RecipeOverlays component) {
                return RecipeOverlayNbt.encode(component.snapshot(), codecs);
            }

            @Override
            public void restore(RecipeProcess process, RecipeOverlays component, NBTTagCompound data) {
                component.restore(RecipeOverlayNbt.decode(data, process.definitionIndex(), codecs));
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
                var list = data.getTagList("Choices", Constants.NBT.TAG_COMPOUND);
                var choices = new Object2ObjectLinkedOpenHashMap<ResourceInputLocks.Key, AEKey>(6);
                for (int i = 0; i < list.tagCount(); i++) {
                    var entry = list.getCompoundTagAt(i);
                    var key = new ResourceInputLocks.Key(RequirementPathNbt.read(entry),
                        RecipeStage.valueOf(entry.getString("Stage")),
                        ResourceInputLocks.Kind.valueOf(entry.getString("Kind")), entry.getInteger("Group"));
                    if (choices.putIfAbsent(key, resources.decode(entry.getCompoundTag("Key"))) != null) {
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
                component.restore(new RequirementAttributeState.Snapshot(attributeReferences(data, "Owned"), attributeReferences(data, "Aborted")));
            }
        };
    }

    private static NBTTagList attributeReferences(List<RequirementAttributeState.Reference> references) {
        var list = new NBTTagList();
        for (int index3 = 0; index3 < references.size(); index3++) {
            var reference = references.get(index3);
            var tag = new NBTTagCompound();
            tag.setTag("Path", RequirementPathNbt.encode(reference.path()));
            tag.setString("Attribute", reference.attribute().toString());
            list.appendTag(tag);
        }
        return list;
    }

    private static List<RequirementAttributeState.Reference> attributeReferences(NBTTagCompound data, String name) {
        var list = data.getTagList(name, Constants.NBT.TAG_COMPOUND);
        var result = new ObjectArrayList<RequirementAttributeState.Reference>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            var tag = list.getCompoundTagAt(index);
            result.add(new RequirementAttributeState.Reference(RequirementPathNbt.read(tag), new ResourceLocation(tag.getString("Attribute"))));
        }
        return result;
    }

    static ProcessComponentCodec<SelectiveState> selections() {
        return new ProcessComponentCodec<>() {
            @Override
            public NBTTagCompound encode(RecipeProcess process, SelectiveState component) {
                var list = new NBTTagList();
                component.snapshot().forEach((path, selection) -> {
                    var entry = new NBTTagCompound();
                    entry.setTag("Path", RequirementPathNbt.encode(path));
                    entry.setInteger("Index", selection.index());
                    entry.setBoolean("Committed", selection.committed());
                    entry.setBoolean("Completed", selection.completed());
                    var modifiers = new NBTTagList();
                    var elements5 = selection.modifiers();
                    for (int index5 = 0; index5 < elements5.size(); index5++) {
                        var reference = elements5.get(index5);
                        var modifier = new NBTTagCompound();
                        modifier.setString("Attribute", reference.attribute().toString());
                        modifier.setString("Id", reference.id());
                        modifiers.appendTag(modifier);
                    }
                    entry.setTag("Modifiers", modifiers);
                    var overlays = new NBTTagList();
                    var elements7 = selection.overlays();
                    for (int index7 = 0; index7 < elements7.size(); index7++) {
                        var reference = elements7.get(index7);
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

                var elements = component.abortReceipts();
                for (int index1 = 0; index1 < elements.size(); index1++) {
                    var receipt = elements.get(index1);
                    var entry = new NBTTagCompound();
                    entry.setTag("Path", RequirementPathNbt.encode(receipt.selectionPath()));
                    var modifiers = new NBTTagList();
                    var elements4 = receipt.modifiers();
                    for (int index4 = 0; index4 < elements4.size(); index4++) {
                        var reference = elements4.get(index4);
                        var modifier = new NBTTagCompound();
                        modifier.setString("Attribute", reference.attribute().toString());
                        modifier.setString("Id", reference.id());
                        modifiers.appendTag(modifier);
                    }
                    entry.setTag("Modifiers", modifiers);
                    var overlays = new NBTTagList();
                    var elements6 = receipt.overlays();
                    for (int index6 = 0; index6 < elements6.size(); index6++) {
                        var reference = elements6.get(index6);
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
                var index = process.definitionIndex();
                var list = data.getTagList("Selections", Constants.NBT.TAG_COMPOUND);
                var selections = new Object2ObjectLinkedOpenHashMap<RequirementPath, SelectiveState.SelectionSnapshot>(6);
                for (int i = 0; i < list.tagCount(); i++) {
                    var entry = list.getCompoundTagAt(i);
                    var savedModifiers = entry.getTagList("Modifiers", Constants.NBT.TAG_COMPOUND);
                    var modifiers = new ObjectArrayList<SelectiveState.ModifierReference>(savedModifiers.tagCount());
                    for (int j = 0; j < savedModifiers.tagCount(); j++) {
                        var modifier = savedModifiers.getCompoundTagAt(j);
                        modifiers.add(new SelectiveState.ModifierReference(new ResourceLocation(modifier.getString("Attribute")),
                            modifier.getString("Id")));
                    }
                    var savedOverlays = entry.getTagList("Overlays", Constants.NBT.TAG_COMPOUND);
                    var overlays = new ObjectArrayList<SelectiveState.OverlayReference>(savedOverlays.tagCount());
                    for (int j = 0; j < savedOverlays.tagCount(); j++) {
                        var overlay = savedOverlays.getCompoundTagAt(j);
                        var path = RequirementPathNbt.read(overlay);
                        overlays.add(new SelectiveState.OverlayReference(path, index.require(path), overlay.getString("Layer")));
                    }
                    var selection = new SelectiveState.SelectionSnapshot(entry.getBoolean("Committed"),
                        entry.getInteger("Index"), entry.getBoolean("Completed"), modifiers, overlays);
                    if (selections.putIfAbsent(RequirementPathNbt.read(entry), selection) != null) {
                        throw new IllegalArgumentException("Duplicate saved selection");
                    }
                }
                var savedReceipts = data.getTagList("AbortReceipts", Constants.NBT.TAG_COMPOUND);
                var receipts = new ObjectArrayList<SelectiveState.AbortReceipt>(savedReceipts.tagCount());
                for (int i = 0; i < savedReceipts.tagCount(); i++) {
                    var entry = savedReceipts.getCompoundTagAt(i);
                    var savedModifiers = entry.getTagList("Modifiers", Constants.NBT.TAG_COMPOUND);
                    var modifiers = new ObjectArrayList<SelectiveState.ModifierReference>(savedModifiers.tagCount());
                    for (int j = 0; j < savedModifiers.tagCount(); j++) {
                        var modifier = savedModifiers.getCompoundTagAt(j);
                        modifiers.add(new SelectiveState.ModifierReference(new ResourceLocation(modifier.getString("Attribute")), modifier.getString("Id")));
                    }
                    var savedOverlays = entry.getTagList("Overlays", Constants.NBT.TAG_COMPOUND);
                    var overlays = new ObjectArrayList<SelectiveState.OverlayReference>(savedOverlays.tagCount());
                    for (int j = 0; j < savedOverlays.tagCount(); j++) {
                        var overlay = savedOverlays.getCompoundTagAt(j);
                        var path = RequirementPathNbt.read(overlay);
                        overlays.add(new SelectiveState.OverlayReference(path, index.require(path), overlay.getString("Layer")));
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
