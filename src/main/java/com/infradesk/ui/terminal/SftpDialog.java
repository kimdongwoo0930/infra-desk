package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.LocalFiles;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ui.Async;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

/**
 * 서버 하나의 SFTP 파일 관리. 왼쪽이 이 컴퓨터, 오른쪽이 서버다. 한쪽에서 파일을 고르고 버튼(또는
 * 더블클릭)으로 반대쪽의 현재 폴더로 보낸다. 모달이 아니다.
 */
public final class SftpDialog extends JDialog {

    private final TerminalService service;
    private final Server server;
    private final FilePane localPane = new FilePane("내 컴퓨터", "내 컴퓨터 경로");
    private final FilePane remotePane;
    private final JLabel status = new JLabel(" ");
    private final JButton upload = Buttons.secondary("서버로 업로드", "upload");
    private final JButton download = Buttons.secondary("내 컴퓨터로 다운로드", "download");
    private final JButton delete = Buttons.danger("삭제", "trash");

    private RemoteFiles files;
    private boolean busy;

    public SftpDialog(Window owner, TerminalService service, Server server) {
        super(owner, "SFTP · " + server.name(), ModalityType.MODELESS);
        this.service = service;
        this.server = server;
        this.remotePane = new FilePane("서버 · " + server.name(), "원격 경로");

        upload.addActionListener(e -> upload());
        download.addActionListener(e -> download());
        delete.addActionListener(e -> delete());
        localPane.addAction(upload);
        remotePane.addAction(download);
        remotePane.addAction(delete);
        localPane.onActivate(f -> upload());
        remotePane.onActivate(f -> download());
        localPane.onSelectionChange(this::updateButtons);
        remotePane.onSelectionChange(this::updateButtons);

        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        status.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        localPane.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 6));
        remotePane.setBorder(BorderFactory.createEmptyBorder(12, 6, 8, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPane, remotePane);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        split.setOpaque(false);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(split, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1120, 600);
        setMinimumSize(new java.awt.Dimension(800, 420));
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                RemoteFiles f = files;
                files = null;
                if (f != null) {
                    Thread.ofVirtual().start(f::close);
                }
            }
        });
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        updateButtons();
        connect();
    }

    private void connect() {
        LocalFiles local = service.localFiles();
        localPane.attach(FilePane.local(local), local.home());

        setBusy(true, "SFTP 연결 중…");
        Async.run(() -> {
            RemoteFiles f = service.openFiles(server, new HostKeyDialog(this));
            String home = f.home();
            return new Object[] {f, home, f.list(home)};
        }, result -> {
            files = (RemoteFiles) result[0];
            @SuppressWarnings("unchecked")
            List<RemoteFile> list = (List<RemoteFile>) result[2];
            remotePane.attach(FilePane.remote(files), (String) result[1], list);
            setBusy(false, " ");
        }, err -> {
            setBusy(false, null);
            showError(Async.message(err));
        });
    }

    /** 이 컴퓨터에서 고른 파일을 서버의 현재 폴더로 올린다. */
    private void upload() {
        RemoteFile f = localPane.selected();
        if (files == null || busy || !localPane.ready() || !remotePane.ready() || f == null || f.directory()) {
            return;
        }
        String remoteDir = remotePane.cwd();
        String remote = RemoteFiles.join(remoteDir, f.name());
        Path local = Path.of(f.path());
        run("확인 중…", () -> files.exists(remote), exists -> {
            if (exists && !confirmOverwrite("업로드", "서버의 '" + f.name() + "'이(가) 이미 있어요. 덮어쓸까요?")) {
                return;
            }
            long total = Math.max(1, f.size());
            run(f.name() + " 업로드 중…", () -> {
                files.upload(local, remote, done -> progress("업로드", f.name(), done, total));
                return null;
            }, ignored -> {
                remotePane.reload();
                status.setText(f.name() + " 업로드 완료 → " + remoteDir);
            });
        });
    }

    /** 서버에서 고른 파일을 이 컴퓨터의 현재 폴더로 받는다. */
    private void download() {
        RemoteFile f = remotePane.selected();
        if (files == null || busy || !localPane.ready() || !remotePane.ready() || f == null || f.directory()) {
            return;
        }
        String localDir = localPane.cwd();
        Path local = Path.of(localPane.source().join(localDir, RemoteFiles.safeLocalName(f.name())));
        if (Files.exists(local) && !confirmOverwrite("다운로드", "내 컴퓨터의 '" + local.getFileName() + "'이(가) 이미 있어요. 덮어쓸까요?")) {
            return;
        }
        long total = Math.max(1, f.size());
        run(f.name() + " 다운로드 중…", () -> {
            files.download(f.path(), local, done -> progress("다운로드", f.name(), done, total));
            return null;
        }, ignored -> {
            localPane.reload();
            status.setText(f.name() + " 다운로드 완료 → " + localDir);
        });
    }

    private void delete() {
        RemoteFile f = remotePane.selected();
        if (files == null || busy || !remotePane.ready() || f == null) {
            return;
        }
        String what = f.directory() ? "폴더(비어 있어야 해요)" : "파일";
        Object[] options = {"삭제", "취소"};
        if (JOptionPane.showOptionDialog(this, "서버의 '" + f.path() + "' " + what + "을 삭제할까요?\n되돌릴 수 없어요.",
                "삭제", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) != 0) {
            return;
        }
        run("삭제 중…", () -> {
            files.delete(f);
            return null;
        }, ignored -> {
            remotePane.reload();
            status.setText(f.name() + " 삭제함");
        });
    }

    private boolean confirmOverwrite(String title, String message) {
        return JOptionPane.showConfirmDialog(this, message, title, JOptionPane.OK_CANCEL_OPTION,
                JOptionPane.WARNING_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void progress(String verb, String name, long done, long total) {
        int percent = (int) Math.min(100, done * 100 / total);
        SwingUtilities.invokeLater(() -> status.setText(name + " " + verb + " 중… " + percent + "%"));
    }

    /** 전송·삭제처럼 양쪽 패널을 잠그고 EDT 밖에서 실행한다. 끝나면 잠금을 풀고 결과를 처리한다. */
    private <T> void run(String message, Callable<T> task, Consumer<T> onSuccess) {
        setBusy(true, message);
        Async.run(task, result -> {
            setBusy(false, null);
            onSuccess.accept(result);
        }, err -> {
            setBusy(false, null);
            showError(Async.message(err));
        });
    }

    private void setBusy(boolean busy, String message) {
        this.busy = busy;
        if (message != null) {
            status.setText(message);
            status.setForeground(Theme.TEXT_MUTED);
        }
        localPane.setLocked(busy);
        remotePane.setLocked(busy);
        updateButtons();
    }

    private void showError(String message) {
        status.setText(IpPrivacy.mask(message));
        status.setForeground(Theme.DANGER_TEXT);
    }

    private void updateButtons() {
        boolean ready = files != null && !busy && localPane.ready() && remotePane.ready();
        RemoteFile local = localPane.selected();
        RemoteFile remote = remotePane.selected();
        upload.setEnabled(ready && local != null && !local.directory());
        download.setEnabled(ready && remote != null && !remote.directory());
        delete.setEnabled(ready && remote != null);
    }
}
