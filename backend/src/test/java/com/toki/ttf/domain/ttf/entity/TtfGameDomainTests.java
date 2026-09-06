package com.toki.ttf.domain.ttf.entity;

import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.constants.RoomStatus;
import com.toki.ttf.domain.room.value.RoomSettings;
import com.toki.ttf.domain.ttf.result.VoteSubmission;
import com.toki.ttf.domain.ttf.constants.RoundStatus;
import com.toki.ttf.domain.ttf.constants.SpeakerOrder;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;
import com.toki.ttf.domain.ttf.value.LeaderboardEntry;
import com.toki.ttf.domain.ttf.value.RoundResult;
import com.toki.ttf.domain.ttf.value.ScoreChange;
import com.toki.ttf.domain.ttf.value.StatementDraft;
import com.toki.ttf.domain.ttf.value.TtfGameSettings;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TtfGameDomainTests {

    private static final Instant NOW = Instant.parse("2026-09-06T00:00:00Z");

    @Test
    void preparationAndRoundTransitionsFollowTheStateMachine() {
        Room room = newRoom(3);
        assertThat(room.status()).isEqualTo(RoomStatus.OPEN);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.LOBBY);

        room.join("participant_1", "Alice", NOW);
        room.join("participant_2", "Bob", NOW);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.SUBMISSION);
        assertDomainCode(() -> room.startGame(new Random(1), NOW),
                DomainException.Code.PARTICIPANTS_NOT_READY);

        room.saveStatements("participant_1", drafts("alice"), new Random(1), NOW);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.SUBMISSION);
        room.saveStatements("participant_2", drafts("bob"), new Random(2), NOW);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.READY);

        room.join("participant_3", "Carol", NOW);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.SUBMISSION);
        room.saveStatements("participant_3", drafts("carol"), new Random(3), NOW);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.READY);

        room.startGame(new Random(4), NOW);
        assertThat(room.status()).isEqualTo(RoomStatus.IN_GAME);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.ROUND_INTRO);
        assertThat(room.activeGame().roundCount()).isEqualTo(3);
        assertThat(currentRound(room).speakerParticipantId()).isEqualTo("participant_1");

        assertDomainCode(() -> room.join("participant_4", "Dana", NOW),
                DomainException.Code.GAME_ALREADY_STARTED);
        assertDomainCode(() -> room.removeParticipant("participant_2", NOW),
                DomainException.Code.INVALID_STATE_TRANSITION);

        Round round = currentRound(room);
        room.startVoting(round.id(), NOW);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.VOTING);
        assertDomainCode(() -> room.startVoting(round.id(), NOW),
                DomainException.Code.INVALID_STATE_TRANSITION);
        assertDomainCode(() -> room.revealResult(round.id(), NOW),
                DomainException.Code.INVALID_STATE_TRANSITION);
        assertDomainCode(() -> room.nextRound(NOW),
                DomainException.Code.INVALID_STATE_TRANSITION);
    }

    @Test
    void statementValidationRejectsInvalidSetsAndEquivalentPutIsIdempotent() {
        Room room = newRoom(2);
        room.join("participant_1", "Alice", NOW);
        room.join("participant_2", "Bob", NOW);
        long versionBeforeValidation = room.version();

        assertDomainCode(() -> room.saveStatements(
                        "participant_1",
                        List.of(
                                new StatementDraft("first truth", false),
                                new StatementDraft("second fake", true)
                        ),
                        new Random(1),
                        NOW),
                DomainException.Code.VALIDATION_ERROR);
        assertDomainCode(() -> room.saveStatements(
                        "participant_1",
                        List.of(
                                new StatementDraft("first truth", false),
                                new StatementDraft("second truth", false),
                                new StatementDraft("third truth", false)
                        ),
                        new Random(1),
                        NOW),
                DomainException.Code.VALIDATION_ERROR);
        assertDomainCode(() -> room.saveStatements(
                        "participant_1",
                        List.of(
                                new StatementDraft("same statement", false),
                                new StatementDraft(" same   statement ", true),
                                new StatementDraft("third truth", false)
                        ),
                        new Random(1),
                        NOW),
                DomainException.Code.VALIDATION_ERROR);
        assertDomainCode(() -> room.saveStatements(
                        "participant_1",
                        List.of(
                                new StatementDraft("tiny", false),
                                new StatementDraft("second fake", true),
                                new StatementDraft("third truth", false)
                        ),
                        new Random(1),
                        NOW),
                DomainException.Code.VALIDATION_ERROR);
        assertThat(room.version()).isEqualTo(versionBeforeValidation);

        List<StatementDraft> firstRequest = List.of(
                new StatementDraft("alpha truth statement", false),
                new StatementDraft("bravo fake statement", true),
                new StatementDraft("charlie truth statement", false)
        );
        List<Statement> firstSave = room.saveStatements(
                "participant_1", firstRequest, new Random(5), NOW);
        long versionAfterFirstSave = room.version();

        List<StatementDraft> equivalentRetry = List.of(
                new StatementDraft(" alpha   truth statement ", false),
                new StatementDraft("bravo fake statement", true),
                new StatementDraft("charlie truth statement", false)
        );
        List<Statement> retrySave = room.saveStatements(
                "participant_1", equivalentRetry, new Random(999), NOW.plusSeconds(1));

        assertThat(retrySave).isEqualTo(firstSave);
        assertThat(retrySave).extracting(Statement::id)
                .containsExactlyElementsOf(firstSave.stream().map(Statement::id).toList());
        assertThat(room.version()).isEqualTo(versionAfterFirstSave);
        assertThat(room.activeGame().readyCount()).isEqualTo(1);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.SUBMISSION);
    }

    @Test
    void voteRejectsSpeakerAndSupportsChangeAndIdempotentRetry() {
        Room room = readyRoom(3);
        room.startGame(new Random(1), NOW);
        Round round = currentRound(room);
        room.startVoting(round.id(), NOW);
        Statement fake = fakeStatement(round);
        Statement truth = round.statements().stream().filter(statement -> !statement.fake()).findFirst().orElseThrow();

        assertDomainCode(() -> room.submitVote(
                        round.speakerParticipantId(), round.id(), fake.id(), NOW.plusSeconds(1)),
                DomainException.Code.SPEAKER_CANNOT_VOTE);

        long beforeFirstVote = room.version();
        VoteSubmission first = room.submitVote(
                "participant_2", round.id(), fake.id(), NOW.plusSeconds(1));
        assertThat(first).isEqualTo(new VoteSubmission(true, true, false));
        assertThat(room.version()).isEqualTo(beforeFirstVote + 1);
        assertThat(round.votes()).hasSize(1);

        long beforeRetry = room.version();
        VoteSubmission retry = room.submitVote(
                "participant_2", round.id(), fake.id(), NOW.plusSeconds(2));
        assertThat(retry).isEqualTo(new VoteSubmission(false, false, false));
        assertThat(room.version()).isEqualTo(beforeRetry);
        assertThat(round.votes()).hasSize(1);

        VoteSubmission changed = room.submitVote(
                "participant_2", round.id(), truth.id(), NOW.plusSeconds(3));
        assertThat(changed).isEqualTo(new VoteSubmission(true, false, false));
        assertThat(round.votes()).hasSize(1);
        assertThat(round.voteBy("participant_2")).get().extracting(Vote::statementId).isEqualTo(truth.id());
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.VOTING);
    }

    @Test
    void deadlineIsInclusiveAndClosesOnlyOnce() {
        Room room = readyRoom(3);
        room.startGame(new Random(1), NOW);
        Round round = currentRound(room);
        room.startVoting(round.id(), NOW);
        Instant deadline = NOW.plusSeconds(60);

        assertThat(round.votingEndsAt()).isEqualTo(deadline);
        assertDomainCode(() -> room.submitVote(
                        "participant_2", round.id(), fakeStatement(round).id(), deadline),
                DomainException.Code.VOTING_NOT_OPEN);
        assertThat(round.votes()).isEmpty();
        assertThat(room.closeVotingIfDue(round.id(), deadline.minusMillis(1))).isFalse();

        long versionBeforeClose = room.version();
        assertThat(room.closeVotingIfDue(round.id(), deadline)).isTrue();
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.VOTE_CLOSED);
        assertThat(room.version()).isEqualTo(versionBeforeClose + 1);
        assertThat(room.closeVotingIfDue(round.id(), deadline.plusSeconds(1))).isFalse();
        assertThat(room.version()).isEqualTo(versionBeforeClose + 1);
    }

    @Test
    void revealIsIdempotentAndFinishUsesOnlyCorrectVoteScoresWithCompetitionRanks() {
        Room room = readyRoom(3);
        room.startGame(new Random(1), NOW);
        Round round = currentRound(room);
        room.startVoting(round.id(), NOW);
        Statement fake = fakeStatement(round);
        room.submitVote("participant_2", round.id(), fake.id(), NOW.plusSeconds(1));
        room.submitVote("participant_3", round.id(), fake.id(), NOW.plusSeconds(1));
        room.closeVoting(round.id(), NOW.plusSeconds(2));

        RoundResult firstResult = room.revealResult(round.id(), NOW.plusSeconds(3));
        long versionAfterReveal = room.version();
        assertThat(firstResult.correctVoterCount()).isEqualTo(2);
        assertThat(firstResult.fooledParticipantCount()).isZero();
        assertThat(firstResult.scoreChanges())
                .extracting(ScoreChange::participantId)
                .containsExactly("participant_2", "participant_3");
        assertThat(firstResult.scoreChanges()).allMatch(change -> change.delta() == 1);
        assertThat(score(room, "participant_1")).isZero();
        assertThat(score(room, "participant_2")).isEqualTo(1);
        assertThat(score(room, "participant_3")).isEqualTo(1);

        RoundResult repeatedResult = room.revealResult(round.id(), NOW.plusSeconds(4));
        assertThat(repeatedResult).isEqualTo(firstResult);
        assertThat(room.version()).isEqualTo(versionAfterReveal);
        assertThat(score(room, "participant_2")).isEqualTo(1);
        assertThat(score(room, "participant_3")).isEqualTo(1);

        room.finish(NOW.plusSeconds(5));
        assertThat(room.status()).isEqualTo(RoomStatus.CLOSED);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.FINISHED);
        assertThat(room.activeGame().leaderboard())
                .containsExactly(
                        new LeaderboardEntry("participant_2", 1, 1),
                        new LeaderboardEntry("participant_3", 1, 1),
                        new LeaderboardEntry("participant_1", 0, 3)
                );
    }

    @Test
    void skippingRoundsAdvancesAndFinishesWithoutAwardingPoints() {
        Room room = readyRoom(3);
        room.startGame(new Random(1), NOW);

        Round first = currentRound(room);
        room.skipRound(first.id(), NOW.plusSeconds(1));
        assertThat(first.status()).isEqualTo(RoundStatus.SKIPPED);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.ROUND_INTRO);
        assertThat(room.activeGame().currentRoundNumber()).contains(2);

        Round second = currentRound(room);
        room.skipRound(second.id(), NOW.plusSeconds(2));
        assertThat(room.activeGame().currentRoundNumber()).contains(3);

        Round third = currentRound(room);
        room.skipRound(third.id(), NOW.plusSeconds(3));
        assertThat(room.status()).isEqualTo(RoomStatus.CLOSED);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.FINISHED);
        assertThat(room.activeGame().leaderboard())
                .extracting(LeaderboardEntry::score)
                .containsOnly(0);
        assertThat(room.activeGame().leaderboard())
                .extracting(LeaderboardEntry::rank)
                .containsOnly(1);
    }

    @Test
    void pauseAndResumePreserveVotingTimeRemaining() {
        Room room = readyRoom(3);
        room.startGame(new Random(1), NOW);
        Round round = currentRound(room);
        room.startVoting(round.id(), NOW);

        room.pause(NOW.plusSeconds(20));
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.PAUSED);
        assertThat(room.activeGame().pausedFromStatus()).contains(TtfGameStatus.VOTING);

        room.resume(NOW.plusSeconds(100));
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.VOTING);
        assertThat(room.activeGame().pausedFromStatus()).isEmpty();
        assertThat(round.votingEndsAt()).isEqualTo(NOW.plusSeconds(140));
        assertThat(room.closeVotingIfDue(round.id(), NOW.plusSeconds(139))).isFalse();
        assertThat(room.closeVotingIfDue(round.id(), NOW.plusSeconds(140))).isTrue();
    }

    @Test
    void concurrentVotesAreAtomicAndRemainOnePerEligibleParticipant() throws Exception {
        Room room = readyRoom(100);
        room.startGame(new Random(1), NOW);
        Round round = currentRound(room);
        room.startVoting(round.id(), NOW);
        String fakeStatementId = fakeStatement(round).id();
        List<String> voters = room.participants().stream()
                .map(participant -> participant.id())
                .filter(participantId -> !participantId.equals(round.speakerParticipantId()))
                .toList();
        long versionBeforeVotes = room.version();

        ExecutorService executor = Executors.newFixedThreadPool(16);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<VoteSubmission>> futures = new ArrayList<>(voters.size());
            for (String voter : voters) {
                futures.add(executor.submit(() -> {
                    start.await();
                    return room.submitVote(voter, round.id(), fakeStatementId, NOW.plusSeconds(1));
                }));
            }
            start.countDown();
            for (Future<VoteSubmission> future : futures) {
                assertThat(future.get(10, TimeUnit.SECONDS))
                        .isEqualTo(new VoteSubmission(true, true, false));
            }
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }

        assertThat(round.votes()).hasSize(99);
        assertThat(round.votes()).extracting(Vote::voterParticipantId).doesNotHaveDuplicates();
        assertThat(room.version()).isEqualTo(versionBeforeVotes + 99);
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.VOTING);
    }

    private static Room newRoom(int maxParticipants) {
        return Room.create(
                "room_test",
                "ABC123",
                "Domain test room",
                new RoomSettings(maxParticipants),
                "game_test",
                new TtfGameSettings(100, 60, SpeakerOrder.JOIN_ORDER, true),
                NOW,
                NOW.plusSeconds(3_600)
        );
    }

    private static Room readyRoom(int participantCount) {
        Room room = newRoom(participantCount);
        for (int index = 1; index <= participantCount; index++) {
            String participantId = "participant_" + index;
            room.join(participantId, "Player " + index, NOW);
            room.saveStatements(participantId, drafts("player" + index), new Random(index), NOW);
        }
        assertThat(room.activeGame().status()).isEqualTo(TtfGameStatus.READY);
        return room;
    }

    private static List<StatementDraft> drafts(String prefix) {
        return List.of(
                new StatementDraft(prefix + " first truth", false),
                new StatementDraft(prefix + " second fake", true),
                new StatementDraft(prefix + " third truth", false)
        );
    }

    private static Round currentRound(Room room) {
        return room.activeGame().currentRound().orElseThrow();
    }

    private static Statement fakeStatement(Round round) {
        return round.statements().stream().filter(Statement::fake).findFirst().orElseThrow();
    }

    private static int score(Room room, String participantId) {
        return room.activeGame().player(participantId).orElseThrow().score();
    }

    private static void assertDomainCode(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable action,
            DomainException.Code expectedCode
    ) {
        assertThatThrownBy(action)
                .isInstanceOfSatisfying(
                        DomainException.class,
                        exception -> assertThat(exception.code()).isEqualTo(expectedCode)
                );
    }
}
