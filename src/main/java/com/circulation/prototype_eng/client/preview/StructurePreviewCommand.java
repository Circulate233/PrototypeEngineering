package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewScreens;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.api.machine.MachineTypeRegistry;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import mcp.MethodsReturnNonnullByDefault;
import net.minecraft.client.Minecraft;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.command.WrongUsageException;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentString;
import org.jetbrains.annotations.Nullable;

import javax.annotation.ParametersAreNonnullByDefault;
import java.util.List;

@ParametersAreNonnullByDefault
@MethodsReturnNonnullByDefault
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class StructurePreviewCommand extends CommandBase {
    @Override
    public String getName() {
        return "preview";
    }

    @Override
    public String getUsage(ICommandSender sender) {
        return StructurePreviewText.Usage.getLocal();
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 0;
    }

    @Override
    public void execute(MinecraftServer server, ICommandSender sender, String[] args) throws WrongUsageException {
        if (args.length == 0 || args.length > 3)
            throw new WrongUsageException(StructurePreviewText.Usage.getTranslationKey());
        var service = StructurePreviews.client();
        if (args[0].equals("stop") || args[0].equals("off")) {
            service.stopProjection();
            sender.sendMessage(new TextComponentString(StructurePreviewText.Stopped.getLocal()));
            return;
        }
        boolean gui = args[0].equals("gui");
        if (gui && (args.length < 2 || args.length > 3) || !gui && args.length != 1)
            throw new WrongUsageException(StructurePreviewText.Usage.getTranslationKey());
        var textId = args[gui ? 1 : 0];
        if (!textId.matches("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+"))
            throw new WrongUsageException(StructurePreviewText.Usage.getTranslationKey());
        var id = new ResourceLocation(textId);
        var player = Minecraft.getMinecraft().player;
        if (args.length == 3 && !args[2].matches("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+"))
            throw new WrongUsageException(StructurePreviewText.Usage.getTranslationKey());
        var machine = args.length == 3 ? MachineTypeRegistry.INSTANCE.get(new ResourceLocation(args[2])) : null;
        if (args.length == 3 && (machine == null || !machine.structureId().equals(id))) {
            sender.sendMessage(new TextComponentString(StructurePreviewText.MachineContextMismatch.getLocal()));
            return;
        }
        var settings = new StructurePreviewSettings(player == null ? BlockOrientation.NORTH_UP : BlockOrientation.get(player.getHorizontalFacing(), EnumFacing.UP));
        if (gui) {
            var mc = Minecraft.getMinecraft();
            var world = mc.world;
            var connection = mc.getConnection();
            var container = player == null ? null : player.openContainer;
            int window = container == null ? -1 : container.windowId;
            if (service.request(id, settings) == null) {
                sender.sendMessage(new TextComponentString(StructurePreviewText.Unavailable.getLocal()));
                return;
            }
            PreviewClientOpenQueue.INSTANCE.enqueue(() -> {
                if (player == null || container == null || mc.player != player || mc.world != world || mc.getConnection() != connection
                    || player.openContainer != container || container.windowId != window || mc.currentScreen != null)
                    return;
                boolean opened = StructurePreviewScreens.open(id, settings, machine);
                sender.sendMessage(new TextComponentString((opened ? StructurePreviewText.Started : StructurePreviewText.Unavailable).getLocal(id)));
            });
            return;
        }
        var anchor = StructurePreviewScreensAnchor.current();
        var target = StructurePreviewScreensAnchor.externalAt(id, anchor);
        boolean opened = service.project(id, target == null ? settings : settings.withOrientation(target.orientation()), anchor,
            new StructureProjectionOptions(StructureProjectionOptions.Style.BLOCK_MODEL, false, null, 2048, 2048, 4096, 64),
            target == null ? null : target.external());
        if (!opened) {
            sender.sendMessage(new TextComponentString(StructurePreviewText.Unavailable.getLocal()));
            return;
        }
        ProjectionInteractions.INSTANCE.projectionStarted();
        sender.sendMessage(new TextComponentString(StructurePreviewText.Started.getLocal(id)));
    }

    @Override
    public List<String> getTabCompletions(MinecraftServer server, ICommandSender sender, String[] args, @Nullable BlockPos target) {
        var choices = new ObjectArrayList<String>();
        if (args.length == 1) {
            choices.add("gui");
            choices.add("stop");
        }
        if (args.length == 1 || args.length == 2 && args[0].equals("gui")) {
            var view = StructurePreviews.client().catalogView();
            if (view != null) for (var id : view.entries().keySet()) choices.add(id.toString());
        }
        if (args.length == 3 && args[0].equals("gui") && args[1].matches("(?:[a-z0-9_.-]+:)?[a-z0-9/._-]+")) {
            var structure = new ResourceLocation(args[1]);
            var elements = MachineTypeRegistry.INSTANCE.types();
            for (int index = 0; index < elements.size(); index++) {
                var machine = elements.get(index);
                if (machine.structureId().equals(structure)) choices.add(machine.id().toString());
            }
        }
        return getListOfStringsMatchingLastWord(args, choices);
    }
}
