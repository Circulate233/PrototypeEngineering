package com.circulation.prototype_eng.scheduler;

import com.circulation.prototype_eng.api.scheduler.ExecutionMode;
import com.circulation.prototype_eng.api.scheduler.ScheduledTask;
import com.circulation.prototype_eng.localization.SchedulerText;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.minecraft.command.ICommandSender;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.util.concurrent.atomic.AtomicBoolean;

class SchedulerCommandTest {
    private static SchedulerConfig.Settings settings(int workers, int lanes, Duration timeout, int window) {
        return new SchedulerConfig.Settings(workers, lanes, timeout, false, 7, window);
    }

    // Injected command dependencies ignore the server: no server/World, Bootstrap or FML is created.
    @SuppressWarnings("DataFlowIssue")
    private static void execute(SchedulerCommand command, Sender sender, String... arguments) {
        command.execute(null, sender, arguments);
    }

    @SuppressWarnings("DataFlowIssue")
    private static List<String> completions(SchedulerCommand command, Sender sender, String... arguments) {
        return command.getTabCompletions(null, sender, arguments, null);
    }

    @SuppressWarnings("DataFlowIssue")
    private static boolean permitted(SchedulerCommand command, Sender sender) {
        return command.checkPermission(null, sender);
    }

    private static TextComponentTranslation message(Sender sender, int index, SchedulerText expected) {
        var message = assertInstanceOf(TextComponentTranslation.class, sender.messages.get(index));
        assertEquals(expected.getTranslationKey(), message.getKey());
        return message;
    }

    @Test
    void nativePermissionAndInvalidSyntaxDoNotResolveOrMutateAScheduler() {
        var command = new SchedulerCommand(_ -> {
            throw new AssertionError("Invalid syntax must not resolve a scheduler");
        }, _ -> {
            throw new AssertionError("Invalid syntax must not reload disk settings");
        });
        var sender = new Sender();
        assertEquals("pe_scheduler", command.getName());
        assertEquals(SchedulerText.Usage.getTranslationKey(), command.getUsage(sender));
        assertEquals(2, command.getRequiredPermissionLevel());
        sender.permission = 1;
        assertFalse(permitted(command, sender));
        sender.permission = 2;
        assertTrue(permitted(command, sender));
        assertEquals(2, sender.requestedPermission);
        assertEquals("pe_scheduler", sender.requestedCommand);
        for (String[] arguments : new String[][]{
            {}, {"switch", "COROUTINES"}, {"status", "extra"}, {"report", "extra"}, {"reload", "extra"},
            {"metrics"}, {"metrics", "maybe"}, {"metrics", "on", "extra"}
        }) {
            execute(command, sender, arguments);
        }
        assertEquals(8, sender.messages.size());
        for (int index = 0; index < sender.messages.size(); index++) message(sender, index, SchedulerText.Usage);
        assertEquals(List.of("status", "report", "reload", "metrics", "strategy"), completions(command, sender, ""));
        assertEquals(List.of("on", "off"), completions(command, sender, "METRICS", ""));
        assertTrue(completions(command, sender, "switch", "").isEmpty());
    }

    @Test
    void statusAndReportExposeRealRegistrationSettingsAndCollectedWorkWhileLoggingIsOff() {
        var initial = settings(1, 1, Duration.ofNanos(1234567), 4);
        var runs = new AtomicInteger();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), initial)) {
            scheduler.register(new ScheduledTask() {
                @Override
                public void onSchedule() {
                    runs.incrementAndGet();
                }

                @Override
                public ExecutionMode executionMode() {
                    return ExecutionMode.MAIN_THREAD;
                }

                @Override
                public boolean active() {
                    return true;
                }
            });
            scheduler.submit(runs::incrementAndGet, ExecutionMode.CONCURRENT);
            scheduler.tick();
            assertEquals(2, runs.get());
            var pending = settings(2, 3, Duration.ofNanos(999999), 8);
            scheduler.requestSettings(pending);
            var command = new SchedulerCommand(_ -> scheduler, _ -> {
                throw new AssertionError("Status/report must not reload settings");
            });
            var sender = new Sender();
            execute(command, sender, "STATUS");
            assertArrayEquals(new Object[]{scheduler.policyLabel(), 1, false, true, false, false, false},
                message(sender, 0, SchedulerText.Status).getFormatArgs());
            var effective = message(sender, 1, SchedulerText.Settings).getFormatArgs();
            assertEquals(SchedulerText.Effective.getTranslationKey(),
                assertInstanceOf(TextComponentTranslation.class, effective[0]).getKey());
            assertArrayEquals(new Object[]{scheduler.policyLabel(), 1, 1, initial.plannedTimeout().toString(), false, 7, 4, "PLATFORM_POOL"},
                Arrays.copyOfRange(effective, 1, effective.length));
            var requested = message(sender, 2, SchedulerText.Settings).getFormatArgs();
            assertEquals(SchedulerText.Pending.getTranslationKey(),
                assertInstanceOf(TextComponentTranslation.class, requested[0]).getKey());
            assertEquals(pending.plannedTimeout().toString(), requested[4]);
            assertSame(pending, scheduler.pendingSettings());
            assertSame(initial, scheduler.effectiveSettings());
            sender.messages.clear();
            execute(command, sender, "RePoRt");
            assertEquals(13, sender.messages.size());
            assertArrayEquals(new Object[]{scheduler.policyLabel(), 1, 4, 1L},
                message(sender, 0, SchedulerText.Report).getFormatArgs());
            var report = scheduler.snapshotReport();
            SchedulerText[] labels = {SchedulerText.Total, SchedulerText.Main, SchedulerText.ConcurrentStage,
                SchedulerText.JoinWait, SchedulerText.Planned, SchedulerText.Cleanup, SchedulerText.StructureWorker,
                SchedulerText.MainTasks, SchedulerText.ConcurrentTasks, SchedulerText.CustomMainTasks,
                SchedulerText.CustomConcurrentTasks, SchedulerText.AffinityGroups};
            for (int index = 0; index < labels.length; index++) {
                var values = message(sender, index + 1, index < 7 ? SchedulerText.Wall : SchedulerText.Counts).getFormatArgs();
                assertEquals(labels[index].getTranslationKey(), assertInstanceOf(TextComponentTranslation.class, values[0]).getKey());
            }
            assertEquals(report.totalWallNanos().wallMicros(), message(sender, 1, SchedulerText.Wall).getFormatArgs()[1]);
            assertEquals("1/1/1", message(sender, 8, SchedulerText.Counts).getFormatArgs()[1]);
            assertEquals("1/1/1", message(sender, 11, SchedulerText.Counts).getFormatArgs()[1]);
            assertFalse(scheduler.effectiveSettings().metricsEnabled());
        }
    }

    @Test
    void reloadThenAllRetainedToggleAliasesPreservePendingThreadsWindowAndNanosecondBudget() {
        var initial = settings(1, 1, Duration.ofSeconds(5), 4);
        var fromDisk = settings(2, 3, Duration.ofNanos(987654321), 3);
        var reloads = new AtomicInteger();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), initial)) {
            var command = new SchedulerCommand(_ -> scheduler, _ -> {
                reloads.incrementAndGet();
                scheduler.requestSettings(fromDisk);
                return fromDisk;
            });
            var sender = new Sender();
            execute(command, sender, "ReLoAd");
            assertSame(fromDisk, scheduler.pendingSettings());
            assertSame(initial, scheduler.effectiveSettings());
            message(sender, 0, SchedulerText.ReloadRequested);
            for (String toggle : List.of("on", "TRUE", "1", "EnAbLe", "ENABLED")) {
                execute(command, sender, "MeTrIcS", toggle);
                assertEquals(fromDisk.withMetricsEnabled(true), scheduler.pendingSettings());
                assertSame(initial, scheduler.effectiveSettings());
            }
            for (String toggle : List.of("off", "FALSE", "0", "DiSaBlE", "DISABLED")) {
                execute(command, sender, "MeTrIcS", toggle);
                assertEquals(fromDisk, scheduler.pendingSettings());
            }
            assertEquals(1, reloads.get(), "Runtime metrics toggle must not reload or overwrite the pending request");
            scheduler.tick();
            assertEquals(fromDisk, scheduler.effectiveSettings());
            assertNull(scheduler.pendingSettings());
            assertEquals(1, scheduler.snapshotReport().sampleCount());
            assertEquals(1, scheduler.snapshotReport().completedTicks());
            execute(command, sender, "metrics", "on");
            assertEquals(fromDisk.withMetricsEnabled(true), scheduler.pendingSettings());
            assertFalse(scheduler.effectiveSettings().metricsEnabled());
        }
    }

    @Test
    void strategyRequestsKeepPendingSettingsDisplayTheRequestedModeAndChangeActualWorkersOnlyAtTick() {
        var initial = settings(1, 1, Duration.ofNanos(1234567), 4);
        var queued = settings(3, 2, Duration.ofNanos(987654321), 3);
        var ranVirtual = new AtomicBoolean();
        try (var scheduler = new TaskScheduler(Thread.currentThread(), initial)) {
            var command = new SchedulerCommand(_ -> scheduler, _ -> {
                throw new AssertionError("Runtime strategy/toggle must preserve the queued request");
            });
            var sender = new Sender();
            scheduler.requestSettings(queued);
            execute(command, sender, "StRaTeGy", "virtual_per_task");
            var selected = queued.withWorkerStrategy(SchedulerConfig.WorkerStrategy.VIRTUAL_PER_TASK);
            assertEquals(selected, scheduler.pendingSettings());
            assertSame(initial, scheduler.effectiveSettings());
            message(sender, 0, SchedulerText.StrategyRequested);
            var displayed = message(sender, 1, SchedulerText.Settings).getFormatArgs();
            assertEquals("JAVA/VIRTUAL_PER_TASK", displayed[1]);
            assertEquals("VIRTUAL_PER_TASK", displayed[8]);
            assertEquals(3, message(sender, 2, SchedulerText.UnboundedWorkers).getFormatArgs()[0]);
            execute(command, sender, "metrics", "TRUE");
            assertEquals(selected.withMetricsEnabled(true), scheduler.pendingSettings());
            scheduler.submit(() -> ranVirtual.set(Thread.currentThread().isVirtual()), ExecutionMode.CONCURRENT);
            scheduler.tick();
            assertTrue(ranVirtual.get());
            assertEquals("JAVA/VIRTUAL_PER_TASK", scheduler.snapshotReport().policyLabel());
            sender.messages.clear();
            execute(command, sender, "strategy", "PLATFORM_POOL");
            displayed = message(sender, 1, SchedulerText.Settings).getFormatArgs();
            assertEquals("JAVA", displayed[1], "Pending display must describe its own future choice");
            assertEquals("PLATFORM_POOL", displayed[8]);
            assertTrue(scheduler.effectiveSettings().metricsEnabled());
            scheduler.submit(() -> ranVirtual.set(Thread.currentThread().isVirtual()), ExecutionMode.CONCURRENT);
            scheduler.tick();
            assertFalse(ranVirtual.get());
            assertTrue(scheduler.effectiveSettings().metricsEnabled());
            assertEquals(List.of("PLATFORM_POOL", "VIRTUAL_PER_TASK"), completions(command, sender, "STRATEGY", ""));
        }
    }

    private static final class Sender implements ICommandSender {
        private final ObjectArrayList<ITextComponent> messages = new ObjectArrayList<>();
        private int permission = 2;
        private int requestedPermission;
        private String requestedCommand;

        @Override
        public @NotNull String getName() {
            return "Scheduler test sender";
        }

        @Override
        public void sendMessage(@NotNull ITextComponent component) {
            messages.add(component);
        }

        @Override
        public boolean canUseCommand(int level, @NotNull String commandName) {
            requestedPermission = level;
            requestedCommand = commandName;
            return permission >= level;
        }

        @Override
        public @NotNull World getEntityWorld() {
            throw new AssertionError("The tested command must never access a live World");
        }

        @Override
        public @Nullable MinecraftServer getServer() {
            return null;
        }
    }
}
