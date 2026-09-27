package com.infradesk.ssh;

/** Opens interactive shells. Blocking; never call on the EDT. */
public interface ShellConnector {

    ShellSession open(SshTarget target, HostKeyPrompt prompt, int columns, int rows);

    /** Runs a command without a PTY; the session's output is the command's stdout and stderr. */
    ShellSession exec(SshTarget target, HostKeyPrompt prompt, String command);

    /** Opens an SFTP session. */
    RemoteFiles sftp(SshTarget target, HostKeyPrompt prompt);
}
