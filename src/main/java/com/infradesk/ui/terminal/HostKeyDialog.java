package com.infradesk.ui.terminal;

import com.infradesk.ssh.HostKeyPrompt;

import java.awt.Component;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;

/** Asks on the EDT whether to trust a new host key; callable from any thread. */
public final class HostKeyDialog implements HostKeyPrompt {

    private final Component parent;

    public HostKeyDialog(Component parent) {
        this.parent = parent;
    }

    @Override
    public boolean trustNewHost(String host, int port, String keyType, String fingerprint) {
        AtomicBoolean answer = new AtomicBoolean(false);
        Runnable ask = () -> {
            Object[] options = {"신뢰하고 연결", "취소"};
            int choice = JOptionPane.showOptionDialog(parent,
                    "처음 연결하는 서버예요. 호스트 키 지문이 서버의 것과 같은지 확인하세요.\n\n"
                            + host + ":" + port + "\n" + keyType + "\n" + fingerprint + "\n\n"
                            + "신뢰하면 이 키를 기억하고, 다음부터 키가 바뀌면 연결을 막아요.",
                    "호스트 키 확인", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]);
            answer.set(choice == 0);
        };
        if (SwingUtilities.isEventDispatchThread()) {
            ask.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(ask);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } catch (InvocationTargetException e) {
                return false;
            }
        }
        return answer.get();
    }
}
