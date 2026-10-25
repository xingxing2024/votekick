package cn.server.votekick.test;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import io.papermc.paper.chat.ChatRenderer;
import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerKickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

/** 仅用于本地隔离测试服务器：用真实假人走完整的 GUI 投票踢人流程。 */
public final class VoteKickIntegrationTest extends JavaPlugin implements Listener {

    private final List<String> results = new ArrayList<>();
    private String kickedName;
    private String kickReason;

    @Override
    public void onEnable() {
        getServer().getPluginManager().registerEvents(this, this);
        Bukkit.getScheduler().runTaskLater(this, () -> {
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp spawn VoteA");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp spawn VoteB");
            Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp spawn VoteC");
            Bukkit.getScheduler().runTaskLater(this, this::firstRound, 100L);
        }, 60L);
    }

    @EventHandler
    public void onKick(PlayerKickEvent event) {
        if (!event.getPlayer().getName().startsWith("Vote")) {
            return;
        }
        kickedName = event.getPlayer().getName();
        kickReason = PlainTextComponentSerializer.plainText().serialize(event.reason());
    }

    private void firstRound() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            Player b = Bukkit.getPlayerExact("VoteB");
            Player c = Bukkit.getPlayerExact("VoteC");
            check(a != null && b != null && c != null, "三个假人在线");
            check(Files.exists(Path.of("plugins/VoteKick/config.yml")), "config.yml 存在");
            check(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vk"), "vk 命令已注册");
            check(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vk reload"), "reload 可用");

            // 确认窗口 4 秒：先发起一次但故意不确认，看它会不会自己作废。
            check(c.performCommand("vk VoteA 测试超时"), "发起后进入待确认状态");
            check(pendingActive(), "待确认的发起已记录");
            check(!voteActive(), "确认前投票还没开始");
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, this::afterExpiry, 120L);
    }

    private void afterExpiry() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            check(!pendingActive(), "确认超时后待确认状态被清掉");
            check(!voteActive(), "确认超时后投票没有开始");

            check(a.performCommand("vk"), "打开 GUI");
            Inventory menu = top(a);
            check(menu.getSize() == 54, "未投票时显示 54 格玩家列表");
            check(headSlot(menu, "VoteB") >= 0 && headSlot(menu, "VoteC") >= 0, "列表显示其他玩家");
            check(headSlot(menu, "VoteA") < 0, "列表不显示自己");
            check(menu.getItem(53) != null && menu.getItem(53).getType() == Material.BARRIER, "列表有关闭按钮");

            Bukkit.dispatchCommand(a, "vk VoteB");
            check(!voteActive() && !pendingActive(), "指令不带理由时拒绝发起");

            int bSlot = headSlot(menu, "VoteB");
            click(a, bSlot);
            check(!voteActive(), "点头像后不直接发起，先要理由");
            chat(a, "刷屏广告");
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, this::afterReason, 20L);
    }

    private void afterReason() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            Player c = Bukkit.getPlayerExact("VoteC");
            check(pendingActive(), "补完理由后进入待确认状态");
            check(!voteActive(), "确认前投票仍未开始");

            a.performCommand("vk");
            Inventory confirm = top(a);
            check(confirm.getSize() == 27, "待确认时显示 27 格确认界面");
            check(confirm.getItem(11) != null && confirm.getItem(11).getType() == Material.LIME_CONCRETE, "有确认按钮");
            check(confirm.getItem(15) != null && confirm.getItem(15).getType() == Material.RED_CONCRETE, "有放弃按钮");
            check(confirm.getItem(4) != null && confirm.getItem(4).getType() == Material.PAPER, "提醒文字在界面里");
            check(loreContains(confirm.getItem(4), "恶意投票"), "提醒内容是恶意投票警告");
            check(headSlot(confirm, "VoteB") >= 0, "确认界面显示目标");

            click(a, 11);
            check(voteActive(), "点确认后投票开始");
            check(!pendingActive(), "确认后待确认状态清掉");

            BossBar bar = bossBar();
            check(bar != null, "投票开始后 BOSS 条出现");
            check(bar != null, "投票开始后 BOSS 条出现");
            check(barBarText(bar).contains("VoteB"), "BOSS 条显示目标: " + barBarText(bar));
            check(barBarText(bar).contains("0/2"), "BOSS 条显示票数进度: " + barBarText(bar));
            check(Math.abs(bar.progress() - 0f) < 0.001f, "初始进度为 0");
            check(bar.color() == BossBar.Color.RED, "初始进度颜色为红");

            a.performCommand("vk");
            Inventory menu = top(a);
            check(menu.getSize() == 54 && menu.getItem(13) != null
                    && menu.getItem(13).getType() == Material.PLAYER_HEAD, "投票界面显示目标头颅");
            check(menu.getItem(20) != null && menu.getItem(20).getType() == Material.LIME_CONCRETE, "有同意按钮");
            check(menu.getItem(24) != null && menu.getItem(24).getType() == Material.RED_CONCRETE, "有反对按钮");
            check(menu.getItem(49) != null && menu.getItem(49).getType() == Material.REDSTONE_BLOCK, "发起者能取消");
            check(loreContains(menu.getItem(22), "120"), "状态栏显示剩余时间");
            check(loreContains(menu.getItem(13), "刷屏广告"), "理由进入投票界面");

            click(a, 20);
            check(voteActive(), "只有一票时仍在进行");
            check(Math.abs(bossBar().progress() - 0.5f) < 0.001f, "投票后 BOSS 条进度为 50%");
            check(bossBar().color() == BossBar.Color.YELLOW, "超过一半前颜色为黄");

            check(c.performCommand("vk yes"), "第二名玩家可以投票");
            check(!voteActive(), "达到门槛后投票结束");
            check(bossBar() == null, "投票结束后 BOSS 条消失");

            String logs = readLogs();
            check(logs.contains("目标=VoteB"), "记录文件写入目标: " + logs);
            check(logs.contains("发起=VoteA"), "记录文件写入发起者: " + logs);
            check(logs.contains("通过"), "记录文件写入结果: " + logs);
            check(logs.contains("理由=刷屏广告"), "记录文件写入理由: " + logs);
            check(logs.contains("同意=2 反对=0 所需=2"), "记录文件写入票数: " + logs);
            check(Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "vk history VoteB"),
                    "history 命令可查询");
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, this::secondRound, 40L);
    }

    /** 读整个 logs 目录，用来断言记录内容。 */
    private String readLogs() throws Exception {
        Path dir = Path.of("plugins/VoteKick/logs");
        if (!Files.isDirectory(dir)) {
            return "";
        }
        try (var files = Files.list(dir)) {
            return String.join("\n", Files.readAllLines(files.findFirst().orElseThrow()));
        }
    }

    private void secondRound() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            Player b = Bukkit.getPlayerExact("VoteB");
            Player c = Bukkit.getPlayerExact("VoteC");
            check(b == null || !b.isOnline(), "目标已被踢出");
            check("VoteB".equals(kickedName), "收到目标的踢出事件");
            check(kickReason != null && kickReason.contains("投票"), "踢出界面使用配置文本: " + kickReason);
            Path bansFile = Path.of("plugins/VoteKick/bans.txt");
            check(Files.exists(bansFile) && Files.readString(bansFile).contains("VoteB"),
                    "投票通过后写入禁入记录: " + (Files.exists(bansFile) ? Files.readString(bansFile) : "缺失"));
            check(top(a).getSize() == 54 && headSlot(top(a), "VoteC") >= 0, "投票结束后界面自动切回列表");

            check(c != null && c.isOnline(), "投票人仍在线");
            c.performCommand("vk");
            int aSlot = headSlot(top(c), "VoteA");
            check(aSlot >= 0, "列表按在线玩家刷新");
            click(c, aSlot);
            check(!voteActive() && !pendingActive(), "点头像后先进理由输入");
            chat(c, "测试人数不足");
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, this::thirdRound, 20L);
    }

    /** 只剩两个玩家时门槛是 2 票、可投票人只有 1 个，应该在确认之前就被拒。 */
    private void thirdRound() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            Player c = Bukkit.getPlayerExact("VoteC");
            check(!voteActive() && !pendingActive(), "可投票人数不足时拒绝发起");
            check(a != null && a.isOnline(), "拒绝发起后目标仍在线");
            if (c != null) {
                c.closeInventory();
            }
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        // 补一个投票人，用来测「目标中途下线，投票照样算完」
        Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "fp spawn VoteD");
        Bukkit.getScheduler().runTaskLater(this, this::offlineTargetRound, 60L);
    }

    /** 目标在投票中下线：投票继续，通过后按 UUID 记禁入，这才是防离线逃避的关键。 */
    private void offlineTargetRound() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            Player c = Bukkit.getPlayerExact("VoteC");
            Player d = Bukkit.getPlayerExact("VoteD");
            check(d != null && d.isOnline(), "补上的投票人在线");
            a.performCommand("vk VoteC 下线逃避");
            check(pendingActive(), "发起对 VoteC 的投票，进入待确认");
            a.performCommand("vk confirm");
            check(voteActive(), "确认后投票开始");

            check(c != null && c.isOnline(), "目标在投票开始时还在线");
            // 真实断开连接：会走 PlayerQuitEvent，正是「下线逃避」的路径
            c.kick(Component.text("模拟下线"));
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        Bukkit.getScheduler().runTaskLater(this, this::afterTargetLeft, 40L);
    }

    private void afterTargetLeft() {
        try {
            Player a = Bukkit.getPlayerExact("VoteA");
            Player d = Bukkit.getPlayerExact("VoteD");
            check(Bukkit.getPlayerExact("VoteC") == null, "目标确实下线了");
            check(voteActive(), "目标下线后投票没有被作废");

            a.performCommand("vk yes");
            d.performCommand("vk yes");
            check(!voteActive(), "离线目标也能投出结果");

            String bans = Files.readString(Path.of("plugins/VoteKick/bans.txt"));
            check(bans.contains("VoteC"), "离线目标照样写入禁入记录: " + bans);

            // 假人不走真实登录流程，直接调插件的禁入判断，验证拦截界面真的能生成。
            String screen = banScreen("VoteC");
            check(screen != null, "禁入期内会被拦住登录");
            check(screen.contains("下线逃避") && screen.contains("小时"),
                    "登录界面显示理由和剩余时长: " + screen);
            check(banScreen("VoteA") == null, "没被禁的玩家正常放行");
        } catch (Throwable ex) {
            fail(ex);
            return;
        }
        finish();
    }

    /** 调插件的禁入判断：返回登录界面文字，没被禁返回 null。 */
    private String banScreen(String name) throws Exception {
        String line = Files.readString(Path.of("plugins/VoteKick/bans.txt")).lines()
                .filter(l -> !l.startsWith("#") && l.contains("|" + name + "|"))
                .findFirst()
                .orElse(null);
        UUID id = line == null ? UUID.randomUUID() : UUID.fromString(line.split("\\|")[0]);
        Plugin plugin = Bukkit.getPluginManager().getPlugin("VoteKick");
        Method method = plugin.getClass().getDeclaredMethod("banScreen", UUID.class);
        method.setAccessible(true);
        Component screen = (Component) method.invoke(plugin, id);
        return screen == null ? null : PlainTextComponentSerializer.plainText().serialize(screen);
    }

    private Inventory top(Player player) {
        return player.getOpenInventory().getTopInventory();
    }

    private void click(Player player, int slot) {
        Bukkit.getPluginManager().callEvent(new InventoryClickEvent(player.getOpenInventory(),
                InventoryType.SlotType.CONTAINER, slot, ClickType.LEFT, InventoryAction.PICKUP_ALL));
    }

    /** 合成一条聊天事件：本核心上 player.chat() 不触发 AsyncChatEvent，异步事件也只能异步触发。 */
    private void chat(Player player, String text) {
        Bukkit.getScheduler().runTaskAsynchronously(this, () -> {
            Component message = Component.text(text);
            Bukkit.getPluginManager().callEvent(new AsyncChatEvent(true, player,
                    new LinkedHashSet<Audience>(Bukkit.getOnlinePlayers()),
                    ChatRenderer.defaultRenderer(), message, message, null));
        });
    }

    private int headSlot(Inventory inventory, String name) {
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != Material.PLAYER_HEAD) {
                continue;
            }
            ItemMeta meta = item.getItemMeta();
            if (meta != null && meta.displayName() != null
                    && PlainTextComponentSerializer.plainText().serialize(meta.displayName()).equals(name)) {
                return slot;
            }
        }
        return -1;
    }

    private boolean loreContains(ItemStack item, String text) {
        if (item == null || item.getItemMeta() == null || item.getItemMeta().lore() == null) {
            return false;
        }
        return item.getItemMeta().lore().stream()
                .map(PlainTextComponentSerializer.plainText()::serialize)
                .anyMatch(line -> line.contains(text));
    }

    private boolean voteActive() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("VoteKick");
        Field field = plugin.getClass().getDeclaredField("vote");
        field.setAccessible(true);
        return field.get(plugin) != null;
    }

    /** 是否有正在等发起者确认的发起。 */
    private boolean pendingActive() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("VoteKick");
        Field field = plugin.getClass().getDeclaredField("pending");
        field.setAccessible(true);
        return field.get(plugin) != null;
    }

    /** 通过反射取插件当前显示中的 BOSS 条，没有投票时为 null。 */
    private BossBar bossBar() throws Exception {
        Plugin plugin = Bukkit.getPluginManager().getPlugin("VoteKick");
        Field holder = plugin.getClass().getDeclaredField("bar");
        holder.setAccessible(true);
        Object voteBar = holder.get(plugin);
        Field field = voteBar.getClass().getDeclaredField("bar");
        field.setAccessible(true);
        return (BossBar) field.get(voteBar);
    }

    private String barBarText(BossBar bar) {
        return PlainTextComponentSerializer.plainText().serialize(bar.name());
    }

    private void check(boolean success, String message) {
        results.add((success ? "PASS " : "FAIL ") + message);
        getLogger().info(results.getLast());
        if (!success) {
            throw new IllegalStateException(message);
        }
    }

    private void fail(Throwable ex) {
        results.add("FAIL " + ex);
        getLogger().log(java.util.logging.Level.SEVERE, "VoteKick integration test failed", ex);
        finish();
    }

    private void finish() {
        try {
            Files.write(Path.of("votekick-integration-results.txt"), results);
        } catch (Exception ex) {
            getLogger().severe(ex.toString());
        }
        Bukkit.shutdown();
    }
}
