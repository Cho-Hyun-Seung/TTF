package com.toki.ttf.domain.ttf.result;

/**
 * 자동 투표 마감 적용 여부와 적용된 게임 버전을 전달합니다.
 */
public record AutoCloseResult(boolean closed, long generation) {
}
