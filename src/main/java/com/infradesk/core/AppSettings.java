package com.infradesk.core;

/**
 * 앱 전체 설정 중 사용자가 화면에서 고르는 것들. 비밀값이 아니라서 금고가 아니라 {@code app-settings.json}에 둔다.
 *
 * @param startServerId 앱을 열 때 처음 보여줄 서버의 id. null이면 개요를 보여 준다
 * @param grouping      사이드바와 개요에서 서버를 묶는 방법
 * @param overviewView  개요에서 서버를 카드로 보여 줄지 목록으로 보여 줄지
 */
public record AppSettings(String startServerId, ServerGrouping grouping, OverviewView overviewView) {

    /** 설정 파일이 없을 때의 값: 개요로 시작하고, 계정별로 묶고, 카드로 보여 준다. */
    public static final AppSettings DEFAULT = new AppSettings(null, ServerGrouping.ACCOUNT, OverviewView.CARDS);

    public AppSettings {
        if (startServerId != null && startServerId.isBlank()) {
            startServerId = null;
        }
        if (grouping == null) {
            grouping = ServerGrouping.ACCOUNT;
        }
        if (overviewView == null) {
            overviewView = OverviewView.CARDS;
        }
    }

    /** 보기 방식은 처음 값(카드)으로 두는 간편 생성자. */
    public AppSettings(String startServerId, ServerGrouping grouping) {
        this(startServerId, grouping, OverviewView.CARDS);
    }

    public AppSettings withStartServerId(String id) {
        return new AppSettings(id, grouping, overviewView);
    }

    public AppSettings withGrouping(ServerGrouping value) {
        return new AppSettings(startServerId, value, overviewView);
    }

    public AppSettings withOverviewView(OverviewView value) {
        return new AppSettings(startServerId, grouping, value);
    }
}
