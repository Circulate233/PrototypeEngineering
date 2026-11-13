package com.circulation.prototype_eng.container;

import ae2.container.AEBaseContainer;
import ae2.container.guisync.GuiSync;
import com.circulation.prototype_eng.api.research.ResearchPermissionPolicy.Decision;
import com.circulation.prototype_eng.api.research.ResearchRegistry;
import com.circulation.prototype_eng.api.research.manual.ManualResearchSession;
import com.circulation.prototype_eng.api.research.reward.RewardClaim;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Entry;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Request;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Selection;
import com.circulation.prototype_eng.container.ResearchGuiSnapshot.Tab;
import com.circulation.prototype_eng.localization.ResearchGuiText;
import com.circulation.prototype_eng.research.ServerResearch;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.entity.player.InventoryPlayer;
import net.minecraft.util.ResourceLocation;
import net.minecraft.world.World;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;
import java.util.UUID;

/**
 * Research uses AE2 window actions and synchronization, with no second packet channel or inventory.
 */
public final class ContainerResearch extends AEBaseContainer {
    private static final String VIEW = "research_view";
    private static final String ACT = "research_action";
    private static final String DATABASE_VIEW = "database_view";
    private static final String DATABASE_DOWNLOAD = "database_download";
    private static final String ITEM_DOWNLOAD = "research_item_download";
    private final World openedWorld;
    @GuiSync(0)
    public ResearchGuiSnapshot snapshot = ResearchGuiSnapshot.EMPTY;
    @GuiSync(1)
    public ResearchGuiText feedback = ResearchGuiText.Ready;
    @GuiSync(2)
    public boolean actionQueued;
    @GuiSync(3)
    public DataCenterGuiSnapshot databaseSnapshot = DataCenterGuiSnapshot.EMPTY;
    @GuiSync(4)
    public boolean databaseMode;
    @GuiSync(5)
    public String displayedTeam = "";
    private Request requested = new Request(Tab.RESEARCH, 0, null, 0);
    private DataCenterGuiSnapshot.Request databaseRequested = new DataCenterGuiSnapshot.Request(0, null);
    @Nullable
    private PendingAction pendingAction;
    @Nullable
    private PendingDatabaseAction pendingDatabaseAction;
    @Nullable
    private PendingItemAction pendingItemAction;
    private boolean boundaryQueued;
    private boolean closed;
    private int refreshTicks;

    public ContainerResearch(InventoryPlayer player) {
        super(player, null);
        openedWorld = player.player.world;
        setGuiTitle(ResearchGuiText.Title.text());
        registerClientAction(VIEW, View.class, 2048, this::acceptView);
        registerClientAction(ACT, Action.class, 2048, this::acceptAction);
        registerClientAction(DATABASE_VIEW, DatabaseView.class, 2048, this::acceptDatabaseView);
        registerClientAction(DATABASE_DOWNLOAD, DatabaseAction.class, 2048, this::acceptDatabaseAction);
        registerClientAction(ITEM_DOWNLOAD, ItemAction.class, 2048, this::acceptItemAction);
    }

    public static boolean canItemDownload(ResearchGuiSnapshot view, DataCenterGuiSnapshot database, Entry entry) {
        return view.initialized() && view.tab() == Tab.RESEARCH && !view.savePending() && entry.sharedLevel() > 0
            && view.teamViewPermission() == Decision.ALLOWED && database.initialized() && !database.savePending()
            && database.viewPermission() == Decision.ALLOWED && database.downloadPermission() == Decision.ALLOWED
            && database.dataCenterId() != null;
    }

    /**
     * Display admission is conservative; the server operation always rechecks current authority.
     */
    public static boolean allowed(ResearchGuiSnapshot view, Entry entry, Kind kind) {
        if (!view.initialized() || view.savePending()) return false;
        return switch (kind) {
            case START -> view.tab() == Tab.RESEARCH && view.startPermission() == Decision.ALLOWED
                && entry.targetLevel() > 0 && !entry.blacklisted();
            case BLACKLIST -> view.tab() == Tab.RESEARCH && view.teamViewPermission() == Decision.ALLOWED
                && view.blacklistPermission() == Decision.ALLOWED;
            case TAKEOVER -> view.tab() == Tab.QUEUE && view.queuePermission() == Decision.ALLOWED
                && entry.tier() == 0 && entry.manualStatus() == ManualResearchSession.Status.PAUSED;
            case FORGET -> view.tab() == Tab.RESEARCH && view.personalPermission() == Decision.ALLOWED
                && view.forgetPermission() == Decision.ALLOWED && entry.personalLevel() > 0;
            case ADMIN_PLAYER, ADMIN_TEAM -> view.tab() == Tab.RESEARCH && view.adminPermission() == Decision.ALLOWED;
            case CLAIM -> view.tab() == Tab.REWARDS && view.personalPermission() == Decision.ALLOWED
                && entry.claimStatus() != null && entry.claimStatus() != RewardClaim.Status.CLAIMED;
        };
    }

    /**
     * Team actions retain the last drawn owner; permanent personal reward claims do not.
     */
    public static boolean matchesDisplayedTeam(Kind kind, @Nullable String expectedTeam, String displayedTeam) {
        return Objects.requireNonNull(kind) == Kind.CLAIM || expectedTeam != null && expectedTeam.equals(displayedTeam);
    }

    /**
     * Bounded decimal intent parsing; invalid input never reaches Integer.parseInt.
     */
    public static int levelInput(@Nullable String text) {
        if (text == null || !text.matches("[0-9]{1,10}") || text.length() == 10 && text.compareTo("2147483647") > 0)
            return -1;
        return Integer.parseInt(text);
    }

    public static boolean playerTarget(@Nullable String text) {
        return text != null && text.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            && !text.equals("00000000-0000-0000-0000-000000000000");
    }

    public static boolean teamTarget(@Nullable String text) {
        if (text == null || text.isBlank() || text.length() > 256 || !text.equals(text.trim())) return false;
        for (int i = 0; i < text.length(); i++) if (Character.isISOControl(text.charAt(i))) return false;
        return true;
    }

    public void requestView(Tab tab, int offset, @Nullable Selection selected, int detailOffset) {
        sendClientAction(VIEW, new View(tab.ordinal(), offset, selected == null ? "" : selected.researchId().toString(),
            selected == null ? 0 : selected.level(), selected == null ? 0 : selected.entryId(), detailOffset));
    }

    public void requestAction(Kind kind, Entry entry, @Nullable ResourceLocation group, String expectedTeam) {
        sendClientAction(ACT, new Action(kind, entry.id().toString(), entry.targetLevel(), entry.entryId(),
            group == null ? "" : group.toString(), !entry.blacklisted(), expectedTeam));
    }

    public void requestLevelAction(Kind kind, Entry entry, String expectedTeam, int operationLevel, String target) {
        var action = new Action(kind, entry.id().toString(), entry.targetLevel(), entry.entryId(), "", false, expectedTeam);
        action.operationLevel = operationLevel;
        action.personalBefore = entry.personalLevel();
        action.target = target;
        sendClientAction(ACT, action);
    }

    public void requestDatabaseView(int offset, @Nullable DataCenterGuiSnapshot.Selection selected) {
        sendClientAction(DATABASE_VIEW, new DatabaseView(offset, selected == null ? "" : selected.researchId().toString(),
            selected == null ? 0 : selected.targetLevel()));
    }

    public void requestDatabaseDownload(DataCenterGuiSnapshot displayed, DataCenterGuiSnapshot.Entry entry) {
        if (displayed.dataCenterId() == null) return;
        sendClientAction(DATABASE_DOWNLOAD, new DatabaseAction(displayed.teamId(), displayed.dataCenterId().toString(),
            entry.id().toString(), entry.targetLevel()));
    }

    public void requestItemDownload(Entry entry, String expectedTeam, UUID databaseId, int slot) {
        sendClientAction(ITEM_DOWNLOAD, new ItemAction(expectedTeam, databaseId.toString(), entry.id().toString(),
            entry.sharedLevel(), entry.targetLevel(), slot, UUID.randomUUID().toString()));
    }

    private void acceptItemAction(@Nullable ItemAction action) {
        if (!current() || databaseMode || action == null || action.team == null || action.database == null
            || action.research == null || action.nonce == null || action.slot < 0 || action.slot >= 9) return;
        if (actionQueued) {
            feedback = ResearchGuiText.Busy;
            return;
        }
        var entry = find(action.research, action.target, 0);
        var database = databaseSnapshot;
        if (entry == null || !entry.selection().equals(snapshot.selected()) || action.level != entry.sharedLevel()
            || !displayedTeam.equals(action.team) || !database.teamId().equals(action.team)
            || database.dataCenterId() == null || !database.dataCenterId().toString().equals(action.database)
            || getPlayer().inventory.currentItem != action.slot) {
            feedback = ResearchGuiText.Stale;
            return;
        }
        if (!canItemDownload(snapshot, database, entry)) {
            feedback = ResearchGuiText.Forbidden;
            return;
        }
        // Match canonical UUID text before it can become a durable nonce.
        if (!action.nonce.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
            || action.nonce.equals("00000000-0000-0000-0000-000000000000")) return;
        pendingItemAction = new PendingItemAction(entry, action.team, database.dataCenterId(), action.slot, UUID.fromString(action.nonce));
        actionQueued = true;
        feedback = ResearchGuiText.ActionQueued;
    }

    private void acceptView(@Nullable View view) {
        if (!current() || view == null || view.tab < 0 || view.tab >= Tab.VALUES.length || view.offset < 0
            || view.detailOffset < 0 || view.research == null || view.level < 0 || view.entryId < 0) return;
        var tab = Tab.VALUES[view.tab];
        Selection selection = null;
        if (!view.research.isEmpty()) {
            if (tab != snapshot.tab()) return;
            var entry = find(view.research, view.level, view.entryId);
            if (entry == null) return;
            selection = new Selection(entry.id(), entry.targetLevel(), entry.entryId());
        }
        requested = new Request(tab, view.offset, selection, view.detailOffset);
        databaseMode = false;
        refreshTicks = 0;
    }

    private void acceptAction(@Nullable Action action) {
        if (!current() || databaseMode || action == null || action.kind == null || action.research == null || action.group == null)
            return;
        if (actionQueued) {
            feedback = ResearchGuiText.Busy;
            return;
        }
        if (!matchesDisplayedTeam(action.kind, action.expectedTeam, displayedTeam)) {
            feedback = ResearchGuiText.Stale;
            return;
        }
        if (!snapshot.initialized() || snapshot.savePending()) {
            feedback = ResearchGuiText.Saving;
            return;
        }
        var entry = find(action.research, action.level, action.entryId);
        if (entry == null || snapshot.selected() == null || !snapshot.selected().equals(new Selection(entry.id(), entry.targetLevel(), entry.entryId()))) {
            feedback = ResearchGuiText.Stale;
            return;
        }
        if (!allowed(snapshot, entry, action.kind)) {
            feedback = ResearchGuiText.Forbidden;
            return;
        }
        if (action.kind == Kind.FORGET || action.kind == Kind.ADMIN_PLAYER || action.kind == Kind.ADMIN_TEAM) {
            var definition = ResearchRegistry.INSTANCE.require(entry.id());
            if (action.operationLevel == null || action.kind == Kind.FORGET && action.personalBefore == null
                || action.operationLevel < (action.kind == Kind.FORGET ? 1 : 0) || action.operationLevel > definition.maxLevel()
                || action.kind == Kind.FORGET && action.personalBefore != entry.personalLevel()
                || action.kind == Kind.ADMIN_PLAYER && !playerTarget(action.target)
                || action.kind == Kind.ADMIN_TEAM && !teamTarget(action.target)) {
                feedback = ResearchGuiText.InvalidLevelAction;
                return;
            }
        }
        ResourceLocation group = null;
        if (action.kind == Kind.CLAIM) {
            // Match canonical server IDs rather than parsing arbitrary client ResourceLocations.
            if (entry.selectedGroup() != null && entry.selectedGroup().toString().equals(action.group))
                group = entry.selectedGroup();
            for (var detail : snapshot.details()) {
                if (detail.kind() == ResearchGuiSnapshot.Kind.REWARD_CHOICE && detail.id().toString().equals(action.group))
                    group = detail.id();
            }
            if (group == null || entry.selectedGroup() != null && !entry.selectedGroup().equals(group)) {
                feedback = ResearchGuiText.UnknownGroup;
                return;
            }
        }
        pendingAction = new PendingAction(action.kind, entry, group, action.blacklisted, displayedTeam, action.operationLevel == null ? 0 : action.operationLevel, action.personalBefore == null ? 0 : action.personalBefore, action.target);
        actionQueued = true;
        feedback = ResearchGuiText.ActionQueued;
    }

    private void acceptDatabaseView(@Nullable DatabaseView view) {
        if (!current() || view == null || view.offset < 0 || view.level < 0 || view.research == null) return;
        DataCenterGuiSnapshot.Selection selected = null;
        if (!view.research.isEmpty()) {
            if (!databaseMode) return;
            for (var row : databaseSnapshot.rows()) {
                if (row.id().toString().equals(view.research) && row.targetLevel() == view.level)
                    selected = row.selection();
            }
            if (selected == null) return;
        }
        databaseRequested = new DataCenterGuiSnapshot.Request(view.offset, selected);
        databaseMode = true;
        refreshTicks = 0;
    }

    private void acceptDatabaseAction(@Nullable DatabaseAction action) {
        if (!current() || !databaseMode || action == null || action.team == null || action.database == null || action.research == null)
            return;
        if (actionQueued) {
            feedback = ResearchGuiText.Busy;
            return;
        }
        var view = databaseSnapshot;
        if (!view.initialized() || view.savePending()) {
            feedback = ResearchGuiText.Saving;
            return;
        }
        if (view.dataCenterId() == null) {
            feedback = ResearchGuiText.NoDatabase;
            return;
        }
        if (!view.teamId().equals(action.team) || !view.dataCenterId().toString().equals(action.database)) {
            feedback = ResearchGuiText.Stale;
            return;
        }
        DataCenterGuiSnapshot.Entry selected = null;
        for (var row : view.rows()) {
            if (row.id().toString().equals(action.research) && row.targetLevel() == action.level) selected = row;
        }
        if (selected == null || !selected.selection().equals(view.selected())) {
            feedback = ResearchGuiText.Stale;
            return;
        }
        if (!view.canDownload(selected)) {
            feedback = ResearchGuiText.Forbidden;
            return;
        }
        pendingDatabaseAction = new PendingDatabaseAction(view.teamId(), view.dataCenterId(), selected);
        actionQueued = true;
        feedback = ResearchGuiText.ActionQueued;
    }

    @Nullable
    private Entry find(String id, int level, long entryId) {
        for (var entry : snapshot.rows()) {
            if (entry.id().toString().equals(id) && entry.targetLevel() == level && entry.entryId() == entryId)
                return entry;
        }
        return null;
    }

    @Override
    public boolean canInteractWith(EntityPlayer player) {
        if (closed || player != getPlayer() || player.world != openedWorld || !player.isEntityAlive() || !super.canInteractWith(player))
            return false;
        return !(player instanceof EntityPlayerMP actor) || actor.getServer() != null
            && actor.getServer().getPlayerList().getPlayerByUUID(actor.getUniqueID()) == actor;
    }

    private boolean current() {
        return isServerSide() && getPlayer().openContainer == this && canInteractWith(getPlayer());
    }

    @Override
    public void onContainerClosed(@NotNull EntityPlayer player) {
        closed = true;
        pendingAction = null;
        pendingDatabaseAction = null;
        pendingItemAction = null;
        super.onContainerClosed(player);
    }

    @Override
    public void broadcastChanges() {
        if (current() && !boundaryQueued) {
            if (pendingAction != null) executePending();
            else if (pendingDatabaseAction != null) executeDatabasePending();
            else if (pendingItemAction != null) executeItemPending();
            else if (--refreshTicks <= 0) capture();
        }
        super.broadcastChanges();
    }

    private void capture() {
        if (databaseMode) {
            captureDatabase();
            return;
        }
        boundaryQueued = true;
        var request = requested;
        ServerResearch.requestView((EntityPlayerMP) getPlayer(), this::current, view -> {
            if (view != null && current() && !databaseMode && requested == request) {
                snapshot = ResearchGuiSnapshot.capture(view, ResearchRegistry.INSTANCE, request);
                displayedTeam = view.team().id();
            }
            ServerResearch.requestDataCenterView((EntityPlayerMP) getPlayer(), this::current, database -> {
                try {
                    if (database != null && current() && !databaseMode && requested == request) {
                        databaseSnapshot = DataCenterGuiSnapshot.capture(database, ResearchRegistry.INSTANCE, databaseRequested);
                    }
                    refreshTicks = !databaseMode && requested == request ? 10 : 0;
                } finally {
                    boundaryQueued = false;
                }
            });
        });
    }

    private void captureDatabase() {
        boundaryQueued = true;
        var request = databaseRequested;
        ServerResearch.requestDataCenterView((EntityPlayerMP) getPlayer(), this::current, view -> {
            try {
                if (view != null && current() && databaseMode && databaseRequested == request) {
                    databaseSnapshot = DataCenterGuiSnapshot.capture(view, ResearchRegistry.INSTANCE, request);
                }
                refreshTicks = databaseMode && databaseRequested == request ? 10 : 0;
            } finally {
                boundaryQueued = false;
            }
        });
    }

    private void executeDatabasePending() {
        var action = Objects.requireNonNull(pendingDatabaseAction);
        pendingDatabaseAction = null;
        boundaryQueued = true;
        ServerResearch.requestDataCenterDownload((EntityPlayerMP) getPlayer(), action.team, action.database,
            action.entry.id(), action.entry.targetLevel(), this::current, result -> finished(ResearchGuiText.database(result)));
    }

    private void executeItemPending() {
        var action = Objects.requireNonNull(pendingItemAction);
        pendingItemAction = null;
        boundaryQueued = true;
        ServerResearch.requestItemDownload((EntityPlayerMP) getPlayer(), action.team, action.database,
            action.entry.id(), action.entry.sharedLevel(), action.slot, action.nonce, this::current,
            result -> finished(ResearchGuiText.itemDownload(result)));
    }

    private void executePending() {
        var action = Objects.requireNonNull(pendingAction);
        pendingAction = null;
        boundaryQueued = true;
        var actor = (EntityPlayerMP) getPlayer();
        var entry = action.entry;
        try {
            switch (action.kind) {
                case FORGET ->
                    ServerResearch.requestForget(actor, entry.id(), action.operationLevel, action.team, action.personalBefore,
                        this::current, result -> finished(ResearchGuiText.levelAction(result)), this::actionFinished);
                case ADMIN_PLAYER ->
                    ServerResearch.requestAdminPlayerLevel(actor, UUID.fromString(Objects.requireNonNull(action.target)), entry.id(),
                        action.operationLevel, action.team, this::current, result -> finished(ResearchGuiText.levelAction(result)), this::actionFinished);
                case ADMIN_TEAM ->
                    ServerResearch.requestAdminTeamLevel(actor, Objects.requireNonNull(action.target), entry.id(), action.operationLevel,
                        action.team, this::current, result -> finished(ResearchGuiText.levelAction(result)), this::actionFinished);
                case START ->
                    ServerResearch.requestStart(actor, entry.id(), entry.targetLevel(), action.team, this::current,
                        result -> finished(ResearchGuiText.start(result.status())), this::actionFinished);
                case TAKEOVER ->
                    ServerResearch.requestTakeover(actor, entry.id(), entry.targetLevel(), action.team, entry.entryId(), this::current,
                        result -> finished(ResearchGuiText.manual(result)), this::actionFinished);
                case BLACKLIST ->
                    ServerResearch.requestBlacklist(actor, entry.id(), action.blacklisted, action.team, this::current,
                        result -> finished(ResearchGuiText.permission(result)), this::actionFinished);
                case CLAIM ->
                    ServerResearch.requestReward(actor, entry.id(), entry.targetLevel(), Objects.requireNonNull(action.group), this::current,
                        result -> finished(ResearchGuiText.reward(result.status())), this::actionFinished);
            }
        } catch (RuntimeException | Error failure) {
            actionFinished();
            throw failure;
        }
    }

    private void finished(ResearchGuiText result) {
        try {
            if (current()) feedback = result;
        } finally {
            actionFinished();
        }
    }

    private void actionFinished() {
        boundaryQueued = false;
        actionQueued = false;
        refreshTicks = 0;
    }

    public enum Kind {START, TAKEOVER, BLACKLIST, CLAIM, FORGET, ADMIN_PLAYER, ADMIN_TEAM}

    public static final class View {
        int tab;
        int offset;
        String research;
        int level;
        long entryId;
        int detailOffset;

        @SuppressWarnings("unused") // AE2's default Gson codec constructs and fills this wire DTO.
        public View() {
        }

        public View(int tab, int offset, String research, int level, long entryId, int detailOffset) {
            this.tab = tab;
            this.offset = offset;
            this.research = research;
            this.level = level;
            this.entryId = entryId;
            this.detailOffset = detailOffset;
        }
    }

    public static final class Action {
        Kind kind;
        String research;
        int level;
        long entryId;
        String group;
        boolean blacklisted;
        @Nullable String expectedTeam;
        @Nullable Integer operationLevel;
        @Nullable Integer personalBefore;
        @Nullable String target;

        @SuppressWarnings("unused") // AE2's default Gson codec constructs and fills this wire DTO.
        public Action() {
        }

        public Action(Kind kind, String research, int level, long entryId, String group, boolean blacklisted, @Nullable String expectedTeam) {
            this.kind = kind;
            this.research = research;
            this.level = level;
            this.entryId = entryId;
            this.group = group;
            this.blacklisted = blacklisted;
            this.expectedTeam = expectedTeam;
        }
    }

    public static final class DatabaseView {
        int offset;
        String research;
        int level;

        @SuppressWarnings("unused") // AE2's default Gson codec constructs and fills this wire DTO.
        public DatabaseView() {
        }

        public DatabaseView(int offset, String research, int level) {
            this.offset = offset;
            this.research = research;
            this.level = level;
        }
    }

    public static final class DatabaseAction {
        String team;
        String database;
        String research;
        int level;

        @SuppressWarnings("unused") // AE2's default Gson codec constructs and fills this wire DTO.
        public DatabaseAction() {
        }

        public DatabaseAction(String team, String database, String research, int level) {
            this.team = team;
            this.database = database;
            this.research = research;
            this.level = level;
        }
    }

    private record PendingAction(Kind kind, Entry entry, @Nullable ResourceLocation group, boolean blacklisted,
                                 String team, int operationLevel, int personalBefore, @Nullable String target) {
    }

    private record PendingDatabaseAction(String team, UUID database, DataCenterGuiSnapshot.Entry entry) {
    }

    private record PendingItemAction(Entry entry, String team, UUID database, int slot, UUID nonce) {
    }

    public static final class ItemAction {
        String team;
        String database;
        String research;
        int level;
        int target;
        int slot;
        String nonce;

        @SuppressWarnings("unused") // AE2 Gson constructs this bounded window action.
        public ItemAction() {
        }

        public ItemAction(String team, String database, String research, int level, int target, int slot, String nonce) {
            this.team = team;
            this.database = database;
            this.research = research;
            this.level = level;
            this.target = target;
            this.slot = slot;
            this.nonce = nonce;
        }
    }
}
