package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.lwjgl.input.Keyboard;

/** Native world-only orientation controls and HUD; GUI/chat input never queues rotations. */
@SuppressWarnings("resource") // The client lifecycle owns the borrowed shared preview service.
public final class ProjectionInteractions {
    public static final ProjectionInteractions INSTANCE = new ProjectionInteractions();
    private final KeyBinding lock = key(StructurePreviewText.KeyLock, Keyboard.KEY_R);
    private final KeyBinding positive = key(StructurePreviewText.KeyRotatePositive, Keyboard.KEY_RBRACKET);
    private final KeyBinding negative = key(StructurePreviewText.KeyRotateNegative, Keyboard.KEY_LBRACKET);
    private final Object2ObjectOpenHashMap<BlockPos, StructurePreviewModel.Cell> indexed = new Object2ObjectOpenHashMap<>();
    private StructurePreviews.Projection intent;
    private StructurePreviewModel model;
    private int indexCursor;
    private boolean following;

    private ProjectionInteractions() {
    }

    private static KeyBinding key(StructurePreviewText text, int key) {
        return new KeyBinding(text.getTranslationKey(), key, StructurePreviewText.KeyCategory.getTranslationKey());
    }

    @SuppressWarnings("StatementWithEmptyBody")
    // Each isPressed consumes one queued native press; GUI input must not leak into world controls.
    private static void drain(KeyBinding key) {
        while (key.isPressed()) { /* Consume every pending press. */ }
    }

    public void registerKeys() {
        ClientRegistry.registerKeyBinding(lock);
        ClientRegistry.registerKeyBinding(positive);
        ClientRegistry.registerKeyBinding(negative);
    }

    public void follow() {
        intent = StructurePreviews.client().projection();
        following = true;
    }

    private void update(BlockOrientation orientation) {
        var service = StructurePreviews.client();
        var current = service.projection();
        if (current == null) return;
        var s = current.settings();
        service.project(current.id(), new StructurePreviewSettings(orientation, s.sliceCounts(), s.maxOccurrences(), s.maxCellVisits(), s.displayOptions()), current.anchor(), current.options());
        intent = service.projection();
    }

    @SubscribeEvent
    public void keys(InputEvent.KeyInputEvent event) {
        var mc = Minecraft.getMinecraft();
        boolean toggle = lock.isPressed(), plus = positive.isPressed(), minus = negative.isPressed();
        if (mc.currentScreen != null) {
            drain(lock);
            drain(positive);
            drain(negative);
            return;
        }
        if (mc.player == null) return;
        var current = StructurePreviews.client().projection();
        if (current == null) return;
        if (current != intent) {
            following = false;
            intent = current;
        }
        if (toggle) {
            following = !following;
            if (following) update(BlockOrientation.get(mc.player.getHorizontalFacing(), EnumFacing.UP));
        }
        if (plus || minus) {
            following = false;
            EnumFacing axis = (Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) ? EnumFacing.EAST
                : (Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL)) ? EnumFacing.SOUTH : EnumFacing.UP;
            update(ProjectionOrientations.rotate(current.settings().orientation(), plus ? axis : axis.getOpposite()));
        }
    }

    @SubscribeEvent
    public void tick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.START) return;
        var mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.world == null) {
            model = null;
            indexed.clear();
            return;
        }
        var service = StructurePreviews.client();
        var current = service.projection();
        if (current != intent) {
            following = false;
            intent = current;
        }
        if (current == null) {
            model = null;
            indexed.clear();
            return;
        }
        if (following && mc.currentScreen == null) {
            var next = BlockOrientation.get(mc.player.getHorizontalFacing(), EnumFacing.UP);
            if (next != current.settings().orientation()) {
                update(next);
                current = service.projection();
            }
        }
        if (current == null) {
            model = null;
            indexed.clear();
            return;
        }
        var request = service.request(current.id(), current.settings());
        var complete = request == null ? null : request.model();
        if (model != complete) {
            model = complete;
            indexed.clear();
            indexCursor = 0;
        }
        if (complete != null) for (int visits = 0; visits < 2048 && indexCursor < complete.cells().size(); visits++) {
            var cell = complete.cells().get(indexCursor++);
            indexed.put(cell.position(), cell);
        }
    }

    @SubscribeEvent
    public void hud(RenderGameOverlayEvent.Text event) {
        var mc = Minecraft.getMinecraft();
        if (mc.player == null || mc.currentScreen != null || intent == null) return;
        var service = StructurePreviews.client();
        if (service.projection() != intent || model == null || !service.current(model)) return;
        var orientation = intent.settings().orientation();
        event.getLeft().add(StructurePreviewText.Orientation.getLocal((following ? StructurePreviewText.Follow : StructurePreviewText.Locked).getLocal(), orientation.rotate(EnumFacing.NORTH), orientation.rotate(EnumFacing.UP)));
        var hit = ProjectionRayMarcher.pick(intent.anchor(), mc.player.getPositionEyes(mc.getRenderPartialTicks()), mc.player.getLook(mc.getRenderPartialTicks()), intent.options().maxDistance(), 512,
            position -> {
                var cell = indexed.get(position);
                return cell != null && (intent.options().layer() == null || intent.options().layer().includes(cell));
            });
        if (hit == null) return;
        var cell = indexed.get(hit.subtract(intent.anchor()));
        var display = cell.display();
        event.getLeft().add(StructurePreviewText.Position.getLocal(hit.getX(), hit.getY(), hit.getZ()));
        event.getLeft().add(StructurePreviewText.Material.getLocal(display == null || !display.complete() ? StructurePreviewText.Unknown.getLocal() : display.key()));
    }
}
