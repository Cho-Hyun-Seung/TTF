package com.toki.ttf.domain.ttf.value;

import java.util.List;

public record StatementResult(
        String id,
        String content,
        int displayOrder,
        boolean fake,
        int voteCount,
        double voteRate,
        List<String> voterParticipantIds
) {
    public StatementResult {
        voterParticipantIds = List.copyOf(voterParticipantIds);
    }
}
