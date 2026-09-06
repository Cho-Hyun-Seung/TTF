package com.toki.ttf.domain.ttf.value;

import java.util.List;

public record RoundResult(
        String fakeStatementId,
        List<StatementResult> statements,
        int correctVoterCount,
        int fooledParticipantCount,
        List<ScoreChange> scoreChanges
) {
    public RoundResult {
        statements = List.copyOf(statements);
        scoreChanges = List.copyOf(scoreChanges);
    }
}
