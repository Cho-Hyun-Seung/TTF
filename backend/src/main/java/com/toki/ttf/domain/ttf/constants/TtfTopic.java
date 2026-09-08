package com.toki.ttf.domain.ttf.constants;

import java.util.List;

/**
 * 진행자가 선택할 수 있는 TTF 라운드 주제 카탈로그입니다.
 * 클라이언트는 이 목록을 별도로 복제하지 않고 공개 카탈로그 API에서 조회합니다.
 */
public enum TtfTopic {
    TRAVEL("여행", "나는 혼자 해외여행을 떠난 적이 있다."),
    FOOD("음식", "나는 한 번도 커피를 마셔본 적이 없다."),
    TALENT("특기", "나는 세 가지 악기를 연주할 수 있다."),
    CHILDHOOD("어린 시절", "나는 어릴 때 전국 대회에서 상을 받은 적이 있다."),
    ENCOUNTER("뜻밖의 만남", "나는 길에서 유명인을 우연히 만난 적이 있다."),
    CHALLENGE("도전과 기록", "나는 번지점프에 도전한 적이 있다."),
    HABIT("나만의 습관", "나는 잠들기 전에 꼭 지키는 습관이 있다."),
    PET("반려동물", "나는 반려동물을 세 마리 이상 키워본 적이 있다.");

    private final String title;
    private final String example;

    TtfTopic(String title, String example) {
        this.title = title;
        this.example = example;
    }

    public String title() {
        return title;
    }

    public String example() {
        return example;
    }

    public static List<TtfTopic> catalog() {
        return List.of(values());
    }
}
