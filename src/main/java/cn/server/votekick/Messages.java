package cn.server.votekick;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** 从 config.yml 的 messages 段读取提示，统一处理前缀、& 颜色和 %占位符%。 */
final class Messages {

    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final JavaPlugin plugin;
    /** reload 在全局线程上清空重填，界面线程同时在读，用并发 Map。 */
    private final Map<String, String> texts = new ConcurrentHashMap<>();
    private volatile String prefix = "";

    Messages(JavaPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    void reload() {
        texts.clear();
        ConfigurationSection section = plugin.getConfig().getConfigurationSection("messages");
        if (section != null) {
            for (String key : section.getKeys(false)) {
                Object raw = section.get(key);
                // 允许写成列表（每项一行），比在一个字符串里塞 \n 好改。
                if (raw instanceof List<?> lines) {
                    texts.put(key, String.join("\n", lines.stream().map(String::valueOf).toList()));
                } else {
                    texts.put(key, section.getString(key, ""));
                }
            }
        }
        prefix = texts.getOrDefault("prefix", "");
    }

    void send(CommandSender to, String key, Object... placeholders) {
        to.sendMessage(component(key, placeholders));
    }

    void broadcast(String key, Object... placeholders) {
        broadcast(component(key, placeholders));
    }

    /** 逐人发，不调 Bukkit.broadcast：Folia 上从全局线程广播是未定义行为，发消息给玩家本身是安全的。 */
    void broadcast(Component component) {
        for (Player online : Bukkit.getOnlinePlayers()) {
            online.sendMessage(component);
        }
    }

    Component component(String key, Object... placeholders) {
        return LEGACY.deserialize(prefix + format(key, placeholders));
    }

    /** 不带前缀的组件，用于 GUI 标题和物品名称。 */
    Component plain(String key, Object... placeholders) {
        return LEGACY.deserialize(format(key, placeholders));
    }

    /** 把一条多行提示拆成组件列表，用作界面物品的 lore。 */
    List<Component> plainLines(String key, Object... placeholders) {
        List<Component> lines = new ArrayList<>();
        for (String line : format(key, placeholders).split("\n")) {
            lines.add(LEGACY.deserialize(line));
        }
        return lines;
    }

    /** 不含前缀的原始文本，用于把一条提示当作另一条提示的占位符值。 */
    String format(String key, Object... placeholders) {
        String text = texts.get(key);
        if (text == null) {
            plugin.getLogger().warning("缺少提示文本 messages." + key);
            return "";
        }
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            text = text.replace("%" + placeholders[i] + "%", String.valueOf(placeholders[i + 1]));
        }
        return text;
    }

    /** 秒数转成人看的时长，单位文案取自 messages.duration-*，只拼接非零的部分。 */
    String duration(long seconds) {
        long hours = seconds / 3600;
        long minutes = seconds % 3600 / 60;
        long secs = seconds % 60;
        StringBuilder text = new StringBuilder();
        if (hours > 0) {
            text.append(format("duration-hour", "value", hours)).append(' ');
        }
        if (minutes > 0) {
            text.append(format("duration-minute", "value", minutes)).append(' ');
        }
        if (secs > 0 || text.isEmpty()) {
            text.append(format("duration-second", "value", secs));
        }
        return text.toString().trim();
    }
}
