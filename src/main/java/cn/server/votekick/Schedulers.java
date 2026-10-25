package cn.server.votekick;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * Paper 和 Folia 的调度差异只在这里出现。
 * 共享状态（投票、待确认、BOSS 条）跑在全局区域线程；碰玩家界面 / 实体的操作跑在该玩家自己的线程。
 * Paper 上两者都是主线程，行为与单线程版本完全一致。
 */
final class Schedulers {

    private static final boolean FOLIA = hasClass("io.papermc.paper.threadedregions.RegionizedServer");

    private Schedulers() {
    }

    static boolean folia() {
        return FOLIA;
    }

    /** 在全局区域线程上执行；Paper 上就是主线程。 */
    static void global(Plugin plugin, Runnable task) {
        if (!FOLIA && Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        Bukkit.getGlobalRegionScheduler().run(plugin, scheduled -> task.run());
    }

    /** 延迟 tps 个 tick 后在全局区域线程上执行。 */
    static ScheduledTask globalLater(Plugin plugin, Runnable task, long ticks) {
        return Bukkit.getGlobalRegionScheduler().runDelayed(plugin,
                scheduled -> task.run(), Math.max(1, ticks));
    }

    /** 定时任务，跑在全局区域线程上。 */
    static ScheduledTask globalTimer(Plugin plugin, Runnable task, long delayTicks, long periodTicks) {
        return Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin,
                scheduled -> task.run(), Math.max(1, delayTicks), Math.max(1, periodTicks));
    }

    /** 在这个玩家自己的线程上执行；Folia 上会等该玩家所在的区域轮到。 */
    static void onPlayer(Plugin plugin, Player player, Runnable task) {
        if (!FOLIA && Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }
        // 玩家已经下线时 retired 为 null：任务直接丢弃，反正界面和实体都没了。
        player.getScheduler().run(plugin, scheduled -> task.run(), null);
    }

    private static boolean hasClass(String name) {
        try {
            Class.forName(name);
            return true;
        } catch (ClassNotFoundException ex) {
            return false;
        }
    }
}
