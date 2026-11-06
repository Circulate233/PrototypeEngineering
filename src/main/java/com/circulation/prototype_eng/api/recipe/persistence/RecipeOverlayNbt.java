package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.persistence.NbtCodec;
import com.circulation.prototype_eng.api.persistence.StrictNbt;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlayType;
import com.circulation.prototype_eng.api.recipe.overlay.RecipeOverlays;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirement;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectLists;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

import java.util.Collection;
import java.util.List;
import java.util.Objects;

final class RecipeOverlayNbt implements NbtCodec<List<RecipeOverlays.LayerSnapshot<?, ?>>> {
    private final RecipeDefinitionIndex index;
    private final Object2ObjectOpenHashMap<ResourceLocation, Binding<?, ?>> bindings = new Object2ObjectOpenHashMap<>();

    RecipeOverlayNbt(RecipeDefinitionIndex index, Collection<Binding<?, ?>> bindings) {
        this.index = index;
        for (var binding : bindings) {
            if (this.bindings.putIfAbsent(binding.type().id(), binding) != null) {
                throw new IllegalArgumentException("Duplicate overlay codec: " + binding.type().id());
            }
        }
    }

    @Override
    public NBTTagCompound encode(List<RecipeOverlays.LayerSnapshot<?, ?>> value) {
        var layers = new NBTTagList();
        for (var layer : value) {
            if (index.require(layer.path()) != layer.target()) {
                throw new IllegalArgumentException("Overlay target differs from the canonical recipe definition");
            }
            var entry = new NBTTagCompound();
            entry.setTag("Path", RequirementPathNbt.encode(layer.path()));
            entry.setString("Layer", layer.layerId());
            entry.setLong("Order", layer.order());
            entry.setString("Type", layer.type().id().toString());
            entry.setTag("Data", require(layer.type().id()).encode(layer).copy());
            layers.appendTag(entry);
        }
        var tag = new NBTTagCompound();
        tag.setTag("Layers", layers);
        return tag;
    }

    @Override
    public List<RecipeOverlays.LayerSnapshot<?, ?>> decode(NBTTagCompound tag) {
        StrictNbt.requireKeys(tag, "Layers");
        var list = StrictNbt.requireList(tag, "Layers", Constants.NBT.TAG_COMPOUND);
        var layers = new ObjectArrayList<RecipeOverlays.LayerSnapshot<?, ?>>();
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            StrictNbt.requireKeys(entry, "Path", "Layer", "Order", "Type", "Data");
            var path = RequirementPathNbt.read(entry);
            layers.add(require(StrictNbt.requireId(entry, "Type")).decode(entry, index.require(path)));
        }
        return ObjectLists.unmodifiable(layers);
    }

    private Binding<?, ?> require(ResourceLocation id) {
        var binding = bindings.get(id);
        if (binding == null) { throw new IllegalArgumentException("Unknown overlay codec: " + id); }
        return binding;
    }

    record Binding<C, D>(RecipeOverlayType<C, D> type, NbtCodec<D> codec) {
        Binding {
            Objects.requireNonNull(type);
            Objects.requireNonNull(codec);
        }

        @SuppressWarnings("unchecked") // Descriptor identity establishes both payload and patch types.
        NBTTagCompound encode(RecipeOverlays.LayerSnapshot<?, ?> layer) {
            if (layer.type() != type) { throw new IllegalArgumentException("Different overlay descriptors share " + type.id()); }
            return Objects.requireNonNull(codec.encode((D) layer.data()));
        }

        @SuppressWarnings("unchecked") // Checked immutable requirement-type identity before the payload cast.
        RecipeOverlays.LayerSnapshot<C, D> decode(NBTTagCompound entry, RecipeRequirement<?> target) {
            if (target.type() != type.requirementType()) { throw new IllegalArgumentException("Overlay requirement type mismatch"); }
            return new RecipeOverlays.LayerSnapshot<>(RequirementPathNbt.read(entry), (RecipeRequirement<C>) target,
                StrictNbt.requireString(entry, "Layer"), StrictNbt.requireLong(entry, "Order"), type,
                Objects.requireNonNull(codec.decode(StrictNbt.requireCompound(entry, "Data").copy())));
        }
    }
}
