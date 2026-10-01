package com.infradesk.storage;

import com.infradesk.ssh.SavedCommand;

import java.util.List;

/** 데모 모드와 테스트용 비영속 저장소. */
public class InMemorySavedCommandStore implements SavedCommandStore {

    private volatile List<SavedCommand> commands;

    public InMemorySavedCommandStore(List<SavedCommand> initial) {
        this.commands = List.copyOf(initial);
    }

    @Override
    public List<SavedCommand> load() {
        return commands;
    }

    @Override
    public void save(List<SavedCommand> commands) {
        this.commands = List.copyOf(commands);
    }
}
