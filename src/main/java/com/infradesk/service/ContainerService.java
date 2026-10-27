package com.infradesk.service;

import com.infradesk.core.Server;
import com.infradesk.ssh.Container;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ssh.HostKeyPrompt;
import com.infradesk.ssh.Purpose;

import java.time.Duration;
import java.util.logging.Logger;

/**
 * 서버의 Docker 컨테이너. SSH로만 다룬다({@link DockerCommands} 참고). SSH로 들어갈 수 있는
 * 서버라면 똑같이 동작하므로 {@link com.infradesk.core.CloudProvider}와 분리되어 있다.
 * 블로킹이므로 EDT 밖에서 호출한다.
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
        ExecResult r = terminal.run(Purpose.QUERY, server, DockerCommands.list(), prompt, LIST_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        return DockerCommands.parseList(r.output());
    }

    /** {@link #list}에서 CPU·메모리 사용량을 뺀 목록. 여러 서버를 한꺼번에 읽는 개요가 쓴다(서버에 주는 부담이 훨씬 작다). */
    public DockerCommands.Listing listPlain(Server server, HostKeyPrompt prompt) {
        ExecResult r = terminal.run(Purpose.QUERY, server, DockerCommands.listPlain(), prompt, LIST_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        return DockerCommands.parseList(r.output());
    }

    /** 컨테이너를 시작, 정지, 재시작한다. 실패하면 Docker의 메시지와 함께 예외를 던진다. */
    public void act(Server server, Container container, DockerCommands.Action action, HostKeyPrompt prompt) {
        LOG.info(() -> "docker " + action + " " + container.name() + " on " + server.name());
        ExecResult r = terminal.run(Purpose.COMMAND, server, DockerCommands.action(action, container.id()), prompt, ACTION_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        if (r.exitCode() != 0 || r.output().contains("Error response from daemon")) {
            throw new IllegalStateException("Docker 오류: " + r.output().strip());
        }
    }

    /** 컨테이너 로그의 마지막 {@code tail}줄(타임스탬프 포함). */
    public String logs(Server server, Container container, int tail, HostKeyPrompt prompt) {
        ExecResult r = terminal.run(Purpose.QUERY, server, DockerCommands.logs(container.id(), tail), prompt, LIST_TIMEOUT);
        if (r.error() != null) {
            throw new IllegalStateException(r.error());
        }
        return r.output() + (r.truncated() ? "\n… (로그가 길어 뒷부분을 생략했어요)" : "");
    }
}
