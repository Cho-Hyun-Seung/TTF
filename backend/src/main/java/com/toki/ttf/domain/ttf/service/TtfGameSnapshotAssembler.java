package com.toki.ttf.domain.ttf.service;

import com.toki.ttf.contract.error.ApiException;
import com.toki.ttf.contract.error.ErrorCode;
import com.toki.ttf.domain.room.entity.Participant;
import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.room.service.RoomService;
import com.toki.ttf.domain.ttf.dto.response.TtfGameSnapshotResponse;
import com.toki.ttf.domain.ttf.entity.Round;
import com.toki.ttf.domain.ttf.entity.Statement;
import com.toki.ttf.domain.ttf.entity.TtfGame;
import com.toki.ttf.domain.ttf.entity.TtfPlayer;
import com.toki.ttf.domain.ttf.constants.SnapshotAudience;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;
import com.toki.ttf.domain.ttf.value.LeaderboardEntry;
import com.toki.ttf.domain.ttf.value.RoundResult;
import com.toki.ttf.domain.ttf.value.ScoreChange;
import com.toki.ttf.domain.ttf.value.StatementResult;
import com.toki.ttf.infrastructure.security.SessionService;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;

@Component
public class TtfGameSnapshotAssembler {

    public TtfGameSnapshotResponse assemble(
            Room room,
            SnapshotAudience audience,
            SessionService.ParticipantGrant participantGrant
    ) {
        synchronized (room) {
            TtfGame game = room.activeGame();
            String viewerParticipantId = participantGrant == null ? null : participantGrant.participantId();
            Participant viewerParticipant = viewerParticipantId == null
                    ? null
                    : room.participant(viewerParticipantId)
                    .orElseThrow(() -> new ApiException(ErrorCode.PARTICIPANT_PERMISSION_REQUIRED));

            TtfGameSnapshotResponse.Viewer viewer = viewer(audience, game, viewerParticipant);
            List<TtfGameSnapshotResponse.ParticipantSummary> participants = audience == SnapshotAudience.HOST
                    ? participants(room, game)
                    : null;
            List<TtfGameSnapshotResponse.MyStatement> myStatements = includeMyStatements(game, audience)
                    ? game.statementsFor(viewerParticipantId).stream().map(this::myStatement).toList()
                    : null;
            TtfGameSnapshotResponse.RoundSnapshot currentRound = game.currentRound()
                    .map(round -> round(room, game, round, audience, viewerParticipantId))
                    .orElse(null);
            List<TtfGameSnapshotResponse.LeaderboardEntry> leaderboard = game.status() == TtfGameStatus.FINISHED
                    ? leaderboard(room, game, audience, viewerParticipantId)
                    : null;

            return new TtfGameSnapshotResponse(
                    game.version(),
                    Instant.now(),
                    RoomService.toSummary(room),
                    new TtfGameSnapshotResponse.Game(
                            game.id(),
                            game.type(),
                            enumValue(TtfGameSnapshotResponse.GameStatus.class, game.status().name()),
                            game.readyCount(),
                            game.roundCount(),
                            game.currentRoundNumber().orElse(null),
                            game.pausedFromStatus()
                                    .map(status -> enumValue(
                                            TtfGameSnapshotResponse.GameStatus.class,
                                            status.name()))
                                    .orElse(null),
                            new TtfGameSnapshotResponse.Settings(
                                    game.settings().statementMinLength(),
                                    game.settings().statementMaxLength(),
                                    game.settings().votingDurationSeconds(),
                                    enumValue(
                                            TtfGameSnapshotResponse.SpeakerOrder.class,
                                            game.settings().speakerOrder().name()),
                                    game.settings().anonymousVoting()
                            )
                    ),
                    viewer,
                    participants,
                    myStatements,
                    currentRound,
                    leaderboard
            );
        }
    }

    private TtfGameSnapshotResponse.Viewer viewer(
            SnapshotAudience audience,
            TtfGame game,
            Participant participant
    ) {
        if (audience == SnapshotAudience.PARTICIPANT) {
            TtfPlayer player = game.player(participant.id())
                    .orElseThrow(() -> new ApiException(ErrorCode.PARTICIPANT_PERMISSION_REQUIRED));
            return new TtfGameSnapshotResponse.Viewer(
                    TtfGameSnapshotResponse.ViewerRole.PARTICIPANT,
                    participant.id(),
                    participant.nickname(),
                    player.ready()
            );
        }
        return new TtfGameSnapshotResponse.Viewer(
                audience == SnapshotAudience.HOST
                        ? TtfGameSnapshotResponse.ViewerRole.HOST
                        : TtfGameSnapshotResponse.ViewerRole.DISPLAY,
                null,
                null,
                null
        );
    }

    private List<TtfGameSnapshotResponse.ParticipantSummary> participants(Room room, TtfGame game) {
        String speakerId = game.currentRound().map(Round::speakerParticipantId).orElse(null);
        return room.participants().stream()
                .map(participant -> {
                    TtfPlayer player = game.player(participant.id())
                            .orElseThrow(() -> new IllegalStateException("participant and player are inconsistent"));
                    return new TtfGameSnapshotResponse.ParticipantSummary(
                            participant.id(),
                            participant.nickname(),
                            enumValue(
                                    TtfGameSnapshotResponse.ConnectionStatus.class,
                                    participant.connectionStatus().name()),
                            player.ready(),
                            player.score(),
                            participant.id().equals(speakerId)
                    );
                })
                .toList();
    }

    private TtfGameSnapshotResponse.RoundSnapshot round(
            Room room,
            TtfGame game,
            Round round,
            SnapshotAudience audience,
            String viewerParticipantId
    ) {
        Participant speaker = room.participant(round.speakerParticipantId())
                .orElseThrow(() -> new IllegalStateException("round speaker is missing"));
        List<TtfGameSnapshotResponse.VisibleStatement> statements = round.statements().stream()
                .sorted(Comparator.comparingInt(Statement::displayOrder))
                .map(statement -> new TtfGameSnapshotResponse.VisibleStatement(
                        statement.id(), statement.content(), statement.displayOrder()))
                .toList();
        String myVote = audience == SnapshotAudience.PARTICIPANT
                ? round.voteBy(viewerParticipantId).map(vote -> vote.statementId()).orElse(null)
                : null;
        TtfGameSnapshotResponse.RoundResult result = round.result()
                .map(value -> result(room, game, value))
                .orElse(null);

        return new TtfGameSnapshotResponse.RoundSnapshot(
                round.id(),
                round.number(),
                game.roundCount(),
                person(speaker),
                statements,
                round.votingStartedAt(),
                round.votingEndsAt(),
                new TtfGameSnapshotResponse.VoteProgress(
                        round.votes().size(),
                        game.eligibleVoterCount()
                ),
                myVote,
                result
        );
    }

    private TtfGameSnapshotResponse.RoundResult result(Room room, TtfGame game, RoundResult result) {
        return new TtfGameSnapshotResponse.RoundResult(
                result.fakeStatementId(),
                result.statements().stream()
                        .sorted(Comparator.comparingInt(StatementResult::displayOrder))
                        .map(value -> statementResult(room, game, value))
                        .toList(),
                result.correctVoterCount(),
                result.fooledParticipantCount(),
                result.scoreChanges().stream()
                        .map(value -> scoreChange(room, value))
                        .toList()
        );
    }

    private TtfGameSnapshotResponse.StatementResult statementResult(
            Room room,
            TtfGame game,
            StatementResult result
    ) {
        List<TtfGameSnapshotResponse.Person> voters = game.settings().anonymousVoting()
                ? null
                : result.voterParticipantIds().stream()
                .map(id -> room.participant(id)
                        .map(this::person)
                        .orElseThrow(() -> new IllegalStateException("voter is missing")))
                .toList();
        return new TtfGameSnapshotResponse.StatementResult(
                result.id(),
                result.content(),
                result.displayOrder(),
                result.fake(),
                result.voteCount(),
                result.voteRate(),
                voters
        );
    }

    private TtfGameSnapshotResponse.ScoreChange scoreChange(Room room, ScoreChange scoreChange) {
        Participant participant = room.participant(scoreChange.participantId())
                .orElseThrow(() -> new IllegalStateException("score participant is missing"));
        return new TtfGameSnapshotResponse.ScoreChange(
                participant.id(),
                participant.nickname(),
                scoreChange.delta(),
                scoreChange.total()
        );
    }

    private List<TtfGameSnapshotResponse.LeaderboardEntry> leaderboard(
            Room room,
            TtfGame game,
            SnapshotAudience audience,
            String viewerParticipantId
    ) {
        return game.leaderboard().stream()
                .map(entry -> leaderboardEntry(room, entry, audience, viewerParticipantId))
                .toList();
    }

    private TtfGameSnapshotResponse.LeaderboardEntry leaderboardEntry(
            Room room,
            LeaderboardEntry entry,
            SnapshotAudience audience,
            String viewerParticipantId
    ) {
        Participant participant = room.participant(entry.participantId())
                .orElseThrow(() -> new IllegalStateException("leaderboard participant is missing"));
        return new TtfGameSnapshotResponse.LeaderboardEntry(
                participant.id(),
                participant.nickname(),
                entry.score(),
                entry.rank(),
                audience == SnapshotAudience.PARTICIPANT && participant.id().equals(viewerParticipantId)
        );
    }

    private TtfGameSnapshotResponse.MyStatement myStatement(Statement statement) {
        return new TtfGameSnapshotResponse.MyStatement(
                statement.id(), statement.content(), statement.fake());
    }

    private TtfGameSnapshotResponse.Person person(Participant participant) {
        return new TtfGameSnapshotResponse.Person(participant.id(), participant.nickname());
    }

    private static boolean includeMyStatements(TtfGame game, SnapshotAudience audience) {
        return audience == SnapshotAudience.PARTICIPANT && switch (game.status()) {
            case LOBBY, SUBMISSION, READY -> true;
            default -> false;
        };
    }

    private static <E extends Enum<E>> E enumValue(Class<E> type, String name) {
        return Enum.valueOf(type, name);
    }

}
