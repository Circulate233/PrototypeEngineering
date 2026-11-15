package com.circulation.prototype_eng.integration.crafttweaker.hatch;

import com.circulation.prototype_eng.api.storage.HatchConfigurationUpdates;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenGetter;


@ZenRegister
@ZenClass("mods.prototype_eng.hatch.Report")
@SuppressWarnings("unused") // ZenScript observes the actual immutable Java report.
public record ZenHatchReport(HatchConfigurationUpdates.Report value) {

    @ZenGetter("revision")
    public long revision() {
        return value.revision();
    }

    @ZenGetter("status")
    public String status() {
        return value.status().name();
    }

    /**
     * One outcome for each real loaded host visited by the original propagation scan.
     */
    @ZenGetter("hostStatuses")
    public String[] hostStatuses() {
        var statuses = new String[value.hosts().size()];
        for (int i = 0; i < statuses.length; i++) statuses[i] = value.hosts().get(i).status().name();
        return statuses;
    }
}
