package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardGroup;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenResource;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.util.ResourceLocation;
import java.util.Locale;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;

/** Ordered children of one choice; Java-provided display metadata is preserved by wrapped groups. */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchReward")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public final class ZenResearchReward {
 private final ResourceLocation id; private final ObjectArrayList<RewardChild> children=new ObjectArrayList<>();
 public ZenResearchReward(RewardGroup group) { id=Objects.requireNonNull(group).id(); original=group; }
 private RewardGroup original;
 private ZenResearchReward(String id) { this.id=new ResourceLocation(id); }
 @ZenMethod public static ZenResearchReward create(String id) { return new ZenResearchReward(id); }
 @ZenMethod public ZenResearchReward item(String childId,ZenResource resource) {
  open(); var stack=Objects.requireNonNull(resource).stack(); children.add(new RewardChild.Item(new ResourceLocation(childId),stack.what(),stack.amount())); return this;
 }
 @ZenMethod public ZenResearchReward event(String childId,ZenResearchRewardCallback callback) {
  open(); Objects.requireNonNull(callback); children.add(new RewardChild.Event(new ResourceLocation(childId),(key,context) ->
   RewardEventAction.Result.valueOf(callback.deliver(new ZenResearchRewardEvent(key,context)).toUpperCase(Locale.ROOT)))); return this;
 }
 private void open() { if(original!=null) throw new IllegalStateException("Wrapped reward groups are immutable"); }
 public RewardGroup value() { return original==null ? new RewardGroup(id,children) : original; }
}
