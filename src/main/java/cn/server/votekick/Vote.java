package cn.server.votekick;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** 一次投票的全部状态，不依赖服务端，方便单元测试。 */
final class Vote {

    enum Result { PENDING, PASSED, FAILED }

    private final UUID target;
    private final String targetName;
    private final String reason;
    private final Set<UUID> eligible;
    private final int required;
    private final Set<UUID> yes = new HashSet<>();
    private final Set<UUID> no = new HashSet<>();

    Vote(UUID target, String targetName, String reason, Set<UUID> eligible, double percent, int minVotes) {
        this.target = target;
        this.targetName = targetName;
        this.reason = reason;
        this.eligible = Set.copyOf(eligible);
        this.required = required(this.eligible.size(), percent, minVotes);
    }

    /** 门槛 = max(最少票数, 向上取整的比例票数)；超过可投票人数时调用方应拒绝发起投票。 */
    static int required(int eligible, double percent, int minVotes) {
        return Math.max(minVotes, (int) Math.ceil(eligible * percent));
    }

    boolean canVote(UUID player) {
        return eligible.contains(player);
    }

    /** 重复投票按改票处理。计票和界面读票数可能在不同线程上，方法加锁。 */
    synchronized void vote(UUID player, boolean agree) {
        if (!eligible.contains(player)) {
            throw new IllegalArgumentException("不在投票名单中: " + player);
        }
        yes.remove(player);
        no.remove(player);
        (agree ? yes : no).add(player);
    }

    synchronized Result result() {
        if (yes.size() >= required) {
            return Result.PASSED;
        }
        if (yes.size() + no.size() >= eligible.size()) {
            return Result.FAILED;
        }
        return Result.PENDING;
    }

    UUID target() {
        return target;
    }

    String targetName() {
        return targetName;
    }

    String reason() {
        return reason;
    }

    synchronized int yes() {
        return yes.size();
    }

    synchronized int no() {
        return no.size();
    }

    int required() {
        return required;
    }

    int eligible() {
        return eligible.size();
    }
}
