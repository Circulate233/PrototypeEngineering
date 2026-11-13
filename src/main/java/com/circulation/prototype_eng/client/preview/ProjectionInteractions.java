package com.circulation.prototype_eng.client.preview;

import ae2.api.orientation.BlockOrientation;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewModel;
import com.circulation.prototype_eng.api.client.structure.StructurePreviewSettings;
import com.circulation.prototype_eng.api.client.structure.StructurePreviews;
import com.circulation.prototype_eng.api.client.structure.StructureProjectionOptions;
import com.circulation.prototype_eng.item.StructureToolItem;
import com.circulation.prototype_eng.localization.StructurePreviewText;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.client.Minecraft;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.client.event.MouseEvent;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.eventhandler.EventPriority;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.InputEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.input.Keyboard;

import java.util.Objects;

/**
 * Native world-only orientation controls and HUD; GUI/chat input never queues rotations.
 */
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
    private @Nullable BuilderProjection builderProjection;

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
        builderProjection = null;
        intent = StructurePreviews.client().projection();
        following = true;
    }

    /**
     * A controller-bound builder projection expires after thirty seconds, independently of its GUI.
     */
    public boolean projectBuilder(StructurePreviews service, ResourceLocation id, StructurePreviewSettings settings,
                                  BlockPos anchor, StructureProjectionOptions options, ItemStack tool, NBTTagCompound binding) {
        var mc = Minecraft.getMinecraft();
        var owner = BuilderBinding.read(binding);
        if (mc.player == null || mc.world == null || service != StructurePreviews.client()
            || !(tool.getItem() instanceof StructureToolItem item) || !item.builder() || owner == null
            || owner.dimension != mc.world.provider.getDimension() || !owner.position.equals(anchor)
            || !owner.structure.equals(id.toString())) return false;
        if (!service.projectFor(id, settings, anchor, options, 30_000)) return false;
        var current = service.projection();
        if (current == null) return false;
        builderProjection = new BuilderProjection(service, current, mc, tool, item, owner);
        following = false;
        intent = current;
        model = null;
        indexed.clear();
        indexCursor = 0;
        return true;
    }

    private void update(BlockOrientation orientation) {
        var service = StructurePreviews.client();
        var current = service.projection();
        if (current == null) return;
        if (service.replaceProjection(current, current.settings().withOrientation(orientation), current.options())) {
            intent = service.projection();
            if (builderProjection != null && builderProjection.intent == current) {
                if (intent == null) builderProjection = null;
                else builderProjection.intent = intent;
            }
        }
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    public void mouse(MouseEvent event) {
        int delta = event.getDwheel();
        if (delta == 0 || !Keyboard.isKeyDown(Keyboard.KEY_LSHIFT) && !Keyboard.isKeyDown(Keyboard.KEY_RSHIFT)) return;
        var mc = Minecraft.getMinecraft();
        var owner = builderProjection;
        if (mc.currentScreen != null || owner == null || !owner.present(mc) || owner.service.closed()
            || owner.service.projection() != owner.intent || mc.player == null
            || !owner.owns(mc.player.getHeldItemMainhand()) && !owner.owns(mc.player.getHeldItemOffhand())) return;
        var current = owner.intent;
        // This wheel belongs to this precise tool/intent even while its bounded model build finishes.
        event.setCanceled(true);
        var request = owner.service.request(current.id(), current.settings());
        var complete = request == null ? null : request.model();
        if (complete == null) return;
        int min = Math.min(0, complete.bounds().min().getY()), max = Math.max(0, complete.bounds().max().getY());
        var previous = current.options().layer();
        @Nullable StructurePreviewModel.Layer next;
        if (delta > 0) {
            if (previous == null || previous.y() < min) next = new StructurePreviewModel.Layer(min);
            else if (previous.y() >= max) next = null;
            else next = new StructurePreviewModel.Layer(previous.y() + 1);
        } else {
            if (previous == null || previous.y() > max) next = new StructurePreviewModel.Layer(max);
            else if (previous.y() <= min) next = null;
            else next = new StructurePreviewModel.Layer(previous.y() - 1);
        }
        var options = current.options().withLayer(next);
        if (owner.service.replaceProjection(current, current.settings(), options)) {
            var replacement = owner.service.projection();
            if (replacement == null) builderProjection = null;
            else owner.intent = replacement;
            intent = replacement;
        }
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
        var builder = builderProjection;
        if (builder != null && (builder.service.closed() || builder.service.projection() != builder.intent || !builder.present(mc))) {
            if (!builder.service.closed() && builder.service.projection() == builder.intent)
                builder.service.stopProjection();
            builderProjection = null;
        }
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

    private record BuilderBinding(String identity, int dimension, BlockPos position, String machine, String structure) {
        static @Nullable BuilderBinding read(@Nullable NBTTagCompound tag) {
            if (tag == null || !tag.getBoolean("Bound") || tag.getString("BoundMachineId").isBlank()
                || tag.getString("BoundStructureId").isBlank()) return null;
            return new BuilderBinding(tag.getString("BuilderIdentity"), tag.getInteger("BoundDim"),
                new BlockPos(tag.getInteger("BoundX"), tag.getInteger("BoundY"), tag.getInteger("BoundZ")),
                tag.getString("BoundMachineId"), tag.getString("BoundStructureId"));
        }
    }

    private static final class BuilderProjection {
        private final StructurePreviews service;
        private final Object player, world, connection;
        private final ItemStack initialTool;
        private final StructureToolItem item;
        private final BuilderBinding binding;
        private final @Nullable BuilderBinding initialBinding;
        private StructurePreviews.Projection intent;
        private boolean confirmed;

        BuilderProjection(StructurePreviews service, StructurePreviews.Projection intent, Minecraft mc,
                          ItemStack tool, StructureToolItem item, BuilderBinding binding) {
            this.service = service;
            this.intent = intent;
            player = mc.player;
            world = mc.world;
            connection = mc.getConnection();
            initialTool = tool;
            this.item = item;
            this.binding = binding;
            initialBinding = BuilderBinding.read(tool.getTagCompound());
        }

        boolean owns(ItemStack tool) {
            if (tool.isEmpty() || tool.getItem() != item) return false;
            var current = BuilderBinding.read(tool.getTagCompound());
            if (!binding.identity.isEmpty() && binding.equals(current)) {
                confirmed = true;
                return true;
            }
            // The server's GUI snapshot may precede its first inventory NBT packet. Only the exact
            // original object can bridge that initial lag; confirmed identities never fall back.
            return tool == initialTool && (!confirmed && Objects.equals(initialBinding, current) || binding.equals(current));
        }

        boolean present(Minecraft mc) {
            if (mc.player == null || mc.player != player || mc.world != world || mc.getConnection() != connection
                || !mc.player.isEntityAlive() || mc.player.getHealth() <= 0) return false;
            for (int slot = 0; slot < mc.player.inventory.getSizeInventory(); slot++)
                if (owns(mc.player.inventory.getStackInSlot(slot))) return true;
            return false;
        }
    }
}
