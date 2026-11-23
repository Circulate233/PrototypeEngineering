package com.circulation.prototype_eng.command;

import com.circulation.prototype_eng.client.PEClientCommand;
import com.circulation.prototype_eng.localization.CommandText;
import com.circulation.prototype_eng.localization.SchedulerText;
import com.circulation.prototype_eng.localization.StructureText;
import net.minecraft.command.CommandException;
import net.minecraft.command.ICommand;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.NumberInvalidException;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import net.minecraftforge.event.CommandEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SuppressWarnings("DataFlowIssue")
class PECommandTest {
    @Test
    void permissionControlsNestedDispatchAndCompletionUsingTheSameRootPermission() throws Exception {
        var command = new PECommand();
        var sender = new Sender();
        sender.permission = 1;
        assertFalse(command.checkPermission(null, sender));
        assertTrue(command.getTabCompletions(null, sender, new String[]{""}, null).isEmpty());
        var denied = assertThrows(CommandException.class,
            () -> command.execute(null, sender, new String[]{"research", "edit"}));
        assertEquals("commands.generic.permission", denied.getMessage());
        var builderDenied = assertThrows(CommandException.class,
            () -> command.execute(null, sender, new String[]{"builder", "reload"}));
        assertEquals("commands.generic.permission", builderDenied.getMessage());

        sender.permission = 2;
        assertTrue(command.checkPermission(null, sender));
        assertEquals(List.of("builder", "research", "scheduler", "structures"),
            command.getTabCompletions(null, sender, new String[]{""}, null));
        assertEquals(List.of("budget", "reload"),
            command.getTabCompletions(null, sender, new String[]{"builder", ""}, null));
        assertEquals(List.of("edit", "reload"),
            command.getTabCompletions(null, sender, new String[]{"research", ""}, null));
        assertEquals(List.of("reload"),
            command.getTabCompletions(null, sender, new String[]{"structures", ""}, null));
        assertEquals(List.of("PLATFORM_POOL", "VIRTUAL_PER_TASK"),
            command.getTabCompletions(null, sender, new String[]{"scheduler", "strategy", ""}, null));
        command.execute(null, sender, new String[0]);
        assertEquals("commands.tree_base.available_subcommands",
            assertInstanceOf(TextComponentTranslation.class, sender.messages.getFirst()).getKey());
        var unknown = assertThrows(CommandException.class,
            () -> command.execute(null, sender, new String[]{"unknown"}));
        assertEquals("commands.tree_base.invalid_cmd.list_subcommands", unknown.getMessage());
    }

    @Test
    void nestedCommandsReceiveTheirOwnArgumentsAndPreserveThePlayerSelectorPosition() throws Exception {
        var command = new PECommand();
        var sender = new Sender();
        var editUsage = assertThrows(WrongUsageException.class,
            () -> command.execute(null, sender, new String[]{"research", "edit", "Alice", "Bob"}));
        assertEquals("/pe research edit [player]", editUsage.getMessage());
        var reloadUsage = assertThrows(WrongUsageException.class,
            () -> command.execute(null, sender, new String[]{"research", "reload", "extra"}));
        assertEquals("/pe research reload", reloadUsage.getMessage());
        var builderUsage = assertThrows(WrongUsageException.class,
            () -> command.execute(null, sender, new String[]{"builder", "reload", "extra"}));
        assertEquals(CommandText.BuilderUsage.getTranslationKey(), builderUsage.getMessage());
        assertThrows(NumberInvalidException.class,
            () -> command.execute(null, sender, new String[]{"builder", "budget", "0"}));

        command.execute(null, sender, new String[]{"structures", "reload", "extra"});
        assertEquals(StructureText.ReloadUsage.getTranslationKey(),
            assertInstanceOf(TextComponentTranslation.class, sender.messages.getFirst()).getKey());
        command.execute(null, sender, new String[]{"scheduler", "metrics", "invalid"});
        assertEquals(SchedulerText.Usage.getTranslationKey(),
            assertInstanceOf(TextComponentTranslation.class, sender.messages.getLast()).getKey());

        String[] selected = {"research", "edit", "@a"};
        assertTrue(command.isUsernameIndex(selected, 2));
        for (int index : new int[]{0, 1, 3}) assertFalse(command.isUsernameIndex(selected, index));
        assertFalse(command.isUsernameIndex(new String[]{"research", "reload", "@a"}, 2));
    }

    @Test
    void clientLeavesServerBranchesAndOriginalArgumentsForNativeForwarding() {
        var command = new PEClientCommand();
        var sender = new Sender();
        assertFalse(command.allowUsageWithoutPrefix(sender, "pe preview stop"));
        assertEquals(List.of("preview"),
            command.getTabCompletions(null, sender, new String[]{""}, null));
        assertTrue(command.getTabCompletions(null, sender, new String[]{"research", "edit", ""}, null).isEmpty());

        for (var arguments : List.of(new String[0], new String[]{"research", "edit", "Alice"},
            new String[]{"structures", "reload"}, new String[]{"scheduler", "status"}, new String[]{"builder", "budget", "20"},
            new String[]{"unknown"})) {
            var event = event(command, sender, arguments);
            command.route(event);
            assertTrue(event.isCanceled());
            assertSame(arguments, event.getParameters());
        }
        var preview = event(command, sender, new String[]{"preview", "stop"});
        command.route(preview);
        assertFalse(preview.isCanceled());
        var serverEvent = event(new PECommand(), sender, new String[]{"research", "reload"});
        command.route(serverEvent);
        assertFalse(serverEvent.isCanceled());
    }

    private static CommandEvent event(ICommand command, Sender sender, String[] arguments) {
        // Plain JUnit does not run Forge's @Cancelable bytecode transformation.
        return new CommandEvent(command, sender, arguments) {
            @Override
            public boolean isCancelable() {
                return true;
            }
        };
    }

    private static final class Sender implements ICommandSender {
        private final List<ITextComponent> messages = new ArrayList<>();
        private int permission = 2;

        @Override
        public @NotNull String getName() {
            return "PE test sender";
        }

        @Override
        public void sendMessage(@NotNull ITextComponent component) {
            messages.add(component);
        }

        @Override
        public boolean canUseCommand(int level, @NotNull String commandName) {
            return level == 0 || commandName.equals("pe") && permission >= level;
        }

        @Override
        public @NotNull World getEntityWorld() {
            throw new AssertionError("Command routing must not access a live world");
        }

        @Override
        public @Nullable MinecraftServer getServer() {
            return null;
        }
    }
}
