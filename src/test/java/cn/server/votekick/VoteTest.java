package cn.server.votekick;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class VoteTest {

    private static final UUID TARGET = UUID.randomUUID();

    private static Set<UUID> players(int count) {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < count; i++) {
            ids.add(UUID.randomUUID());
        }
        return ids;
    }

    @Test
    void thresholdIsMaxOfMinVotesAndRoundedUpPercent() {
        assertEquals(3, Vote.required(5, 0.6, 3));
        assertEquals(3, Vote.required(4, 0.6, 3));
        assertEquals(5, Vote.required(10, 0.5, 1));
        assertEquals(3, Vote.required(2, 0.6, 3), "人数太少时仍按 min-votes，由调用方拒绝发起");
        assertEquals(1, Vote.required(1, 0.01, 1));
    }

    @Test
    void passesOnceYesReachesThreshold() {
        Set<UUID> eligible = players(5);
        Vote vote = new Vote(TARGET, "Target", "刷屏", eligible, 0.6, 1);

        for (UUID voter : eligible) {
            vote.vote(voter, true);
            if (vote.result() == Vote.Result.PASSED) {
                break;
            }
        }
        assertEquals(Vote.Result.PASSED, vote.result());
        assertEquals(3, vote.yes());
        assertEquals(3, vote.required());
    }

    @Test
    void failsEarlyWhenEveryoneVotedAndThresholdNotReached() {
        Set<UUID> eligible = players(4);
        Vote vote = new Vote(TARGET, "Target", "刷屏", eligible, 1.0, 1);

        for (UUID voter : eligible) {
            vote.vote(voter, false);
        }
        assertEquals(Vote.Result.FAILED, vote.result());
    }

    @Test
    void staysPendingUntilEnoughVotesOrEveryoneVoted() {
        Set<UUID> eligible = players(5);
        Vote vote = new Vote(TARGET, "Target", "刷屏", eligible, 0.6, 1);

        vote.vote(eligible.iterator().next(), true);
        assertEquals(Vote.Result.PENDING, vote.result());
        assertEquals(1, vote.yes());
        assertEquals(0, vote.no());
    }

    @Test
    void changingVoteMovesTheCount() {
        Set<UUID> eligible = players(5);
        Vote vote = new Vote(TARGET, "Target", "刷屏", eligible, 0.6, 1);
        UUID voter = eligible.iterator().next();

        vote.vote(voter, true);
        vote.vote(voter, false);

        assertEquals(0, vote.yes());
        assertEquals(1, vote.no());
    }

    @Test
    void rejectsPlayersOutsideTheSnapshot() {
        Vote vote = new Vote(TARGET, "Target", "刷屏", players(3), 0.6, 1);

        assertThrows(IllegalArgumentException.class, () -> vote.vote(UUID.randomUUID(), true));
    }
}
