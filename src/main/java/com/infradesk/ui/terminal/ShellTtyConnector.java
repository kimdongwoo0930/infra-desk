package com.infradesk.ui.terminal;

import com.infradesk.ssh.ShellSession;
import com.jediterm.core.util.TermSize;
import com.jediterm.terminal.TtyConnector;

import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Adapts a {@link ShellSession} to JediTerm. */
final class ShellTtyConnector implements TtyConnector {

    private final ShellSession session;
    private final InputStreamReader reader;

    ShellTtyConnector(ShellSession session) {
        this.session = session;
        this.reader = new InputStreamReader(session.output(), StandardCharsets.UTF_8);
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        return reader.read(buf, offset, length);
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        session.input().write(bytes);
        session.input().flush();
    }

    @Override
    public void write(String string) throws IOException {
        write(string.getBytes(StandardCharsets.UTF_8));
    }

    @Override
    public boolean isConnected() {
        return session.isOpen();
    }

    @Override
    public void resize(TermSize size) {
        session.resize(size.getColumns(), size.getRows());
    }

    @Override
    public int waitFor() throws InterruptedException {
        return session.waitFor();
    }

    @Override
    public boolean ready() throws IOException {
        return reader.ready();
    }

    @Override
    public String getName() {
        return session.address();
    }

    @Override
    public void close() {
        session.close();
    }
}
