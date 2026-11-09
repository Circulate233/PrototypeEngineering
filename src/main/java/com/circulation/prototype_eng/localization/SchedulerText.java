package com.circulation.prototype_eng.localization;

import ae2.core.localization.LocalizationEnum;
import com.circulation.prototype_eng.Tags;

/** Server command messages use text(); log numeric summaries remain language-independent. */
public enum SchedulerText implements LocalizationEnum {
    Usage, Status, Effective, Pending, Settings, Report, Wall, Counts, ReloadRequested, MetricsRequested, OperationFailed,
    Total, Main, ConcurrentStage, JoinWait, Planned, Cleanup, StructureWorker,
    MainTasks, ConcurrentTasks, CustomMainTasks, CustomConcurrentTasks, AffinityGroups, StrategyRequested, UnboundedWorkers;

    private final String key = "scheduler." + Tags.MOD_ID + "." + name();

    @Override
    public String getTranslationKey() {
        return key;
    }
}
