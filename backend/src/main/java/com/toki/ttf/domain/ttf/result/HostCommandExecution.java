package com.toki.ttf.domain.ttf.result;

import com.toki.ttf.domain.room.entity.Room;

/**
 * 진행자 명령의 대상 방, 결과와 멱등 재실행 여부를 전달합니다.
 */
public record HostCommandExecution(Room room, HostCommandResult result, boolean replayed) {
}
