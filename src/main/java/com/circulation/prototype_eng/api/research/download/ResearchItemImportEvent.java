package com.circulation.prototype_eng.api.research.download;

import org.jetbrains.annotations.Nullable;

/**
 * Computes additive research and one output stack without side effects. Null means no matching
 * conversion. The result and every retained item or map remain read-only for the operation lifetime.
 */
@FunctionalInterface
public interface ResearchItemImportEvent {
    @Nullable ResearchItemImportEvaluation createOutput(ResearchItemImportContext context);
}
