package com.infradesk.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/** 진행률을 알리며 스트림을 복사한다. SFTP 구현들이 함께 쓴다. */
public final class Transfers {

    private Transfers() {
    }

    public static void copy(InputStream in, OutputStream out, LongConsumer progress) throws IOException {
        copy(in, out, 0, progress);
    }

    /**
     * @param startOffset 이어받기로 이미 옮겨진 바이트 수. 진행률은 이 값을 포함한 누적 바이트로 알린다.
     *                    {@code progress}가 예외를 던지면 복사가 중단된다(전송 취소에 쓴다)
     */
    public static void copy(InputStream in, OutputStream out, long startOffset, LongConsumer progress) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = startOffset;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            progress.accept(total);
        }
    }
}
