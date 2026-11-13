package com.circulation.prototype_eng.mixin;

import com.circulation.prototype_eng.integration.crafttweaker.ZenComponentDataMembers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import stanhebben.zenscript.compiler.IEnvironmentGlobal;
import stanhebben.zenscript.expression.partial.IPartialExpression;
import stanhebben.zenscript.type.ZenTypeNative;
import stanhebben.zenscript.util.ZenPosition;

/**
 * Optional compiler bridge only; common data storage never links ZenScript.
 */
@Mixin(value = ZenTypeNative.class, remap = false)
public abstract class ZenComponentDataMemberMixin {
    @Inject(method = "getMember(Lstanhebben/zenscript/util/ZenPosition;Lstanhebben/zenscript/compiler/IEnvironmentGlobal;Lstanhebben/zenscript/expression/partial/IPartialExpression;Ljava/lang/String;)Lstanhebben/zenscript/expression/partial/IPartialExpression;",
        at = @At("HEAD"), cancellable = true, remap = false)
    private void prototype_eng$writableDataMember(ZenPosition position, IEnvironmentGlobal environment, IPartialExpression value,
                                                  String name, CallbackInfoReturnable<IPartialExpression> callback) {
        var nativeType = (ZenTypeNative) (Object) this;
        var member = ZenComponentDataMembers.member(nativeType, position, environment, value, name);
        if (member != null) callback.setReturnValue(member);
    }
}
