package com.infradesk.storage;

import com.infradesk.ssh.SavedCommand;

import java.util.List;

/** 저장된 터미널 명령어. 표시 순서대로. */
public interface SavedCommandStore {

    List<SavedCommand> load();

    void save(List<SavedCommand> commands);
}
