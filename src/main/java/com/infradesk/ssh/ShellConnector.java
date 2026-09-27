package com.infradesk.ssh;

/** Opens interactive shells. Blocking; never call on the EDT. */
public interface ShellConnector {

    ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows);
}
