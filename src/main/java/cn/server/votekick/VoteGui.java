package cn.server.votekick;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;

import io.papermc.paper.threadedregions.scheduler.ScheduledTask;

/**
 * 三种界面：选人列表、恶意投票提醒确认、投票进行中。
 * 所有点击都被拦截，物品只当按钮用；界面打开期间每秒刷新一次。
 * Folia 上界面属于玩家所在区域，只跟踪自己打开的界面，刷新也回到玩家线程做。
 */
final class VoteGui implements Listener {

    private static final int SIZE = 54;
    private static final int LIST_SLOTS = 45;
    private static final int CONFIRM_SIZE = 27;
    private static final int TARGET_SLOT = 13;
    private static final int YES_SLOT = 20;
    private static final int STATUS_SLOT = 22;
    private static final int NO_SLOT = 24;
    private static final int CLOSE_SLOT = 45;
    private static final int CANCEL_SLOT = 49;
    private static final int PREV_SLOT = 48;
    private static final int PAGE_SLOT = 49;
    private static final int NEXT_SLOT = 50;
    private static final int LIST_CLOSE_SLOT = 53;
    private static final int CONFIRM_INFO_SLOT = 4;
    private static final int CONFIRM_YES_SLOT = 11;
    private static final int CONFIRM_NO_SLOT = 15;
    private static final int CONFIRM_CLOSE_SLOT = 22;

    private enum Kind { LIST, CONFIRM, VOTE }

    private final VoteKickPlugin plugin;
    /** 当前打开着的界面；每个界面属于一个玩家，刷新要回到那个玩家的线程。 */
    private final Set<MenuHolder> openMenus = ConcurrentHashMap.newKeySet();
    private ScheduledTask ticker;

    VoteGui(VoteKickPlugin plugin) {
        this.plugin = plugin;
    }

    void open(Player player) {
        Kind kind = kindFor(player);
        MenuHolder holder = new MenuHolder(kind, player.getUniqueId());
        String titleKey = switch (kind) {
            case CONFIRM -> "gui-title-confirm";
            case VOTE -> "gui-title-vote";
            case LIST -> "gui-title-players";
        };
        Inventory inventory = Bukkit.createInventory(holder, kind == Kind.CONFIRM ? CONFIRM_SIZE : SIZE,
                plugin.messages().plain(titleKey));
        holder.inventory = inventory;
        fill(holder, player);
        player.openInventory(inventory);
        openMenus.add(holder);
        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.6f);
        ensureTicker();
    }

    /** 投票状态或在线人数变化时刷新所有打开着的界面；界面类型变了就重开。 */
    void refreshAll() {
        for (MenuHolder holder : openMenus) {
            Player viewer = Bukkit.getPlayer(holder.viewer);
            if (viewer == null) {
                openMenus.remove(holder);
                continue;
            }
            Schedulers.onPlayer(plugin, viewer, () -> refresh(holder, viewer));
        }
        if (openMenus.isEmpty()) {
            stopTicker();
        } else {
            ensureTicker();
        }
    }

    private void refresh(MenuHolder holder, Player viewer) {
        if (viewer.getOpenInventory().getTopInventory().getHolder() != holder) {
            openMenus.remove(holder);
            return;
        }
        if (holder.kind != kindFor(viewer)) {
            open(viewer);
        } else {
            fill(holder, viewer);
        }
    }

    private synchronized void ensureTicker() {
        if (ticker == null) {
            ticker = Schedulers.globalTimer(plugin, this::refreshAll, 20L, 20L);
        }
    }

    private synchronized void stopTicker() {
        if (ticker != null) {
            ticker.cancel();
            ticker = null;
        }
    }

    /** 这个玩家现在该看哪个界面：自己有待确认的发起 → 确认界面；有投票 → 投票界面；否则列表。 */
    private Kind kindFor(Player viewer) {
        VoteKickPlugin.Pending pending = plugin.pending();
        if (pending != null && pending.starter().equals(viewer.getUniqueId())) {
            return Kind.CONFIRM;
        }
        return plugin.vote() != null ? Kind.VOTE : Kind.LIST;
    }

    private void fill(MenuHolder holder, Player viewer) {
        holder.inventory.clear();
        holder.targets.clear();
        switch (holder.kind) {
            case LIST -> fillPlayers(holder, viewer);
            case VOTE -> fillVote(holder, viewer);
            case CONFIRM -> fillConfirm(holder);
        }
    }

    private void fillVote(MenuHolder holder, Player viewer) {
        Vote vote = plugin.vote();
        if (vote == null) {
            return;
        }
        border(holder.inventory, SIZE);
        Component progress = progressBar(vote);
        Component yesLine = plugin.messages().plain("gui-lore-yes", "yes", vote.yes(), "need", vote.required());
        Component noLine = plugin.messages().plain("gui-lore-no", "no", vote.no());
        holder.inventory.setItem(TARGET_SLOT, head(Bukkit.getPlayer(vote.target()),
                plugin.messages().plain("gui-target-name", "target", vote.targetName()),
                List.of(plugin.messages().plain("gui-lore-reason", "reason", vote.reason()),
                        yesLine, noLine)));
        holder.inventory.setItem(STATUS_SLOT, button(Material.CLOCK, "gui-status-name",
                List.of(progress, plugin.messages().plain("gui-lore-time", "time", plugin.remainingSeconds()))));
        holder.inventory.setItem(YES_SLOT, button(Material.LIME_CONCRETE, "gui-yes-name", List.of(progress, yesLine)));
        holder.inventory.setItem(NO_SLOT, button(Material.RED_CONCRETE, "gui-no-name", List.of(progress, noLine)));
        holder.inventory.setItem(CLOSE_SLOT, button(Material.BARRIER, "gui-close-name", List.of()));
        if (viewer.getUniqueId().equals(plugin.starter()) || viewer.hasPermission("votekick.admin")) {
            holder.inventory.setItem(CANCEL_SLOT, button(Material.REDSTONE_BLOCK, "gui-cancel-name",
                    List.of(plugin.messages().plain("gui-cancel-lore"))));
        }
    }

    /** 恶意投票提醒：整段提示文字原样铺在纸上看，下面才是确认 / 放弃。 */
    private void fillConfirm(MenuHolder holder) {
        VoteKickPlugin.Pending pending = plugin.pending();
        if (pending == null) {
            return;
        }
        border(holder.inventory, CONFIRM_SIZE);
        holder.inventory.setItem(CONFIRM_INFO_SLOT, button(Material.PAPER, "gui-confirm-info-name",
                plugin.messages().plainLines("confirm-warning",
                        "target", pending.targetName(), "reason", pending.reason(),
                        "time", plugin.confirmSecondsLeft())));
        holder.inventory.setItem(TARGET_SLOT, head(Bukkit.getPlayer(pending.target()),
                plugin.messages().plain("gui-target-name", "target", pending.targetName()),
                List.of(plugin.messages().plain("gui-lore-reason", "reason", pending.reason()))));
        holder.inventory.setItem(CONFIRM_YES_SLOT, button(Material.LIME_CONCRETE, "gui-confirm-name",
                List.of(plugin.messages().plain("gui-confirm-lore", "time", plugin.confirmSecondsLeft()))));
        holder.inventory.setItem(CONFIRM_NO_SLOT, button(Material.RED_CONCRETE, "gui-confirm-cancel-name", List.of()));
        holder.inventory.setItem(CONFIRM_CLOSE_SLOT, button(Material.BARRIER, "gui-close-name", List.of()));
    }

    private void fillPlayers(MenuHolder holder, Player viewer) {
        List<Player> targets = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (!online.equals(viewer) && !online.hasPermission("votekick.immune")) {
                targets.add(online);
            }
        }
        targets.sort(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER));
        int pages = Math.max(1, (targets.size() + LIST_SLOTS - 1) / LIST_SLOTS);
        holder.page = Math.min(holder.page, pages - 1);
        int from = holder.page * LIST_SLOTS;
        for (int i = 0; i < LIST_SLOTS && from + i < targets.size(); i++) {
            Player target = targets.get(from + i);
            holder.inventory.setItem(i, head(target,
                    plugin.messages().plain("gui-player-name", "target", target.getName()),
                    List.of(plugin.messages().plain("gui-player-lore"))));
            holder.targets.put(i, target.getUniqueId());
        }
        if (targets.isEmpty()) {
            holder.inventory.setItem(STATUS_SLOT, button(Material.BARRIER, "gui-no-players", List.of()));
        }
        bottomBar(holder.inventory, holder.page, pages, targets.size());
    }

    /** 底部导航：上一页 / 页码 / 下一页 / 关闭，其余用玻璃板补齐。 */
    private void bottomBar(Inventory inventory, int page, int pages, int total) {
        for (int slot = LIST_SLOTS; slot < SIZE; slot++) {
            inventory.setItem(slot, filler());
        }
        if (page > 0) {
            inventory.setItem(PREV_SLOT, button(Material.ARROW, "gui-prev-name", List.of()));
        }
        inventory.setItem(PAGE_SLOT, button(Material.PAPER, "gui-page-name",
                List.of(plugin.messages().plain("gui-page-lore", "total", total)),
                "page", page + 1, "pages", pages));
        if (page < pages - 1) {
            inventory.setItem(NEXT_SLOT, button(Material.ARROW, "gui-next-name", List.of()));
        }
        inventory.setItem(LIST_CLOSE_SLOT, button(Material.BARRIER, "gui-close-name", List.of()));
    }

    private void border(Inventory inventory, int size) {
        for (int slot = 0; slot < size; slot++) {
            if (slot < 9 || slot >= size - 9 || slot % 9 == 0 || slot % 9 == 8) {
                inventory.setItem(slot, filler());
            }
        }
    }

    private Component progressBar(Vote vote) {
        int filled = vote.required() == 0 ? 0 : (int) Math.round(vote.yes() * 10.0 / vote.required());
        filled = Math.max(0, Math.min(10, filled));
        String bar = "&a" + "█".repeat(filled) + "&8" + "█".repeat(10 - filled);
        return plugin.messages().plain("gui-lore-progress", "bar", bar, "yes", vote.yes(), "need", vote.required());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }
        if (holder.kind != kindFor(player)) {
            open(player);
            return;
        }
        switch (holder.kind) {
            case CONFIRM -> {
                switch (slot) {
                    case CONFIRM_YES_SLOT -> {
                        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
                        Schedulers.global(plugin, () -> plugin.confirm(player));
                    }
                    case CONFIRM_NO_SLOT -> {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                        Schedulers.global(plugin, () -> plugin.cancel(player));
                    }
                    case CONFIRM_CLOSE_SLOT -> player.closeInventory();
                    default -> { }
                }
            }
            case VOTE -> {
                switch (slot) {
                    case YES_SLOT -> {
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 1.4f);
                        Schedulers.global(plugin, () -> plugin.cast(player, true));
                    }
                    case NO_SLOT -> {
                        player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.7f, 0.9f);
                        Schedulers.global(plugin, () -> plugin.cast(player, false));
                    }
                    case CLOSE_SLOT -> player.closeInventory();
                    case CANCEL_SLOT -> {
                        player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.6f, 1.0f);
                        Schedulers.global(plugin, () -> plugin.cancel(player));
                    }
                    default -> { }
                }
            }
            case LIST -> {
                if (slot == PREV_SLOT && holder.page > 0) {
                    holder.page--;
                    fill(holder, player);
                } else if (slot == NEXT_SLOT) {
                    holder.page++;
                    fill(holder, player);
                } else if (slot == LIST_CLOSE_SLOT) {
                    player.closeInventory();
                } else {
                    UUID targetId = holder.targets.get(slot);
                    Player target = targetId == null ? null : Bukkit.getPlayer(targetId);
                    if (target != null) {
                        player.closeInventory();
                        plugin.askReason(player, target.getName());
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getView().getTopInventory().getHolder() instanceof MenuHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (event.getInventory().getHolder() instanceof MenuHolder holder) {
            openMenus.remove(holder);
        }
    }

    private ItemStack filler() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack button(Material material, String nameKey, List<Component> lore, Object... placeholders) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.messages().plain(nameKey, placeholders));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack head(Player owner, Component name, List<Component> lore) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (owner != null) {
            meta.setPlayerProfile(owner.getPlayerProfile());
        }
        meta.displayName(name);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private static final class MenuHolder implements InventoryHolder {
        private final Kind kind;
        private final UUID viewer;
        private final Map<Integer, UUID> targets = new HashMap<>();
        private Inventory inventory;
        private int page;

        private MenuHolder(Kind kind, UUID viewer) {
            this.kind = kind;
            this.viewer = viewer;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
