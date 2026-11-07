package com.circulation.prototype_eng.research;

import com.circulation.prototype_eng.api.machine.MachineInstance;
import com.circulation.prototype_eng.api.machine.component.MachineHatches;
import com.circulation.prototype_eng.api.machine.identity.PersistentMachineIdentity;
import com.circulation.prototype_eng.api.machine.lifecycle.MachineIdentity;
import com.circulation.prototype_eng.api.network.resource.NetworkResourceRegistry;
import com.circulation.prototype_eng.api.persistence.ResourceKeyNbt;
import com.circulation.prototype_eng.api.recipe.process.ProcessResult;
import com.circulation.prototype_eng.api.recipe.process.RecipeProcess;
import com.circulation.prototype_eng.api.recipe.requirement.EnergyRequirement;
import com.circulation.prototype_eng.api.recipe.requirement.RecipeRequirements;
import com.circulation.prototype_eng.api.research.PlayerResearchState;
import com.circulation.prototype_eng.api.research.ResearchDefinition;
import com.circulation.prototype_eng.api.research.ResearchDownloadDeliveryPolicy;
import com.circulation.prototype_eng.api.research.ResearchLevelStage;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.ResearchResourceFormula;
import com.circulation.prototype_eng.api.research.ResearchWorldSnapshot;
import com.circulation.prototype_eng.api.research.TeamOwnedResearch;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.queue.TeamResearchQueue;
import com.circulation.prototype_eng.api.research.station.ResearchStationWork;
import com.circulation.prototype_eng.api.research.station.ResearchStations;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import it.unimi.dsi.fastutil.objects.ObjectList;
import it.unimi.dsi.fastutil.objects.ObjectOpenHashSet;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.nbt.NBTTagString;
import net.minecraft.util.ResourceLocation;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResearchWorldStationPersistenceTest {
    private static final UUID PLAYER = new UUID(0, 1);
    private static final ResourceLocation STATION = id("station_research");
    private static final ResourceLocation MANUAL = id("manual_research");
    @TempDir
    Path directory;

    private static ObjectOpenHashSet<UUID> members() {
        return new ObjectOpenHashSet<>(ObjectList.of(PLAYER));
    }

    private static ResearchStationWork.SavedSegment segment(ResearchWorldSnapshot.Team team) {
        return Objects.requireNonNull(team.stationWork().segment());
    }

    private static MachineHatches emptyHatches() {
        return new MachineHatches(List.of(), List.of(), List.of());
    }

    private static NBTTagCompound stationWork(NBTTagCompound root) {
        return root.getTagList("Teams", 10).getCompoundTagAt(0).getCompoundTag("StationWork");
    }

    private static NBTTagList sites(NBTTagCompound root) {
        return stationWork(root).getCompoundTag("Segment").getTagList("Sites", 10);
    }

    private static NBTTagCompound processTag(NBTTagCompound root) {
        return stationWork(root).getCompoundTag("Segment").getCompoundTag("Process");
    }

    private static ResourceLocation id(String path) {
        return new ResourceLocation("research_world_station_test", path);
    }

    @Test
    void actualStateFileRoundTripsStationMaterialsAlongsideManualAndPlayerWorldData() throws IOException {
        var fixture = new Fixture();
        var world = fixture.world(fixture.saved());
        var file = new ResearchStateFile(directory.resolve("research.dat"), fixture.codec);
        file.write(world);
        var restored = file.read().orElseThrow();
        assertEquals(world, restored);
        var team = restored.teams().getFirst();
        var segment = segment(team);
        assertEquals(4, team.stationWork().lastGrantedTime());
        assertEquals(fixture.queue.activeHead().orElseThrow().key(), segment.key());
        assertEquals(fixture.sites, segment.sites());
        assertEquals(PLAYER, team.manualSessions().getFirst().contributor());
        assertEquals(MANUAL, team.manualSessions().getFirst().queueKey().researchId());
        assertEquals(ManualResearchSession.Status.ACTIVE, team.manualSessions().getFirst().status());
        assertEquals(PLAYER, restored.players().getFirst().playerUuid());
        var materials = fixture.registry.materials(STATION, 1).decodeMaterialCycle(fixture.owner, emptyHatches(),
            segment.processTag());
        assertTrue(materials.isMaterialCycle());
        assertEquals(RecipeProcess.Lifecycle.RUNNING, materials.state().lifecycle());
        assertEquals(0, materials.state().successfulTicks());
        assertNull(materials.host());
    }

    @Test
    void rawNbtAndAllReturnedProcessTagsCannotMutateAWorldCheckpoint() {
        var fixture = new Fixture();
        var original = fixture.processTag.copy();
        assertTrue(original.hasKey("State", 10));
        var mutableSites = new ObjectArrayList<>(fixture.sites);
        var segment = new ResearchStationWork.SavedSegment(fixture.head(), mutableSites, fixture.processTag);
        var world = fixture.world(new ResearchStationWork.Saved(4, segment));
        fixture.processTag.getCompoundTag("State").setString("Injected", "input mutation");
        mutableSites.clear();
        assertEquals(original, segment(world.teams().getFirst()).processTag());
        assertEquals(2, segment.sites().size());
        assertThrows(UnsupportedOperationException.class, () -> segment.sites().clear());
        segment.processTag().getCompoundTag("State").setString("Injected", "getter mutation");
        assertEquals(original, segment.processTag());
        var encoded = fixture.codec.encode(world);
        var decoded = fixture.codec.decode(encoded);
        processTag(encoded).getCompoundTag("State").setString("Injected", "encoded input mutation");
        assertEquals(original, segment(decoded.teams().getFirst()).processTag());
        assertEquals(original, segment(world.teams().getFirst()).processTag());
        segment(decoded.teams().getFirst()).processTag().setString("Revision", "getter revision mutation");
        assertEquals(original.getString("Revision"), segment(decoded.teams().getFirst()).processTag().getString("Revision"));
    }

    @Test
    void stationCheckpointRequiresAnExactAllocatedHeadAndWorldTime() {
        var fixture = new Fixture();
        assertThrows(IllegalArgumentException.class, () -> new ResearchStationWork.Saved(-2, null));
        assertThrows(IllegalArgumentException.class, () -> fixture.world(new ResearchStationWork.Saved(11, null)));
        assertThrows(IllegalArgumentException.class, () -> new TeamResearchQueue.Key(STATION, 1, 0));
        var another = new ResearchStationWork.SavedSegment(new TeamResearchQueue.Key(STATION, 1, 2), fixture.sites, fixture.processTag);
        assertThrows(IllegalArgumentException.class, () -> fixture.world(new ResearchStationWork.Saved(4, another)));
        var savedQueue = fixture.queue.snapshot();
        var unallocated = new TeamResearchQueue.Snapshot("team", savedQueue.entries(), savedQueue.activeHead(), savedQueue.resourceProgress(),
            1, savedQueue.epoch(), savedQueue.updatedAtWorldTime(), savedQueue.lastGrantedEntryId(), savedQueue.lastGrantedWorldTime());
        assertThrows(IllegalArgumentException.class, () -> fixture.team(unallocated, fixture.saved()));
        var missingHead = new TeamResearchQueue.Snapshot("team", savedQueue.entries(), null, savedQueue.resourceProgress(),
            savedQueue.nextEntryId(), savedQueue.epoch(), savedQueue.updatedAtWorldTime(), savedQueue.lastGrantedEntryId(), savedQueue.lastGrantedWorldTime());
        assertThrows(IllegalArgumentException.class, () -> fixture.team(missingHead, fixture.saved()));
        var future = fixture.codec.encode(fixture.world(fixture.saved()));
        stationWork(future).setLong("LastGrantedTime", 11);
        assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(future));
        var entry = fixture.codec.encode(fixture.world(fixture.saved()));
        stationWork(entry).getCompoundTag("Segment").getCompoundTag("Key").setLong("EntryId", 2);
        assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(entry));
        List<Consumer<NBTTagCompound>> invalidKeys = List.of(
            key -> key.setInteger("Level", 0), key -> key.setString("EntryId", "1"), key -> key.setString("Research", "missing_namespace"));
        for (var invalid : invalidKeys) {
            var tag = fixture.codec.encode(fixture.world(fixture.saved()));
            invalid.accept(stationWork(tag).getCompoundTag("Segment").getCompoundTag("Key"));
            assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(tag));
        }
    }

    @Test
    void physicalSitesRequireStrictTypesCanonicalIdsDistinctIdentitiesAndOneDimension() {
        var fixture = new Fixture();
        List<Consumer<NBTTagCompound>> corruptions = List.of(
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Identity").setString("machineMost", "not a long"),
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Identity").setInteger("version", 2),
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Location").setString("Type", "missing_namespace"),
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Location").setString("Type", "UPPER:station"),
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Location").setLong("X", 12),
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Location").removeTag("Y"),
            tag -> sites(tag).getCompoundTagAt(0).getCompoundTag("Location").setString("Dimension", "-1"),
            tag -> sites(tag).getCompoundTagAt(0).setString("Permission", "forbidden saved authority"),
            tag -> sites(tag).appendTag(sites(tag).getCompoundTagAt(0).copy()),
            tag -> sites(tag).getCompoundTagAt(1).getCompoundTag("Location").setInteger("Dimension", 0),
            tag -> sites(tag).getCompoundTagAt(1).setTag("Identity", sites(tag).getCompoundTagAt(0).getCompoundTag("Identity").copy()),
            tag -> sites(tag).getCompoundTagAt(1).setTag("Location", sites(tag).getCompoundTagAt(0).getCompoundTag("Location").copy()),
            tag -> stationWork(tag).getCompoundTag("Segment").setTag("Sites", new NBTTagCompound()));
        for (var corrupt : corruptions) {
            var tag = fixture.codec.encode(fixture.world(fixture.saved()));
            corrupt.accept(tag);
            assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(tag));
        }
        var empty = fixture.codec.encode(fixture.world(fixture.saved()));
        stationWork(empty).getCompoundTag("Segment").setTag("Sites", new NBTTagList());
        assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(empty));
    }

    @Test
    void versionThreeRequiresExplicitStationEnvelopeAndNeverFallsBackToMissingOrOldFields() {
        var fixture = new Fixture();
        var oldHost = new ResearchWorldSnapshot.Team("team", members(), new ObjectOpenHashSet<>(), fixture.research.snapshot(),
            fixture.queue.snapshot(), fixture.sessions());
        assertEquals(new ResearchStationWork.Saved(-1, null), oldHost.stationWork());
        var empty = new ResearchWorldSnapshot(10, ObjectList.of(fixture.player.snapshot()), ObjectList.of(oldHost));
        var encoded = fixture.codec.encode(empty);
        assertEquals(3, encoded.getInteger("Version"));
        assertFalse(stationWork(encoded).getBoolean("HasSegment"));
        assertEquals(empty, fixture.codec.decode(encoded));
        List<Consumer<NBTTagCompound>> corruptions = List.of(
            tag -> tag.setInteger("Version", 1),
            tag -> tag.getTagList("Teams", 10).getCompoundTagAt(0).removeTag("StationWork"),
            tag -> tag.getTagList("Teams", 10).getCompoundTagAt(0).setTag("StationWork", new NBTTagString("wrong type")),
            tag -> stationWork(tag).setInteger("HasSegment", 1),
            tag -> stationWork(tag).setByte("HasSegment", (byte) 2),
            tag -> stationWork(tag).setBoolean("HasSegment", false),
            tag -> stationWork(tag).removeTag("Segment"),
            tag -> stationWork(tag).setString("Epoch", "not persisted"),
            tag -> stationWork(tag).getCompoundTag("Segment").setString("Process", "wrong type"));
        for (var corrupt : corruptions) {
            var tag = fixture.codec.encode(fixture.world(fixture.saved()));
            corrupt.accept(tag);
            assertThrows(IllegalArgumentException.class, () -> fixture.codec.decode(tag));
        }
    }

    @Test
    void envelopeValidatesResearchTierButFullMaterialRevisionWaitsForTheRealOwner() {
        var fixture = new Fixture();
        var manualQueue = new TeamResearchQueue(fixture.registry, fixture.resources, "team");
        var manualEntry = manualQueue.enqueue(MANUAL, 1, PLAYER, 1);
        var wrongTier = new ResearchStationWork.Saved(4, new ResearchStationWork.SavedSegment(manualEntry.key(), fixture.sites, fixture.processTag));
        var manualTeam = new ResearchWorldSnapshot.Team("team", members(), new ObjectOpenHashSet<>(), fixture.research.snapshot(),
            manualQueue.snapshot(), ObjectList.of(), wrongTier);
        assertThrows(IllegalArgumentException.class, () -> fixture.codec.encode(new ResearchWorldSnapshot(10,
            ObjectList.of(fixture.player.snapshot()), ObjectList.of(manualTeam))));
        var encoded = fixture.codec.encode(fixture.world(fixture.saved()));
        processTag(encoded).setString("Revision", "incompatible revision");
        var decoded = fixture.codec.decode(encoded);
        var restoredTag = segment(decoded.teams().getFirst()).processTag();
        assertEquals("incompatible revision", restoredTag.getString("Revision"));
        assertThrows(IllegalArgumentException.class, () -> fixture.registry.materials(STATION, 1).decodeMaterialCycle(fixture.owner,
            emptyHatches(), restoredTag));
    }

    private static final class Fixture {
        private final NetworkResourceRegistry resources = new NetworkResourceRegistry();
        private final ResearchRegistry registry = new ResearchRegistry();
        private final PlayerResearchState player = new PlayerResearchState(PLAYER);
        private final MachineInstance owner = new MachineInstance();
        private final TeamResearchQueue queue;
        private final TeamOwnedResearch research;
        private final ResearchWorldNbt codec;
        private final NBTTagCompound processTag;
        private final List<ResearchStations.Site> sites = List.of(
            new ResearchStations.Site(new PersistentMachineIdentity(new UUID(0, 5), PLAYER), new MachineIdentity(id("station"), -1, new BlockPos(12, 70, 19))),
            new ResearchStations.Site(new PersistentMachineIdentity(new UUID(0, 6), PLAYER), new MachineIdentity(id("other_station"), -1, new BlockPos(-14, 69, 21))));

        private Fixture() {
            resources.freeze();
            var energy = RecipeRequirements.ENERGY.requirement(id("material_energy"), new EnergyRequirement(0, 3, 0, 0, false));
            registry.register(ResearchDefinition.builder(STATION).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, 1).researchTier(1).resourceFormula(ResearchResourceFormula.builder().build())
                                                                         .materialRequirement(energy).materialPersistence("station-v1", _ -> {
                                                    }).build()).build());
            registry.register(ResearchDefinition.builder(MANUAL).maxLevel(1).deliveryPolicy(ResearchDownloadDeliveryPolicy.DATABASE_AND_ITEM)
                                                .stage(ResearchLevelStage.builder(1, 1).researchTier(0).resourceFormula(ResearchResourceFormula.builder().build()).build()).build());
            registry.freeze(resources);
            queue = new TeamResearchQueue(registry, resources, "team");
            queue.enqueue(STATION, 1, PLAYER, 1);
            queue.enqueue(MANUAL, 1, PLAYER, 2);
            research = new TeamOwnedResearch("team", registry);
            codec = new ResearchWorldNbt(registry, resources, new ResourceKeyNbt(_ -> null));
            var materials = RecipeProcess.materialCycle(owner, registry.materials(STATION, 1).recipe(), emptyHatches(), 7);
            assertInstanceOf(ProcessResult.Blocked.class, materials.tick());
            processTag = registry.materials(STATION, 1).encode(materials);
        }

        private TeamResearchQueue.Key head() {
            return queue.activeHead().orElseThrow().key();
        }

        private ResearchStationWork.Saved saved() {
            return new ResearchStationWork.Saved(4, new ResearchStationWork.SavedSegment(head(), sites, processTag));
        }

        private ObjectList<ManualResearchSession> sessions() {
            return ObjectList.of(ManualResearchSession.newActive(new UUID(0, 7), "team", queue.queueSnapshot().get(1).key(), PLAYER, 3));
        }

        private ResearchWorldSnapshot.Team team(TeamResearchQueue.Snapshot savedQueue, ResearchStationWork.Saved saved) {
            return new ResearchWorldSnapshot.Team("team", members(), new ObjectOpenHashSet<>(), research.snapshot(), savedQueue, sessions(), saved);
        }

        private ResearchWorldSnapshot world(ResearchStationWork.Saved saved) {
            return new ResearchWorldSnapshot(10, ObjectList.of(player.snapshot()), ObjectList.of(team(queue.snapshot(), saved)));
        }
    }
}
