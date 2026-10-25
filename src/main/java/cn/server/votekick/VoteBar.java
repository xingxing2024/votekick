package cn.server.votekick;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 投票期间所有在线玩家顶部的实时进度条：票数进度、颜色随进度变化、每秒刷新剩余时间。
 * BOSS 条只往连接里写包，不需要在玩家线程上更新；定时器跑在全局区域线程。
 */
final class VoteBar {

    private final VoteKickPlugin plugin;
    private volatile BossBar bar;
    private ScheduledTask task;

    VoteBar(VoteKickPlugin plugin) {
        this.plugin = plugin;
    }

    void show(Vote vote) {
        hide();
        if (!plugin.bossBarEnabled()) {
            return;
        }
        bar = BossBar.bossBar(title(vote), progress(vote), color(vote), BossBar.Overlay.PROGRESS);
        for (Player online : Bukkit.getOnlinePlayers()) {
            bar.addViewer(online);
        }
        task = Schedulers.globalTimer(plugin, () -> {
            Vote current = plugin.vote();
            if (current == null) {
                hide();
                return;
            }
            update(current);
        }, 20L, 20L);
    }

    void update(Vote vote) {
        if (bar == null) {
            return;
        }
        bar.name(title(vote));
        bar.progress(progress(vote));
        bar.color(color(vote));
    }

    void hide() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        if (bar != null) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                bar.removeViewer(online);
            }
            bar = null;
        }
    }

    void add(Player player) {
        if (bar != null) {
            bar.addViewer(player);
        }
    }

    void remove(Player player) {
        if (bar != null) {
            bar.removeViewer(player);
        }
    }

    private Component title(Vote vote) {
        return plugin.messages().plain("bossbar",
                "target", vote.targetName(),
                "yes", vote.yes(), "need", vote.required(), "no", vote.no(),
                "time", plugin.remainingSeconds());
    }

    private float progress(Vote vote) {
        if (vote.required() <= 0) {
            return 0f;
        }
        return Math.max(0f, Math.min(1f, vote.yes() / (float) vote.required()));
    }

    private BossBar.Color color(Vote vote) {
        float progress = progress(vote);
        if (progress >= 1f) {
            return BossBar.Color.GREEN;
        }
        return progress >= 0.5f ? BossBar.Color.YELLOW : BossBar.Color.RED;
    }
}
