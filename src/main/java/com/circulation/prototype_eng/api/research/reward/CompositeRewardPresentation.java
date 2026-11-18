package com.circulation.prototype_eng.api.research.reward;

import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;

import java.util.List;

public record CompositeRewardPresentation(List<RewardPresentation> children) implements RewardPresentation {
    public static final ResourceLocation TYPE = new ResourceLocation(Tags.MOD_ID, "reward_composite");
    private static final int GAP = 4;

    public CompositeRewardPresentation {
        children = List.copyOf(children);
    }

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    @Override
    public double height(RewardRenderContext context, double width) {
        return layout(context, 0, 0, width, false);
    }

    @Override
    public void render(RewardRenderContext context, double x, double y, double width) {
        layout(context, x, y, width, true);
    }

    private double layout(RewardRenderContext context, double x, double y, double width, boolean draw) {
        double left = 0;
        double top = 0;
        double rowHeight = 0;
        for (var child : children) {
            double childWidth = child.width(context, width);
            if (!(childWidth > 0 && childWidth <= width)) throw new IllegalArgumentException("Invalid reward presentation width: " + child.type());
            double height = child.height(context, childWidth);
            if (!Double.isFinite(height) || height < 0) throw new IllegalArgumentException("Invalid reward presentation height: " + child.type());
            if (height == 0) continue;
            if (left > 0 && left + childWidth > width) {
                top += rowHeight + GAP;
                left = 0;
                rowHeight = 0;
            }
            if (draw) child.render(context, x + left, y + top, childWidth);
            rowHeight = Math.max(rowHeight, height);
            left += childWidth + GAP;
        }
        return top + rowHeight;
    }
}
