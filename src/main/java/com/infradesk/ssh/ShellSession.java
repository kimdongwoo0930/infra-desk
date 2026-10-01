package com.infradesk.ssh;

import java.io.InputStream;
import java.io.OutputStream;

/** PTY가 있는 대화형 셸. SSH 위의 구현과 데모용 가짜 셸이 있다. */
public interface ShellSession extends AutoCloseable {

    /** 원격 셸이 쓰는 바이트(stdout과 stderr를 합침). */
    InputStream output();

    /** 원격 셸의 stdin으로 보내는 바이트. */
    OutputStream input();

    void resize(int columns, int rows);

    boolean isOpen();

    /** 셸이 종료될 때까지 블로킹한다. 종료 코드를 돌려주며 알 수 없으면 -1. */
    int waitFor() throws InterruptedException;

    /** 사람이 읽는 주소. 예: "ubuntu@203.0.113.24:22". */
    String address();

    @Override
    void close();
}
