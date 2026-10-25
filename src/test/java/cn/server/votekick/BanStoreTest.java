package cn.server.votekick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.logging.Logger;

import org.junit.jupiter.api.Test;

/** 禁入名单：拦人、到期放行、重启后还在。 */
class BanStoreTest {

    @Test
    void banBlocksThenExpiresAndSurvivesRestart() throws Exception {
        Path file = Files.createTempFile("votekick-bans", ".txt");
        UUID id = UUID.randomUUID();
        Logger logger = Logger.getLogger("votekick-test");

        BanStore store = new BanStore(file, logger);
        assertNull(store.active(id), "没被禁的玩家不拦");

        store.ban(id, "VoteB", "刷屏广告", 3600);
        BanStore.Ban ban = store.active(id);
        assertNotNull(ban, "被禁后要拦下来");
        assertEquals("刷屏广告", ban.reason(), "理由要带在记录里给登录界面用");

        BanStore reopened = new BanStore(file, logger);
        assertNotNull(reopened.active(id), "重启后禁入仍然有效");

        reopened.ban(id, "VoteB", "刷屏广告", 0);
        assertNull(reopened.active(id), "到期后放行");
    }
}
