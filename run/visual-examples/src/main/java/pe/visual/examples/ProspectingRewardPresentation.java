package pe.visual.examples;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.GenericStack;
import com.circulation.prototype_eng.api.research.reward.RewardPresentation;
import com.circulation.prototype_eng.api.research.reward.RewardRenderContext;
import net.minecraft.network.PacketBuffer;
import net.minecraft.util.ResourceLocation;

record ProspectingRewardPresentation(GenericStack pickaxe, GenericStack bow) implements RewardPresentation {
    static final ResourceLocation TYPE = new ResourceLocation(VisualExamples.MOD_ID, "prospecting_reward");
    private static final int GAP = 6;
    private static final int SLOT_SIZE = 36;

    static ProspectingRewardPresentation read(PacketBuffer buffer) {
        return new ProspectingRewardPresentation(readStack(buffer), readStack(buffer));
    }

    void write(PacketBuffer buffer) {
        writeStack(buffer, pickaxe);
        writeStack(buffer, bow);
    }

    @Override
    public ResourceLocation type() {
        return TYPE;
    }

    @Override
    public double height(RewardRenderContext context, double width) {
        return 2 * Math.min(SLOT_SIZE, width) + GAP;
    }

    @Override
    public void render(RewardRenderContext context, double x, double y, double width) {
        double size = Math.min(SLOT_SIZE, width);
        tool(context, pickaxe, x, y, width, size);
        tool(context, bow, x, y + size + GAP, width, size);
    }

    private static void tool(RewardRenderContext context, GenericStack stack, double x, double y, double width, double size) {
        context.item(stack, x, y, size);
        if (width > size + GAP) {
            context.text(stack.what().getDisplayName().getFormattedText(), x + size + GAP, y + (size - 11) / 2, width - size - GAP);
        }
    }

    private static void writeStack(PacketBuffer buffer, GenericStack stack) {
        AEKey.writeKey(buffer, stack.what());
        buffer.writeVarLong(stack.amount());
    }

    private static GenericStack readStack(PacketBuffer buffer) {
        var key = AEKey.readKey(buffer);
        long amount = buffer.readVarLong();
        if (key == null || amount <= 0) throw new IllegalArgumentException("Invalid prospecting reward stack");
        return new GenericStack(key, amount);
    }
}
