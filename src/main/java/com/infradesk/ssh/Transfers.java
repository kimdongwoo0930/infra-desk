package com.infradesk.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/** 진행률을 알리며 스트림을 복사한다. SFTP 구현들이 함께 쓴다. */
final class Transfers {

    private Transfers() {
    }

    static void copy(InputStream in, OutputStream out, LongConsumer progress) throws IOException {
        byte[] buf = new byte[64 * 1024];
        long total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
            total += n;
            progress.accept(total);
        }
    }
}
