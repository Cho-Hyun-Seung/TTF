package com.toki.ttf.domain.ttf.controller;

import com.toki.ttf.contract.response.ApiResponse;
import com.toki.ttf.domain.ttf.constants.TtfTopic;
import com.toki.ttf.domain.ttf.dto.response.TtfTopicResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/games/ttf")
public class TtfCatalogController {

    @GetMapping("/topics")
    public ApiResponse<List<TtfTopicResponse>> topics() {
        return ApiResponse.success(TtfTopic.catalog().stream().map(TtfTopicResponse::from).toList());
    }
}
