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
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
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

    /** 이보다 큰 파일을 편집하려 하면 한 번 확인한다. */
    private static final long EDIT_WARN_BYTES = 10L * 1024 * 1024;
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
    private final JButton edit = Buttons.secondary("편집", "file");
    private final Set<Long> reloaded = new HashSet<>();
    private final List<EditSession> edits = new ArrayList<>();
    private final javax.swing.Timer editTimer = new javax.swing.Timer(1000, e -> checkEdits());
    private Path editRoot;
    private int editSeq;

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
        edit.addActionListener(e -> edit());
        remotePane.addAction(download);
        remotePane.addAction(edit);
        remotePane.addAction(newRemote);
        remotePane.addAction(deleteRemote);
        localPane.onActivate(f -> upload());
        remotePane.onActivate(f -> download());
        localPane.onSelectionChange(this::updateButtons);
        remotePane.onSelectionChange(this::updateButtons);
        installDropTargets();
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
                long unsaved = edits.stream().filter(s -> s.busy || s.watch.dirty()).count();
                if ((active > 0 || unsaved > 0) && JOptionPane.showConfirmDialog(SftpDialog.this,
                        (active > 0 ? "진행 중인 전송이 " + active + "개 있어요. 창을 닫으면 중단돼요.\n" : "")
                                + (unsaved > 0 ? "아직 서버에 올리지 못한 편집 내용이 " + unsaved + "개 있어요.\n" : "")
                                + "닫을까요?", "SFTP",
                        JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                    return;
                }
                dispose();
            }

            @Override
            public void windowClosed(java.awt.event.WindowEvent e) {
                RemoteFiles f = files;
                files = null;
                editTimer.stop();
                Path tempEdits = editRoot;
                Thread.ofVirtual().start(() -> {
                    queue.close();
                    if (f != null) {
                        f.close();
                    }
                    deleteTree(tempEdits);
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
        uploadItems(localPane.selection(), remotePane.cwd(), true);
    }

    /** 서버에서 고른 파일·폴더를 이 컴퓨터의 현재 폴더로 받는다. */
    private void download() {
        downloadItems(remotePane.selection(), localPane.cwd(), true);
    }

    /**
     * @param inCurrentFolder 대상이 서버 패널의 현재 폴더인지. 그렇다면 같은 이름이 있는지 미리 묻고,
     *                        아니면(폴더 행에 끌어다 놓은 경우) 덮어쓰지 않고 겹치는 파일은 건너뛴다
     */
    private void uploadItems(List<RemoteFile> items, String remoteDir, boolean inCurrentFolder) {
        if (!canTransfer() || items.isEmpty() || remoteDir == null) {
            return;
        }
        List<RemoteFile> conflicts = List.of();
        Decision decision = Decision.SKIP_NONE;
        if (inCurrentFolder) {
            Set<String> existing = remotePane.names();
            conflicts = items.stream().filter(f -> existing.contains(f.name())).toList();
            decision = conflicts.isEmpty() ? Decision.SKIP_NONE : askConflict(conflicts, "서버");
        }
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

    private void downloadItems(List<RemoteFile> items, String localDirPath, boolean inCurrentFolder) {
        if (!canTransfer() || items.isEmpty() || localDirPath == null) {
            return;
        }
        Path localDir = Path.of(localDirPath);
        List<RemoteFile> conflicts = List.of();
        Decision decision = Decision.SKIP_NONE;
        if (inCurrentFolder) {
            Set<String> existing = localPane.names();
            conflicts = items.stream().filter(f -> existing.contains(RemoteFiles.safeLocalName(f.name()))).toList();
            decision = conflicts.isEmpty() ? Decision.SKIP_NONE : askConflict(conflicts, "내 컴퓨터");
        }
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

    // ---- 끌어서 놓기 ----

    private void installDropTargets() {
        remotePane.setDropHandler(new FilePane.DropHandler() {
            @Override
            public boolean accepts(javax.swing.TransferHandler.TransferSupport support) {
                return support.isDataFlavorSupported(FilePane.PAYLOAD)
                        || support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }

            @Override
            public boolean drop(Transferable data, RemoteFile folder) {
                List<RemoteFile> items = droppedItems(data);
                if (items.isEmpty()) {
                    return false;
                }
                uploadItems(items, folder != null ? folder.path() : remotePane.cwd(), folder == null);
                return true;
            }
        });
        localPane.setDropHandler(new FilePane.DropHandler() {
            @Override
            public boolean accepts(javax.swing.TransferHandler.TransferSupport support) {
                return support.isDataFlavorSupported(FilePane.PAYLOAD);
            }

            @Override
            public boolean drop(Transferable data, RemoteFile folder) {
                List<RemoteFile> items = droppedItems(data);
                if (items.isEmpty()) {
                    return false;
                }
                downloadItems(items, folder != null ? folder.path() : localPane.cwd(), folder == null);
                return true;
            }
        });
    }

    /** 끌어다 놓은 것에서 항목을 꺼낸다. 다른 패널의 선택이거나, Finder·탐색기에서 끌어온 파일. */
    private static List<RemoteFile> droppedItems(Transferable data) {
        try {
            if (data.isDataFlavorSupported(FilePane.PAYLOAD)) {
                return ((FilePane.Payload) data.getTransferData(FilePane.PAYLOAD)).items();
            }
            if (data.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                @SuppressWarnings("unchecked")
                List<java.io.File> dropped = (List<java.io.File>) data.getTransferData(DataFlavor.javaFileListFlavor);
                List<RemoteFile> items = new ArrayList<>();
                for (java.io.File f : dropped) {
                    items.add(new RemoteFile(f.getName(), f.getAbsolutePath(), f.isDirectory(), f.length(),
                            Instant.ofEpochMilli(f.lastModified())));
                }
                return items;
            }
        } catch (UnsupportedFlavorException | IOException e) {
            // 읽을 수 없는 드롭이다. 받지 않는다.
        }
        return List.of();
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
        for (EditSession s : edits) {
            if (s.job == job && job.state().finished()) {
                s.job = null;
                if (job.state() == TransferQueue.State.DONE) {
                    s.needsSnapshot = true;
                    status.setForeground(Theme.TEXT_MUTED);
                    status.setText("'" + s.remote.name() + "' 저장한 내용을 서버에 올렸어요");
                } else {
                    s.busy = false;
                    showError("'" + s.remote.name() + "' 편집한 내용을 서버에 올리지 못했어요: " + job.message()
                            + " · 다시 저장하면 다시 올려요");
                }
            }
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

    private void newFile(FilePane pane) {
        if (!pane.ready()) {
            return;
        }
        String name = askName("새 파일 이름", "새 파일", "", pane, pane == localPane, null);
        if (name == null) {
            return;
        }
        FilePane.Source src = pane.source();
        String path = src.join(pane.cwd(), name);
        pane.run("파일을 만드는 중…", () -> {
            src.createFile(path);
            return null;
        }, done -> {
            pane.reload();
            status.setText("'" + name + "' 파일을 만들었어요");
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

    // ---- 서버 파일 편집 ----

    /** 편집 중인 서버 파일 하나. 임시 폴더의 복사본을 편집기가 저장하면 서버에 다시 올린다. */
    private static final class EditSession {
        final RemoteFile remote;
        final String remoteDir;
        final Path local;
        final ChangeWatch watch;
        /** 내가 받거나 마지막으로 올린 뒤의 서버 파일 상태. 서버에서 그 사이 바뀌었는지 비교한다. */
        RemoteFile snapshot;
        TransferQueue.Job job;
        boolean busy;
        boolean needsSnapshot;

        EditSession(RemoteFile remote, String remoteDir, Path local, RemoteFile snapshot) throws IOException {
            this.remote = remote;
            this.remoteDir = remoteDir;
            this.local = local;
            this.watch = new ChangeWatch(local);
            this.snapshot = snapshot;
        }
    }

    /** 서버 파일을 임시 폴더로 받아 내 편집기로 연다. 편집기에서 저장하면 자동으로 서버에 올린다. */
    private void edit() {
        RemoteFile f = remotePane.single();
        if (f == null || f.directory() || files == null || !remotePane.ready()) {
            return;
        }
        for (EditSession s : edits) {
            if (s.remote.path().equals(f.path())) {
                openEditor(s);
                return;
            }
        }
        if (!RemoteFiles.safeLocalName(f.name()).equals(f.name())) {
            showError("이 이름의 파일은 편집할 수 없어요: " + f.name());
            return;
        }
        if (f.size() > EDIT_WARN_BYTES && JOptionPane.showConfirmDialog(this,
                "'" + f.name() + "'은(는) " + FilePane.size(f.size()) + "로 커요. 그래도 받아서 편집할까요?", "편집",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        Path root;
        try {
            if (editRoot == null) {
                editRoot = java.nio.file.Files.createTempDirectory("infradesk-edit-");
            }
            root = editRoot;
        } catch (IOException e) {
            showError("임시 폴더를 만들지 못했어요: " + e.getMessage());
            return;
        }
        Path local = root.resolve(String.valueOf(++editSeq)).resolve(f.name());
        String remoteDir = remotePane.cwd();
        remotePane.run("편집할 파일을 받는 중…", () -> {
            java.nio.file.Files.createDirectories(local.getParent());
            files.download(f.path(), local, done -> { });
            return files.stat(f.path());
        }, snapshot -> {
            try {
                EditSession session = new EditSession(f, remoteDir, local, snapshot);
                edits.add(session);
                editTimer.start();
                openEditor(session);
            } catch (IOException e) {
                showError("받은 파일을 읽지 못했어요: " + e.getMessage());
            }
        }, () -> { });
    }

    private void openEditor(EditSession s) {
        try {
            ExternalEditor.open(s.local);
            status.setForeground(Theme.TEXT_MUTED);
            status.setText("'" + s.remote.name() + "'을(를) 편집기로 열었어요. 저장하면 서버에 자동으로 올려요");
        } catch (IOException e) {
            showError("편집기를 열지 못했어요. 임시 파일: " + s.local);
        }
    }

    /** 1초마다: 저장된 편집 내용이 있으면 서버에 올린다. */
    private void checkEdits() {
        for (EditSession s : edits) {
            if (s.needsSnapshot) {
                refreshSnapshot(s);
            } else if (!s.busy && s.watch.poll()) {
                uploadEdit(s);
            }
        }
    }

    private void uploadEdit(EditSession s) {
        if (files == null || !remotePane.ready()) {
            return;
        }
        s.busy = true;
        remotePane.run("서버 파일을 확인하는 중…", () -> files.stat(s.remote.path()), current -> {
            boolean serverChanged = current.size() != s.snapshot.size()
                    || !java.util.Objects.equals(current.modified(), s.snapshot.modified());
            if (serverChanged && JOptionPane.showConfirmDialog(this,
                    "'" + s.remote.name() + "'이(가) 편집하는 사이 서버에서 바뀌었어요.\n내 수정으로 덮어쓸까요?", "편집",
                    JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                s.busy = false;
                s.watch.acknowledge();
                showError("덮어쓰지 않았어요. 편집한 내용은 임시 파일에 있어요: " + s.local);
                return;
            }
            // 올리기 시작하는 시점의 파일을 기준으로 삼아서, 올리는 동안 또 저장하면 다시 올린다.
            s.watch.acknowledge();
            s.job = queue.upload(s.local, s.remoteDir, true);
            status.setForeground(Theme.TEXT_MUTED);
            status.setText("'" + s.remote.name() + "' 저장한 내용을 서버에 올려요");
        }, () -> {
            s.busy = false;
            s.watch.acknowledge();
        });
    }

    private void refreshSnapshot(EditSession s) {
        if (files == null || !remotePane.ready()) {
            return;
        }
        remotePane.run("서버 파일을 확인하는 중…", () -> files.stat(s.remote.path()), current -> {
            s.snapshot = current;
            s.needsSnapshot = false;
            s.busy = false;
        }, () -> {
            s.needsSnapshot = false;
            s.busy = false;
        });
    }

    private static void deleteTree(Path root) {
        if (root == null) {
            return;
        }
        try (var walk = java.nio.file.Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        } catch (IOException ignored) {
            // 임시 폴더라 못 지워도 괜찮다.
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
            addItem(menu, "편집", pane.ready() && selection.size() == 1 && !selection.get(0).directory(), this::edit);
        } else {
            addItem(menu, "서버로 업로드", canTransfer() && any, this::upload);
        }
        menu.addSeparator();
        addItem(menu, "새 폴더…", pane.ready(), () -> newFolder(pane));
        addItem(menu, "새 파일…", pane.ready(), () -> newFile(pane));
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
        edit.setEnabled(files != null && remotePane.ready() && remote.size() == 1 && !remote.get(0).directory());
        newLocal.setEnabled(localPane.ready());
        deleteLocal.setEnabled(localPane.ready() && !local.isEmpty());
        newRemote.setEnabled(remotePane.ready());
        deleteRemote.setEnabled(remotePane.ready() && !remote.isEmpty());
    }

}
