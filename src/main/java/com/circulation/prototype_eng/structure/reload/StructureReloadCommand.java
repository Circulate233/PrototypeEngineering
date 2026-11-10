package com.circulation.prototype_eng.structure.reload;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.localization.StructureText;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import org.jetbrains.annotations.NotNull;

/**
 * Actual administrative entry; accepted work reports completion only after the joined publication.
 */
public final class StructureReloadCommand extends CommandBase {
    @Override
    public @NotNull String getName() {
        return "pe_structures";
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
    public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] arguments) {
        if (arguments.length != 1 || !arguments[0].equals("reload")) {
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
