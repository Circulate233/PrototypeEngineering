package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.command.PECommand;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.TextComponentTranslation;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class ResearchReloadCommand extends CommandBase {
    @Override
    public String getName() {
        return "reload";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return "/pe research reload";
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
        return PECommand.canAdminister(sender);
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws CommandException {
        if (args.length != 0) throw new WrongUsageException(getUsage(sender));
        try {
            ServerResearch.reloadCatalog(server);
        } catch (RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Cannot reload research catalog", failure);
            throw new CommandException("commands.prototype_eng.research_reload.failed");
        }
        sender.sendMessage(new TextComponentTranslation("commands.prototype_eng.research_reload.success"));
    }
}
