package com.circulation.prototype_eng.integration.crafttweaker.research;

import com.circulation.prototype_eng.api.research.ResearchPermissionContext;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy;
import java.util.Locale;
import java.util.Objects;
import crafttweaker.annotations.ZenRegister;
import stanhebben.zenscript.annotations.ZenClass;
import stanhebben.zenscript.annotations.ZenMethod;
import stanhebben.zenscript.annotations.ZenGetter;

/** Real server-captured actors and role flags, never client-supplied permissions. */
@ZenRegister
@ZenClass("mods.prototype_eng.research.ResearchPermissionContext")
@SuppressWarnings("unused") // Public adapters are discovered by ZenScript.
public record ZenResearchPermissionContext(ResearchPermissionContext value) {
 public ZenResearchPermissionContext { Objects.requireNonNull(value); }
 @ZenGetter("actorId") public String actorId() { return value.actorId().toString(); }
 @ZenGetter("actorTeamId") public String actorTeamId() { return value.actorTeamId()==null ? "" : value.actorTeamId(); }
 @ZenGetter("targetPlayerId") public String targetPlayerId() { return value.targetPlayerId()==null ? "" : value.targetPlayerId().toString(); }
 @ZenGetter("targetTeamId") public String targetTeamId() { return value.targetTeamId()==null ? "" : value.targetTeamId(); }
 @ZenGetter("personalTargetId") public String personalTargetId() { return value.personalTarget().toString(); }
 @ZenGetter("teamLeader") public boolean teamLeader() { return value.teamLeader(); }
 @ZenGetter("teamModerator") public boolean teamModerator() { return value.teamModerator(); }
 @ZenGetter("serverOperator") public boolean serverOperator() { return value.serverOperator(); }
 @ZenMethod public String standardDecision(String action) {
  return ResearchPermissionPolicy.DEFAULT.can(ResearchPermissionPolicy.Action.valueOf(action.toUpperCase(Locale.ROOT)),value).name();
 }
}
