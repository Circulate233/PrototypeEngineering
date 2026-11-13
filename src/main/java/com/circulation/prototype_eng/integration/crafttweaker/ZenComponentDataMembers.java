package com.circulation.prototype_eng.integration.crafttweaker;

import crafttweaker.api.data.IData;
import org.jetbrains.annotations.Nullable;
import stanhebben.zenscript.annotations.OperatorType;
import stanhebben.zenscript.compiler.IEnvironmentGlobal;
import stanhebben.zenscript.compiler.IEnvironmentMethod;
import stanhebben.zenscript.expression.Expression;
import stanhebben.zenscript.expression.ExpressionString;
import stanhebben.zenscript.expression.partial.IPartialExpression;
import stanhebben.zenscript.symbols.IZenSymbol;
import stanhebben.zenscript.type.ZenType;
import stanhebben.zenscript.type.ZenTypeNative;
import stanhebben.zenscript.util.ZenPosition;

/**
 * CT2 dynamic native getters otherwise return an Expression which cannot be assigned.
 */
public final class ZenComponentDataMembers {
    private ZenComponentDataMembers() {
    }

    /**
     * Preserve ordinary native methods/getters and every class other than this data adapter.
     */
    public static @Nullable IPartialExpression member(ZenTypeNative nativeType, ZenPosition position, IEnvironmentGlobal environment,
                                                      IPartialExpression value, String name) {
        if (nativeType.toJavaClass() != ZenComponentData.class || nativeType.getMembers().containsKey(name))
            return null;
        var receiver = value.eval(environment);
        var expansion = environment.getExpansion(nativeType.getName());
        if (expansion != null) {
            var member = expansion.instanceMember(position, environment, receiver, name);
            if (member != null) return member;
        }
        return new Member(nativeType, position, receiver, new ExpressionString(position, name), environment.getType(IData.class));
    }

    private record Member(ZenTypeNative nativeType, ZenPosition position, Expression receiver, Expression key,
                          ZenType type) implements IPartialExpression {
        @Override
        public Expression eval(IEnvironmentGlobal environment) {
            return nativeType.binary(position, environment, receiver, key, OperatorType.MEMBERGETTER);
        }

        @Override
        public Expression assign(ZenPosition position, IEnvironmentGlobal environment, Expression value) {
            return nativeType.trinary(position, environment, receiver, key, value, OperatorType.MEMBERSETTER);
        }

        @Override
        public IPartialExpression getMember(ZenPosition position, IEnvironmentGlobal environment, String name) {
            return type.getMember(position, environment, this, name);
        }

        @Override
        public Expression call(ZenPosition position, IEnvironmentMethod environment, Expression... arguments) {
            return eval(environment).call(position, environment, arguments);
        }

        @Override
        public IZenSymbol toSymbol() {
            return null;
        }

        @Override
        public ZenType getType() {
            return type;
        }

        @Override
        public ZenType toType(IEnvironmentGlobal environment) {
            environment.error(position, "A data member is a value, not a type");
            return ZenType.ANY;
        }

        @Override
        public ZenType[] predictCallTypes(int numArguments) {
            return type.predictCallTypes(numArguments);
        }
    }
}
