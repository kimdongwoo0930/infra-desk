package com.infradesk.ui;

import java.util.Map;

/** 자주 쓰는 리전의 짧은 한국어 이름. 사이드바에 표시된다. */
public final class Regions {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry(com.infradesk.core.SshHostProperties.REGION, "SSH"),
            Map.entry("ap-chuncheon-1", "춘천"),
            Map.entry("ap-seoul-1", "서울"),
            Map.entry("ap-tokyo-1", "도쿄"),
            Map.entry("ap-osaka-1", "오사카"),
            Map.entry("ap-singapore-1", "싱가포르"),
            Map.entry("us-ashburn-1", "애슈번"),
            Map.entry("us-phoenix-1", "피닉스"),
            Map.entry("us-sanjose-1", "산호세"),
            Map.entry("eu-frankfurt-1", "프랑크푸르트"));

    private Regions() {
    }

    public static String shortName(String regionId) {
        return NAMES.getOrDefault(regionId, regionId);
    }
}
