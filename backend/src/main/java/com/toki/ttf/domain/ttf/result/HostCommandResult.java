package com.toki.ttf.domain.ttf.result;

import com.toki.ttf.domain.ttf.constants.TtfGameStatus;

import java.time.Instant;

/**
 * 진행자 명령으로 계산된 마감 시각과 게임 상태, 버전을 전달합니다.
 */
public record HostCommandResult(
        Instant deadline,
        TtfGameStatus state,
        String marker,
        long generation
) {

    public static HostCommandResult atVersion(long generation) {
        return new HostCommandResult(null, null, null, generation);
    }
}
