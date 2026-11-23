package com.circulation.prototype_eng.structure.tool;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.command.PECommand;
import com.circulation.prototype_eng.localization.CommandText;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.command.CommandBase;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import javax.annotation.Nullable;
import javax.annotation.ParametersAreNonnullByDefault;
import java.io.IOException;
import java.util.List;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class BuilderCommand extends CommandBase {
    @Override
    public String getName() {
        return "builder";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return CommandText.BuilderUsage.getTranslationKey();
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
    public void execute(MinecraftServer server, ICommandSender sender, String[] arguments) throws CommandException {
        if (arguments.length == 0) throw new WrongUsageException(getUsage(sender));
        try {
            int budgetMillis = switch (arguments[0]) {
                case "reload" -> {
                    if (arguments.length != 1) throw new WrongUsageException(getUsage(sender));
                    yield ConstructionConfig.reload();
                }
                case "budget" -> {
                    if (arguments.length != 2) throw new WrongUsageException(getUsage(sender));
                    yield ConstructionConfig.setBudgetMillis(parseInt(arguments[1], 1));
                }
                default -> throw new WrongUsageException(getUsage(sender));
            };
            sender.sendMessage(CommandText.BuilderUpdated.text(budgetMillis));
        } catch (IOException | RuntimeException failure) {
            PrototypeEngineering.LOGGER.error("Cannot update construction configuration", failure);
            throw new CommandException(CommandText.BuilderUpdateFailed.getTranslationKey(), failure.getMessage());
        }
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] arguments, @Nullable BlockPos position) {
        return arguments.length == 1 ? getListOfStringsMatchingLastWord(arguments, "budget", "reload") : List.of();
    }
}
