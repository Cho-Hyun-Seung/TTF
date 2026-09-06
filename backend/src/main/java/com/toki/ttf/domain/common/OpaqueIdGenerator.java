package com.toki.ttf.domain.common;

import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.util.UUID;

@Component
public class OpaqueIdGenerator {

    private static final char[] ROOM_CODE_ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789".toCharArray();
    private static final int ROOM_CODE_LENGTH = 6;

    private final SecureRandom random = new SecureRandom();

    public String roomId() {
        return opaque("room_");
    }

    public String gameId() {
        return opaque("game_");
    }

    public String participantId() {
        return opaque("participant_");
    }

    public String roundId() {
        return opaque("round_");
    }

    public String statementId() {
        return opaque("statement_");
    }

    public String roomCode() {
        StringBuilder result = new StringBuilder(ROOM_CODE_LENGTH);
        for (int index = 0; index < ROOM_CODE_LENGTH; index++) {
            result.append(ROOM_CODE_ALPHABET[random.nextInt(ROOM_CODE_ALPHABET.length)]);
        }
        return result.toString();
    }

    private static String opaque(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }
}
