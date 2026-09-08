package com.toki.ttf.domain.room.entity;

import com.toki.ttf.domain.common.DomainException;
import com.toki.ttf.domain.room.constants.ConnectionStatus;
import com.toki.ttf.domain.room.constants.RoomStatus;
import com.toki.ttf.domain.room.value.RoomSettings;
import com.toki.ttf.domain.ttf.entity.Statement;
import com.toki.ttf.domain.ttf.entity.TtfGame;
import com.toki.ttf.domain.ttf.result.VoteSubmission;
import com.toki.ttf.domain.ttf.value.RoundResult;
import com.toki.ttf.domain.ttf.value.StatementDraft;
import com.toki.ttf.domain.ttf.value.StatementSetDraft;
import com.toki.ttf.domain.ttf.value.TtfGameSettings;
import lombok.Getter;
import lombok.experimental.Accessors;

import java.text.Normalizer;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import java.util.regex.Pattern;

@Accessors(fluent = true)
public final class Room {
    private static final Pattern ROOM_CODE = Pattern.compile("[A-Z0-9]{6}");
    private static final Pattern WHITESPACE = Pattern.compile("(?U)\\s+");

    @Getter
    private final String id;
    @Getter
    private final String code;
    @Getter
    private final String name;
    @Getter
    private final RoomSettings settings;
    private final Map<String, Participant> participants = new LinkedHashMap<>();
    private final Map<String, String> participantIdByNormalizedNickname = new LinkedHashMap<>();
    @Getter
    private final TtfGame activeGame;
    @Getter
    private final Instant createdAt;
    private RoomStatus status;
    private Instant lastActivityAt;
    private final Instant activeExpiresAt;
    private Instant deletionAt;

    public Room(
            String id,
            String code,
            String name,
            RoomSettings settings,
            TtfGame activeGame,
            Instant createdAt,
            Instant expiresAt
    ) {
        this.id = requireOpaqueId(id, "room id");
        this.code = normalizeCode(code);
        this.name = normalizeRoomName(name);
        this.settings = Objects.requireNonNull(settings, "settings");
        this.activeGame = Objects.requireNonNull(activeGame, "activeGame");
        if (!this.id.equals(activeGame.roomId())) {
            throw validation("게임이 속한 방과 room id가 일치하지 않습니다.");
        }
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.lastActivityAt = createdAt;
        this.activeExpiresAt = expiresAt;
        this.status = RoomStatus.OPEN;
    }

    public static Room create(
            String roomId,
            String code,
            String name,
            RoomSettings roomSettings,
            String gameId,
            TtfGameSettings gameSettings,
            Instant now,
            Instant expiresAt
    ) {
        Objects.requireNonNull(now, "now");
        return new Room(
                roomId,
                code,
                name,
                roomSettings,
                TtfGame.create(gameId, roomId, gameSettings, now),
                now,
                expiresAt
        );
    }

    public synchronized Participant join(String participantId, String nickname, Instant now) {
        requireOpenForMembership();
        Objects.requireNonNull(now, "now");
        String validParticipantId = requireOpaqueId(participantId, "participant id");
        String displayNickname = normalizeDisplayNickname(nickname);
        String normalizedNickname = normalizeNicknameForComparison(displayNickname);

        Participant existing = participants.get(validParticipantId);
        if (existing != null) {
            if (!existing.normalizedNickname().equals(normalizedNickname)) {
                throw new DomainException(
                        DomainException.Code.NICKNAME_TAKEN,
                        "이미 참가 중인 세션의 닉네임과 일치하지 않습니다."
                );
            }
            boolean reconnected = existing.changeConnectionStatus(ConnectionStatus.ONLINE);
            if (reconnected) {
                activeGame.markSnapshotChanged();
                touch(now);
            }
            return existing;
        }

        if (participants.size() >= settings.maxParticipants()) {
            throw new DomainException(DomainException.Code.ROOM_FULL, "방 정원이 가득 찼습니다.");
        }
        if (participantIdByNormalizedNickname.containsKey(normalizedNickname)) {
            throw new DomainException(
                    DomainException.Code.NICKNAME_TAKEN,
                    "이미 사용 중인 닉네임이에요. 다른 이름을 입력해 주세요."
            );
        }

        Participant participant = new Participant(validParticipantId, displayNickname, normalizedNickname, now);
        participants.put(validParticipantId, participant);
        participantIdByNormalizedNickname.put(normalizedNickname, validParticipantId);
        activeGame.registerParticipant(validParticipantId);
        touch(now);
        return participant;
    }

    public synchronized boolean removeParticipant(String participantId, Instant now) {
        if (status != RoomStatus.OPEN || !activeGame.acceptsParticipants()) {
            throw new DomainException(
                    DomainException.Code.INVALID_STATE_TRANSITION,
                    "게임 시작 후에는 참가자를 내보낼 수 없습니다."
            );
        }
        Objects.requireNonNull(now, "now");
        Participant removed = participants.remove(participantId);
        if (removed == null) {
            return false;
        }
        participantIdByNormalizedNickname.remove(removed.normalizedNickname());
        activeGame.unregisterParticipant(participantId);
        touch(now);
        return true;
    }

    public synchronized boolean setParticipantConnection(
            String participantId,
            ConnectionStatus connectionStatus,
            Instant now
    ) {
        Participant participant = requireParticipant(participantId);
        boolean changed = participant.changeConnectionStatus(connectionStatus);
        if (changed) {
            activeGame.markSnapshotChanged();
            touch(now);
        }
        return changed;
    }

    public synchronized List<Statement> saveStatements(
            String participantId,
            List<StatementDraft> drafts,
            Instant now
    ) {
        requireParticipant(participantId);
        long previousVersion = activeGame.version();
        List<Statement> statements = activeGame.saveStatements(
                participantId,
                drafts,
                new java.security.SecureRandom(),
                now
        );
        if (activeGame.version() != previousVersion) {
            touch(now);
        }
        return statements;
    }

    public synchronized List<Statement> saveStatementSets(
            String participantId,
            List<StatementSetDraft> statementSets,
            Instant now
    ) {
        requireParticipant(participantId);
        long previousVersion = activeGame.version();
        List<Statement> statements = activeGame.saveStatementSets(
                participantId,
                statementSets,
                new java.security.SecureRandom(),
                now
        );
        if (activeGame.version() != previousVersion) {
            touch(now);
        }
        return statements;
    }

    public synchronized List<Statement> saveStatementSets(
            String participantId,
            List<StatementSetDraft> statementSets,
            RandomGenerator random,
            Instant now
    ) {
        requireParticipant(participantId);
        long previousVersion = activeGame.version();
        List<Statement> statements = activeGame.saveStatementSets(participantId, statementSets, random, now);
        if (activeGame.version() != previousVersion) {
            touch(now);
        }
        return statements;
    }

    public synchronized List<Statement> saveStatements(
            String participantId,
            List<StatementDraft> drafts,
            RandomGenerator random,
            Instant now
    ) {
        requireParticipant(participantId);
        long previousVersion = activeGame.version();
        List<Statement> statements = activeGame.saveStatements(participantId, drafts, random, now);
        if (activeGame.version() != previousVersion) {
            touch(now);
        }
        return statements;
    }

    public synchronized void startGame(Instant now) {
        ensureStatus(RoomStatus.OPEN);
        activeGame.start(now);
        status = RoomStatus.IN_GAME;
        touch(now);
    }

    public synchronized void startGame(RandomGenerator random, Instant now) {
        ensureStatus(RoomStatus.OPEN);
        activeGame.start(random, now);
        status = RoomStatus.IN_GAME;
        touch(now);
    }

    public synchronized void startVoting(String roundId, Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.startVoting(roundId, now);
        touch(now);
    }

    public synchronized void extendVoting(String roundId, int seconds, Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.extendVoting(roundId, seconds, now);
        touch(now);
    }

    public synchronized VoteSubmission submitVote(
            String participantId,
            String roundId,
            String statementId,
            Instant now
    ) {
        ensureStatus(RoomStatus.IN_GAME);
        requireParticipant(participantId);
        VoteSubmission submission = activeGame.submitVote(participantId, roundId, statementId, now);
        if (submission.changed()) {
            touch(now);
        }
        return submission;
    }

    public synchronized void closeVoting(String roundId, Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.closeVoting(roundId);
        touch(now);
    }

    public synchronized boolean closeVotingIfDue(String roundId, Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        boolean closed = activeGame.closeVotingIfDue(roundId, now);
        if (closed) {
            touch(now);
        }
        return closed;
    }

    public synchronized RoundResult revealResult(String roundId, Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        long previousVersion = activeGame.version();
        RoundResult result = activeGame.revealResult(roundId, now);
        if (activeGame.version() != previousVersion) {
            touch(now);
        }
        return result;
    }

    public synchronized boolean revealResultIfDue(String roundId, Instant now) {
        if (status != RoomStatus.IN_GAME) {
            return false;
        }
        boolean revealed = activeGame.revealResultIfDue(roundId, now);
        if (revealed) {
            touch(now);
        }
        return revealed;
    }

    public synchronized void skipRound(String roundId, Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.skipRound(roundId);
        closeRoomWhenGameFinished();
        touch(now);
    }

    public synchronized void nextRound(Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.nextRound();
        closeRoomWhenGameFinished();
        touch(now);
    }

    public synchronized void pause(Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.pause(now);
        touch(now);
    }

    public synchronized void resume(Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.resume(now);
        touch(now);
    }

    public synchronized void finish(Instant now) {
        ensureStatus(RoomStatus.IN_GAME);
        activeGame.finish();
        status = RoomStatus.CLOSED;
        touch(now);
    }

    public synchronized void cancel(Instant now) {
        ensureStatus(RoomStatus.OPEN);
        activeGame.cancel();
        status = RoomStatus.CLOSED;
        touch(now);
    }

    public synchronized void expire(Instant now) {
        Objects.requireNonNull(now, "now");
        if (status == RoomStatus.EXPIRED) {
            return;
        }
        status = RoomStatus.EXPIRED;
        activeGame.markSnapshotChanged();
        touch(now);
    }

    public synchronized void scheduleDeletionAt(Instant deletionTime) {
        deletionAt = Objects.requireNonNull(deletionTime, "deletionTime");
    }

    public synchronized boolean isExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return switch (status) {
            case OPEN, IN_GAME -> activeExpiresAt != null && !activeExpiresAt.isAfter(now);
            case CLOSED, EXPIRED -> deletionAt != null && !deletionAt.isAfter(now);
        };
    }

    public synchronized boolean isActiveExpiredAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return activeExpiresAt != null && !activeExpiresAt.isAfter(now);
    }

    public synchronized boolean isDeletionDueAt(Instant now) {
        Objects.requireNonNull(now, "now");
        return deletionAt != null && !deletionAt.isAfter(now);
    }

    public synchronized RoomStatus status() {
        return status;
    }

    public synchronized Instant lastActivityAt() {
        return lastActivityAt;
    }

    public synchronized Instant expiresAt() {
        return activeExpiresAt;
    }

    public synchronized Instant deletionAt() {
        return deletionAt;
    }

    public synchronized boolean joinable() {
        return status == RoomStatus.OPEN && activeGame.acceptsParticipants();
    }

    public synchronized int participantCount() {
        return participants.size();
    }

    public synchronized List<Participant> participants() {
        return List.copyOf(participants.values());
    }

    public synchronized Optional<Participant> participant(String participantId) {
        return Optional.ofNullable(participants.get(participantId));
    }

    public long version() {
        return activeGame.version();
    }

    private Participant requireParticipant(String participantId) {
        Participant participant = participants.get(participantId);
        if (participant == null) {
            throw new DomainException(
                    DomainException.Code.PARTICIPANT_PERMISSION_REQUIRED,
                    "이 방의 참가자만 실행할 수 있습니다."
            );
        }
        return participant;
    }

    private void requireOpenForMembership() {
        if (status != RoomStatus.OPEN || !activeGame.acceptsParticipants()) {
            throw new DomainException(
                    DomainException.Code.GAME_ALREADY_STARTED,
                    "게임이 시작되어 새로 참가할 수 없습니다."
            );
        }
    }

    private void ensureStatus(RoomStatus expected) {
        if (status != expected) {
            throw new DomainException(
                    DomainException.Code.INVALID_STATE_TRANSITION,
                    "현재 방 상태에서는 이 작업을 실행할 수 없습니다."
            );
        }
    }

    private void closeRoomWhenGameFinished() {
        if (activeGame.isFinished()) {
            status = RoomStatus.CLOSED;
        }
    }

    private void touch(Instant now) {
        lastActivityAt = Objects.requireNonNull(now, "now");
    }

    private static String normalizeCode(String code) {
        if (code == null) {
            throw validation("방 코드는 필수입니다.");
        }
        String normalized = code.strip().toUpperCase(Locale.ROOT);
        if (!ROOM_CODE.matcher(normalized).matches()) {
            throw validation("방 코드는 대문자 영문과 숫자로 구성된 6자리여야 합니다.");
        }
        return normalized;
    }

    private static String normalizeRoomName(String name) {
        if (name == null) {
            throw validation("방 이름은 필수입니다.");
        }
        String normalized = name.strip();
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 40) {
            throw validation("방 이름은 1자 이상 40자 이하여야 합니다.");
        }
        return normalized;
    }

    private static String normalizeDisplayNickname(String nickname) {
        if (nickname == null) {
            throw validation("닉네임은 필수입니다.");
        }
        String normalized = Normalizer.normalize(nickname, Normalizer.Form.NFC).strip();
        normalized = WHITESPACE.matcher(normalized).replaceAll(" ");
        int length = normalized.codePointCount(0, normalized.length());
        if (length < 1 || length > 20) {
            throw validation("닉네임은 1자 이상 20자 이하여야 합니다.");
        }
        return normalized;
    }

    private static String normalizeNicknameForComparison(String nickname) {
        // Java has no direct full Unicode case-folding API. Folding each scalar
        // through lower/upper/lower expands multi-character forms such as ß/ẞ
        // and canonicalizes context forms such as σ/ς. U+0131 is preserved because
        // Unicode's locale-independent default fold does not equate dotless ı to i.
        StringBuilder folded = new StringBuilder(nickname.length());
        nickname.codePoints().forEach(codePoint -> {
            if (codePoint == 0x0131) {
                folded.appendCodePoint(codePoint);
                return;
            }
            String scalar = new String(Character.toChars(codePoint));
            folded.append(scalar.toLowerCase(Locale.ROOT)
                    .toUpperCase(Locale.ROOT)
                    .toLowerCase(Locale.ROOT));
        });
        return Normalizer.normalize(folded, Normalizer.Form.NFC);
    }

    private static String requireOpaqueId(String id, String field) {
        if (id == null || id.isBlank()) {
            throw validation(field + "는 필수입니다.");
        }
        return id;
    }

    private static DomainException validation(String message) {
        return new DomainException(DomainException.Code.VALIDATION_ERROR, message);
    }
}
