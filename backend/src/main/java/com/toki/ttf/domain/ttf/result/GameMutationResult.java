package com.toki.ttf.domain.ttf.result;

import com.toki.ttf.domain.room.entity.Room;
import com.toki.ttf.domain.ttf.constants.TtfGameStatus;

/**
 * 게임 변경 전후 버전과 변경 전 상태를 전달하는 연산 결과입니다.
 */
public record GameMutationResult(long beforeVersion, TtfGameStatus beforeStatus, long afterVersion) {

    public static GameMutationResult of(Room room, long beforeVersion, TtfGameStatus beforeStatus) {
        return new GameMutationResult(beforeVersion, beforeStatus, room.version());
    }

    public boolean changed() {
        return beforeVersion != afterVersion;
    }
}
