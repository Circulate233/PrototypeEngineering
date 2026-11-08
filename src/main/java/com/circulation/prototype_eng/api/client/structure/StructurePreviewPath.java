package com.circulation.prototype_eng.api.client.structure;

import org.jetbrains.annotations.Nullable;

/**
 * Child-edge path, so shared nodes and repeated equal IDs remain separate occurrences.
 */
public final class StructurePreviewPath {
    public static final StructurePreviewPath ROOT = new StructurePreviewPath(null, -1);
    @Nullable
    private final StructurePreviewPath parent;
    private final int child;
    private final int depth;
    private final int hash;

    private StructurePreviewPath(@Nullable StructurePreviewPath parent, int child) {
        this.parent = parent;
        this.child = child;
        depth = parent == null ? 0 : Math.incrementExact(parent.depth);
        hash = parent == null ? 1 : 31 * parent.hash + child;
    }

    public StructurePreviewPath child(int index) {
        if (index < 0) throw new IllegalArgumentException("Negative child edge index");
        return new StructurePreviewPath(this, index);
    }

    public @Nullable StructurePreviewPath parent() {
        return parent;
    }

    @SuppressWarnings("unused") // Public child-edge introspection for addon preview selection.
    public int childIndex() {
        return child;
    }

    public int depth() {
        return depth;
    }

    @Override
    public int hashCode() {
        return hash;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof StructurePreviewPath path) || depth != path.depth || hash != path.hash) return false;
        StructurePreviewPath left = this;
        StructurePreviewPath right = path;
        while (left != null && right != null) {
            if (left.child != right.child) return false;
            left = left.parent;
            right = right.parent;
        }
        return left == right;
    }
}
