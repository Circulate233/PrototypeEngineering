package com.circulation.prototype_eng.api.persistence;

import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraftforge.common.util.Constants;

import java.util.List;

/**
 * Shared strict business-result schema for process and host state.
 */
public final class ProcessResultNbt implements NbtCodec<ProcessResult> {
    public static final ProcessResultNbt INSTANCE = new ProcessResultNbt();

    private ProcessResultNbt() {
    }

    private static void writeDetails(NBTTagCompound tag, String kind, String reason, List<String> arguments) {
        tag.setString("Kind", kind);
        tag.setString("Reason", reason);
        var list = new NBTTagList();
        for (int index1 = 0; index1 < arguments.size(); index1++) {
            var argument = arguments.get(index1);
            list.appendTag(new NBTTagString(argument));
        }
        tag.setTag("Arguments", list);
    }

    @Override
    public NBTTagCompound encode(ProcessResult value) {
        var tag = new NBTTagCompound();
        switch (value) {
            case ProcessResult.Success ignored -> tag.setString("Kind", "SUCCESS");
            case ProcessResult.Blocked(var reason, var arguments) -> writeDetails(tag, "BLOCKED", reason, arguments);
            case ProcessResult.Failure(var reason, var arguments) -> writeDetails(tag, "FAILURE", reason, arguments);
        }
        return tag;
    }

    @Override
    public ProcessResult decode(NBTTagCompound tag) {
        var kind = tag.getString("Kind");
        if (kind.equals("SUCCESS")) {
            return ProcessResult.SUCCESS;
        }
        var list = tag.getTagList("Arguments", Constants.NBT.TAG_STRING);
        var arguments = new ObjectArrayList<String>(list.tagCount());
        for (int index = 0; index < list.tagCount(); index++) {
            arguments.add(list.getStringTagAt(index));
        }
        var reason = tag.getString("Reason");
        return switch (kind) {
            case "BLOCKED" -> new ProcessResult.Blocked(reason, arguments);
            case "FAILURE" -> new ProcessResult.Failure(reason, arguments);
            default -> throw new IllegalArgumentException("Unknown saved process result");
        };
    }
}
