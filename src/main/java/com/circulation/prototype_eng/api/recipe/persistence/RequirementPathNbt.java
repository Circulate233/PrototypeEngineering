package com.circulation.prototype_eng.api.recipe.persistence;

import com.circulation.prototype_eng.api.recipe.process.RequirementPath;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ResourceLocation;
import net.minecraftforge.common.util.Constants;

/**
 * Structured ancestry avoids delimiter parsing and collisions between nested requirement paths.
 */
final class RequirementPathNbt {
    private RequirementPathNbt() {
    }

    static NBTTagList encode(RequirementPath path) {
        var ancestry = new ObjectArrayList<RequirementPath>();
        for (var node = path; node != null; node = node.parent()) {
            ancestry.add(node);
        }
        var result = new NBTTagList();
        for (int i = ancestry.size() - 1; i >= 0; i--) {
            var node = ancestry.get(i);
            var entry = new NBTTagCompound();
            entry.setString("Type", node.typeId().toString());
            entry.setString("Id", node.requirementId().toString());
            result.appendTag(entry);
        }
        return result;
    }

    static RequirementPath decode(NBTTagList list) {
        if (list.isEmpty()) {
            throw new IllegalArgumentException("Empty requirement path");
        }
        RequirementPath path = null;
        for (int i = 0; i < list.tagCount(); i++) {
            var entry = list.getCompoundTagAt(i);
            path = new RequirementPath(path, new ResourceLocation(entry.getString("Type")), new ResourceLocation(entry.getString("Id")));
        }
        return path;
    }

    static RequirementPath read(NBTTagCompound tag) {
        return decode(tag.getTagList("Path", Constants.NBT.TAG_COMPOUND));
    }
}
