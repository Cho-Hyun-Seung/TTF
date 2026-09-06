package com.toki.ttf.domain.ttf.result;

/**
 * 투표 요청 처리와 마감 경합 결과를 전달합니다.
 */
public record VoteMutationResult(
        VoteSubmission submission,
        boolean closedByDeadline,
        boolean notOpen,
        long generation
) {
}
