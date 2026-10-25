package cn.server.votekick;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;

/** /vk &lt;玩家&gt; &lt;理由&gt; | confirm | yes | no | cancel | history | reload */
public final class VoteKickCommand implements CommandExecutor, TabCompleter {

    private final VoteKickPlugin plugin;

    VoteKickCommand(VoteKickPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player player) {
                plugin.openMenu(player);
            } else {
                plugin.messages().send(sender, "usage");
            }
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("reload")) {
            if (!sender.hasPermission("votekick.admin")) {
                plugin.messages().send(sender, "no-permission");
                return true;
            }
            plugin.reload();
            plugin.messages().send(sender, "reloaded");
            return true;
        }
        if (sub.equals("history")) {
            history(sender, Arrays.copyOfRange(args, 1, args.length));
            return true;
        }
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "player-only");
            return true;
        }
        switch (sub) {
            // 投票状态统一在全局区域线程上改，Paper 上就是主线程。
            case "yes", "y", "同意" -> Schedulers.global(plugin, () -> plugin.cast(player, true));
            case "no", "n", "反对" -> Schedulers.global(plugin, () -> plugin.cast(player, false));
            case "cancel" -> Schedulers.global(plugin, () -> plugin.cancel(player));
            case "confirm" -> Schedulers.global(plugin, () -> plugin.confirm(player));
            default -> {
                String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : "";
                Schedulers.global(plugin, () -> plugin.start(player, args[0], reason));
            }
        }
        return true;
    }

    /** /vk history [玩家] [页码]，每页 10 条，新记录在前。 */
    private void history(CommandSender sender, String[] args) {
        if (!sender.hasPermission("votekick.admin")) {
            plugin.messages().send(sender, "no-permission");
            return;
        }
        String player = null;
        int page = 1;
        for (String arg : args) {
            if (arg.matches("\\d+")) {
                page = Math.max(1, Integer.parseInt(arg));
            } else if (player == null) {
                player = arg;
            }
        }
        int size = 10;
        List<String> lines = plugin.voteLog().recent(player, page * size + 1);
        int from = (page - 1) * size;
        if (lines.size() <= from) {
            plugin.messages().send(sender, "history-empty", "player", player == null ? "*" : player);
            return;
        }
        plugin.messages().send(sender, "history-header",
                "player", player == null ? plugin.messages().format("history-all") : player,
                "page", page);
        for (int i = from; i < Math.min(lines.size(), from + size); i++) {
            plugin.messages().send(sender, "history-line", "line", lines.get(i));
        }
        if (lines.size() > from + size) {
            plugin.messages().send(sender, "history-more", "next", page + 1);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 2 && args[0].equalsIgnoreCase("history")) {
            String typed = args[1].toLowerCase(Locale.ROOT);
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .filter(name -> name.toLowerCase(Locale.ROOT).startsWith(typed))
                    .toList();
        }
        if (args.length != 1) {
            return List.of();
        }
        List<String> options = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) {
            options.add(online.getName());
        }
        if (plugin.vote() != null) {
            options.addAll(List.of("yes", "no", "cancel"));
        }
        if (sender instanceof Player player && plugin.pending() != null
                && plugin.pending().starter().equals(player.getUniqueId())) {
            options.addAll(List.of("confirm", "cancel"));
        }
        if (sender.hasPermission("votekick.admin")) {
            options.add("reload");
            options.add("history");
        }
        String typed = args[0].toLowerCase(Locale.ROOT);
        options.removeIf(option -> !option.toLowerCase(Locale.ROOT).startsWith(typed));
        return options;
    }
}
