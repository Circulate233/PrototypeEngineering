package com.circulation.prototype_eng.command;

import com.circulation.prototype_eng.localization.CommandText;
import com.circulation.prototype_eng.research.ResearchEditCommand;
import com.circulation.prototype_eng.research.ResearchReloadCommand;
import com.circulation.prototype_eng.scheduler.SchedulerCommand;
import com.circulation.prototype_eng.structure.reload.StructureReloadCommand;
import com.circulation.prototype_eng.structure.tool.BuilderCommand;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.server.command.CommandTreeBase;

import javax.annotation.ParametersAreNonnullByDefault;

@MethodsReturnNonnullByDefault
@ParametersAreNonnullByDefault
public final class PECommand extends CommandTreeBase {
    public PECommand() {
        addSubcommand(new BuilderCommand());
        addSubcommand(new Group("research", CommandText.ResearchUsage.getTranslationKey(),
            new ResearchEditCommand(), new ResearchReloadCommand()));
        addSubcommand(new Group("structures", "structure.prototype_eng.ReloadUsage", new StructureReloadCommand()));
        addSubcommand(new SchedulerCommand());
    }

    public static boolean canAdminister(ICommandSender sender) {
        return sender.canUseCommand(2, "pe");
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
        return 2;
    }

    private static final class Group extends CommandTreeBase {
        private final String name;
        private final String usage;

        private Group(String name, String usage, ICommand... commands) {
            this.name = name;
            this.usage = usage;
            for (var command : commands) addSubcommand(command);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public String getUsage(ICommandSender sender) {
            return usage;
        }

        @Override
        public int getRequiredPermissionLevel() {
            return 2;
        }

        @Override
        public boolean checkPermission(MinecraftServer server, ICommandSender sender) {
            return canAdminister(sender);
        }
    }
}
