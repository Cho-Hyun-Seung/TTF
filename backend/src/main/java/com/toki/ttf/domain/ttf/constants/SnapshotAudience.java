package com.toki.ttf.domain.ttf.constants;

import com.toki.ttf.contract.error.ApiException;
import com.toki.ttf.contract.error.ErrorCode;

import java.util.Locale;

/**
 * 게임 스냅샷과 실시간 이벤트의 정보 공개 대상을 구분합니다.
 */
public enum SnapshotAudience {
    PARTICIPANT,
    HOST,
    DISPLAY;

    public static SnapshotAudience parse(String value) {
        if (value == null) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
        try {
            return valueOf(value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            throw new ApiException(ErrorCode.VALIDATION_ERROR);
        }
    }
}
