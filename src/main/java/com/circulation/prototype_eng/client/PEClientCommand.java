package com.circulation.prototype_eng.client;

import com.circulation.prototype_eng.client.preview.StructurePreviewCommand;
import com.circulation.prototype_eng.localization.CommandText;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.command.ICommandSender;
import net.minecraftforge.client.IClientCommand;
import net.minecraftforge.event.CommandEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.server.command.CommandTreeBase;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class PEClientCommand extends CommandTreeBase implements IClientCommand {
    public PEClientCommand() {
        addSubcommand(new StructurePreviewCommand());
    }

    @Override
    public String getName() {
        return "pe";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return CommandText.Usage.getTranslationKey();
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public boolean allowUsageWithoutPrefix(ICommandSender sender, String message) {
        return false;
    }

    @SubscribeEvent
    public void route(CommandEvent event) {
        if (event.getCommand() != this) return;
        var arguments = event.getParameters();
        // A canceled client CommandEvent returns 0, so Forge sends the original command to the server.
        if (arguments.length == 0 || getSubCommand(arguments[0]) == null) event.setCanceled(true);
    }
}
