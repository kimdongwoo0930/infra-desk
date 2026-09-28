package com.infradesk.ui;

import com.infradesk.service.UpdateInstaller;
import com.infradesk.service.UpdateService;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Window;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.SwingUtilities;
import javax.swing.WindowConstants;

/**
 * Downloads, verifies and self-tests an update with a progress bar, then hands the prepared update
 * to {@code onReady} (which starts the swap and quits). Cancel stops the download; failures are
 * shown in place and leave the installed app untouched.
 */
final class UpdateProgressDialog extends JDialog {

    private final UpdateInstaller installer;
    private final UpdateService.Release release;
    private final Consumer<UpdateInstaller.Prepared> onReady;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final JLabel status = new JLabel("받는 중…");
    private final JProgressBar bar = new JProgressBar(0, 1000);
    private final JButton button = Buttons.ghost("취소");

    UpdateProgressDialog(Window owner, UpdateInstaller installer, UpdateService.Release release,
                         Consumer<UpdateInstaller.Prepared> onReady) {
        super(owner, "업데이트", ModalityType.APPLICATION_MODAL);
        this.installer = installer;
        this.release = release;
        this.onReady = onReady;
        setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);

        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(BorderFactory.createEmptyBorder(20, 22, 16, 22));
        JLabel title = new JLabel("새 베타 빌드 " + release.build() + " 설치");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 15f));
        body.add(title, BorderLayout.NORTH);
        JPanel center = new JPanel(new BorderLayout(0, 8));
        center.setOpaque(false);
        bar.setPreferredSize(new java.awt.Dimension(380, 6));
        center.add(bar, BorderLayout.NORTH);
        status.setForeground(Theme.TEXT_SECONDARY);
        center.add(status, BorderLayout.CENTER);
        body.add(center, BorderLayout.CENTER);
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        buttons.setOpaque(false);
        buttons.add(button);
        body.add(buttons, BorderLayout.SOUTH);
        setContentPane(body);

        button.addActionListener(e -> {
            if (cancelled.get() || !bar.isEnabled()) {
                dispose();
            } else {
                cancelled.set(true);
                button.setEnabled(false);
                status.setText("취소하는 중…");
            }
        });
        pack();
        setResizable(false);
        setLocationRelativeTo(owner);
    }

    /** Starts the work and shows the dialog (blocks until it closes, like any modal dialog). */
    void start() {
        Async.run(() -> installer.prepare(release, this::progress, cancelled::get), prepared -> {
            status.setText("설치하는 중이에요. 잠시 뒤 새 버전이 열려요.");
            bar.setIndeterminate(true);
            button.setEnabled(false);
            onReady.accept(prepared);
        }, err -> {
            if (err instanceof CancellationException) {
                dispose();
                return;
            }
            bar.setEnabled(false);
            bar.setValue(0);
            status.setText("<html>" + escape(Async.message(err)) + "<br>지금 쓰는 버전은 그대로예요.</html>");
            status.setForeground(Theme.DANGER_TEXT);
            button.setText("닫기");
            button.setEnabled(true);
            pack();
        });
        setVisible(true);
    }

    private void progress(long done, long total) {
        SwingUtilities.invokeLater(() -> {
            if (total > 0) {
                bar.setValue((int) (done * 1000 / total));
                status.setText(String.format("받는 중… %.1f / %.1f MB", done / 1e6, total / 1e6));
            } else {
                bar.setIndeterminate(true);
                status.setText(String.format("받는 중… %.1f MB", done / 1e6));
            }
            if (total > 0 && done >= total) {
                bar.setIndeterminate(true);
                status.setText("받은 파일을 확인하고 새 버전을 점검하는 중…");
            }
        });
    }

    /** Shows a progress state without downloading; for the UI snapshot tool. */
    void previewProgress(long done, long total) {
        bar.setValue((int) (done * 1000 / total));
        status.setText(String.format("받는 중… %.1f / %.1f MB", done / 1e6, total / 1e6));
    }

    private static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
