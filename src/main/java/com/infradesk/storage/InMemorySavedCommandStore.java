package com.infradesk.storage;

import com.infradesk.ssh.SavedCommand;

import java.util.List;

/** Non-persistent store for demo mode and tests. */
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
