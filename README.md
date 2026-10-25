# VoteKick

Paper/Purpur 的玩家投票踢人插件：任意玩家发起投票，在线玩家在时限内投票，同意票达到门槛就把目标踢出，并在设定时间内禁止其进服。

支持 **Minecraft 1.21.1 ~ 26.x**，一个 jar 通吃；线程调度按 **Folia** 的区域线程写好，Paper / Purpur / Folia 都能跑。

---

## 下载

- **推荐**：[Releases 页面](https://github.com/xingxing2024/votekick/releases/latest) → `Assets` → `VoteKick-1.0.0.jar`
- 直链：[VoteKick-1.0.0.jar](https://github.com/xingxing2024/votekick/releases/download/v1.0.0/VoteKick-1.0.0.jar)（约 47 KB）
- 仓库里也放了一份：[`dist/VoteKick-1.0.0.jar`](dist/VoteKick-1.0.0.jar)
- 想自己构建：见 [构建](#构建)

## 特性

- **图形界面**：选人列表 → 恶意投票提醒确认 → 投票进行中，三态自动切换，每秒刷新。
- **BOSS 条**：投票期间全服屏幕顶部实时显示票数、剩余时间，颜色随进度从红变黄，通过时变绿。
- **理由必填**：指令里写在玩家名后面，或在界面里点头像后到聊天里补；理由会进记录文件。
- **发起确认**：通过所有规则检查后先弹恶意投票提醒，发起者确认才真正开始，防止一时手滑。
- **踢出后禁入**：投票通过后按 UUID 禁入一段时间（默认 1 小时），重启也有效。
- **防离线逃避**：目标在投票中掉线不会让投票作废，票照样算，通过后照样禁入。
- **投票记录**：按天写文本日志，游戏内 `/vk history` 分页查。
- **全文本可配置**：所有提示、界面文字、日志结果词、时长单位都在 `config.yml`，支持 `&` 颜色、`%占位符%`、多行列表。
- **多版本 + Folia**：对着 1.21.1 API 编译，1.21.1 ~ 26.x 通用；调度走 `Schedulers` 抽象，Paper 上等价于主线程。

## 兼容性

| 服务端 | 版本 | 状态 |
| --- | --- | --- |
| Paper / Purpur | 26.3（Minecraft 1.26.3） | 完整集成测试通过（81 项） |
| Paper | 1.21.7 | 加载与指令冒烟通过 |
| Paper / Purpur | 1.21.1 ~ 26.x | 同一个 jar，未逐版本实测 |
| Folia | 1.21+ | `folia-supported: true`，代码按区域线程写好，**未在真实 Folia 服务端实测** |

- 编译基线：`paper-api 1.21.1-R0.1-SNAPSHOT`，`maven.compiler.release=21`，class 版本 65。
- `plugin.yml` 里 `api-version: '1.21'`，1.21.1 及以上服务端都能加载。
- 需要 **Java 21 或更高**。

## 安装

1. 把 `VoteKick-1.0.0.jar` 放进服务器的 `plugins/`。
2. 启动服务器，自动生成 `plugins/VoteKick/config.yml`。
3. 改完配置用 `/vk reload` 生效，不需要重启。
4. 第一次有投票通过后会生成 `plugins/VoteKick/bans.txt`（禁入名单），可以手改。

## 指令

主指令是 `/vk`，`/votekick` 保留为别名，老习惯照样能用。

| 指令 | 说明 |
| --- | --- |
| `/vk` | 打开图形界面（无参数，仅玩家可用） |
| `/vk <玩家> <理由>` | 发起投票，**理由必填**（超过 `max-reason-length` 截断） |
| `/vk confirm` | 确认恶意投票提醒，确认后投票才真正开始 |
| `/vk yes` / `/vk no` | 投同意票 / 反对票；同一人重复投视为改票 |
| `/vk cancel` | 放弃待确认的发起，或取消当前投票（发起者本人或 `votekick.admin`） |
| `/vk history [玩家] [页码]` | 查投票记录，每页 10 条，新记录在前（`votekick.admin`） |
| `/vk reload` | 重载配置和禁入名单（控制台也能用） |

- `yes/no` 也接受 `y/n/同意/反对`。
- 投票开始后聊天里会广播一行可点击的 `[ 同意 ]` / `[ 反对 ]`，手机上点一下就能投。

## 权限

| 权限 | 默认 | 说明 |
| --- | --- | --- |
| `votekick.start` | 所有玩家 | 发起投票 |
| `votekick.immune` | OP | 不能被投票踢出（OP 默认免疫，给管理组加上即可） |
| `votekick.admin` | OP | 取消任意投票、查记录、重载配置 |

## 图形界面

`/vk` 打开的界面会跟着状态自动切换，打开期间每秒刷新：

- **选人列表**（54 格）：所有可被投票的在线玩家头像（排除自己与 `votekick.immune`），按名字排序，每页 45 人；底栏是上一页 / 页码 / 下一页 / 关闭。
- **确认发起**（27 格）：左边是提醒纸（整段提醒文字），中间是目标头颅，下面确认 / 放弃；倒计时每秒刷新。
- **投票界面**（54 格）：目标头颅（含原因）、同意（绿块）、实时进度（时钟）、反对（红块）、关闭；发起者或管理员还会看到取消按钮。
- **点头像 → 关界面 → 在聊天里输入理由**：理由必填，输入 `cancel`（或中文「取消」）作罢；这条聊天不会广播出去，60 秒不输入自动失效。
- 界面里的点击和拖拽全部被拦截，物品只当按钮用。

## BOSS 条

投票开始后，所有在线玩家屏幕顶部出现实时进度条：

- 文本：目标、同意/所需、反对、剩余秒数（`messages.bossbar`）。
- 进度按「同意票 / 所需票数」填充，颜色从红 → 黄 → 通过时绿。
- 每秒刷新；中途进服的玩家自动补上，退服自动移除，投票结束立刻消失。
- 不需要可在配置里设 `settings.bossbar: false`。

## 踢出后的禁入

投票通过后，目标不只是被踢出：在 `settings.ban-seconds`（默认 `3600` 秒 = 1 小时）内不能进服。

- 按 UUID 记在 `plugins/VoteKick/bans.txt`，重启也有效；换名字、退服重进都躲不掉。
- **目标在投票进行中下线不会让投票作废**：票照样算，通过后照样记禁入，他下次连服就会被挡在门外（全服会收到一句「目标已下线，投票继续」）。
- 被挡时看到的是 `messages.ban-screen` 界面：剩余时间、当时的理由，都能改，也能只留一部分（删掉 `%reason%` 就不显示理由）。
- 投票通过、禁入生效时全服再播一行 `messages.ban-applied`。
- 不想禁入就把 `settings.ban-seconds` 设成 `0`，回到「只踢不禁」；这时目标中途下线仍然会终止投票（既禁不了，人也踢不到）。
- 管理员要提前解禁：删掉 `bans.txt` 里对应的那一行，再 `/vk reload`。
- 这里的禁入由插件自己拦登录，不写进原版 `banned-players.json`，`/banlist` 里看不到。

## 投票记录

每次投票结束都会往 `plugins/VoteKick/logs/votes-日期.log` 追加一行，按天分文件、UTF-8：

```
2026-10-25 18:17:35 | 通过 | 目标=VoteB | 发起=VoteA | 同意=2 反对=0 所需=2 | 理由=刷屏广告
```

结果词有：通过 / 未通过 / 超时作废 / 被取消 / 目标离线（文字在 `messages.log-*` 里改）。
管理员可以直接看文件，也可以用 `/vk history [玩家] [页码]` 在游戏里分页查（新记录在前，`votekick.admin`，控制台也能用）。

## 配置

配置文件是 `plugins/VoteKick/config.yml`，改完 `/vk reload` 生效。

### settings

| 键 | 默认 | 说明 |
| --- | --- | --- |
| `percent` | `0.6` | 通过门槛比例，按「有资格的在线玩家」（不含被投票者）计算，0.01 ~ 1.0 |
| `min-votes` | `3` | 最少同意票；实际门槛 = `max(min-votes, 向上取整(人数 × percent))` |
| `duration-seconds` | `120` | 投票时限，超时未达标作废 |
| `cooldown-seconds` | `300` | 同一玩家两次发起的最短间隔，`0` 不限制 |
| `max-reason-length` | `60` | 理由最大长度，超出截断 |
| `confirm-seconds` | `30` | 发起确认窗口；`0` = 不需要确认，直接开始 |
| `confirm-broadcast` | `false` | 确认提醒是否广播给全服 |
| `ban-seconds` | `3600` | 投票通过后的禁入时长；`0` = 只踢出、不禁入 |
| `bossbar` | `true` | 投票期间是否显示全服 BOSS 条 |

### messages

所有提示都在 `messages` 段下，支持 `&` 颜色代码和 `%占位符%`，也可以写成多行列表（每项一行）。

| 分组 | 键 |
| --- | --- |
| 基础 | `prefix` `usage` `player-only` `no-permission` `player-not-found` `self` `immune` |
| 发起 | `reason-required` `reason-prompt` `reason-cancelled` `vote-running` `cooldown` `not-enough-players` |
| 确认提醒 | `confirm-warning` `confirm-broadcast` `confirm-cancelled` `confirm-expired` `confirm-none` `confirm-busy` |
| 投票过程 | `started` `vote-progress` `no-vote` `not-eligible` `side-yes` `side-no` `vote-button-yes` `vote-button-no` |
| 结果 | `passed` `failed` `cancelled` `target-left` `target-offline` |
| 禁入 | `ban-applied` `ban-screen` `kick-screen` `duration-hour` `duration-minute` `duration-second` |
| 界面 | `gui-title-*` `gui-confirm-*` `gui-target-name` `gui-lore-*` `gui-yes-name` `gui-no-name` `gui-status-name` `gui-close-name` `gui-cancel-*` `gui-player-*` `gui-page-*` |
| BOSS 条 | `bossbar` |
| 记录 | `log-passed` `log-failed` `log-timeout` `log-cancelled` `log-left` `history-*` |

几个常用占位符：`%target%` `%player%` `%reason%` `%yes%` `%no%` `%need%` `%time%` `%page%` `%next%` `%value%`。

时长文案 `duration-hour/minute/second` 里的 `%value%` 是数字，只拼非零的部分（3600 秒 → `1 小时`）。想显示成 `1h5m` 就改这三行。

## 行为约定

- 发起投票必须填写理由；理由不超过 `settings.max-reason-length`。
- 通过检查后先提醒恶意投票的后果，发起者确认才真正开始；确认窗口内不确认就作废，且不消耗冷却。
- 同一时间只允许一场投票，也只有一个待确认的发起。
- 投票名单在发起时固定：之后进服的玩家不能参与本次投票，中途退出的人仍计入门槛。
- 所有人投完但同意票不够时立刻判定失败，不用等满时限。
- 通过后按 UUID 禁入 `settings.ban-seconds` 秒，人在不在线都生效。
- 禁入只拦登录，不封号、不写原版封禁名单；需要真封禁请配合封禁插件。
- 目标被踢后，发起者仍受冷却限制，防止连续针对同一玩家。

## 构建

```bash
mvn -B package                      # 产出 target/VoteKick-1.0.0.jar
mvn -B test                         # 只跑单元测试
cd test-harness && mvn -B package   # 集成测试钩子，只在隔离测试服安装
```

`test-harness` 是本地验证用的假人测试插件，**不要装到正式服**。

## 测试情况

- 单元测试 **7 项**：门槛计算、达标通过、全员投完早退失败、改票、名单外投票被拒，以及禁入名单的拦人 / 到期放行 / 重启后仍有效。
- 集成测试 **81 项**（Purpur 26.3 + minecraft-fakeplayer-next 0.4.5，真实假人跑完整流程）：加载、指令注册、reload、发起确认与超时作废、GUI 三态与各按钮、BOSS 条文本/进度/颜色/消失、达门槛立即踢出、记录文件与 `history`、通过后写入 `bans.txt`、目标中途下线投票不作废且照样禁入、禁入拦截界面与放行判断。
- 1.21.7 冒烟：插件正常加载启用，`/vk` 与 `/votekick` 指令、reload 均正常，无异常。

## 已知限制

- **Folia 未在真实服务端实测**：调度已按区域线程写好，Paper 上行为等价；Folia 上最可能需要留意目标被踢时的线程与头颅皮肤跨区域读取（最坏是皮肤不显示）。
- 真实客户端被禁入时的登录画面未实机验证（测试是直接调插件的拦截判断，假人不走真实登录握手）。
- 未验证：真实客户端界面排版与提醒换行、与其它聊天/踢人/封禁插件（如 CMI、LuckPerms 权限组）的联动、跨服群组服、与其它 BOSS 条插件的叠加。
- 1.21.7 冒烟跑在 Java 25 上（本机没有 JDK 21）；class 版本 65 说明 1.21 及以上 JVM 都能加载。
