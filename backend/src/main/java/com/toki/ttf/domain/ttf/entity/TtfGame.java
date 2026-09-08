package com.toki.ttf.domain.ttf.entity;

import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.ttf.result.VoteSubmission;
import com.toki.ttf.domain.ttf.constants.SpeakerOrder;
import com.toki.ttf.domain.ttf.constants.TtfTopic;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;
import com.toki.ttf.domain.ttf.value.LeaderboardEntry;
import com.toki.ttf.domain.ttf.value.RoundResult;
import com.toki.ttf.domain.ttf.value.ScoreChange;
import com.toki.ttf.domain.ttf.value.StatementDraft;
import com.toki.ttf.domain.ttf.value.StatementSetDraft;
import com.toki.ttf.domain.ttf.value.StatementResult;
import com.toki.ttf.domain.ttf.value.TtfGameSettings;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.security.SecureRandom;
import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

@Accessors(fluent = true)
public final class TtfGame {
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");
    private static final Duration RESULT_REVEAL_DELAY = Duration.ofSeconds(2);

    @Getter
    private final String id;
    @Getter
    private final String roomId;
    @Getter
    private final TtfGameSettings settings;
    @Getter
    private final Instant createdAt;
    private final Map<String, TtfPlayer> players = new LinkedHashMap<>();
    private final Map<String, Map<TtfTopic, List<Statement>>> statementSetsByParticipantId =
            new LinkedHashMap<>();
    private final List<Round> rounds = new ArrayList<>();
    private final List<LeaderboardEntry> leaderboard = new ArrayList<>();
    private TtfGameStatus status;
    private TtfGameStatus pausedFromStatus;
    private Duration pausedVotingTimeRemaining;
    private Duration pausedResultTimeRemaining;
    private int currentRoundIndex = -1;
    private int nextJoinOrder;
    private long version;

    public TtfGame(String id, String roomId, TtfGameSettings settings, Instant createdAt) {
        this.id = requireId(id, "game id");
        this.roomId = requireId(roomId, "room id");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.status = TtfGameStatus.LOBBY;
    }

    public static TtfGame create(String id, String roomId, TtfGameSettings settings, Instant now) {
        return new TtfGame(id, roomId, settings, now);
    }

    public synchronized boolean registerParticipant(String participantId) {
        requirePreGameMutation();
        String validParticipantId = requireId(participantId, "participant id");
        if (players.containsKey(validParticipantId)) {
            return false;
        }
        players.put(validParticipantId, new TtfPlayer(validParticipantId, nextJoinOrder++));
        reevaluatePreparationStatus();
        incrementVersion();
        return true;
    }

    public synchronized boolean unregisterParticipant(String participantId) {
        requirePreGameMutation();
        TtfPlayer removed = players.remove(participantId);
        if (removed == null) {
            return false;
        }
        statementSetsByParticipantId.remove(participantId);
        reevaluatePreparationStatus();
        incrementVersion();
        return true;
    }

    public synchronized List<Statement> saveStatements(
            String participantId,
            List<StatementDraft> drafts,
            RandomGenerator random,
            Instant now
    ) {
        if (settings.roundCount() != 1) {
            throw validation("모든 라운드 주제의 문장을 함께 제출해 주세요.");
        }
        return saveStatementSets(
                participantId,
                List.of(new StatementSetDraft(settings.topics().get(0), drafts)),
                random,
                now
        );
    }

    public synchronized List<Statement> saveStatementSets(
            String participantId,
            List<StatementSetDraft> statementSetDrafts,
            RandomGenerator random,
            Instant now
    ) {
        requirePreGameMutation();
        TtfPlayer player = requirePlayer(participantId);
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(now, "now");
        Map<TtfTopic, List<StatementDraft>> normalizedSets =
                validateAndNormalizeStatementSets(statementSetDrafts);
        Map<TtfTopic, List<Statement>> current = statementSetsByParticipantId.get(participantId);
        if (hasSameRepresentation(current, normalizedSets)) {
            return flattenStatements(current);
        }

        Map<TtfTopic, List<Statement>> statementSets = new LinkedHashMap<>();
        for (TtfTopic topic : settings.topics()) {
            List<StatementDraft> normalizedDrafts = normalizedSets.get(topic);
            List<Integer> displayOrders = new ArrayList<>(List.of(1, 2, 3));
            shuffle(displayOrders, random);
            List<Statement> statements = new ArrayList<>(3);
            for (int index = 0; index < normalizedDrafts.size(); index++) {
                StatementDraft draft = normalizedDrafts.get(index);
                statements.add(new Statement(
                        newOpaqueId("statement_"),
                        id,
                        participantId,
                        topic,
                        draft.content(),
                        draft.fake(),
                        displayOrders.get(index),
                        now
                ));
            }
            statementSets.put(topic, List.copyOf(statements));
        }

        Map<TtfTopic, List<Statement>> immutableStatementSets = Map.copyOf(statementSets);
        statementSetsByParticipantId.put(participantId, immutableStatementSets);
        player.markReady();
        reevaluatePreparationStatus();
        incrementVersion();
        return flattenStatements(immutableStatementSets);
    }

    public synchronized void start(Instant now) {
        start(new SecureRandom(), now);
    }

    public synchronized void start(RandomGenerator random, Instant now) {
        Objects.requireNonNull(random, "random");
        Objects.requireNonNull(now, "now");
        if (players.size() < 2) {
            throw new DomainException(
                    DomainException.Code.NOT_ENOUGH_PARTICIPANTS,
                    "게임을 시작하려면 준비된 참가자가 2명 이상 필요합니다."
            );
        }
        if (players.values().stream().anyMatch(player -> !player.ready())) {
            throw new DomainException(
                    DomainException.Code.PARTICIPANTS_NOT_READY,
                    "아직 문장 제출을 완료하지 않은 참가자가 있습니다."
            );
        }
        requireStatus(TtfGameStatus.READY);

        List<TtfPlayer> speakerOrder = new ArrayList<>(players.values());
        speakerOrder.sort(Comparator.comparingInt(TtfPlayer::joinOrder));
        if (settings.speakerOrder() == SpeakerOrder.RANDOM) {
            shuffle(speakerOrder, random);
        }

        rounds.clear();
        int roundNumber = 1;
        for (TtfTopic topic : settings.topics()) {
            for (TtfPlayer speaker : speakerOrder) {
                List<Statement> visibleStatements = statementsFor(speaker.participantId(), topic).stream()
                        .sorted(Comparator.comparingInt(Statement::displayOrder))
                        .toList();
                rounds.add(new Round(
                        newOpaqueId("round_"),
                        id,
                        speaker.participantId(),
                        topic,
                        roundNumber++,
                        visibleStatements
                ));
            }
        }
        currentRoundIndex = 0;
        status = TtfGameStatus.ROUND_INTRO;
        incrementVersion();
    }

    public synchronized void startVoting(String roundId, Instant now) {
        Round round = requireCurrentRound(roundId);
        requireStatus(TtfGameStatus.ROUND_INTRO);
        Instant startsAt = Objects.requireNonNull(now, "now");
        round.startVoting(startsAt, startsAt.plusSeconds(settings.votingDurationSeconds()));
        status = TtfGameStatus.VOTING;
        incrementVersion();
    }

    public synchronized void extendVoting(String roundId, int seconds, Instant now) {
        Round round = requireCurrentRound(roundId);
        requireStatus(TtfGameStatus.VOTING);
        if (seconds < 5 || seconds > 60) {
            throw validation("투표 연장 시간은 5초 이상 60초 이하여야 합니다.");
        }
        Objects.requireNonNull(now, "now");
        if (round.votingEndsAt() == null || !now.isBefore(round.votingEndsAt())) {
            throw votingNotOpen();
        }
        round.extendVotingTo(round.votingEndsAt().plusSeconds(seconds));
        incrementVersion();
    }

    public synchronized VoteSubmission submitVote(
            String voterParticipantId,
            String roundId,
            String statementId,
            Instant now
    ) {
        Round round = requireCurrentRound(roundId);
        if (status != TtfGameStatus.VOTING) {
            throw votingNotOpen();
        }
        Instant submittedAt = Objects.requireNonNull(now, "now");
        if (!submittedAt.isBefore(round.votingEndsAt())) {
            throw votingNotOpen();
        }
        requirePlayer(voterParticipantId);
        if (round.speakerParticipantId().equals(voterParticipantId)) {
            throw new DomainException(
                    DomainException.Code.SPEAKER_CANNOT_VOTE,
                    "발표자는 자신의 라운드에 투표할 수 없습니다."
            );
        }
        if (!round.containsStatement(statementId)) {
            throw validation("현재 라운드에 속한 문장을 선택해 주세요.");
        }

        Optional<Vote> previous = round.voteBy(voterParticipantId);
        if (previous.isPresent() && previous.get().statementId().equals(statementId)) {
            return new VoteSubmission(false, false, false);
        }

        boolean firstVote = previous.isEmpty();
        round.putVote(voterParticipantId, statementId, submittedAt);
        boolean votingClosed = round.voteCount() == eligibleVoterCount();
        if (votingClosed) {
            round.closeVoting();
            round.scheduleResultReveal(submittedAt.plus(RESULT_REVEAL_DELAY));
            status = TtfGameStatus.VOTE_CLOSED;
        }
        incrementVersion();
        return new VoteSubmission(true, firstVote, votingClosed);
    }

    public synchronized void closeVoting(String roundId) {
        Round round = requireCurrentRound(roundId);
        requireStatus(TtfGameStatus.VOTING);
        round.closeVoting();
        status = TtfGameStatus.VOTE_CLOSED;
        incrementVersion();
    }

    public synchronized boolean closeVotingIfDue(String roundId, Instant now) {
        if (status != TtfGameStatus.VOTING) {
            return false;
        }
        Round round = currentRoundRequired();
        if (!round.id().equals(roundId)) {
            return false;
        }
        if (Objects.requireNonNull(now, "now").isBefore(round.votingEndsAt())) {
            return false;
        }
        round.closeVoting();
        status = TtfGameStatus.VOTE_CLOSED;
        incrementVersion();
        return true;
    }

    public synchronized void requireRoundExists(String roundId) {
        if (rounds.stream().noneMatch(round -> round.id().equals(roundId))) {
            throw new DomainException(DomainException.Code.ROUND_NOT_FOUND, "라운드를 찾을 수 없습니다.");
        }
    }

    public synchronized RoundResult revealResult(String roundId, Instant now) {
        Round round = requireCurrentRound(roundId);
        if (status == TtfGameStatus.RESULT && round.result().isPresent()) {
            return round.result().orElseThrow();
        }
        requireStatus(TtfGameStatus.VOTE_CLOSED);
        Objects.requireNonNull(now, "now");
        if (round.resultRevealsAt() != null && now.isBefore(round.resultRevealsAt())) {
            throw invalidTransition();
        }

        Statement fakeStatement = round.statements().stream()
                .filter(Statement::fake)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("round has no fake statement"));
        Map<String, List<String>> votersByStatementId = new LinkedHashMap<>();
        for (Statement statement : round.statements()) {
            votersByStatementId.put(statement.id(), new ArrayList<>());
        }
        for (Vote vote : round.votes()) {
            List<String> voters = votersByStatementId.get(vote.statementId());
            if (voters == null) {
                throw new IllegalStateException("vote references a statement outside the round");
            }
            voters.add(vote.voterParticipantId());
        }

        int totalVotes = round.votes().size();
        List<StatementResult> statementResults = round.statements().stream()
                .map(statement -> {
                    List<String> voters = votersByStatementId.get(statement.id());
                    double rate = totalVotes == 0
                            ? 0.0
                            : roundToOneDecimal(voters.size() * 100.0 / totalVotes);
                    return new StatementResult(
                            statement.id(),
                            statement.content(),
                            statement.displayOrder(),
                            statement.fake(),
                            voters.size(),
                            rate,
                            voters
                    );
                })
                .toList();

        Set<String> correctVoterIds = new HashSet<>(votersByStatementId.get(fakeStatement.id()));
        List<ScoreChange> scoreChanges = new ArrayList<>(correctVoterIds.size() + 1);
        for (TtfPlayer player : players.values()) {
            if (correctVoterIds.contains(player.participantId())) {
                player.addScore(1);
                scoreChanges.add(new ScoreChange(player.participantId(), 1, player.score()));
            }
        }

        int fooledCount = totalVotes - correctVoterIds.size();
        if (fooledCount > 0) {
            TtfPlayer speaker = requirePlayer(round.speakerParticipantId());
            speaker.addScore(fooledCount);
            scoreChanges.add(new ScoreChange(speaker.participantId(), fooledCount, speaker.score()));
        }
        RoundResult result = new RoundResult(
                fakeStatement.id(),
                statementResults,
                correctVoterIds.size(),
                fooledCount,
                scoreChanges
        );
        round.reveal(result, Objects.requireNonNull(now, "now"));
        status = TtfGameStatus.RESULT;
        incrementVersion();
        return result;
    }

    public synchronized boolean revealResultIfDue(String roundId, Instant now) {
        if (status != TtfGameStatus.VOTE_CLOSED) {
            return false;
        }
        Round round = currentRoundRequired();
        if (!round.id().equals(roundId)
                || round.resultRevealsAt() == null
                || Objects.requireNonNull(now, "now").isBefore(round.resultRevealsAt())) {
            return false;
        }
        revealResult(roundId, now);
        return true;
    }

    public synchronized void skipRound(String roundId) {
        Round round = requireCurrentRound(roundId);
        requireStatus(TtfGameStatus.ROUND_INTRO);
        round.skip();
        moveToNextRoundOrFinish();
        incrementVersion();
    }

    public synchronized void nextRound() {
        requireStatus(TtfGameStatus.RESULT);
        moveToNextRoundOrFinish();
        incrementVersion();
    }

    public synchronized void pause(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status != TtfGameStatus.ROUND_INTRO
                && status != TtfGameStatus.VOTING
                && status != TtfGameStatus.VOTE_CLOSED
                && status != TtfGameStatus.RESULT) {
            throw invalidTransition();
        }
        pausedFromStatus = status;
        if (status == TtfGameStatus.VOTING) {
            Duration remaining = Duration.between(now, currentRoundRequired().votingEndsAt());
            pausedVotingTimeRemaining = remaining.isNegative() ? Duration.ZERO : remaining;
        } else {
            pausedVotingTimeRemaining = null;
        }
        if (status == TtfGameStatus.VOTE_CLOSED && currentRoundRequired().resultRevealsAt() != null) {
            Duration remaining = Duration.between(now, currentRoundRequired().resultRevealsAt());
            pausedResultTimeRemaining = remaining.isNegative() ? Duration.ZERO : remaining;
        } else {
            pausedResultTimeRemaining = null;
        }
        status = TtfGameStatus.PAUSED;
        incrementVersion();
    }

    public synchronized void resume(Instant now) {
        requireStatus(TtfGameStatus.PAUSED);
        Objects.requireNonNull(now, "now");
        TtfGameStatus resumedStatus = Objects.requireNonNull(pausedFromStatus, "pausedFromStatus");
        if (resumedStatus == TtfGameStatus.VOTING) {
            Duration remaining = Objects.requireNonNull(pausedVotingTimeRemaining, "pausedVotingTimeRemaining");
            currentRoundRequired().extendVotingTo(now.plus(remaining));
        }
        if (resumedStatus == TtfGameStatus.VOTE_CLOSED && pausedResultTimeRemaining != null) {
            currentRoundRequired().scheduleResultReveal(now.plus(pausedResultTimeRemaining));
        }
        status = resumedStatus;
        pausedFromStatus = null;
        pausedVotingTimeRemaining = null;
        pausedResultTimeRemaining = null;
        incrementVersion();
    }

    public synchronized void finish() {
        if (status != TtfGameStatus.ROUND_INTRO
                && status != TtfGameStatus.VOTING
                && status != TtfGameStatus.VOTE_CLOSED
                && status != TtfGameStatus.RESULT
                && status != TtfGameStatus.PAUSED) {
            throw invalidTransition();
        }
        finishInternal();
        incrementVersion();
    }

    public synchronized void cancel() {
        requirePreGameMutation();
        status = TtfGameStatus.CANCELLED;
        incrementVersion();
    }

    public synchronized void markSnapshotChanged() {
        incrementVersion();
    }

    public String type() {
        return "TTF";
    }

    public synchronized TtfGameStatus status() {
        return status;
    }

    public synchronized Optional<TtfGameStatus> pausedFromStatus() {
        return Optional.ofNullable(pausedFromStatus);
    }

    public synchronized long version() {
        return version;
    }

    public synchronized int readyCount() {
        return (int) players.values().stream().filter(TtfPlayer::ready).count();
    }

    public synchronized int roundCount() {
        return rounds.size();
    }

    public synchronized Optional<Integer> currentRoundNumber() {
        return currentRoundIndex < 0 ? Optional.empty() : Optional.of(currentRoundIndex + 1);
    }

    public synchronized Optional<Round> currentRound() {
        return currentRoundIndex < 0 ? Optional.empty() : Optional.of(rounds.get(currentRoundIndex));
    }

    public synchronized List<Round> rounds() {
        return List.copyOf(rounds);
    }

    public synchronized List<TtfPlayer> players() {
        return List.copyOf(players.values());
    }

    public synchronized Optional<TtfPlayer> player(String participantId) {
        return Optional.ofNullable(players.get(participantId));
    }

    public synchronized List<Statement> statementsFor(String participantId) {
        return flattenStatements(statementSetsByParticipantId.get(participantId));
    }

    public synchronized List<Statement> statementsFor(String participantId, TtfTopic topic) {
        Map<TtfTopic, List<Statement>> statementSets = statementSetsByParticipantId.get(participantId);
        if (statementSets == null) {
            return List.of();
        }
        return statementSets.getOrDefault(topic, List.of());
    }

    public synchronized List<LeaderboardEntry> leaderboard() {
        return List.copyOf(leaderboard);
    }

    public synchronized int eligibleVoterCount() {
        return currentRoundIndex < 0 ? 0 : Math.max(0, players.size() - 1);
    }

    public synchronized boolean acceptsParticipants() {
        return status == TtfGameStatus.LOBBY
                || status == TtfGameStatus.SUBMISSION
                || status == TtfGameStatus.READY;
    }

    public synchronized boolean isFinished() {
        return status == TtfGameStatus.FINISHED;
    }

    static String newOpaqueId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    private void reevaluatePreparationStatus() {
        if (players.isEmpty()) {
            status = TtfGameStatus.LOBBY;
        } else if (players.size() >= 2 && players.values().stream().allMatch(TtfPlayer::ready)) {
            status = TtfGameStatus.READY;
        } else {
            status = TtfGameStatus.SUBMISSION;
        }
    }

    private List<StatementDraft> validateAndNormalizeStatements(List<StatementDraft> drafts) {
        if (drafts == null || drafts.size() != 3 || drafts.stream().anyMatch(Objects::isNull)) {
            throw validation("문장은 정확히 3개여야 합니다.");
        }
        if (drafts.stream().filter(StatementDraft::fake).count() != 1) {
            throw validation("가짜 문장은 정확히 1개여야 합니다.");
        }

        Set<String> normalizedForComparison = new HashSet<>();
        List<StatementDraft> normalized = new ArrayList<>(3);
        for (StatementDraft draft : drafts) {
            String content = normalizeStatement(draft.content());
            if (!normalizedForComparison.add(content)) {
                throw validation("같은 문장을 중복해서 제출할 수 없습니다.");
            }
            normalized.add(new StatementDraft(content, draft.fake()));
        }
        return List.copyOf(normalized);
    }

    private Map<TtfTopic, List<StatementDraft>> validateAndNormalizeStatementSets(
            List<StatementSetDraft> statementSetDrafts
    ) {
        if (statementSetDrafts == null
                || statementSetDrafts.size() != settings.roundCount()
                || statementSetDrafts.stream().anyMatch(Objects::isNull)) {
            throw validation("문장 세트 수는 설정한 라운드 수와 같아야 합니다.");
        }

        Map<TtfTopic, List<StatementDraft>> provided = new EnumMap<>(TtfTopic.class);
        for (StatementSetDraft statementSet : statementSetDrafts) {
            if (statementSet.topic() == null || !settings.topics().contains(statementSet.topic())) {
                throw validation("게임에 선택된 라운드 주제만 제출할 수 있습니다.");
            }
            if (provided.put(statementSet.topic(), validateAndNormalizeStatements(statementSet.statements())) != null) {
                throw validation("같은 라운드 주제를 중복해서 제출할 수 없습니다.");
            }
        }
        if (!provided.keySet().containsAll(settings.topics())) {
            throw validation("모든 라운드 주제의 문장을 제출해 주세요.");
        }

        Set<String> allContents = new HashSet<>();
        Map<TtfTopic, List<StatementDraft>> ordered = new LinkedHashMap<>();
        for (TtfTopic topic : settings.topics()) {
            List<StatementDraft> drafts = provided.get(topic);
            for (StatementDraft draft : drafts) {
                if (!allContents.add(draft.content())) {
                    throw validation("다른 주제에도 같은 문장을 중복해서 제출할 수 없습니다.");
                }
            }
            ordered.put(topic, drafts);
        }
        return ordered;
    }

    private String normalizeStatement(String content) {
        if (content == null) {
            throw validation("문장 내용은 필수입니다.");
        }
        String normalized = Normalizer.normalize(content, Normalizer.Form.NFC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < settings.statementMinLength() || length > settings.statementMaxLength()) {
            throw validation("문장 길이가 허용 범위를 벗어났습니다.");
        }
        return normalized;
    }

    private static boolean hasSameRepresentation(
            Map<TtfTopic, List<Statement>> current,
            Map<TtfTopic, List<StatementDraft>> drafts
    ) {
        if (current == null || !current.keySet().equals(drafts.keySet())) {
            return false;
        }
        for (TtfTopic topic : drafts.keySet()) {
            List<Statement> currentStatements = current.get(topic);
            List<StatementDraft> currentDrafts = drafts.get(topic);
            if (currentStatements == null || currentStatements.size() != currentDrafts.size()) {
                return false;
            }
            for (int index = 0; index < currentStatements.size(); index++) {
                Statement statement = currentStatements.get(index);
                StatementDraft draft = currentDrafts.get(index);
                if (!statement.content().equals(draft.content()) || statement.fake() != draft.fake()) {
                    return false;
                }
            }
        }
        return true;
    }

    private List<Statement> flattenStatements(Map<TtfTopic, List<Statement>> statementSets) {
        if (statementSets == null) {
            return List.of();
        }
        return settings.topics().stream()
                .flatMap(topic -> statementSets.getOrDefault(topic, List.of()).stream())
                .toList();
    }

    private void moveToNextRoundOrFinish() {
        if (currentRoundIndex + 1 < rounds.size()) {
            currentRoundIndex++;
            status = TtfGameStatus.ROUND_INTRO;
        } else {
            finishInternal();
        }
    }

    private void finishInternal() {
        leaderboard.clear();
        List<TtfPlayer> ordered = new ArrayList<>(players.values());
        ordered.sort(Comparator.comparingInt(TtfPlayer::score).reversed()
                .thenComparingInt(TtfPlayer::joinOrder));
        Integer previousScore = null;
        int rank = 0;
        for (int index = 0; index < ordered.size(); index++) {
            TtfPlayer player = ordered.get(index);
            if (previousScore == null || player.score() != previousScore) {
                rank = index + 1;
                previousScore = player.score();
            }
            leaderboard.add(new LeaderboardEntry(player.participantId(), player.score(), rank));
        }
        status = TtfGameStatus.FINISHED;
        pausedFromStatus = null;
        pausedVotingTimeRemaining = null;
    }

    private Round requireCurrentRound(String roundId) {
        Round current = currentRoundRequired();
        if (current.id().equals(roundId)) {
            return current;
        }
        boolean belongsToGame = rounds.stream().anyMatch(round -> round.id().equals(roundId));
        if (!belongsToGame) {
            throw new DomainException(DomainException.Code.ROUND_NOT_FOUND, "라운드를 찾을 수 없습니다.");
        }
        throw invalidTransition();
    }

    private Round currentRoundRequired() {
        if (currentRoundIndex < 0 || currentRoundIndex >= rounds.size()) {
            throw new DomainException(DomainException.Code.ROUND_NOT_FOUND, "현재 라운드를 찾을 수 없습니다.");
        }
        return rounds.get(currentRoundIndex);
    }

    private TtfPlayer requirePlayer(String participantId) {
        TtfPlayer player = players.get(participantId);
        if (player == null) {
            throw new DomainException(
                    DomainException.Code.PARTICIPANT_PERMISSION_REQUIRED,
                    "이 게임의 참가자만 실행할 수 있습니다."
            );
        }
        return player;
    }

    private void requirePreGameMutation() {
        if (!acceptsParticipants()) {
            throw invalidTransition();
        }
    }

    private void requireStatus(TtfGameStatus expected) {
        if (status != expected) {
            throw invalidTransition();
        }
    }

    private void incrementVersion() {
        version++;
    }

    private static <T> void shuffle(List<T> values, RandomGenerator random) {
        for (int index = values.size() - 1; index > 0; index--) {
            int replacementIndex = random.nextInt(index + 1);
            T value = values.get(index);
            values.set(index, values.get(replacementIndex));
            values.set(replacementIndex, value);
        }
    }

    private static double roundToOneDecimal(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    private static String requireId(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw validation(fieldName + "는 필수입니다.");
        }
        return value;
    }

    private static DomainException validation(String message) {
        return new DomainException(DomainException.Code.VALIDATION_ERROR, message);
    }

    private static DomainException invalidTransition() {
        return new DomainException(
                DomainException.Code.INVALID_STATE_TRANSITION,
                "현재 게임 상태에서는 이 작업을 실행할 수 없습니다."
        );
    }

    private static DomainException votingNotOpen() {
        return new DomainException(
                DomainException.Code.VOTING_NOT_OPEN,
                "현재는 투표할 수 없습니다."
        );
    }

}
