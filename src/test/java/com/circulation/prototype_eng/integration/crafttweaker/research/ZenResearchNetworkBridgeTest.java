package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.network.recipe.NetworkRecipeExtensions;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistrationEvent;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceType;
import com.circulation.prototype_eng.api.recipe.DefaultRecipePool;
import com.circulation.prototype_eng.api.recipe.scanning.RecipeCatalog;
import com.circulation.prototype_eng.api.registration.DefinitionRegistrations;
import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import com.circulation.prototype_eng.api.research.ResearchRegistrationEvent;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchServerConfiguration;
import com.circulation.prototype_eng.api.research.ResearchServerConfigurationEvent;
import com.circulation.prototype_eng.api.research.ResearchUnlockQuery;
import com.circulation.prototype_eng.api.research.TeamResearchStart;
import com.circulation.prototype_eng.api.research.TeamResearchView;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.reward.ResearchRewardContext;
import com.circulation.prototype_eng.api.research.reward.RewardChild;
import com.circulation.prototype_eng.api.research.reward.RewardEventAction;
import com.circulation.prototype_eng.api.research.reward.RewardEventKey;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkRecipeCallback;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkRecipeContext;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkRecipes;
import com.circulation.prototype_eng.integration.crafttweaker.network.ZenNetworkRequirements;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRecipe;
import com.circulation.prototype_eng.integration.crafttweaker.recipe.ZenRequirement;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResource;
import com.circulation.prototype_eng.integration.crafttweaker.resource.ZenComputingResources;
import crafttweaker.api.data.IData;
import crafttweaker.zenscript.expand.ExpandAnyDict;
import crafttweaker.zenscript.expand.ExpandBool;
import crafttweaker.zenscript.expand.ExpandDataStringDict;
import crafttweaker.zenscript.expand.ExpandInt;
import crafttweaker.zenscript.expand.ExpandString;
import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2LongOpenHashMap;
import it.unimi.dsi.fastutil.objects.Object2ObjectOpenHashMap;
import net.minecraft.util.ResourceLocation;
import org.junit.jupiter.api.Test;
import stanhebben.zenscript.ZenModule;
import stanhebben.zenscript.ZenParsedFile;
import stanhebben.zenscript.ZenTokener;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.impl.GenericCompileEnvironment;
import stanhebben.zenscript.impl.GenericErrorLogger;
import stanhebben.zenscript.impl.GenericRegistry;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZenResearchNetworkBridgeTest {
    private static ZenResearch definitionWrapper() {
        return ZenResearch.create("ct_research:late", 1, false, "ITEM_ONLY", 0,
            new ZenResearchStage[]{ZenResearchStage.create(1, 1, 0, ZenResearchFormula.create())}, ZenResearch.alwaysAllowed());
    }

    @Test
    void realCompilerRegistersResearchAndPermissionsThenAdmitsQueueAndRetainsExactRecipeGates() throws Exception {
        Harness.recipe = ZenRecipe.create("ct_research:recipe", "old", 5, new ZenRequirement[0]);
        var output = new ByteArrayOutputStream();
        var environment = new GenericCompileEnvironment();
        var registry = new GenericRegistry(environment, new GenericErrorLogger(new PrintStream(output, true, StandardCharsets.UTF_8)));
        for (var type : List.of(ZenComputingResource.class, ZenComputingResources.class,
            ZenResearch.class, ZenResearchStage.class, ZenResearchFormula.class, ZenResearchStartCondition.class,
            ZenResearchStartContext.class, ZenResearchReward.class, ZenResearchRewardCallback.class,
            ZenResearchRewardEvent.class, ZenResearchPermissionCallback.class, ZenResearchPermissionContext.class,
            ZenResearchServer.class, ZenNetworkRecipeCallback.class, ZenNetworkRecipeContext.class,
            ZenNetworkRequirements.class, ZenRecipe.class, ZenRequirement.class, IData.class, Harness.class))
            registry.registerNativeClass(type);
        for (var expansion : List.of(ExpandAnyDict.class, ExpandDataStringDict.class, ExpandInt.class, ExpandBool.class, ExpandString.class))
            registry.registerExpansion(expansion);
        var classes = new Object2ObjectOpenHashMap<String, byte[]>();
        var global = registry.makeGlobalEnvironment(classes);
        String script = """
            import mods.prototype_eng.resource.ComputingResource;
            import mods.prototype_eng.research.Research;
            import mods.prototype_eng.research.ResearchStage;
            import mods.prototype_eng.research.ResearchFormula;
            import mods.prototype_eng.research.ResearchReward;
            import mods.prototype_eng.research.ResearchServer;
            import tests.prototype_eng.ResearchHarness;
            var compute = ComputingResource.create("ct_research:compute", false);
            compute.register();
            var reward = ResearchReward.create("ct_research:choice").event("ct_research:event", function(event) {
                if event.groupId == "ct_research:choice" && event.level == 1 { return "RETRY_LATER"; }
                return "APPLIED";
            });
            var stage = ResearchStage.create(1, 2, 1, ResearchFormula.create().affine(compute, 3000000000L, 7))
                .reward(1, reward);
            Research.create("ct_research:research", 2, false, "ITEM_ONLY", 1, [stage], function(context) {
                return context.playerId != "" && context.teamId == "team" && context.available(compute) >= 100;
            }).unlock("ct_research:effect", "ct_research:target", 1).register();
            ResearchServer.permissions(function(action, context) { return context.standardDecision(action); });
            var original = ResearchHarness.recipe();
            ResearchHarness.extension(function(recipe, requirements) {
                if recipe.matches(original) { requirements.resource(compute, 11).research("ct_research:research", 1); }
            });
            """;
        var parsed = new ZenParsedFile("research.zs", "ResearchBridgeScript", new ZenTokener(new StringReader(script), environment, "research.zs", false), global);
        ZenModule.compileScripts("research.zs", List.of(parsed), global, false);
        assertEquals("", output.toString(StandardCharsets.UTF_8));
        ZenModule.classes.clear();
        ZenModule.loadedClasses.clear();
        Objects.requireNonNull(new ZenModule(classes, getClass().getClassLoader()).getMain()).run();
        var resources = new NetworkResourceRegistry();
        DefinitionRegistrations.dispatch(new NetworkResourceRegistrationEvent(resources));
        resources.freeze();
        var research = new ResearchRegistry();
        DefinitionRegistrations.dispatch(new ResearchRegistrationEvent(research));
        research.freeze(resources);
        var configuration = new ResearchServerConfiguration();
        DefinitionRegistrations.dispatch(new ResearchServerConfigurationEvent(configuration));
        configuration.freeze();
        var definition = research.require(new ResourceLocation("ct_research:research"));
        var compute = resources.require(new ResourceLocation("ct_research:compute"));
        assertEquals(3000000000L, definition.totalResourceDemandForLevel(1).getLong(compute));
        assertTrue(definition.unlocks(new ResearchUnlockQuery(new ResourceLocation("ct_research:effect"), new ResourceLocation("ct_research:target"), 1)));
        var player = UUID.randomUUID();
        var permission = new ResearchPermissionContext(player, "team", player, "team", false, false, false);
        var levels = new Object2IntOpenHashMap<ResourceLocation>();
        var view = new TeamResearchView(research, "team", List.of(player), levels, levels, List.of());
        var queue = new TeamResearchQueue(research, resources, "team");
        var amounts = new Object2LongOpenHashMap<NetworkResourceType>();
        var admission = new TeamResearchStart(research, resources, configuration.permissions());
        var missing = new TeamResearchStart.Request(queue, view, permission, definition.id(), 1,
            new TeamResearchStart.Environment(1, 1, 1, amounts), 1, () -> true);
        assertEquals(TeamResearchStart.Status.NOT_STARTABLE, admission.start(missing).status());
        amounts.put(compute, 100);
        var supplied = new TeamResearchStart.Request(queue, view, permission, definition.id(), 1,
            new TeamResearchStart.Environment(1, 1, 1, amounts), 2, () -> true);
        assertEquals(TeamResearchStart.Status.STARTED, admission.start(supplied).status());
        assertEquals(1, queue.size());
        assertEquals(ResearchPermissionPolicy.Decision.NOT_SERVER_OPERATOR, configuration.permissions().can(ResearchPermissionPolicy.Action.MODIFY_ANY_RESEARCH_LEVEL, permission));
        var group = definition.rewardGroupsForLevel(1).getFirst();
        var child = (RewardChild.Event) group.children().getFirst();
        assertEquals(RewardEventAction.Result.RETRY_LATER, child.action().deliver(new RewardEventKey(player, definition.id(), 1, group.id(), child.id()), new ResearchRewardContext(player, definition, 1, 2)));
        var extensions = new NetworkRecipeExtensions();
        extensions.register(1, ZenNetworkRecipes.extension(Objects.requireNonNull(Harness.extension)));
        extensions.publish(new RecipeCatalog(List.of(new DefaultRecipePool(new ResourceLocation("ct_research:pool"), "pool", List.of(Objects.requireNonNull(Harness.recipe).recipe())))), research, resources);
        var replacement = ZenRecipe.create("ct_research:recipe", "new", 6, new ZenRequirement[0]);
        var prepared = extensions.prepare(extensions.snapshot(), List.of(replacement.recipe()));
        extensions.install(prepared);
        assertEquals(11L, extensions.require(Harness.recipe.recipe()).resources().getLong(compute));
        assertTrue(extensions.require(replacement.recipe()).resources().isEmpty());
        assertThrows(IllegalStateException.class, () -> definitionWrapper().register());
    }

    @SuppressWarnings("unused") // Called by the real ZenScript compiler fixture.
    @ZenClass("tests.prototype_eng.ResearchHarness")
    public static final class Harness {
        private static ZenRecipe recipe;
        private static ZenNetworkRecipeCallback extension;

        @ZenMethod
        public static ZenRecipe recipe() {
            return Objects.requireNonNull(recipe);
        }

        @ZenMethod
        public static void extension(ZenNetworkRecipeCallback value) {
            extension = Objects.requireNonNull(value);
        }
    }
}
