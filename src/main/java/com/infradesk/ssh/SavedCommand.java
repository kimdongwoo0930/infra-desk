package com.infradesk.ssh;

import java.util.Objects;

/**
 * 터미널 사이드 패널에 버튼으로 표시되는 이름 붙은 셸 명령.
 *
 * @param id      안정적인 로컬 id
 * @param name    라벨. 예: "봇 재시작"
 * @param command 명령줄. 예: "docker restart bot-app"
 */
public record SavedCommand(String id, String name, String command) {

    public SavedCommand {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(command, "command");
    }
}
