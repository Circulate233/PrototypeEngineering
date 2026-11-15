package com.circulation.prototype_eng.api.recipe.requirement;

import ae2.api.stacks.AEKey;
import ae2.api.stacks.AEKey2LongMap;
import com.circulation.prototype_eng.Tags;
import net.minecraft.util.ResourceLocation;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Source allocation for each independent input projection, after quantity scaling. Outputs keep
 * their ordinary routing. Source indices refer to the captured, identity-distinct input order;
 * they are not inventory slots, JVM lanes, or identities that may be saved for later execution.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class InputAllocation {
    public static final InputAllocation SPLITTABLE = new InputAllocation(Kind.SPLITTABLE,
        new ResourceLocation(Tags.MOD_ID, "splittable"), null);
    public static final InputAllocation SINGLE_SOURCE = new InputAllocation(Kind.SINGLE_SOURCE,
        new ResourceLocation(Tags.MOD_ID, "single_source"), null);
    private final Kind kind;
    private final ResourceLocation id;
    @Nullable
    private final Strategy strategy;

    private InputAllocation(Kind kind, ResourceLocation id, @Nullable Strategy strategy) {
        this.kind = kind;
        this.id = id;
        this.strategy = strategy;
    }

    /**
     * The strategy and its captured state must remain immutable for the definition's lifetime.
     */
    public static InputAllocation custom(ResourceLocation id, Strategy strategy) {
        return new InputAllocation(Kind.CUSTOM, id, strategy);
    }

    public Kind kind() {
        return kind;
    }

    public ResourceLocation id() {
        return id;
    }

    /**
     * Called by acquisition on its owner, once per frozen proposal, never under a storage lock.
     */
    public List<Portion> allocate(Context context) {
        if (strategy == null) throw new IllegalStateException("Built-in allocation has no custom callback");
        return strategy.allocate(context);
    }

    public enum Kind {SPLITTABLE, SINGLE_SOURCE, CUSTOM}

    @FunctionalInterface
    public interface Strategy {
        /**
         * Pure cooperative callback. Return positive portions totalling exactly context.amount(),
         * with no duplicate or unknown source index. Empty and partial proposals are invalid.
         * No world, storage, process, reservation or mutable context is supplied. Do not retain
         * external mutable state or perform effects: probes and later attempts may call this again.
         * The returned list and its entries must remain unchanged until this allocation is resolved.
         */
        List<Portion> allocate(Context context);
    }

    /**
     * Frozen facts only. A null resource denotes independent FE, never an artificial AEKey.
     */
    public interface Context {
        RecipeStage stage();

        int projection();

        @Nullable AEKey resource();

        long amount();

        List<Source> sources();
    }

    /**
     * Initial snapshot facts and availability remaining for this projection, addressed by source index.
     * resources contains every AEKey balance captured before this requirement's first projection;
     * FE sources have an empty resource map. Their available amount is the unreserved balance;
     * maxExtract reports only the external port limit and does not restrict machine allocation.
     * The revision describes the snapshot; reservations use the actual storage context.
     * The resources map is borrowed as an immutable snapshot and must not be changed after construction.
     */
    public record Source(int index, long revision, long available,
                         AEKey2LongMap resources, long energyAvailable, long maxExtract) {
        public Source {
            if (index < 0 || revision < 0 || available < 0 || energyAvailable < 0 || maxExtract < 0)
                throw new IllegalArgumentException("Invalid allocation source");
        }
    }

    public record Portion(int source, long amount) {
        public Portion {
            if (source < 0 || amount <= 0) throw new IllegalArgumentException("Invalid allocation portion");
        }
    }
}
