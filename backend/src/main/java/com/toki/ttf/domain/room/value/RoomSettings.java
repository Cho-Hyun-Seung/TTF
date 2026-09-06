package com.toki.ttf.domain.room.value;

import com.toki.ttf.domain.common.DomainException;

public record RoomSettings(int maxParticipants) {
    public RoomSettings {
        if (maxParticipants < 2 || maxParticipants > 100) {
            throw new DomainException(
                    DomainException.Code.VALIDATION_ERROR,
                    "최대 참가 인원은 2명 이상 100명 이하여야 합니다."
            );
        }
    }
}
