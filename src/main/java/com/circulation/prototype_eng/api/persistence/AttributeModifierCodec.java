package com.circulation.prototype_eng.api.persistence;

import com.circulation.prototype_eng.api.machine.attribute.AttributeModifier;

/**
 * Explicit persistence support for an immutable attribute modifier implementation. Implementations
 * must keep support decisions stable and follow the codec's input isolation contract.
 */
public interface AttributeModifierCodec extends NbtCodec<AttributeModifier> {
    boolean supports(AttributeModifier modifier);
}
