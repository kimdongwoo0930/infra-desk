package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ssh.HostKeyPrompt;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * Docker containers on a server, over SSH only (see {@link DockerCommands}). Separate from
 * {@link com.infradesk.core.CloudProvider}: it works the same for any server we can SSH into.
 * Blocking; call off the EDT.
 */
public class ContainerService {

    private static final Logger LOG = Logger.getLogger(ContainerService.class.getName());
    private static final Duration LIST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration ACTION_TIMEOUT = Duration.ofSeconds(90);

    private final TerminalService terminal;

    public ContainerService(TerminalService terminal) {
        this.terminal = terminal;
    }

    public DockerCommands.Listing list(Server server, HostKeyPrompt prompt) {
        ExecResult r = terminal.run(server, DockerCommands.list(), prompt, LIST_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        return DockerCommands.parseList(r.output());
    }

    /** Starts, stops or restarts a container; throws with Docker's message on failure. */
    public void act(Server server, Container container, DockerCommands.Action action, HostKeyPrompt prompt) {
        LOG.info(() -> "docker " + action + " " + container.name() + " on " + server.name());
        ExecResult r = terminal.run(server, DockerCommands.action(action, container.id()), prompt, ACTION_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        if (r.exitCode() != 0 || r.output().contains("Error response from daemon")) {
            throw new IllegalStateException("Docker 오류: " + r.output().strip());
        }
    }

    /** Last {@code tail} lines of the container's log, with timestamps. */
    public String logs(Server server, Container container, int tail, HostKeyPrompt prompt) {
        ExecResult r = terminal.run(server, DockerCommands.logs(container.id(), tail), prompt, LIST_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        return r.output() + (r.truncated() ? "\n… (로그가 길어 뒷부분을 생략했어요)" : "");
    }
}
