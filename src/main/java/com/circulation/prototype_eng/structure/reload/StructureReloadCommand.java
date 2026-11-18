package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.command.PECommand;
import com.circulation.prototype_eng.localization.StructureText;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

public final class StructureReloadCommand extends CommandBase {
    @Override
    public @NotNull String getName() {
        return "reload";
    }

    @Override
    public @NotNull String getUsage(@NotNull ICommandSender sender) {
        return StructureText.ReloadUsage.getTranslationKey();
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public boolean checkPermission(@NotNull MinecraftServer server, @NotNull ICommandSender sender) {
        return PECommand.canAdminister(sender);
    }

    @Override
    public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] arguments) {
        if (arguments.length != 0) {
            sender.sendMessage(StructureText.ReloadUsage.text());
            return;
        }
        ServerStructureReloads.reload(server, result -> {
            sender.sendMessage((!result.published() ? StructureText.ReloadFailed : result.failure() == null ? StructureText.Reloaded : StructureText.ReloadRefreshFailed).text(Long.toString(result.sequence())));
            if (result.failure() != null)
                PrototypeEngineering.LOGGER.error("Structure reload result at generation {}", result.sequence(), result.failure());
        });
    }
}
