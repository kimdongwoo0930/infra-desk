package com.infradesk.storage;

import com.infradesk.ssh.SavedCommand;

import java.util.List;

/** Saved terminal commands, in display order. */
public interface SavedCommandStore {

    List<SavedCommand> load();

    void save(List<SavedCommand> commands);
}
