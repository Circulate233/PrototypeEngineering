package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.gui.ResearchGuiAccess;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import mcp.MethodsReturnNonnullByDefault;

import javax.annotation.ParametersAreNonnullByDefault;

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
        return "/pe_research_edit";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) throw new CommandException(getUsage(sender));
        ResearchGuiAccess.openEditor(getCommandSenderAsPlayer(sender));
    }
}
