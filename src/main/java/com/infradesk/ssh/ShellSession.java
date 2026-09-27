package com.infradesk.ssh;

import java.io.InputStream;
import java.io.OutputStream;

/** An interactive shell with a PTY. Implemented over SSH and by the demo fake shell. */
public interface ShellSession extends AutoCloseable {

    /** Bytes the remote shell writes (stdout and stderr merged). */
    InputStream output();

    /** Bytes sent to the remote shell's stdin. */
    OutputStream input();

    void resize(int columns, int rows);

    boolean isOpen();

    /** Blocks until the shell exits; returns the exit status or -1 if unknown. */
    int waitFor() throws InterruptedException;

    /** Human-readable address, e.g. "ubuntu@203.0.113.24:22". */
    String address();

    @Override
    void close();
}
