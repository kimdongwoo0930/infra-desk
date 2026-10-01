package com.infradesk.alert;

import java.time.Instant;

/** 보낼 알림 하나. */
public record Alert(Level level, String title, String description, Instant time) {

    /** embed 색을 결정한다. 제목에 무슨 일인지 항상 쓰므로 색만으로 구분하지 않는다. */
    public enum Level { PROBLEM, RECOVERED, INFO }
}
