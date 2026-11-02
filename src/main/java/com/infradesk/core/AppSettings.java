package com.infradesk.core;

/**
 * 앱 전체 설정 중 설정 "일반"에서 고르는 것들. 비밀값이 아니라서 금고가 아니라 {@code app-settings.json}에 둔다.
 *
 * @param startServerId 앱을 열 때 처음 보여줄 서버의 id. null이면 개요를 보여 준다
 * @param grouping      사이드바와 개요에서 서버를 묶는 방법
 */
public record AppSettings(String startServerId, ServerGrouping grouping) {

    /** 설정 파일이 없을 때의 값: 개요로 시작하고 계정별로 묶는다. */
    public static final AppSettings DEFAULT = new AppSettings(null, ServerGrouping.ACCOUNT);

    public AppSettings {
        if (startServerId != null && startServerId.isBlank()) {
            startServerId = null;
        }
        if (grouping == null) {
            grouping = ServerGrouping.ACCOUNT;
        }
    }

    public AppSettings withStartServerId(String id) {
        return new AppSettings(id, grouping);
    }

    public AppSettings withGrouping(ServerGrouping value) {
        return new AppSettings(startServerId, value);
    }
}
