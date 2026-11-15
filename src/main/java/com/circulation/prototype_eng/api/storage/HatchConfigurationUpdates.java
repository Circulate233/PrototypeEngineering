package com.circulation.prototype_eng.api.storage;

import com.circulation.prototype_eng.HatchConfig;
import com.circulation.prototype_eng.api.energy.EnergyHatchConfiguration;
import com.circulation.prototype_eng.scheduler.ServerTaskSchedulers;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.server.MinecraftServer;
import net.minecraftforge.common.DimensionManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * PM-compatible loaded propagation through the existing server scheduler boundary.
 * List arguments and results, including nested lists, must support constant-time indexed access.
 */
public final class HatchConfigurationUpdates {
    private HatchConfigurationUpdates() {
    }

    /**
     * Startup script defaults retain precedence when the file configuration is subsequently loaded.
     */
    @SuppressWarnings("unused")
    public static void configureDefaults(List<Target> targets) {
        HatchConfig.installTargets(targets);
    }

    /**
     * The report describes this one scan; busy/capacity hosts retain and retry their own pending target.
     */
    @SuppressWarnings("unused")
    public static CompletableFuture<Report> request(MinecraftServer server, List<Target> targets) {
        if (!server.isCallingFromMinecraftThread())
            throw new IllegalStateException("Hatch configuration requires the server owner");
        var scheduler = ServerTaskSchedulers.forServer(server);
        var status = scheduler.status();
        if (status.closing() || status.closed())
            return CompletableFuture.completedFuture(new Report(HatchConfig.targetRevision(), HatchReconfigurationStatus.CLOSED, List.of()));
        long revision = HatchConfig.installTargets(targets);
        var result = new CompletableFuture<Report>();
        scheduler.runAtBoundary(() -> {
            if (scheduler.status().closing() || scheduler.status().closed()) {
                result.complete(new Report(revision, HatchReconfigurationStatus.CLOSED, List.of()));
                return;
            }
            if (HatchConfig.targetRevision() != revision) {
                result.complete(new Report(revision, HatchReconfigurationStatus.STALE_TARGET, List.of()));
                return;
            }
            try {
                var outcomes = new ObjectArrayList<Outcome>();
                for (var world : DimensionManager.getWorlds()) {
                    if (world.isRemote || world.getMinecraftServer() != server) continue;
                    var elements = world.loadedTileEntityList;
                    for (int index = 0; index < elements.size(); index++) {
                        var tile = elements.get(index);
                        if (tile.isInvalid() || !(tile instanceof ReconfigurableHatch host))
                            continue;
                        var effective = host.effectiveConfiguration();
                        if (!HatchConfig.hasTarget(host.tier(), effective)) continue;
                        HatchConfiguration target = effective instanceof ResourceHatchConfiguration
                            ? HatchConfig.resourceConfiguration(host.tier(), effective.mode())
                            : HatchConfig.energyConfiguration(host.tier(), effective.mode());
                        outcomes.add(new Outcome(host, host.requestConfiguration(target)));
                    }

                }
                result.complete(new Report(revision, HatchReconfigurationStatus.APPLIED, outcomes));
            } catch (RuntimeException | Error failure) {
                result.completeExceptionally(failure);
                throw failure;
            }
        });
        return result;
    }

    public record Target(HatchTier tier, HatchConfiguration configuration) {
        public Target {
            if (!(configuration instanceof ResourceHatchConfiguration) && !(configuration instanceof EnergyHatchConfiguration))
                throw new IllegalArgumentException("Unsupported hatch configuration family");
        }
    }

    public record Outcome(ReconfigurableHatch host, HatchReconfigurationStatus status) {
    }

    /**
     * The completed outcome list is read-only for all report consumers.
     */
    public record Report(long revision, HatchReconfigurationStatus status, List<Outcome> hosts) {
    }
}
