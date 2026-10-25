package cn.server.votekick;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 投票通过后一段时间内禁止目标进服，按 UUID 记，人在不在线都生效，重启也不丢。
 * 记录放在 bans.txt（UUID|名字|到期毫秒|理由）：登录前是在异步线程上读的，所以用并发 Map；
 * 文件很小，改动时整体重写，不做增量。
 */
final class BanStore {

    /** 一条禁入记录；until 是到期时间（毫秒）。 */
    record Ban(String name, long until, String reason) {
    }

    private final Path file;
    private final Logger logger;
    /** 登录线程在异步读，reload 时整体换一个新 Map，不会出现"清空到填好"之间漏拦的窗口。 */
    private volatile Map<UUID, Ban> bans = new ConcurrentHashMap<>();

    BanStore(Path file, Logger logger) {
        this.file = file;
        this.logger = logger;
        reload();
    }

    /** 禁入 seconds 秒，返回到期时间（毫秒）。 */
    synchronized long ban(UUID id, String name, String reason, long seconds) {
        long until = System.currentTimeMillis() + seconds * 1000L;
        bans.put(id, new Ban(name, until, reason));
        save();
        return until;
    }

    /** 还没到期的记录；没被禁或已经到期返回 null，过期的顺手清掉。 */
    Ban active(UUID id) {
        Ban ban = bans.get(id);
        if (ban == null) {
            return null;
        }
        if (ban.until() <= System.currentTimeMillis()) {
            bans.remove(id, ban);
            return null;
        }
        return ban;
    }

    /** 重新读文件，顺便丢掉过期的行；管理员删掉某行后 /votekick reload 即可解禁。 */
    synchronized void reload() {
        Map<UUID, Ban> loaded = new ConcurrentHashMap<>();
        if (!Files.exists(file)) {
            bans = loaded;
            return;
        }
        long now = System.currentTimeMillis();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.trim();
                if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                    continue;
                }
                String[] parts = trimmed.split("\\|", 4);
                if (parts.length < 3) {
                    continue;
                }
                long until = Long.parseLong(parts[2]);
                if (until > now) {
                    loaded.put(UUID.fromString(parts[0]),
                            new Ban(parts[1], until, parts.length > 3 ? parts[3] : ""));
                }
            }
        } catch (IOException | IllegalArgumentException ex) {
            // 文件被手改坏了就当没有记录：宁可漏拦，也不要把所有玩家挡在门外。
            logger.log(Level.WARNING, "读取 " + file.getFileName() + " 失败，本次不启用禁入名单", ex);
            loaded = new ConcurrentHashMap<>();
        }
        bans = loaded;
    }

    private void save() {
        List<String> lines = new ArrayList<>();
        lines.add("# VoteKick 禁入名单：UUID|名字|到期毫秒|理由。删掉某行后 /votekick reload 即解禁。");
        long now = System.currentTimeMillis();
        for (Map.Entry<UUID, Ban> entry : bans.entrySet()) {
            Ban ban = entry.getValue();
            if (ban.until() > now) {
                lines.add(entry.getKey() + "|" + ban.name() + "|" + ban.until()
                        + "|" + ban.reason().replace('|', '/'));
            }
        }
        try {
            Files.createDirectories(file.getParent());
            Files.write(file, lines, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            // 写不进去只影响重启后还记不记得，当前进程里照样拦人。
            logger.log(Level.WARNING, "写入 " + file.getFileName() + " 失败", ex);
        }
    }
}
