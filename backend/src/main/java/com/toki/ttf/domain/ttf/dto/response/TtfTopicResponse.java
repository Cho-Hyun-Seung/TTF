package com.toki.ttf.domain.ttf.dto.response;

import tools.jackson.databind.PropertyNamingStrategies;
import tools.jackson.databind.annotation.JsonNaming;
import com.toki.ttf.domain.ttf.constants.TtfTopic;

@JsonNaming(PropertyNamingStrategies.SnakeCaseStrategy.class)
public record TtfTopicResponse(
        String id,
        String title,
        String example
) {
    public static TtfTopicResponse from(TtfTopic topic) {
        return new TtfTopicResponse(topic.name(), topic.title(), topic.example());
    }
}
