package com.circulation.prototype_eng.api.structure.json;

import com.circulation.prototype_eng.api.structure.StructurePredicate;

/**
 * Decode one spec into an immutable, declared-read predicate or relative-position validator.
 * The registry enforces the declared top-level fields; factories must validate nested objects and
 * semantics through the same strict parameter view, without side effects or live matching reads.
 */
@FunctionalInterface
public interface StructurePredicateFactory {
    StructurePredicate create(StructureJsonParameters parameters, StructureBlockResolver blocks);
}
