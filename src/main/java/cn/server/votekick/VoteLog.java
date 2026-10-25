package cn.server.votekick;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * 投票记录：每次投票结束往 plugins/VoteKick/logs/votes-yyyy-MM-dd.log 追加一行，
 * 管理员可以直接看文件，也可以用 /votekick history 在游戏里查。
 * 记录只写不改，读的时候从最新的文件往回找。
 */
final class VoteLog {

    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    private final VoteKickPlugin plugin;

    VoteLog(VoteKickPlugin plugin) {
        this.plugin = plugin;
    }

    /** result 是已格式化的结果词（通过 / 未通过 / 取消 ...），starter 是发起者名字。 */
    void record(String result, Vote vote, String starter) {
        String line = LocalDateTime.now().format(STAMP)
                + " | " + result
                + " | 目标=" + vote.targetName()
                + " | 发起=" + (starter == null ? "?" : starter)
                + " | 同意=" + vote.yes() + " 反对=" + vote.no() + " 所需=" + vote.required()
                + " | 理由=" + vote.reason();
        Path file = dir().resolve("votes-" + LocalDate.now().format(DAY) + ".log");
        try {
            Files.createDirectories(file.getParent());
            // 主线程直接追加：每次投票结束才写一行，文件很小，不值得为它引入异步写队列。
            Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException ex) {
            plugin.getLogger().warning("写入投票记录失败: " + ex);
        }
    }

    /** 最近的记录，新 → 旧；player 为空表示不按玩家过滤。最多返回 max 行。 */
    List<String> recent(String player, int max) {
        List<String> out = new ArrayList<>();
        Path dir = dir();
        if (!Files.isDirectory(dir)) {
            return out;
        }
        try (Stream<Path> stream = Files.list(dir)) {
            List<Path> files = stream
                    .filter(path -> path.getFileName().toString().matches("votes-\\d{4}-\\d{2}-\\d{2}\\.log"))
                    .sorted(Comparator.reverseOrder())
                    .toList();
            for (Path file : files) {
                List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
                for (int i = lines.size() - 1; i >= 0 && out.size() < max; i--) {
                    String line = lines.get(i);
                    if (player == null || mentions(line, player)) {
                        out.add(line);
                    }
                }
                if (out.size() >= max) {
                    break;
                }
            }
        } catch (IOException ex) {
            plugin.getLogger().warning("读取投票记录失败: " + ex);
        }
        return out;
    }

    /** 只认「目标=」「发起=」两个字段，避免玩家名互相包含时误匹配。 */
    private boolean mentions(String line, String player) {
        for (String part : line.split(" \\| ")) {
            if ((part.startsWith("目标=") || part.startsWith("发起="))
                    && part.substring(3).equalsIgnoreCase(player)) {
                return true;
            }
        }
        return false;
    }

    private Path dir() {
        return plugin.getDataFolder().toPath().resolve("logs");
    }
}
