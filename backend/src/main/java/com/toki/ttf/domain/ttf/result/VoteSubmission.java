package com.toki.ttf.domain.ttf.result;

/**
 * 투표 반영 여부와 최초 투표 여부를 나타내는 도메인 연산 결과입니다.
 */
public record VoteSubmission(boolean changed, boolean firstVote, boolean votingClosed) {
}
