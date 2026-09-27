package com.infradesk.ssh;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.LongConsumer;

/** Stream copy with progress, shared by SFTP implementations. */
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
