package com.infradesk.alert;

import java.time.Instant;

/** One notification to send. */
public record Alert(Level level, String title, String description, Instant time) {

    /** Drives the embed color; the title always says what happened, so color is never alone. */
    public enum Level { PROBLEM, RECOVERED, INFO }
}
