package com.infradesk.ui.files;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.LocalFiles;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.ReconnectingFiles;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ssh.TransferQueue;
import com.infradesk.ui.Async;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.terminal.HostKeyDialog;

import java.awt.BorderLayout;
import java.awt.Dimension;
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
 * Delete)로도 쓴다.
 *
 * <p>서버 화면의 "파일" 탭 본문이다(디자인 v2). 서버마다 하나씩 만들어 {@link FilesHost}가 들고 있다:
 * 탭을 옮기거나 다른 서버를 보고 돌아와도 목록, 전송, 편집 상태가 그대로 남고, 연결은 {@link #close()}를
 * 부를 때(앱을 끌 때, 서버를 지울 때)만 닫는다. 그래서 전송이 끝나기 전에 연결이 끊기는 일이 없다.
 */
public final class FilesTab extends JPanel {

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
    // 선택 작업 막대의 버튼(디자인 v2): 눌렀을 때 하는 일은 예전 창의 버튼과 같다. 새 폴더는 창 머리의 아이콘으로 옮겼다.
    private final JButton upload = Buttons.primary("서버로 올리기", "upload");
    private final JButton deleteLocal = Buttons.danger("삭제…", null);
    private final JButton download = Buttons.primary("내 컴퓨터로 받기", "download");
    private final JButton deleteRemote = Buttons.danger("삭제…", null);
    private final JButton permissions = Buttons.secondary("권한…", null);
    private final JButton edit = Buttons.secondary("편집", null);
    private final JButton editLocal = Buttons.secondary("편집", null);
    private final EditBanner banner = new EditBanner();
    private final JPanel bannerRow = new JPanel(new BorderLayout());
    private Runnable onTransfersChanged = () -> { };
    private final Set<Long> reloaded = new HashSet<>();
    private final Set<Long> ownerWarned = new HashSet<>();
    private final List<EditSession> edits = new ArrayList<>();
    private final javax.swing.Timer editTimer = new javax.swing.Timer(1000, e -> checkEdits());
    /** 이 시간 동안 안 쓴 탐색 연결은 풀에 돌려준다(목록은 화면에 그대로 남고, 다음에 쓸 때 다시 연결한다). */
    private static final java.time.Duration CONNECTION_IDLE = java.time.Duration.ofMinutes(3);
    private final javax.swing.Timer idleTimer = new javax.swing.Timer(60_000, e -> releaseIdleConnection());
    private Path editRoot;
    /** 화면에 이미 알린 작업. 처음 알리는 작업이 새 전송이다. */
    private final Set<Long> announced = new HashSet<>();
    /** 더블클릭·버튼으로 받은 파일이 저장되는 폴더(보통 Downloads). 끌어서 놓기는 놓은 곳에 저장된다. */
    private Path downloadDir;
    private int editSeq;

    /** 탐색·파일 작업용 연결. 전송은 {@link #queue}가 따로 연결을 맺는다. */
    private RemoteFiles files;

    public FilesTab(TerminalService service, Server server) {
        super(new BorderLayout());
        this.service = service;
        this.server = server;
        this.remotePane = new FilePane("서버 · " + server.name(), "원격 경로");
        this.queue = new TransferQueue(() -> service.openTransferFiles(server, new HostKeyDialog(this)),
                job -> SwingUtilities.invokeLater(() -> onJobChanged(job)));
        this.transfers = new TransferPanel(queue, this::updateTransfersVisible);

        upload.addActionListener(e -> upload());
        download.addActionListener(e -> download());
        localPane.onNewFolder(() -> newFolder(localPane));
        remotePane.onNewFolder(() -> newFolder(remotePane));
        permissions.addActionListener(e -> chmod());
        deleteLocal.addActionListener(e -> delete(localPane));
        deleteRemote.addActionListener(e -> delete(remotePane));
        editLocal.addActionListener(e -> editLocal());
        localPane.addAction(upload);
        localPane.addAction(editLocal);
        localPane.addAction(deleteLocal);
        edit.addActionListener(e -> edit());
        remotePane.addAction(download);
        remotePane.addAction(edit);
        remotePane.addAction(permissions);
        remotePane.addAction(deleteRemote);
        remotePane.enableHiddenToggle();
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
        JLabel hint = new JLabel("끌어다 놓아 주고받아요 · Finder·탐색기에서 서버 쪽으로 끌어와도 올라가요 · 끊긴 전송은 이어받아요");
        hint.setForeground(Theme.TEXT_MUTED);
        hint.putClientProperty(FlatClientProperties.STYLE, "font: -1");

        // 두 창은 카드 하나씩이고 사이를 16 띄운다. 가운데 줄을 끌어 폭을 나눌 수 있다.
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, localPane, remotePane);
        split.setResizeWeight(0.5);
        split.setDividerSize(16);
        split.setContinuousLayout(true);
        split.setBorder(null);
        split.setOpaque(false);
        split.putClientProperty(FlatClientProperties.STYLE, "gripColor: #00000000");   // 가운데 점 세 개 손잡이는 숨긴다
        // 처음 폭이 정해질 때 정확히 반반으로 나눈다(기본값은 왼쪽 창의 원래 폭이라 한쪽이 좁아진다). 그 뒤로는 사용자가 끈 대로 둔다.
        split.addComponentListener(new java.awt.event.ComponentAdapter() {
            private boolean positioned;

            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                if (!positioned && split.getWidth() > 0) {
                    positioned = true;
                    split.setDividerLocation(0.5);
                }
            }
        });

        // 아래 줄: 작업 결과·오류 한 줄, 그 아래 안내 문구. 닫아 둔 전송 목록을 다시 여는 버튼은 오른쪽.
        JPanel statusRow = new JPanel(new BorderLayout(12, 0));
        statusRow.setOpaque(false);
        statusRow.add(status, BorderLayout.CENTER);
        JPanel notes = new JPanel(new BorderLayout(0, 4));
        notes.setOpaque(false);
        notes.setBorder(BorderFactory.createEmptyBorder(10, 2, 0, 0));
        notes.add(statusRow, BorderLayout.NORTH);
        notes.add(hint, BorderLayout.SOUTH);

        JPanel content = new JPanel(new BorderLayout());
        content.setOpaque(false);
        content.setBorder(BorderFactory.createEmptyBorder(20, 28, 14, 28));
        // 편집 중인 서버 파일이 있을 때만 위쪽에 배너가 나타난다.
        banner.onOpen(this::openEditorFromBanner);
        banner.onEnd(this::endEdits);
        // 배너와 두 창 사이 간격은 배너 바깥에 둔다(배너 테두리 안에 넣으면 배너가 그만큼 높아진다).
        bannerRow.setOpaque(false);
        bannerRow.setBorder(BorderFactory.createEmptyBorder(0, 0, 16, 0));
        bannerRow.add(banner, BorderLayout.CENTER);
        bannerRow.setVisible(false);
        content.add(bannerRow, BorderLayout.NORTH);
        content.add(split, BorderLayout.CENTER);
        content.add(notes, BorderLayout.SOUTH);

        transfers.setVisible(false);
        south.setOpaque(false);
        south.add(transfers, BorderLayout.CENTER);

        setOpaque(false);
        add(content, BorderLayout.CENTER);
        add(south, BorderLayout.SOUTH);
        setMinimumSize(new Dimension(860, 480));
        updateButtons();
        connect();
    }

    /** 전송 목록이 바뀔 때마다 불린다(EDT). 상태 막대가 "전송 N개 진행 중"을 갱신하는 데 쓴다. */
    public void onTransfersChanged(Runnable listener) {
        this.onTransfersChanged = listener;
    }

    /**
     * 진행 중인 전송 전체의 진행 정도: 작업 수와, 크기를 아는 작업들의 받은 바이트·전체 바이트.
     * 크기를 아직 모르는 작업(목록을 읽는 중)은 바이트에 넣지 않는다.
     */
    public Progress transferProgress() {
        int active = 0;
        long done = 0;
        long total = 0;
        for (TransferQueue.Job j : queue.jobs()) {
            if (!j.state().finished()) {
                active++;
                if (j.totalBytes() >= 0) {
                    done += j.doneBytes();
                    total += j.totalBytes();
                }
            }
        }
        return new Progress(active, done, total);
    }

    /** 진행 중인 작업 수와 그 진행 정도. */
    public record Progress(int active, long doneBytes, long totalBytes) {

        /** 0~100. 크기를 모르면 -1. */
        public int percent() {
            return totalBytes <= 0 ? -1 : (int) Math.min(100, doneBytes * 100 / totalBytes);
        }
    }

    /** 1분마다: 오래 안 쓴 탐색 연결을 풀에 돌려준다. 연결 해제는 EDT 밖에서 한다. */
    private void releaseIdleConnection() {
        if (files instanceof ReconnectingFiles connection) {
            Thread.ofVirtual().start(() -> connection.releaseIfIdle(CONNECTION_IDLE.toNanos()));
        }
    }

    /** 지금 진행 중이거나 기다리는 전송 수. */
    public int activeTransfers() {
        return transfers.activeCount();
    }

    /** 아직 서버에 올리지 못한 편집 내용이 있는 파일 수. */
    public int unsavedEdits() {
        return (int) edits.stream().filter(s -> s.busy || s.watch.dirty()).count();
    }

    /**
     * 연결과 전송 대기열을 닫고 편집용 임시 폴더를 지운다. 진행 중인 전송은 중단된다. 닫기 전에 사용자에게
     * 확인받는 일은 부르는 쪽(앱 종료, 서버 삭제)이 {@link #activeTransfers()}와 {@link #unsavedEdits()}로 한다.
     */
    public void close() {
        RemoteFiles f = files;
        files = null;
        editTimer.stop();
        idleTimer.stop();
        Path tempEdits = editRoot;
        Thread.ofVirtual().start(() -> {
            queue.close();
            if (f != null) {
                f.close();
            }
            deleteTree(tempEdits);
        });
    }

    private void connect() {
        LocalFiles local = service.localFiles();
        localPane.setFooterNote(local.movesToTrash() ? "지우면 휴지통으로 가요" : "");
        remotePane.setFooterNote(service.settings(server.id()).map(s -> s.username() + " 권한으로 접속 중").orElse(""));
        localPane.attach(FilePane.local(local), local.home());
        downloadDir = Path.of(local.home());

        status.setText("SFTP 연결 중…");
        // 탐색 연결은 쓸 때 맺고, 한동안 안 쓰면 풀에 돌려주고, 끊어졌으면 다음에 쓸 때 다시 맺는다(탭이 오래 열려 있으므로).
        ReconnectingFiles connection = new ReconnectingFiles(() -> service.openFiles(server, new HostKeyDialog(this)));
        Async.run(() -> {
            String home = connection.home();
            return new Object[] {connection, home, connection.list(home)};
        }, result -> {
            files = (RemoteFiles) result[0];
            idleTimer.start();
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

    /**
     * 서버에서 고른 파일·폴더를 다운로드 폴더로 받는다(더블클릭, 버튼, 우클릭 메뉴). 왼쪽 패널이 어느 폴더를
     * 보고 있든 같은 곳에 쌓인다. 원하는 폴더에 받으려면 그 폴더로 끌어다 놓는다.
     */
    private void download() {
        downloadItems(remotePane.selection(), downloadDir == null ? null : downloadDir.toString(), true);
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

    /**
     * @param askConflicts 같은 이름이 이미 있는지 미리 묻는다. 아니면(폴더 행에 끌어다 놓은 경우) 덮어쓰지 않고
     *                     겹치는 파일은 건너뛴다
     */
    private void downloadItems(List<RemoteFile> items, String localDirPath, boolean askConflicts) {
        if (!canTransfer() || items.isEmpty() || localDirPath == null) {
            return;
        }
        Path localDir = Path.of(localDirPath);
        List<RemoteFile> conflicts = List.of();
        Decision decision = Decision.SKIP_NONE;
        if (askConflicts) {
            // 받을 폴더가 왼쪽 패널에 보이는 폴더와 다를 수 있으니 패널 목록이 아니라 실제 폴더를 본다.
            conflicts = items.stream()
                    .filter(f -> java.nio.file.Files.exists(localDir.resolve(RemoteFiles.safeLocalName(f.name()))))
                    .toList();
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
        status.setText(queued + "개 항목을 '" + (localDir.getFileName() == null ? localDir : localDir.getFileName())
                + "' 폴더로 받아요");
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

    /**
     * 전송 목록은 항목이 있을 때만 보인다(없으면 자리를 차지하지 않는다). 사용자가 접어 둔 목록은 머리 줄만 남고,
     * 접거나 펼쳐서 높이가 바뀌면 아래 칸이 다시 배치된다.
     */
    private void updateTransfersVisible() {
        boolean show = !transfers.isEmpty();
        if (transfers.isVisible() != show) {
            transfers.setVisible(show);
        }
        south.revalidate();
        south.repaint();
        onTransfersChanged.run();
    }

    private void onJobChanged(TransferQueue.Job job) {
        transfers.refresh();
        // 새 전송이 시작되거나 실패가 생기면 닫아 둔 패널을 다시 연다. 상태는 이 코드가 도는 시점의 값이라
        // 작은 파일은 이미 끝나 있을 수 있으므로, 새 작업인지는 처음 알림을 받았는지로 판단한다.
        if (announced.add(job.id()) || job.state() == TransferQueue.State.FAILED) {
            transfers.setExpanded(true);   // 접어 둔 목록도 새 전송이나 실패가 생기면 다시 펼친다
        }
        updateTransfersVisible();
        for (EditSession s : edits) {
            if (s.job == job && job.state().finished()) {
                s.job = null;
                if (job.state() == TransferQueue.State.DONE) {
                    s.needsSnapshot = true;
                    s.lastUpload = Instant.now();
                    updateBanner();
                    status.setForeground(Theme.TEXT_MUTED);
                    status.setText("'" + s.remote.name() + "' 저장한 내용을 서버에 올렸어요");
                } else {
                    s.busy = false;
                    showError("'" + s.remote.name() + "' 편집한 내용을 서버에 올리지 못했어요: " + job.message()
                            + " · 다시 저장하면 다시 올려요");
                }
            }
        }
        if (job.state() == TransferQueue.State.DONE && job.ownerChangedCount() > 0 && ownerWarned.add(job.id())) {
            warnOwnerChanged(job);
        }
        if (job.state() == TransferQueue.State.DONE && reloaded.add(job.id())) {
            if (job.direction() == TransferQueue.Direction.UPLOAD && job.targetDir().equals(remotePane.cwd())) {
                remotePane.reload();
            } else if (job.direction() == TransferQueue.Direction.DOWNLOAD && job.targetDir().equals(localPane.cwd())) {
                localPane.reload();
            }
        }
    }

    /**
     * 덮어쓴 서버 파일의 소유자가 바뀌었다고 알린다. 일반 계정은 소유자를 되돌릴 수 없어서, 다른 사용자(웹 서버·서비스 계정 등)
     * 소유였던 파일이면 서버에서 직접 맞춰야 할 수 있다. 전송 하나당 한 번만 띄운다.
     */
    private void warnOwnerChanged(TransferQueue.Job job) {
        List<String> names = job.ownerChangedNames();
        String shown = String.join(", ", names.stream().limit(3).toList()) + (names.size() > 3 ? " 외 " + (names.size() - 3) + "개" : "");
        JOptionPane.showMessageDialog(this,
                "덮어쓴 서버 파일 " + names.size() + "개의 소유자가 이 계정으로 바뀌었어요.\n" + shown
                        + "\n\n권한(rwx)은 그대로 유지했지만 소유자는 일반 계정으로 되돌릴 수 없어요.\n"
                        + "다른 사용자(웹 서버, 서비스 계정 등)가 이 파일을 읽거나 써야 한다면 서버에서 소유자를 다시 맞춰 주세요.",
                "소유자 변경", JOptionPane.WARNING_MESSAGE);
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
        /** 편집한 내용을 서버에 마지막으로 올린 시각. 아직 올린 적 없으면 null. */
        Instant lastUpload;

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
                updateBanner();
                openEditor(session);
            } catch (IOException e) {
                showError("받은 파일을 읽지 못했어요: " + e.getMessage());
            }
        }, () -> { });
    }

    /** 내 컴퓨터 쪽 파일을 이 컴퓨터의 편집기로 바로 연다. 서버와 주고받을 것이 없으니 복사본을 만들지 않는다. */
    private void editLocal() {
        List<RemoteFile> selection = localPane.selection();
        if (selection.size() != 1 || selection.get(0).directory()) {
            return;
        }
        Path file = Path.of(selection.get(0).path());
        try {
            ExternalEditor.open(file);
            status.setForeground(Theme.TEXT_MUTED);
            status.setText("'" + file.getFileName() + "'을(를) 편집기로 열었어요");
        } catch (IOException e) {
            showError("편집기를 열지 못했어요: " + file.getFileName());
        }
    }

    /** 배너를 편집 세션에 맞춘다: 편집 중인 파일이 없으면 숨기고, 있으면 가장 최근 파일과 마지막 업로드 시각을 보여 준다. */
    private void updateBanner() {
        bannerRow.setVisible(!edits.isEmpty());
        if (edits.isEmpty()) {
            banner.update(0, "", null);
            return;
        }
        Instant lastUpload = edits.stream().map(s -> s.lastUpload).filter(java.util.Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        banner.update(edits.size(), edits.getLast().remote.path(), lastUpload);
        banner.revalidate();
    }

    /** [편집기 열기]: 하나뿐이면 바로 열고, 여러 개면 고르게 한다. */
    private void openEditorFromBanner() {
        if (edits.size() == 1) {
            openEditor(edits.getFirst());
            return;
        }
        JPopupMenu menu = new JPopupMenu();
        for (EditSession s : edits) {
            addItem(menu, s.remote.path(), true, () -> openEditor(s));
        }
        menu.show(banner.openButton(), 0, banner.openButton().getHeight());
    }

    /**
     * [편집 끝내기]: 편집 중인 파일을 모두 놓는다. 더 이상 저장을 지켜보지 않고 임시 복사본을 지운다.
     * 아직 서버에 올리지 못한 내용이 있으면 먼저 묻는다.
     */
    private void endEdits() {
        int unsaved = unsavedEdits();
        if (unsaved > 0 && JOptionPane.showConfirmDialog(this,
                "아직 서버에 올리지 못한 편집 내용이 " + unsaved + "개 있어요.\n그래도 편집을 끝낼까요? 올리지 못한 내용은 사라져요.",
                "편집 끝내기", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        for (EditSession s : edits) {
            if (s.job != null && !s.job.state().finished()) {
                queue.cancel(s.job);
            }
            deleteTree(s.local.getParent());
        }
        edits.clear();
        editTimer.stop();
        updateBanner();
        status.setForeground(Theme.TEXT_MUTED);
        status.setText("편집을 끝냈어요");
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
            addItem(menu, "내 컴퓨터로 받기", canTransfer() && any, this::download);
            addItem(menu, "편집", pane.ready() && selection.size() == 1 && !selection.get(0).directory(), this::edit);
        } else {
            addItem(menu, "서버로 올리기", canTransfer() && any, this::upload);
            addItem(menu, "편집", pane.ready() && selection.size() == 1 && !selection.get(0).directory(), this::editLocal);
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
        editLocal.setEnabled(localPane.ready() && local.size() == 1 && !local.get(0).directory());
        deleteLocal.setEnabled(localPane.ready() && !local.isEmpty());
        permissions.setEnabled(remotePane.ready() && !remote.isEmpty());
        deleteRemote.setEnabled(remotePane.ready() && !remote.isEmpty());
    }

}
