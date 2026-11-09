package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.PrototypeEngineering;
import com.circulation.prototype_eng.api.scheduler.SchedulerReport;
import com.circulation.prototype_eng.localization.SchedulerText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.command.CommandBase;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;

/** Actual permission-2 command; settings changes flow through the retained owner tick boundary. */
public final class SchedulerCommand extends CommandBase {
    private final Function<MinecraftServer, TaskScheduler> scheduler;
    private final Function<MinecraftServer, SchedulerConfig.Settings> reload;

    public SchedulerCommand() {
        this(ServerTaskSchedulers::forServer, ServerTaskSchedulers::requestReloadFromConfig);
    }

    SchedulerCommand(Function<MinecraftServer, TaskScheduler> scheduler,
                     Function<MinecraftServer, SchedulerConfig.Settings> reload) {
        this.scheduler = Objects.requireNonNull(scheduler);
        this.reload = Objects.requireNonNull(reload);
    }

    @Override
    public @NotNull String getName() {
        return "pe_scheduler";
    }

    @Override
    public @NotNull String getUsage(@NotNull ICommandSender sender) {
        return SchedulerText.Usage.getTranslationKey();
    }

    @Override
    public int getRequiredPermissionLevel() {
        return 2;
    }

    @Override
    public void execute(@NotNull MinecraftServer server, @NotNull ICommandSender sender, String @NotNull [] arguments) {
        if (arguments.length == 0) {
            sender.sendMessage(SchedulerText.Usage.text());
            return;
        }
        String operation = arguments[0].toLowerCase(Locale.ROOT);
        boolean metrics = operation.equals("metrics");
        boolean strategy = operation.equals("strategy");
        String toggle = arguments.length == 2 ? arguments[1].toLowerCase(Locale.ROOT) : "";
        var requestedStrategy = strategy && arguments.length == 2 ? SchedulerConfig.WorkerStrategy.fromName(arguments[1]) : null;
        Boolean loggingEnabled = switch (toggle) {
            case "on", "true", "1", "enable", "enabled" -> true;
            case "off", "false", "0", "disable", "disabled" -> false;
            default -> null;
        };
        if (metrics ? arguments.length != 2 || loggingEnabled == null
            : strategy ? arguments.length != 2 || requestedStrategy == null
            : arguments.length != 1 || !operation.equals("status") && !operation.equals("report") && !operation.equals("reload")) {
            sender.sendMessage(SchedulerText.Usage.text());
            return;
        }
        try {
            var current = Objects.requireNonNull(scheduler.apply(server));
            switch (operation) {
                case "status" -> {
                    var status = current.status();
                    sender.sendMessage(SchedulerText.Status.text(status.policyLabel(), status.registeredCount(),
                        status.pendingExecution(), status.boundaryReady(), status.closing(), status.faulted(), status.closed()));
                    settings(sender, SchedulerText.Effective, current.effectiveSettings());
                    var pending = current.pendingSettings();
                    if (pending != null) settings(sender, SchedulerText.Pending, pending);
                }
                case "report" -> report(sender, current.snapshotReport());
                case "reload" -> {
                    var requested = Objects.requireNonNull(reload.apply(server));
                    sender.sendMessage(SchedulerText.ReloadRequested.text());
                    settings(sender, SchedulerText.Pending, requested);
                }
                case "metrics" -> {
                    var base = current.pendingSettings();
                    if (base == null) base = current.effectiveSettings();
                    var requested = base.withMetricsEnabled(loggingEnabled);
                    current.requestSettings(requested);
                    sender.sendMessage(SchedulerText.MetricsRequested.text(requested.metricsEnabled()));
                    settings(sender, SchedulerText.Pending, requested);
                }
                case "strategy" -> {
                    var base = current.pendingSettings();
                    if (base == null) base = current.effectiveSettings();
                    var requested = base.withWorkerStrategy(requestedStrategy);
                    current.requestSettings(requested);
                    sender.sendMessage(SchedulerText.StrategyRequested.text(requested.workerStrategy().name()));
                    settings(sender, SchedulerText.Pending, requested);
                }
                default -> throw new AssertionError("Validated scheduler command");
            }
        } catch (IllegalStateException failure) {
            sender.sendMessage(SchedulerText.OperationFailed.text(failure.getMessage()));
            PrototypeEngineering.LOGGER.error("Scheduler command {} failed", operation, failure);
        }
    }

    private static void settings(ICommandSender sender, SchedulerText state, SchedulerConfig.Settings settings) {
        sender.sendMessage(SchedulerText.Settings.text(state.text(), settings.workerStrategy().policyLabel(), settings.workerThreads(), settings.laneCount(),
            settings.plannedTimeout().toString(), settings.metricsEnabled(), settings.metricsLogIntervalTicks(), settings.metricsWindowTicks(),
            settings.workerStrategy().name()));
        if (settings.workerStrategy() == SchedulerConfig.WorkerStrategy.VIRTUAL_PER_TASK)
            sender.sendMessage(SchedulerText.UnboundedWorkers.text(settings.workerThreads()));
    }

    private static void report(ICommandSender sender, SchedulerReport report) {
        sender.sendMessage(SchedulerText.Report.text(report.policyLabel(), report.sampleCount(), report.windowTicks(), report.completedTicks()));
        wall(sender, SchedulerText.Total, report.totalWallNanos());
        wall(sender, SchedulerText.Main, report.mainWallNanos());
        wall(sender, SchedulerText.ConcurrentStage, report.concurrentStageWallNanos());
        wall(sender, SchedulerText.JoinWait, report.joinWallNanos());
        wall(sender, SchedulerText.Planned, report.plannedWallNanos());
        wall(sender, SchedulerText.Cleanup, report.cleanupWallNanos());
        wall(sender, SchedulerText.StructureWorker, report.structureWorkerWallNanos());
        counts(sender, SchedulerText.MainTasks, report.mainTasks());
        counts(sender, SchedulerText.ConcurrentTasks, report.concurrentTasks());
        counts(sender, SchedulerText.CustomMainTasks, report.customMainTasks());
        counts(sender, SchedulerText.CustomConcurrentTasks, report.customConcurrentTasks());
        counts(sender, SchedulerText.AffinityGroups, report.affinityGroups());
    }

    private static void wall(ICommandSender sender, SchedulerText label, SchedulerReport.Stat values) {
        sender.sendMessage(SchedulerText.Wall.text(label.text(), values.wallMicros()));
    }

    private static void counts(ICommandSender sender, SchedulerText label, SchedulerReport.Stat values) {
        sender.sendMessage(SchedulerText.Counts.text(label.text(), values.counts()));
    }

    @Override
    public @NotNull List<String> getTabCompletions(@NotNull MinecraftServer server, @NotNull ICommandSender sender,
                                                  String @NotNull [] arguments, @Nullable BlockPos targetPos) {
        if (arguments.length == 1)
            return getListOfStringsMatchingLastWord(arguments, "status", "report", "reload", "metrics", "strategy");
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("metrics"))
            return getListOfStringsMatchingLastWord(arguments, "on", "off");
        if (arguments.length == 2 && arguments[0].equalsIgnoreCase("strategy"))
            return getListOfStringsMatchingLastWord(arguments, "PLATFORM_POOL", "VIRTUAL_PER_TASK");
        return new ObjectArrayList<>();
    }
}
