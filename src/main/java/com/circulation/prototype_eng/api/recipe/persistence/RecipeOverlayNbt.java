package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.List;
import java.util.Map;

final class RecipeOverlayNbt {
    private RecipeOverlayNbt() {
    }

    static NBTTagCompound encode(List<RecipeOverlays.LayerSnapshot<?, ?>> value,
                                 Map<ResourceLocation, Binding<?, ?>> bindings) {
        var layers = new NBTTagList();
        for (int index1 = 0; index1 < value.size(); index1++) {
            var layer = value.get(index1);
            var entry = new NBTTagCompound();
            entry.setTag("Path", RequirementPathNbt.encode(layer.path()));
            entry.setString("Layer", layer.layerId());
            entry.setLong("Order", layer.order());
            entry.setString("Type", layer.type().id().toString());
            entry.setTag("Data", require(bindings, layer.type().id()).encode(layer));
            layers.appendTag(entry);
        }
        var tag = new NBTTagCompound();
        tag.setTag("Layers", layers);
        return tag;
    }

    static List<RecipeOverlays.LayerSnapshot<?, ?>> decode(NBTTagCompound tag, RecipeDefinitionIndex index,
                                                           Map<ResourceLocation, Binding<?, ?>> bindings) {
        var list = tag.getTagList("Layers", Constants.NBT.TAG_COMPOUND);
        var layers = new ObjectArrayList<RecipeOverlays.LayerSnapshot<?, ?>>(list.tagCount());
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            var path = RequirementPathNbt.read(entry);
            layers.add(require(bindings, new ResourceLocation(entry.getString("Type")))
                .decode(entry, path, index.require(path)));
        }
        return layers;
    }

    private static Binding<?, ?> require(Map<ResourceLocation, Binding<?, ?>> bindings, ResourceLocation id) {
        var binding = bindings.get(id);
        if (binding == null) {
            throw new IllegalArgumentException("Unknown overlay codec: " + id);
        }
        return binding;
    }

    record Binding<C, D>(RecipeOverlayType<C, D> type, NbtCodec<D> codec) {
        @SuppressWarnings("unchecked")
        NBTTagCompound encode(RecipeOverlays.LayerSnapshot<?, ?> layer) {
            return codec.encode((D) layer.data());
        }

        @SuppressWarnings("unchecked")
        RecipeOverlays.LayerSnapshot<C, D> decode(NBTTagCompound entry, RequirementPath path, RecipeRequirement<?> target) {
            return new RecipeOverlays.LayerSnapshot<>(path, (RecipeRequirement<C>) target,
                entry.getString("Layer"), entry.getLong("Order"), type,
                codec.decode(entry.getCompoundTag("Data")));
        }
    }
}
