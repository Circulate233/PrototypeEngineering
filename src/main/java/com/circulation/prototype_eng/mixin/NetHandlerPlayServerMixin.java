package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.research.reward.RewardPlayerActions;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.Packet;
import net.minecraft.network.play.client.CPacketClickWindow;
import net.minecraft.network.play.client.CPacketCloseWindow;
import net.minecraft.network.play.client.CPacketCreativeInventoryAction;
import net.minecraft.network.play.client.CPacketCustomPayload;
import net.minecraft.network.play.client.CPacketEnchantItem;
import net.minecraft.network.play.client.CPacketPlaceRecipe;
import net.minecraft.network.play.client.CPacketPlayerDigging;
import net.minecraft.network.play.client.CPacketPlayerTryUseItem;
import net.minecraft.network.play.client.CPacketPlayerTryUseItemOnBlock;
import net.minecraft.network.play.client.CPacketUseEntity;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Preserves vanilla's original queue and guards the first MAIN-thread item effect. */
@Mixin(NetHandlerPlayServer.class)
public abstract class NetHandlerPlayServerMixin {
    @Unique private static final String MAIN_HANDOFF = "Lnet/minecraft/network/PacketThreadUtil;checkThreadAndEnqueue"
        + "(Lnet/minecraft/network/Packet;Lnet/minecraft/network/INetHandler;Lnet/minecraft/util/IThreadListener;)V";

    @Shadow @Final private MinecraftServer server;

    @Inject(method = "processClickWindow", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardClick(CPacketClickWindow packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processCloseWindow", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardClose(CPacketCloseWindow packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processCloseWindow", at = @At("TAIL"), require = 1, allow = 1)
    private void prototype_eng$syncRetainedCursor(CallbackInfo callback) {
        RewardPlayerActions.synchronizeProtectedCursor(server, (NetHandlerPlayServer) (Object) this);
    }

    @Inject(method = "processCreativeInventoryAction", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardCreative(CPacketCreativeInventoryAction packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processPlayerDigging", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardDigging(CPacketPlayerDigging packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processTryUseItemOnBlock", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardBlockUse(CPacketPlayerTryUseItemOnBlock packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processTryUseItem", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardItemUse(CPacketPlayerTryUseItem packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processUseEntity", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardEntityUse(CPacketUseEntity packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processEnchantItem", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardEnchant(CPacketEnchantItem packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "func_194308_a", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardPlaceRecipe(CPacketPlaceRecipe packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Inject(method = "processCustomPayload", at = @At(value = "INVOKE", target = MAIN_HANDOFF, shift = At.Shift.AFTER),
        cancellable = true, require = 1, allow = 1)
    private void prototype_eng$guardVanillaPayload(CPacketCustomPayload packet, CallbackInfo callback) {
        prototype_eng$guard(packet, callback);
    }

    @Unique private void prototype_eng$guard(Packet<?> packet, CallbackInfo callback) {
        if (RewardPlayerActions.reject(server, (NetHandlerPlayServer) (Object) this, packet)) callback.cancel();
    }
}
