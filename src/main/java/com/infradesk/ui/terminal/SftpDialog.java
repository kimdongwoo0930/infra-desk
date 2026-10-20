package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.LocalFiles;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ssh.TransferQueue;
import com.infradesk.ui.Async;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.Window;
import java.io.IOException;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JSplitPane;
import javax.swing.SwingUtilities;

/**
 * 서버 하나의 SFTP 파일 관리. 왼쪽이 이 컴퓨터, 오른쪽이 서버다. 한쪽에서 파일이나 폴더를 여러 개
 * 골라 버튼(또는 더블클릭)으로 반대쪽의 현재 폴더로 보낸다. 전송은 아래 대기열에서 차례로 실행되고
 * 취소하거나 이어받을 수 있다. 새 폴더, 이름 바꾸기, 삭제, 권한 변경은 우클릭 메뉴와 단축키(F2,
 * Delete)로도 쓴다. 모달이 아니다.
 */
public final class SftpDialog extends JDialog {

    /** 삭제 전에 항목 수를 셀 때 이 수에서 멈춘다. */
    private static final int COUNT_CAP = 5000;
    /** 이 폴더를 지우면 서버가 망가질 수 있어서 따로 경고한다. */
    private static final Set<String> SYSTEM_DIRS = Set.of("/etc", "/usr", "/var", "/bin", "/sbin", "/lib", "/lib64",
            "/boot", "/dev", "/proc", "/sys", "/root", "/home", "/opt", "/srv", "/run", "/mnt");

    private final TerminalService service;
    private final Server server;
    private final FilePane localPane = new FilePane("내 컴퓨터", "내 컴퓨터 경로");
    private final FilePane remotePane;
    private final TransferQueue queue;
    private final TransferPanel transfers;
    private final JPanel south = new JPanel(new BorderLayout());
    private final JLabel status = new JLabel(" ");
    private final JButton upload = Buttons.secondary("서버로 업로드", "upload");
    private final JButton newLocal = Buttons.secondary("새 폴더", "plus");
    private final JButton deleteLocal = Buttons.danger("삭제", "trash");
    private final JButton download = Buttons.secondary("내 컴퓨터로 다운로드", "download");
    private final JButton newRemote = Buttons.secondary("새 폴더", "plus");
    private final JButton deleteRemote = Buttons.danger("삭제", "trash");
    private final Set<Long> reloaded = new HashSet<>();

    /** 탐색·파일 작업용 연결. 전송은 {@link #queue}가 따로 연결을 맺는다. */
    private RemoteFiles files;

    public SftpDialog(Window owner, TerminalService service, Server server) {
        super(owner, "SFTP · " + server.name(), ModalityType.MODELESS);
        this.service = service;
        this.server = server;
        this.remotePane = new FilePane("서버 · " + server.name(), "원격 경로");
        this.queue = new TransferQueue(() -> service.openFiles(server, new HostKeyDialog(this)),
                job -> SwingUtilities.invokeLater(() -> onJobChanged(job)));
        this.transfers = new TransferPanel(queue);

        upload.addActionListener(e -> upload());
        download.addActionListener(e -> download());
        newLocal.addActionListener(e -> newFolder(localPane));
        newRemote.addActionListener(e -> newFolder(remotePane));
        deleteLocal.addActionListener(e -> delete(localPane));
        deleteRemote.addActionListener(e -> delete(remotePane));
        localPane.addAction(upload);
        localPane.addAction(newLocal);
        localPane.addAction(deleteLocal);
        remotePane.addAction(download);
        remotePane.addAction(newRemote);
        remotePane.addAction(deleteRemote);
        localPane.onActivate(f -> upload());
        remotePane.onActivate(f -> download());
        localPane.onSelectionChange(this::updateButtons);
        remotePane.onSelectionChange(this::updateButtons);
        localPane.setMenuProvider(() -> menu(false));
        remotePane.setMenuProvider(() -> menu(true));
        for (FilePane pane : List.of(localPane, remotePane)) {
            pane.bind("F2", () -> rename(pane));
            pane.bind("DELETE", () -> delete(pane));
            pane.bind("BACK_SPACE", () -> delete(pane));
        }

        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        status.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        localPane.setBorder(BorderFactory.createEmptyBorder(12, 12, 8, 6));
        remotePane.setBorder(BorderFactory.createEmptyBorder(12, 6, 8, 12));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPane, remotePane);
        split.setResizeWeight(0.5);
        split.setBorder(null);
        split.setOpaque(false);

        transfers.setVisible(false);
        transfers.setPreferredSize(new Dimension(0, 190));
        south.setOpaque(false);
        south.add(transfers, BorderLayout.CENTER);
        south.add(status, BorderLayout.SOUTH);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(split, BorderLayout.CENTER);
        root.add(south, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(1180, 680);
        setMinimumSize(new Dimension(860, 480));
        setLocationRelativeTo(owner);
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowClosing(java.awt.event.WindowEvent e) {
                int active = transfers.activeCount();
                if (active > 0 && JOptionPane.showConfirmDialog(SftpDialog.this,
                        "진행 중인 전송이 " + active + "개 있어요. 창을 닫으면 중단돼요.\n닫을까요?", "SFTP",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                    return;
                }
                dispose();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                RemoteFiles f = files;
                files = null;
                Thread.ofVirtual().start(() -> {
                    queue.close();
                    if (f != null) {
                        f.close();
                    }
                });
            }
        });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
        updateButtons();
        connect();
    }

    private void connect() {
        LocalFiles local = service.localFiles();
        localPane.attach(FilePane.local(local), local.home());

        status.setText("SFTP 연결 중…");
        Async.run(() -> {
            RemoteFiles f = service.openFiles(server, new HostKeyDialog(this));
            String home = f.home();
            return new Object[] {f, home, f.list(home)};
        }, result -> {
            files = (RemoteFiles) result[0];
            @SuppressWarnings("unchecked")
            List<RemoteFile> list = (List<RemoteFile>) result[2];
            remotePane.attach(FilePane.remote(files), (String) result[1], list);
            status.setText(" ");
            updateButtons();
        }, err -> showError(FilePane.friendly(err)));
    }

    // ---- 전송 ----

    /** 이 컴퓨터에서 고른 파일·폴더를 서버의 현재 폴더로 올린다. */
    private void upload() {
        List<RemoteFile> items = localPane.selection();
        if (!canTransfer() || items.isEmpty()) {
            return;
        }
        String remoteDir = remotePane.cwd();
        Set<String> existing = remotePane.names();
        List<RemoteFile> conflicts = items.stream().filter(f -> existing.contains(f.name())).toList();
        Decision decision = conflicts.isEmpty() ? Decision.SKIP_NONE : askConflict(conflicts, "서버");
        if (decision == Decision.CANCEL) {
            return;
        }
        boolean overwrite = decision == Decision.OVERWRITE;
        int queued = 0;
        for (RemoteFile f : items) {
            if (!overwrite && conflicts.contains(f)) {
                continue;
            }
            queue.upload(Path.of(f.path()), remoteDir, overwrite);
            queued++;
        }
        status.setForeground(Theme.TEXT_MUTED);
        status.setText(queued + "개 항목을 서버로 올려요");
    }

    /** 서버에서 고른 파일·폴더를 이 컴퓨터의 현재 폴더로 받는다. */
    private void download() {
        List<RemoteFile> items = remotePane.selection();
        if (!canTransfer() || items.isEmpty()) {
            return;
        }
        Path localDir = Path.of(localPane.cwd());
        Set<String> existing = localPane.names();
        List<RemoteFile> conflicts = items.stream()
                .filter(f -> existing.contains(RemoteFiles.safeLocalName(f.name()))).toList();
        Decision decision = conflicts.isEmpty() ? Decision.SKIP_NONE : askConflict(conflicts, "내 컴퓨터");
        if (decision == Decision.CANCEL) {
            return;
        }
        boolean overwrite = decision == Decision.OVERWRITE;
        int queued = 0;
        for (RemoteFile f : items) {
            if (!overwrite && conflicts.contains(f)) {
                continue;
            }
            queue.download(f, localDir, overwrite);
            queued++;
        }
        status.setForeground(Theme.TEXT_MUTED);
        status.setText(queued + "개 항목을 내 컴퓨터로 받아요");
    }

    private boolean canTransfer() {
        return files != null && localPane.cwd() != null && remotePane.cwd() != null;
    }

    private enum Decision {
        OVERWRITE, SKIP, CANCEL,
        /** 겹치는 항목이 없어서 묻지 않았다. */
        SKIP_NONE
    }

    private Decision askConflict(List<RemoteFile> conflicts, String where) {
        String names = conflicts.stream().limit(5).map(f -> "· " + f.name()).reduce((a, b) -> a + "\n" + b).orElse("");
        String more = conflicts.size() > 5 ? "\n… 외 " + (conflicts.size() - 5) + "개" : "";
        Object[] options = {"덮어쓰기", "겹치는 항목은 건너뛰기", "취소"};
        int choice = JOptionPane.showOptionDialog(this,
                where + "에 같은 이름이 이미 있어요.\n" + names + more + "\n\n폴더는 안의 같은 이름 파일만 덮어써요.",
                "전송", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[2]);
        return switch (choice) {
            case 0 -> Decision.OVERWRITE;
            case 1 -> Decision.SKIP;
            default -> Decision.CANCEL;
        };
    }

    private void onJobChanged(TransferQueue.Job job) {
        transfers.refresh();
        boolean show = !transfers.isEmpty();
        if (transfers.isVisible() != show) {
            transfers.setVisible(show);
            south.revalidate();
            south.repaint();
        }
        if (job.state() == TransferQueue.State.DONE && reloaded.add(job.id())) {
            if (job.direction() == TransferQueue.Direction.UPLOAD && job.targetDir().equals(remotePane.cwd())) {
                remotePane.reload();
            } else if (job.direction() == TransferQueue.Direction.DOWNLOAD && job.targetDir().equals(localPane.cwd())) {
                localPane.reload();
            }
        }
    }

    // ---- 새 폴더, 이름 바꾸기, 삭제, 권한 ----

    private void newFolder(FilePane pane) {
        if (!pane.ready()) {
            return;
        }
        boolean local = pane == localPane;
        String name = askName("새 폴더 이름", "새 폴더", "", pane, local, null);
        if (name == null) {
            return;
        }
        FilePane.Source src = pane.source();
        String path = src.join(pane.cwd(), name);
        pane.run("폴더를 만드는 중…", () -> {
            src.mkdir(path);
            return null;
        }, done -> {
            pane.reload();
            status.setText("'" + name + "' 폴더를 만들었어요");
        }, pane::reload);
    }

    private void rename(FilePane pane) {
        RemoteFile f = pane.single();
        if (f == null || !pane.ready()) {
            return;
        }
        String name = askName("새 이름", "이름 바꾸기", f.name(), pane, pane == localPane, f.name());
        if (name == null || name.equals(f.name())) {
            return;
        }
        FilePane.Source src = pane.source();
        String to = src.join(pane.cwd(), name);
        pane.run("이름을 바꾸는 중…", () -> {
            src.rename(f.path(), to);
            return null;
        }, done -> {
            pane.reload();
            status.setText("'" + f.name() + "' → '" + name + "'");
        }, pane::reload);
    }

    /**
     * 이름을 묻고 쓸 수 있는 이름인지 확인한다.
     *
     * @param current 이름 바꾸기일 때 지금 이름(같은 이름은 충돌로 보지 않는다). 아니면 null
     * @return 입력한 이름. 취소하면 null
     */
    private String askName(String message, String title, String initial, FilePane pane, boolean local, String current) {
        String value = initial;
        while (true) {
            Object input = JOptionPane.showInputDialog(this, message, title, JOptionPane.PLAIN_MESSAGE, null, null, value);
            if (input == null) {
                return null;
            }
            value = input.toString().strip();
            String problem = nameProblem(value, local);
            if (problem == null && !value.equals(current) && pane.names().contains(value)) {
                problem = "같은 이름이 이미 있어요.";
            }
            if (problem == null) {
                return value;
            }
            JOptionPane.showMessageDialog(this, problem, title, JOptionPane.WARNING_MESSAGE);
        }
    }

    /** 폴더나 파일 이름으로 쓸 수 없으면 이유, 괜찮으면 null. */
    static String nameProblem(String name, boolean local) {
        if (name.isEmpty()) {
            return "이름을 입력해 주세요.";
        }
        if (name.equals(".") || name.equals("..")) {
            return "'.'과 '..'은 이름으로 쓸 수 없어요.";
        }
        if (name.contains("/") || name.indexOf('\0') >= 0) {
            return "이름에 '/'를 쓸 수 없어요.";
        }
        if (local) {
            if (name.contains("\\")) {
                return "이름에 '\\'를 쓸 수 없어요.";
            }
            if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")
                    && name.matches(".*[:*?\"<>|].*")) {
                return "Windows에서는 이름에 : * ? \" < > | 를 쓸 수 없어요.";
            }
        }
        return null;
    }

    private void delete(FilePane pane) {
        List<RemoteFile> items = pane.selection();
        if (items.isEmpty() || !pane.ready()) {
            return;
        }
        FilePane.Source src = pane.source();
        boolean remote = pane == remotePane;
        pane.run("확인 중…", () -> count(src, items), counted -> {
            if (confirmDelete(pane, src, items, counted, remote)) {
                pane.run("삭제 중…", () -> {
                    boolean trashed = true;
                    for (RemoteFile f : items) {
                        trashed &= src.delete(f);
                    }
                    return trashed;
                }, trashed -> {
                    pane.reload();
                    status.setText(subject(items) + (trashed ? "을(를) 휴지통으로 보냈어요" : "을(를) 삭제했어요"));
                }, pane::reload);
            }
        });
    }

    private record Counted(int total, boolean capped, boolean hasFolders) {
    }

    private static Counted count(FilePane.Source src, List<RemoteFile> items) throws IOException {
        int[] n = {0};
        boolean[] folders = {false};
        for (RemoteFile f : items) {
            countTree(src, f, n, folders);
        }
        return new Counted(n[0], n[0] >= COUNT_CAP, folders[0]);
    }

    private static void countTree(FilePane.Source src, RemoteFile f, int[] n, boolean[] folders) throws IOException {
        n[0]++;
        if (!f.directory() || f.symlink()) {
            return;
        }
        folders[0] = true;
        for (RemoteFile child : src.list(f.path())) {
            if (n[0] >= COUNT_CAP) {
                return;
            }
            countTree(src, child, n, folders);
        }
    }

    private boolean confirmDelete(FilePane pane, FilePane.Source src, List<RemoteFile> items, Counted counted, boolean remote) {
        boolean recoverable = src.recoverableDelete();
        StringBuilder msg = new StringBuilder();
        msg.append(subject(items)).append(remote ? "을(를) 서버에서 삭제할까요?"
                : recoverable ? "을(를) 휴지통으로 보낼까요?" : "을(를) 삭제할까요?");
        msg.append("\n").append(IpPrivacy.mask(pane.cwd()));
        if (counted.hasFolders()) {
            msg.append("\n\n폴더 안의 항목까지 합쳐 ")
                    .append(counted.capped() ? String.format("%,d개 이상", COUNT_CAP) : counted.total() + "개")
                    .append("가 함께 삭제돼요.");
        }
        if (!recoverable) {
            msg.append("\n되돌릴 수 없어요.");
        }
        if (remote && items.stream().anyMatch(f -> SYSTEM_DIRS.contains(f.path()))) {
            msg.append("\n\n⚠ 시스템 폴더예요. 지우면 서버가 제대로 동작하지 않을 수 있어요.");
        }
        Object[] options = {"삭제", "취소"};
        return JOptionPane.showOptionDialog(this, msg.toString(), "삭제", JOptionPane.DEFAULT_OPTION,
                JOptionPane.WARNING_MESSAGE, null, options, options[1]) == 0;
    }

    private static String subject(List<RemoteFile> items) {
        return items.size() == 1 ? "'" + items.get(0).name() + "'" : items.size() + "개 항목";
    }

    private void chmod() {
        List<RemoteFile> items = remotePane.selection();
        if (items.isEmpty() || !remotePane.ready()) {
            return;
        }
        boolean hasFolder = items.stream().anyMatch(RemoteFile::directory);
        int initial = items.get(0).permissions() >= 0 ? items.get(0).permissions() : 0644;
        PermissionDialog.Result result = PermissionDialog.ask(this, subject(items), initial, hasFolder);
        if (result == null) {
            return;
        }
        FilePane.Source src = remotePane.source();
        remotePane.run("권한을 바꾸는 중…", () -> {
            for (RemoteFile f : items) {
                chmodTree(src, f, result.mode(), result.recursive());
            }
            return null;
        }, done -> {
            remotePane.reload();
            status.setText(subject(items) + "의 권한을 " + String.format("%03o", result.mode()) + "로 바꿨어요");
        }, remotePane::reload);
    }

    /** 링크는 건드리지 않는다(권한 변경이 링크가 가리키는 다른 파일에 적용되지 않도록). */
    private static void chmodTree(FilePane.Source src, RemoteFile f, int mode, boolean recursive) throws IOException {
        if (f.symlink()) {
            return;
        }
        src.chmod(f.path(), mode);
        if (recursive && f.directory()) {
            for (RemoteFile child : src.list(f.path())) {
                chmodTree(src, child, mode, true);
            }
        }
    }

    // ---- 우클릭 메뉴와 버튼 ----

    private JPopupMenu menu(boolean remote) {
        FilePane pane = remote ? remotePane : localPane;
        List<RemoteFile> selection = pane.selection();
        boolean any = !selection.isEmpty();
        JPopupMenu menu = new JPopupMenu();
        if (remote) {
            addItem(menu, "내 컴퓨터로 다운로드", canTransfer() && any, this::download);
        } else {
            addItem(menu, "서버로 업로드", canTransfer() && any, this::upload);
        }
        menu.addSeparator();
        addItem(menu, "새 폴더…", pane.ready(), () -> newFolder(pane));
        addItem(menu, "이름 바꾸기…   F2", pane.ready() && selection.size() == 1, () -> rename(pane));
        if (remote) {
            addItem(menu, "권한 변경…", pane.ready() && any, this::chmod);
        }
        addItem(menu, "삭제…", pane.ready() && any, () -> delete(pane));
        menu.addSeparator();
        addItem(menu, "새로고침", pane.ready(), pane::reload);
        return menu;
    }

    private static void addItem(JPopupMenu menu, String text, boolean enabled, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setEnabled(enabled);
        item.addActionListener(e -> action.run());
        menu.add(item);
    }

    private void showError(String message) {
        status.setText(IpPrivacy.mask(message));
        status.setForeground(Theme.DANGER_TEXT);
    }

    private void updateButtons() {
        List<RemoteFile> local = localPane.selection();
        List<RemoteFile> remote = remotePane.selection();
        boolean transferable = canTransfer();
        upload.setEnabled(transferable && !local.isEmpty());
        download.setEnabled(transferable && !remote.isEmpty());
        newLocal.setEnabled(localPane.ready());
        deleteLocal.setEnabled(localPane.ready() && !local.isEmpty());
        newRemote.setEnabled(remotePane.ready());
        deleteRemote.setEnabled(remotePane.ready() && !remote.isEmpty());
    }

}
