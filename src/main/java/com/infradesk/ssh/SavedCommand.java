package com.infradesk.ssh;

import java.util.Objects;

/**
 * A named shell command shown as a button in the terminal side panel.
 *
 * @param id      stable local id
 * @param name    label, e.g. "봇 재시작"
 * @param command command line, e.g. "docker restart bot-app"
 */
public record SavedCommand(String id, String name, String command) {

    public SavedCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
    }
}
