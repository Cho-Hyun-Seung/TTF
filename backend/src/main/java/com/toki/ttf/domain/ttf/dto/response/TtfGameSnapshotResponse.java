package com.toki.ttf.domain.ttf.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.domain.room.dto.response.RoomSummaryResponse;

import java.time.Instant;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TtfGameSnapshotResponse(
        long version,
        Instant serverTime,
        RoomSummaryResponse room,
        Game game,
        Viewer viewer,
        List<ParticipantSummary> participants,
        List<MyStatementSet> myStatementSets,
        RoundSnapshot currentRound,
        List<LeaderboardEntry> leaderboard
) {
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Game(
            String id,
            String type,
            GameStatus status,
            int readyCount,
            int roundCount,
            Integer currentRoundNumber,
            @JsonInclude(JsonInclude.Include.NON_NULL)
            GameStatus pausedFromStatus,
            Settings settings
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Settings(
            int statementMinLength,
            int statementMaxLength,
            int votingDurationSeconds,
            SpeakerOrder speakerOrder,
            boolean anonymousVoting,
            int roundCount,
            List<Topic> topics
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Topic(
            String id,
            String title,
            String example
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Viewer(
            ViewerRole role,
            String participantId,
            String nickname,
            Boolean isReady
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ParticipantSummary(
            String id,
            String nickname,
            ConnectionStatus connectionStatus,
            boolean isReady,
            int score,
            boolean isCurrentSpeaker
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MyStatementSet(
            Topic topic,
            List<MyStatement> statements
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record MyStatement(
            String id,
            String content,
            boolean isFake
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoundSnapshot(
            String id,
            int number,
            int total,
            Topic topic,
            Person speaker,
            List<VisibleStatement> statements,
            Instant votingStartedAt,
            Instant votingEndsAt,
            Instant resultRevealsAt,
            VoteProgress voteProgress,
            String myVoteStatementId,
            RoundResult result
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record Person(
            String id,
            String nickname
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VisibleStatement(
            String id,
            String content,
            int displayOrder
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record VoteProgress(
            int completed,
            int eligible
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record RoundResult(
            String fakeStatementId,
            List<StatementResult> statements,
            int correctVoterCount,
            int fooledParticipantCount,
            List<ScoreChange> scoreChanges
    ) {}

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record StatementResult(
            String id,
            String content,
            int displayOrder,
            boolean isFake,
            int voteCount,
            double voteRate,
            List<Person> voters
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record ScoreChange(
            String participantId,
            String nickname,
            int delta,
            int total
    ) {}

    @JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
    public record LeaderboardEntry(
            String participantId,
            String nickname,
            int score,
            int rank,
            boolean isMe
    ) {}

    public enum GameStatus {
        LOBBY,
        SUBMISSION,
        READY,
        ROUND_INTRO,
        VOTING,
        VOTE_CLOSED,
        RESULT,
        PAUSED,
        FINISHED,
        CANCELLED
    }

    public enum ViewerRole {
        PARTICIPANT,
        HOST,
        DISPLAY
    }

    public enum SpeakerOrder {
        RANDOM,
        JOIN_ORDER
    }

    public enum ConnectionStatus {
        ONLINE,
        OFFLINE
    }
}
