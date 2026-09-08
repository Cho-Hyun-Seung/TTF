package com.toki.ttf.domain.ttf.value;

import com.toki.ttf.domain.ttf.constants.TtfTopic;

import java.util.List;

public record StatementSetDraft(TtfTopic topic, List<StatementDraft> statements) {
}
