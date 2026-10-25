package cn.server.votekick;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 玩家投票踢人：一人发起，在线玩家在时限内投票，同意票达到门槛就把目标踢出。
 * 同一时间只允许一场投票；名单在发起时固定，避免投票过程中人数变化导致门槛漂移。
 */
public final class VoteKickPlugin extends JavaPlugin implements Listener {

    /** 发起冷却：命令和界面在不同线程上读，用并发 Map。 */
    private final Map<UUID, Long> cooldowns = new ConcurrentHashMap<>();
    /** 点了头像、正在聊天里补理由的玩家 → 目标名字；聊天事件是异步线程，用并发 Map。 */
    private final Map<UUID, String> pendingReason = new ConcurrentHashMap<>();

    private Messages messages;
    private double percent;
    private int minVotes;
    private int durationSeconds;
    private int cooldownSeconds;
    private int maxReasonLength;
    private int confirmSeconds;
    private int banSeconds;
    private boolean confirmBroadcast;
    private boolean bossBar;

    /** 投票状态在全局区域线程上改，界面线程只读，所以发布用 volatile。 */
    private volatile Vote vote;
    private volatile UUID starter;
    private volatile String starterName;
    private volatile long endsAt;
    private ScheduledTask timeoutTask;
    private VoteGui gui;
    private final VoteBar bar = new VoteBar(this);
    private VoteLog voteLog;
    /** 投票通过后的禁入名单；登录前会读，所以放在主类里跟着插件一起活。 */
    private BanStore bans;
    /** 已通过全部检查、等着发起者点确认的那次发起；同一时间只允许一个。 */
    private volatile Pending pending;
    private ScheduledTask pendingTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        messages = new Messages(this);
        loadSettings();
        gui = new VoteGui(this);
        voteLog = new VoteLog(this);
        bans = new BanStore(getDataFolder().toPath().resolve("bans.txt"), getLogger());
        PluginCommand command = getCommand("vk");
        if (command != null) {
            VoteKickCommand executor = new VoteKickCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
        getServer().getPluginManager().registerEvents(this, this);
        getServer().getPluginManager().registerEvents(gui, this);
        getLogger().info("调度模式：" + (Schedulers.folia() ? "Folia 区域线程" : "Paper 主线程"));
    }

    @Override
    public void onDisable() {
        if (timeoutTask != null) {
            timeoutTask.cancel();
        }
        if (pendingTask != null) {
            pendingTask.cancel();
        }
        if (bar != null) {
            bar.hide();
        }
    }

    void reload() {
        Schedulers.global(this, () -> {
            reloadConfig();
            messages.reload();
            loadSettings();
            bans.reload();
            if (vote != null) {
                bar.show(vote);
            } else {
                bar.hide();
            }
        });
    }

    private void loadSettings() {
        percent = Math.min(1.0, Math.max(0.01, getConfig().getDouble("settings.percent", 0.6)));
        minVotes = Math.max(1, getConfig().getInt("settings.min-votes", 3));
        durationSeconds = Math.max(10, getConfig().getInt("settings.duration-seconds", 120));
        cooldownSeconds = Math.max(0, getConfig().getInt("settings.cooldown-seconds", 300));
        maxReasonLength = Math.max(1, getConfig().getInt("settings.max-reason-length", 60));
        confirmSeconds = Math.max(0, getConfig().getInt("settings.confirm-seconds", 30));
        banSeconds = Math.max(0, getConfig().getInt("settings.ban-seconds", 3600));
        confirmBroadcast = getConfig().getBoolean("settings.confirm-broadcast", false);
        bossBar = getConfig().getBoolean("settings.bossbar", true);
    }

    Messages messages() {
        return messages;
    }

    Vote vote() {
        return vote;
    }

    /** 等待发起者确认的发起；没有则为 null。 */
    Pending pending() {
        return pending;
    }

    /** 确认窗口剩余秒数。 */
    int confirmSecondsLeft() {
        return pending == null ? 0
                : (int) Math.max(0, (pending.expiresAt() - System.currentTimeMillis() + 999) / 1000);
    }

    VoteLog voteLog() {
        return voteLog;
    }

    UUID starter() {
        return starter;
    }

    int remainingSeconds() {
        return (int) Math.max(0, (endsAt - System.currentTimeMillis() + 999) / 1000);
    }

    boolean bossBarEnabled() {
        return bossBar;
    }

    void openMenu(Player player) {
        Schedulers.onPlayer(this, player, () -> gui.open(player));
    }

    /** GUI 点了头像：让玩家在聊天里补理由，填完再发起。 */
    void askReason(Player player, String targetName) {
        pendingReason.put(player.getUniqueId(), targetName);
        messages.send(player, "reason-prompt", "target", targetName);
        Schedulers.globalLater(this, () -> pendingReason.remove(player.getUniqueId(), targetName), 60 * 20L);
    }

    /** 补理由的那条聊天：取消广播，按内容发起投票或放弃。 */
    @EventHandler
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String targetName = pendingReason.remove(player.getUniqueId());
        if (targetName == null) {
            return;
        }
        event.setCancelled(true);
        String text = PlainTextComponentSerializer.plainText().serialize(event.message()).trim();
        Schedulers.global(this, () -> {
            if (text.isEmpty() || text.equalsIgnoreCase("cancel") || text.equals("取消")) {
                messages.send(player, "reason-cancelled");
                return;
            }
            start(player, targetName, text);
        });
    }

    private void refreshGui() {
        if (gui != null) {
            gui.refreshAll();
        }
    }

    /** 发起投票；所有规则检查都在这里，命令层和 GUI 只负责解析输入。 */
    boolean start(Player player, String targetName, String reason) {
        if (!player.hasPermission("votekick.start")) {
            messages.send(player, "no-permission");
            return false;
        }
        String text = reason == null ? "" : reason.trim();
        if (text.isEmpty()) {
            messages.send(player, "reason-required");
            return false;
        }
        Player target = Bukkit.getPlayerExact(targetName);
        if (target == null) {
            messages.send(player, "player-not-found", "target", targetName);
            return false;
        }
        if (target.equals(player)) {
            messages.send(player, "self");
            return false;
        }
        if (target.hasPermission("votekick.immune")) {
            messages.send(player, "immune", "target", target.getName());
            return false;
        }
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) {
            messages.send(player, "cooldown", "time", (until - now + 999) / 1000);
            return false;
        }
        if (vote != null) {
            messages.send(player, "vote-running", "target", vote.targetName());
            return false;
        }
        if (pending != null) {
            messages.send(player, "confirm-busy", "target", pending.targetName());
            return false;
        }
        if (text.length() > maxReasonLength) {
            text = text.substring(0, maxReasonLength);
        }
        if (!enoughPlayers(player, target)) {
            return false;
        }
        if (confirmSeconds <= 0) {
            return begin(player, target, text);
        }

        // 先只提醒恶意投票的后果，等发起者自己确认再真正开始。
        pending = new Pending(player.getUniqueId(), player.getName(),
                target.getUniqueId(), target.getName(), text, now + confirmSeconds * 1000L);
        pendingTask = Schedulers.globalLater(this, this::expirePending, confirmSeconds * 20L);
        messages.send(player, "confirm-warning",
                "target", target.getName(), "reason", text, "time", confirmSeconds);
        if (confirmBroadcast) {
            messages.broadcast("confirm-broadcast", "player", player.getName(), "target", target.getName());
        }
        refreshGui();
        return true;
    }

    /** 发起者确认提醒后真正开始投票；目标可能在这期间下线或被加免疫，所以整套检查再走一遍。 */
    boolean confirm(Player player) {
        Pending current = pending;
        if (current == null || !current.starter().equals(player.getUniqueId())) {
            messages.send(player, "confirm-none");
            return false;
        }
        Player target = Bukkit.getPlayer(current.target());
        String reason = current.reason();
        clearPending();
        if (target == null) {
            messages.send(player, "player-not-found", "target", current.targetName());
            refreshGui();
            return false;
        }
        if (target.equals(player)) {
            messages.send(player, "self");
            refreshGui();
            return false;
        }
        if (target.hasPermission("votekick.immune")) {
            messages.send(player, "immune", "target", target.getName());
            refreshGui();
            return false;
        }
        long now = System.currentTimeMillis();
        Long until = cooldowns.get(player.getUniqueId());
        if (until != null && until > now) {
            messages.send(player, "cooldown", "time", (until - now + 999) / 1000);
            refreshGui();
            return false;
        }
        if (vote != null) {
            messages.send(player, "vote-running", "target", vote.targetName());
            refreshGui();
            return false;
        }
        boolean started = begin(player, target, reason);
        refreshGui();
        return started;
    }

    /** 真正开始投票：门槛检查、广播、超时任务、BOSS 条、界面刷新。 */
    private boolean begin(Player player, Player target, String reason) {
        if (!enoughPlayers(player, target)) {
            return false;
        }
        Set<UUID> eligible = eligibleVoters(target);
        long now = System.currentTimeMillis();
        vote = new Vote(target.getUniqueId(), target.getName(), reason, eligible, percent, minVotes);
        starter = player.getUniqueId();
        starterName = player.getName();
        endsAt = now + durationSeconds * 1000L;
        cooldowns.put(player.getUniqueId(), now + cooldownSeconds * 1000L);
        messages.broadcast("started",
                "player", player.getName(), "target", target.getName(), "reason", reason,
                "yes", vote.yes(), "no", vote.no(), "need", vote.required(), "time", durationSeconds);
        broadcastButtons();
        timeoutTask = Schedulers.globalLater(this, () -> {
            if (vote != null) {
                finish(vote.result(), false, "log-timeout");
            }
        }, durationSeconds * 20L);
        bar.show(vote);
        refreshGui();
        return true;
    }

    /** 除目标外的在线玩家，即本次投票的选民。 */
    private Set<UUID> eligibleVoters(Player target) {
        Set<UUID> eligible = new HashSet<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(target)) {
                eligible.add(online.getUniqueId());
            }
        }
        return eligible;
    }

    /** 人数够不够开这场投票；不够就提示。 */
    private boolean enoughPlayers(Player starterPlayer, Player target) {
        int eligible = eligibleVoters(target).size();
        int required = Vote.required(eligible, percent, minVotes);
        if (required > eligible) {
            messages.send(starterPlayer, "not-enough-players", "need", required, "eligible", eligible);
            return false;
        }
        return true;
    }

    /** 确认窗口到期：作废这次发起并告诉发起者。 */
    private void expirePending() {
        if (pending == null) {
            return;
        }
        Player starterPlayer = Bukkit.getPlayer(pending.starter());
        pending = null;
        pendingTask = null;
        if (starterPlayer != null) {
            messages.send(starterPlayer, "confirm-expired");
        }
        refreshGui();
    }

    private void clearPending() {
        pending = null;
        if (pendingTask != null) {
            pendingTask.cancel();
            pendingTask = null;
        }
    }

    /** 一次通过检查、等待发起者确认的发起。 */
    record Pending(UUID starter, String starterName, UUID target, String targetName, String reason, long expiresAt) {
    }

    /** 聊天里的可点击投票按钮，手机上也能用。 */
    private void broadcastButtons() {
        Component line = messages.plain("vote-button-yes")
                .clickEvent(ClickEvent.runCommand("/votekick yes"))
                .append(Component.text("   "))
                .append(messages.plain("vote-button-no").clickEvent(ClickEvent.runCommand("/votekick no")));
        messages.broadcast(line);
    }

    void cast(Player player, boolean agree) {
        Vote current = vote;
        if (current == null) {
            messages.send(player, "no-vote");
            return;
        }
        if (!current.canVote(player.getUniqueId())) {
            messages.send(player, "not-eligible");
            return;
        }
        current.vote(player.getUniqueId(), agree);
        messages.broadcast("vote-progress",
                "player", player.getName(),
                "side", messages.format(agree ? "side-yes" : "side-no"),
                "yes", current.yes(), "no", current.no(), "need", current.required());
        if (current.result() != Vote.Result.PENDING) {
            finish(current.result(), false,
                    current.result() == Vote.Result.PASSED ? "log-passed" : "log-failed");
            return;
        }
        bar.update(current);
        refreshGui();
    }

    void cancel(Player player) {
        Pending waiting = pending;
        if (waiting != null
                && (waiting.starter().equals(player.getUniqueId()) || player.hasPermission("votekick.admin"))) {
            clearPending();
            messages.send(player, "confirm-cancelled");
            refreshGui();
            return;
        }
        Vote current = vote;
        if (current == null) {
            messages.send(player, "no-vote");
            return;
        }
        if (!player.getUniqueId().equals(starter) && !player.hasPermission("votekick.admin")) {
            messages.send(player, "no-permission");
            return;
        }
        String starter = starterName;
        takeVote();
        voteLog.record(messages.format("log-cancelled"), current, starter);
        bar.hide();
        messages.broadcast("cancelled", "player", player.getName(), "target", current.targetName());
        refreshGui();
    }

    /** 禁入期内的登录界面；没被禁返回 null。登录事件和集成测试都走这里。 */
    Component banScreen(UUID id) {
        BanStore.Ban ban = bans.active(id);
        if (ban == null) {
            return null;
        }
        long left = Math.max(1, (ban.until() - System.currentTimeMillis() + 999) / 1000);
        return messages.component("ban-screen", "time", messages.duration(left), "reason", ban.reason());
    }

    /** 禁入期内登录直接挡掉；这里在登录线程（异步）上跑，只读并发 Map，不碰世界。 */
    @EventHandler
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        Component screen = banScreen(event.getUniqueId());
        if (screen != null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, screen);
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        bar.add(event.getPlayer());
        refreshGui();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingReason.remove(id);
        bar.remove(event.getPlayer());
        Schedulers.global(this, () -> {
            if (pending != null && (pending.starter().equals(id) || pending.target().equals(id))) {
                clearPending();
            }
            if (vote != null && vote.target().equals(id)) {
                if (banSeconds > 0) {
                    // 目标下线不算投票结束：票照算，通过后按 UUID 记禁入，他再进服时照样被挡住。
                    messages.broadcast("target-offline", "target", vote.targetName());
                    refreshGui();
                } else {
                    finish(Vote.Result.FAILED, true, "log-left");
                }
            } else {
                refreshGui();
            }
        });
    }

    /** 取出并清空当前投票，同时取消超时任务。 */
    private Vote takeVote() {
        Vote current = vote;
        vote = null;
        starter = null;
        starterName = null;
        if (timeoutTask != null) {
            timeoutTask.cancel();
            timeoutTask = null;
        }
        return current;
    }

    private void finish(Vote.Result result, boolean targetLeft, String logKey) {
        String starter = starterName;
        Vote current = takeVote();
        if (current == null) {
            return;
        }
        voteLog.record(messages.format(logKey), current, starter);
        try {
            if (targetLeft) {
                messages.broadcast("target-left", "target", current.targetName());
                return;
            }
            if (result == Vote.Result.PASSED) {
                messages.broadcast("passed",
                        "target", current.targetName(), "reason", current.reason(),
                        "yes", current.yes(), "no", current.no(), "need", current.required());
                // 禁入写在踢人之前：目标这时可能已经下线，靠 UUID 记录，他下次进服就会被挡。
                if (banSeconds > 0) {
                    bans.ban(current.target(), current.targetName(), current.reason(), banSeconds);
                    messages.broadcast("ban-applied", "target", current.targetName(),
                            "time", messages.duration(banSeconds));
                }
                Player target = Bukkit.getPlayer(current.target());
                if (target != null) {
                    Component screen = messages.component("kick-screen",
                            "yes", current.yes(), "reason", current.reason());
                    Schedulers.onPlayer(this, target, () -> target.kick(screen));
                }
            } else {
                messages.broadcast("failed",
                        "target", current.targetName(),
                        "yes", current.yes(), "no", current.no(), "need", current.required());
            }
        } finally {
            bar.hide();
            refreshGui();
        }
    }
}
