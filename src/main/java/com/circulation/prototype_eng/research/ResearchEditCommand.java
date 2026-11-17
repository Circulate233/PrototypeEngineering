package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import mcp.MethodsReturnNonnullByDefault;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

/**
 * Administrator editing remains an explicit command; regular opening is supplied by addons.
 */
@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class ResearchEditCommand extends CommandBase {
    @Override
    public String getName() {
        return "pe_research_edit";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/pe_research_edit [player]";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length > 1) throw new WrongUsageException(getUsage(sender));
        if (args.length == 0) ResearchGuiAccess.openEditor(getCommandSenderAsPlayer(sender));
        else ResearchGuiAccess.openEditor(sender, getPlayer(server, sender, args[0]));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos target) {
        return args.length == 1 ? getListOfStringsMatchingLastWord(args, server.getOnlinePlayerNames()) : List.of();
    }

    @Override
    public boolean isUsernameIndex(String[] args, int index) {
        return index == 0;
    }
}
