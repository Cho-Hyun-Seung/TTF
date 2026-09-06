package com.toki.ttf.domain.ttf.entity;

import com.toki.ttf.domain.ttf.constants.RoundStatus;
import com.toki.ttf.domain.ttf.value.RoundResult;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

@Accessors(fluent = true)
public final class Round {
    @Getter
    private final String id;
    @Getter
    private final String gameId;
    @Getter
    private final String speakerParticipantId;
    @Getter
    private final int number;
    @Getter
    private final List<Statement> statements;
    private final Map<String, Vote> votesByParticipantId = new LinkedHashMap<>();
    private RoundStatus status;
    private Instant votingStartedAt;
    private Instant votingEndsAt;
    private Instant revealedAt;
    private RoundResult result;

    Round(String id, String gameId, String speakerParticipantId, int number, List<Statement> statements) {
        this.id = Objects.requireNonNull(id, "id");
        this.gameId = Objects.requireNonNull(gameId, "gameId");
        this.speakerParticipantId = Objects.requireNonNull(speakerParticipantId, "speakerParticipantId");
        this.number = number;
        this.statements = List.copyOf(statements);
        this.status = RoundStatus.ROUND_INTRO;
    }

    public synchronized RoundStatus status() {
        return status;
    }

    public synchronized List<Vote> votes() {
        return List.copyOf(votesByParticipantId.values());
    }

    public synchronized Optional<Vote> voteBy(String participantId) {
        return Optional.ofNullable(votesByParticipantId.get(participantId));
    }

    public synchronized Instant votingStartedAt() {
        return votingStartedAt;
    }

    public synchronized Instant votingEndsAt() {
        return votingEndsAt;
    }

    public synchronized Instant revealedAt() {
        return revealedAt;
    }

    public synchronized Optional<RoundResult> result() {
        return Optional.ofNullable(result);
    }

    synchronized boolean containsStatement(String statementId) {
        return statements.stream().anyMatch(statement -> statement.id().equals(statementId));
    }

    synchronized void startVoting(Instant startedAt, Instant endsAt) {
        status = RoundStatus.VOTING;
        votingStartedAt = startedAt;
        votingEndsAt = endsAt;
    }

    synchronized void extendVotingTo(Instant endsAt) {
        votingEndsAt = endsAt;
    }

    synchronized Vote putVote(String voterParticipantId, String statementId, Instant now) {
        Vote current = votesByParticipantId.get(voterParticipantId);
        if (current == null) {
            Vote created = new Vote(
                    TtfGame.newOpaqueId("vote_"),
                    id,
                    voterParticipantId,
                    statementId,
                    now,
                    now
            );
            votesByParticipantId.put(voterParticipantId, created);
            return created;
        }
        Vote changed = current.changeTo(statementId, now);
        votesByParticipantId.put(voterParticipantId, changed);
        return changed;
    }

    synchronized int voteCount() {
        return votesByParticipantId.size();
    }

    synchronized void closeVoting() {
        status = RoundStatus.VOTE_CLOSED;
    }

    synchronized void reveal(RoundResult roundResult, Instant now) {
        result = Objects.requireNonNull(roundResult, "roundResult");
        revealedAt = Objects.requireNonNull(now, "now");
        status = RoundStatus.RESULT;
    }

    synchronized void skip() {
        status = RoundStatus.SKIPPED;
    }
}
