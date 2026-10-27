package com.infradesk.ui.files;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.LocalFiles;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ui.Async;
import com.infradesk.ui.Icons;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ComingSoon;
import com.infradesk.ui.components.FilterChip;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.DropMode;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.TransferHandler;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.JTableHeader;

/**
 * SFTP 창의 폴더 패널 하나: 제목, 경로 입력, 상위 폴더·새로고침, 파일 표. 이 컴퓨터와 서버가
 * 같은 모양으로 쓴다. 어느 쪽인지는 {@link Source}만 다르다. 폴더 목록과 파일 작업은 EDT 밖에서 한다.
 */
final class FilePane extends JPanel {

    /** 폴더 목록과 파일 작업을 하는 쪽(이 컴퓨터 또는 서버). 블로킹이다. */
    interface Source {
        List<RemoteFile> list(String directory) throws IOException;

        String parent(String directory);

        String join(String directory, String name);

        boolean isRoot(String directory);

        void mkdir(String path) throws IOException;

        /** 빈 파일. 같은 이름이 있으면 실패한다. */
        void createFile(String path) throws IOException;

        void rename(String from, String to) throws IOException;

        /** 폴더면 안의 모든 것까지 지운다. 휴지통으로 보냈으면 true(되돌릴 수 있음). */
        boolean delete(RemoteFile file) throws IOException;

        /** 권한(rwx)을 보여주고 바꿀 수 있는 쪽인지. */
        boolean hasPermissions();

        void chmod(String path, int permissions) throws IOException;

        /** 지운 것을 되돌릴 수 있는지(휴지통). 확인 창 문구를 정하는 데 쓴다. */
        boolean recoverableDelete();
    }

    static Source local(LocalFiles files) {
        return new Source() {
            @Override
            public List<RemoteFile> list(String directory) throws IOException {
                return files.list(directory);
            }

            @Override
            public String parent(String directory) {
                return files.parent(directory);
            }

            @Override
            public String join(String directory, String name) {
                return files.join(directory, name);
            }

            @Override
            public boolean isRoot(String directory) {
                return files.isRoot(directory);
            }

            @Override
            public void mkdir(String path) throws IOException {
                files.mkdir(path);
            }

            @Override
            public void createFile(String path) throws IOException {
                files.createFile(path);
            }

            @Override
            public void rename(String from, String to) throws IOException {
                files.rename(from, to);
            }

            @Override
            public boolean delete(RemoteFile file) throws IOException {
                return files.delete(file.path());
            }

            @Override
            public boolean hasPermissions() {
                return false;
            }

            @Override
            public void chmod(String path, int permissions) {
                throw new UnsupportedOperationException();
            }

            @Override
            public boolean recoverableDelete() {
                return files.movesToTrash();
            }
        };
    }

    static Source remote(RemoteFiles files) {
        return new Source() {
            @Override
            public List<RemoteFile> list(String directory) throws IOException {
                return files.list(directory);
            }

            @Override
            public String parent(String directory) {
                return RemoteFiles.parent(directory);
            }

            @Override
            public String join(String directory, String name) {
                return RemoteFiles.join(directory, name);
            }

            @Override
            public boolean isRoot(String directory) {
                return directory.equals("/");
            }

            @Override
            public void mkdir(String path) throws IOException {
                files.mkdir(path);
            }

            @Override
            public void createFile(String path) throws IOException {
                files.createFile(path);
            }

            @Override
            public void rename(String from, String to) throws IOException {
                files.rename(from, to);
            }

            @Override
            public boolean delete(RemoteFile file) throws IOException {
                files.deleteRecursively(file);
                return false;
            }

            @Override
            public boolean hasPermissions() {
                return true;
            }

            @Override
            public void chmod(String path, int permissions) throws IOException {
                files.chmod(path, permissions);
            }

            @Override
            public boolean recoverableDelete() {
                return false;
            }
        };
    }

    /** 끌어서 놓기로 패널 사이를 오가는 선택 항목. 같은 프로그램 안에서만 쓴다. */
    record Payload(FilePane from, List<RemoteFile> items) {
    }

    static final DataFlavor PAYLOAD = payloadFlavor();

    private static DataFlavor payloadFlavor() {
        try {
            return new DataFlavor(DataFlavor.javaJVMLocalObjectMimeType + ";class=" + Payload.class.getName(),
                    "SFTP 선택 항목", FilePane.class.getClassLoader());
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(e);
        }
    }

    /** 이 패널에 무언가를 끌어다 놓았을 때 처리하는 쪽. */
    interface DropHandler {
        /** 이 데이터를 받을 수 있는지. 끌고 있는 동안 계속 불리므로 데이터를 읽지 말고 종류만 본다. */
        boolean accepts(TransferHandler.TransferSupport support);

        /** @param folder 폴더 행 위에 놓았으면 그 폴더, 빈 곳이면 null(지금 폴더) */
        boolean drop(Transferable data, RemoteFile folder);
    }

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final FileTableModel model = new FileTableModel();
    private final JTable table = new JTable(model);
    private final JTextField pathField = new JTextField();
    private final JLabel info = new JLabel(" ");
    private final JButton up = Buttons.icon("arrow-up", "상위 폴더", 30);
    private final JButton refresh = Buttons.icon("refresh", "새로고침", 30);
    private final JButton newFolder = Buttons.icon("folder-plus", "새 폴더", 30);
    /** "숨김 파일" 토글(서버 창에만 둔다). 켜져 있으면 점(.)으로 시작하는 이름도 보인다. */
    private final FilterChip hiddenToggle = new FilterChip("숨김 파일");
    private final FilterChip startChip = new FilterChip("처음 경로");
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
    /** 선택한 항목이 있을 때만 보이는 작업 막대: "N개 선택  [받기] [편집] …". */
    private final JPanel selectionBar = new JPanel(new BorderLayout(12, 0));
    private final JLabel selectionLabel = new JLabel(" ");
    /** 폴더에 있는 모든 항목. 표에는 숨김 파일 토글에 따라 걸러서 보이지만 같은 이름 확인은 전부를 본다. */
    private List<RemoteFile> allFiles = List.of();
    private boolean showHidden = true;
    /** 처음 연결한 폴더. [처음 경로] 칩이 돌아가는 곳. */
    private String startDir;
    /** 요약 줄 끝에 덧붙이는 안내(예: "지우면 휴지통으로 가요", "ubuntu 권한으로 접속 중"). */
    private String footerNote = "";
    /** macOS가 폴더를 막았을 때만 요약 줄 옆에 나타나는 [권한 설정 열기]. */
    private final JButton permissionButton = Buttons.secondary("권한 설정 열기", null);
    private Runnable onNewFolder = () -> { };

    private Source source;
    private String cwd;
    private boolean loading;
    private boolean locked;
    private boolean reloadRequested;
    private Consumer<RemoteFile> onActivate = f -> { };
    private Runnable onSelection = () -> { };
    private Supplier<JPopupMenu> menuProvider = () -> null;
    private DropHandler dropHandler;

    FilePane(String title, String accessibleName) {
        super(new BorderLayout());
        setOpaque(false);

        JLabel heading = new JLabel(title);
        heading.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");

        pathField.setFont(Theme.monoFont(12.5f));
        pathField.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        pathField.getAccessibleContext().setAccessibleName(accessibleName);
        pathField.addActionListener(e -> open(pathField.getText().strip()));
        up.addActionListener(e -> {
            if (source != null && cwd != null) {
                open(source.parent(cwd));
            }
        });
        refresh.addActionListener(e -> reload());
        newFolder.addActionListener(e -> onNewFolder.run());
        hiddenToggle.setSelected(true);
        hiddenToggle.setVisible(false);
        hiddenToggle.addActionListener(e -> {
            showHidden = hiddenToggle.isSelected();
            applyView();
        });
        startChip.addActionListener(e -> {
            startChip.setSelected(false);
            if (startDir != null) {
                open(startDir);
            }
        });
        // 준비 중(v2): 자주 가는 경로를 저장해 두는 칩. 저장 기능이 생기면 연다.
        FilterChip addChip = ComingSoon.apply(new FilterChip("＋ 경로 추가"));

        // 머리: 제목, 오른쪽에 위로 · 새로고침 · 새 폴더 (· 숨김 파일)
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        tools.setOpaque(false);
        tools.add(up);
        tools.add(refresh);
        tools.add(newFolder);
        tools.add(hiddenToggle);
        JPanel head = new JPanel(new BorderLayout(8, 0));
        head.setOpaque(false);
        head.add(heading, BorderLayout.CENTER);
        head.add(tools, BorderLayout.EAST);

        JPanel chips = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        chips.setOpaque(false);
        chips.add(startChip);
        chips.add(addChip);
        JPanel pathBlock = new JPanel(new BorderLayout(0, 8));
        pathBlock.setOpaque(false);
        pathBlock.add(pathField, BorderLayout.NORTH);
        pathBlock.add(chips, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout(0, 10));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(14, 16, 12, 16));
        top.add(head, BorderLayout.NORTH);
        top.add(pathBlock, BorderLayout.CENTER);

        table.setRowHeight(32);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setBackground(Theme.PANEL_BG);
        table.getColumnModel().getColumn(0).setPreferredWidth(220);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setPreferredWidth(70);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        DefaultTableCellRenderer mono = new DefaultTableCellRenderer();
        mono.setFont(Theme.monoFont(12f));
        table.getColumnModel().getColumn(3).setPreferredWidth(90);
        table.getColumnModel().getColumn(3).setCellRenderer(mono);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && !e.isPopupTrigger()) {
                    activateSelected();
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                popup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                popup(e);
            }
        });
        bind("ENTER", this::activateSelected);
        installDragAndDrop();
        JTableHeader header = table.getTableHeader();
        header.setReorderingAllowed(false);
        header.setPreferredSize(new java.awt.Dimension(0, 34));
        header.setDefaultRenderer(new HeaderRenderer());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));
        scroll.getViewport().setBackground(Theme.PANEL_BG);

        info.setForeground(Theme.TEXT_MUTED);
        info.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        // 상태 문구가 길어도 옆 칸을 밀지 않게 하고, 넘치면 …으로 줄인다.
        info.setMinimumSize(new java.awt.Dimension(0, 0));
        actions.setOpaque(false);

        // 선택 작업 막대: 왼쪽에 "N개 선택", 오른쪽에 작업 버튼. 선택이 없으면 숨긴다.
        selectionLabel.putClientProperty(FlatClientProperties.STYLE, "font: bold");
        selectionBar.setBackground(Theme.ACCENT_SOFT_BG);
        selectionBar.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(8, 16, 8, 12)));
        selectionBar.add(selectionLabel, BorderLayout.WEST);
        selectionBar.add(actions, BorderLayout.CENTER);
        selectionBar.setVisible(false);

        JPanel footer = new JPanel(new BorderLayout());
        footer.setBackground(Theme.PANEL_LOW_BG);
        footer.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER),
                BorderFactory.createEmptyBorder(9, 16, 9, 16)));
        footer.add(info, BorderLayout.CENTER);
        permissionButton.setVisible(false);
        permissionButton.addActionListener(e -> openMacPrivacySettings());
        footer.add(permissionButton, BorderLayout.EAST);
        JPanel bottom = new JPanel(new BorderLayout());
        bottom.setOpaque(false);
        bottom.add(selectionBar, BorderLayout.NORTH);
        bottom.add(footer, BorderLayout.SOUTH);

        // 카드 하나: 머리 · 경로 · 표 · 선택 작업 막대 · 요약 줄이 한 테두리 안에 든다.
        RoundedPanel card = new RoundedPanel(new BorderLayout(), Theme.PANEL_BG, Theme.DIVIDER, Theme.ARC_CARD);
        card.add(top, BorderLayout.NORTH);
        card.add(scroll, BorderLayout.CENTER);
        card.add(bottom, BorderLayout.SOUTH);
        add(card, BorderLayout.CENTER);
        updateButtons();
    }

    /** 표 위에서 쓰는 단축키(예: "F2", "DELETE"). */
    void bind(String keyStroke, Runnable action) {
        String name = "pane-" + keyStroke;
        table.getInputMap().put(KeyStroke.getKeyStroke(keyStroke), name);
        table.getActionMap().put(name, new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }

    /** 선택 작업 막대에 줄지어 놓을 동작 버튼(왼쪽부터 순서대로). */
    void addAction(JButton button) {
        // 막대는 창 폭이 좁아도 버튼 넷(받기·편집·권한·삭제)이 한 줄에 들어가야 해서 안쪽 여백을 줄인다.
        // 버튼마다 이미 색 설정(STYLE)이 있으므로 덮어쓰지 않고 뒤에 이어 붙인다.
        Object style = button.getClientProperty(FlatClientProperties.STYLE);
        button.putClientProperty(FlatClientProperties.STYLE,
                (style == null ? "" : style + "; ") + "margin: 3,10,3,10; minimumWidth: 0; font: -1");
        // Buttons가 만들 때 정해 둔 크기는 예전 여백 기준이다: 지우고 새 여백으로 다시 잰다(높이는 그대로).
        button.setPreferredSize(null);
        button.setPreferredSize(new java.awt.Dimension(button.getPreferredSize().width + 4, Theme.BUTTON_HEIGHT));
        actions.add(button);
    }

    /** 머리의 [새 폴더] 아이콘을 눌렀을 때. */
    void onNewFolder(Runnable listener) {
        this.onNewFolder = listener;
    }

    /** 서버 창처럼 "숨김 파일" 토글이 필요한 창에서 켠다. 처음에는 숨김 파일도 보인다(예전과 같다). */
    void enableHiddenToggle() {
        hiddenToggle.setVisible(true);
    }

    /** 요약 줄 끝에 덧붙일 안내. */
    void setFooterNote(String note) {
        this.footerNote = note == null ? "" : note;
        if (cwd != null) {
            showCount();
        }
    }

    /** 파일을 더블클릭하거나 Enter를 눌렀을 때(폴더는 패널이 직접 연다). */
    void onActivate(Consumer<RemoteFile> listener) {
        this.onActivate = listener;
    }

    /** 선택이나 불러오는 상태가 바뀌었을 때. 동작 버튼의 활성 상태를 다시 계산하는 데 쓴다. */
    void onSelectionChange(Runnable listener) {
        this.onSelection = listener;
    }

    /** 우클릭하면 이 메뉴를 보여준다. null이거나 비어 있으면 아무것도 띄우지 않는다. */
    void setMenuProvider(Supplier<JPopupMenu> provider) {
        this.menuProvider = provider;
    }

    JTable table() {
        return table;
    }

    /** 끌어다 놓는 것을 받을 쪽. null이면 받지 않는다(끌어내는 것은 항상 된다). */
    void setDropHandler(DropHandler handler) {
        this.dropHandler = handler;
    }

    private void installDragAndDrop() {
        table.setDragEnabled(true);
        table.setDropMode(DropMode.ON);
        table.setTransferHandler(new TransferHandler() {
            @Override
            public int getSourceActions(javax.swing.JComponent c) {
                return COPY;
            }

            @Override
            protected Transferable createTransferable(javax.swing.JComponent c) {
                List<RemoteFile> items = ready() ? selection() : List.of();
                if (items.isEmpty()) {
                    return null;
                }
                Payload payload = new Payload(FilePane.this, items);
                return new Transferable() {
                    @Override
                    public DataFlavor[] getTransferDataFlavors() {
                        return new DataFlavor[] {PAYLOAD};
                    }

                    @Override
                    public boolean isDataFlavorSupported(DataFlavor flavor) {
                        return PAYLOAD.equals(flavor);
                    }

                    @Override
                    public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                        if (!isDataFlavorSupported(flavor)) {
                            throw new UnsupportedFlavorException(flavor);
                        }
                        return payload;
                    }
                };
            }

            @Override
            public boolean canImport(TransferSupport support) {
                if (dropHandler == null || !ready() || !support.isDrop()) {
                    return false;
                }
                if (support.isDataFlavorSupported(PAYLOAD)) {
                    try {
                        // 자기 패널에서 끌어 자기 패널에 놓는 것은 받지 않는다.
                        Payload payload = (Payload) support.getTransferable().getTransferData(PAYLOAD);
                        if (payload.from() == FilePane.this) {
                            return false;
                        }
                    } catch (UnsupportedFlavorException | IOException e) {
                        return false;
                    }
                }
                return dropHandler.accepts(support);
            }

            @Override
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) {
                    return false;
                }
                RemoteFile folder = null;
                if (support.getDropLocation() instanceof JTable.DropLocation location && location.getRow() >= 0) {
                    RemoteFile target = model.get(table.convertRowIndexToModel(location.getRow()));
                    if (target.directory()) {
                        folder = target;
                    }
                }
                return dropHandler.drop(support.getTransferable(), folder);
            }
        });
    }

    /** 목록을 이미 읽어 온 상태로 연결한다(서버 연결을 맺으면서 첫 목록까지 가져온 경우). */
    void attach(Source source, String directory, List<RemoteFile> list) {
        this.source = source;
        show(directory, list);
    }

    /** 처음 목록을 EDT 밖에서 읽어서 연결한다. */
    void attach(Source source, String directory) {
        this.source = source;
        open(directory);
    }

    String cwd() {
        return cwd;
    }

    Source source() {
        return source;
    }

    /** 선택한 항목들(표에 보이는 순서). */
    List<RemoteFile> selection() {
        List<RemoteFile> result = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            result.add(model.get(table.convertRowIndexToModel(row)));
        }
        return result;
    }

    /** 정확히 하나만 선택했을 때 그 항목, 아니면 null. */
    RemoteFile single() {
        return table.getSelectedRowCount() == 1 ? selection().getFirst() : null;
    }

    /** 지금 보이는 폴더의 항목 이름들(이미 있는 이름인지 확인하는 데 쓴다). */
    Set<String> names() {
        Set<String> names = new HashSet<>();
        for (RemoteFile f : allFiles) {
            names.add(f.name());
        }
        return names;
    }

    boolean ready() {
        return source != null && !loading && !locked;
    }

    /** 전송 중처럼 밖에서 패널 조작을 막아야 할 때. */
    void setLocked(boolean locked) {
        this.locked = locked;
        updateButtons();
    }

    /** 지금 폴더를 다시 읽는다. 다른 작업 중이면 그것이 끝난 뒤에 읽는다. */
    void reload() {
        if (cwd == null) {
            return;
        }
        if (loading) {
            reloadRequested = true;
            return;
        }
        open(cwd);
    }

    /** 폴더를 EDT 밖에서 읽어서 보여준다. 실패하면 이전 폴더를 그대로 두고 오류를 알린다. */
    void open(String directory) {
        if (source == null || loading || locked || directory.isEmpty()) {
            return;
        }
        Source s = source;
        boolean remote = s.hasPermissions();
        run("불러오는 중…", () -> {
            try {
                return s.list(directory);
            } catch (IOException e) {
                String hint = isPermissionError(e) ? hintFor(directory, remote, IS_MAC) : null;
                if (hint == null) {
                    throw e;
                }
                throw new HintedException("권한이 없어서 폴더를 읽지 못했어요. " + hint, e);
            }
        }, list -> show(directory, list), () -> pathField.setText(cwd == null ? "" : cwd));
    }

    /**
     * 파일 작업을 EDT 밖에서 한다. 하는 동안 이 패널은 다른 작업을 받지 않는다. 실패하면 오류를
     * 패널 아래에 보여준다.
     */
    <T> void run(String message, Callable<T> task, Consumer<T> onSuccess) {
        run(message, task, onSuccess, () -> { });
    }

    /** 위와 같고, 실패했을 때 {@code onFailure}도 부른다(일부만 처리됐을 수 있어 목록을 다시 읽는 데 쓴다). */
    <T> void run(String message, Callable<T> task, Consumer<T> onSuccess, Runnable onFailure) {
        if (source == null || loading) {
            return;
        }
        loading = true;
        showInfo(message, false);
        updateButtons();
        Async.run(task, result -> {
            loading = false;
            // "~ 하는 중…" 안내를 개수 요약으로 되돌린다. 오류 문구는 실패 쪽에서만 나오므로 지워지지 않는다.
            if (cwd != null) {
                showCount();
            }
            onSuccess.accept(result);
            updateButtons();
            runPendingReload();
        }, err -> {
            loading = false;
            onFailure.run();
            showInfo(friendly(err), true);
            // macOS가 이 컴퓨터의 폴더를 막은 경우: 안내 옆에 설정 화면을 여는 버튼을 보여 준다.
            permissionButton.setVisible(IS_MAC && source != null && !source.hasPermissions() && isPermissionError(err));
            updateButtons();
            runPendingReload();
        });
    }

    private void runPendingReload() {
        if (reloadRequested && !loading) {
            reloadRequested = false;
            reload();
        }
    }

    /** 폴더를 읽지 못한 이유에 해결 방법까지 붙인 오류. {@link #friendly}가 그대로 보여준다. */
    private static final class HintedException extends IOException {
        HintedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** 권한이 없어서 난 오류인지. macOS의 개인정보 보호가 막을 때는 "Operation not permitted"로 온다. */
    static boolean isPermissionError(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage());
            if (c instanceof java.nio.file.AccessDeniedException || m.contains("PERMISSION_DENIED")
                    || m.contains("Operation not permitted")) {
                return true;
            }
        }
        return false;
    }

    private static final java.util.regex.Pattern MAC_PROTECTED = java.util.regex.Pattern
            .compile("^/Users/[^/]+/(Desktop|Documents|Downloads|Movies|Music|Pictures|Library)(/.*)?$");

    /**
     * macOS는 데스크탑·문서·다운로드 같은 폴더를 SSH로 들어온 프로그램에게 기본으로 보여주지 않는다
     * (이 컴퓨터에서도 앱이 허용을 받아야 한다). 그런 폴더에서 권한 오류가 났을 때 해결 방법을 알려준다.
     *
     * @return 안내 문구. 해당하지 않으면 null
     */
    static String permissionHint(String directory, boolean remote) {
        if (directory == null || !MAC_PROTECTED.matcher(directory).matches()) {
            return null;
        }
        return remote
                ? "서버가 Mac이라면 SSH 접속에는 이 폴더를 읽을 권한이 기본으로 없어요. 서버의 시스템 설정 → 일반 → 공유 → "
                        + "원격 로그인(ⓘ)에서 전체 디스크 접근을 허용해 보세요."
                : LOCAL_MAC_HINT;
    }

    /** 이 컴퓨터(macOS)가 폴더를 앱에 열어 주지 않을 때의 안내. [권한 설정 열기] 버튼이 옆에 붙는다. */
    private static final String LOCAL_MAC_HINT = "macOS가 이 폴더를 앱에 열어 주지 않았어요. [권한 설정 열기]를 눌러 "
            + "개인정보 보호 및 보안 → 전체 디스크 접근(또는 파일 및 폴더)에서 이 앱을 허용해 보세요. 개발 중에 "
            + "실행했다면 앱을 띄운 터미널이나 IntelliJ를 허용해야 해요.";

    /**
     * 권한 오류에 붙일 안내. 이 컴퓨터가 macOS면 어느 경로든 안내한다(보호되는 곳은 사용자 폴더 말고도 많다:
     * 다른 사용자의 홈, {@code /Library}의 일부 등). 그 밖에는 {@link #permissionHint}처럼 알려진 보호 폴더만.
     *
     * @param mac 이 앱이 macOS에서 도는지
     */
    static String hintFor(String directory, boolean remote, boolean mac) {
        return !remote && mac ? LOCAL_MAC_HINT : permissionHint(directory, remote);
    }

    static final boolean IS_MAC = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac");

    /** macOS의 "전체 디스크 접근" 설정 화면을 연다. 어느 앱을 허용해야 하는지는 거기서 사용자가 고른다. */
    private static void openMacPrivacySettings() {
        Thread.ofVirtual().start(() -> {
            try {
                new ProcessBuilder("open", "x-apple.systempreferences:com.apple.preference.security?Privacy_AllFiles").start();
            } catch (IOException ignored) {
                // 설정 화면을 못 열어도 안내 문구에 경로가 적혀 있다.
            }
        });
    }

    /** 서버·파일 시스템이 주는 영어 오류를 알아보기 쉬운 한국어로 바꾼다. */
    static String friendly(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage());
            if (c instanceof HintedException) {
                return m;
            }
            if (c instanceof java.nio.file.AccessDeniedException || m.contains("PERMISSION_DENIED")
                    || m.contains("Operation not permitted")) {
                return "권한이 없어요. 이 계정으로는 할 수 없는 작업이에요.";
            }
            if (c instanceof java.nio.file.NoSuchFileException || m.contains("NO_SUCH_FILE")) {
                return "파일이나 폴더가 없어요. 새로고침해 보세요.";
            }
            if (c instanceof java.nio.file.FileAlreadyExistsException || m.contains("FILE_ALREADY_EXISTS")) {
                return "같은 이름이 이미 있어요.";
            }
            if (c instanceof java.nio.file.DirectoryNotEmptyException) {
                return "폴더가 비어 있지 않아요.";
            }
        }
        return Async.message(t);
    }

    void showInfo(String message, boolean error) {
        permissionButton.setVisible(false);
        String text = IpPrivacy.mask(message);
        info.setText(text);
        info.setToolTipText(text.length() > 40 ? text : null);
        info.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_MUTED);
    }

    /** 폴더 안의 항목 수 같은 기본 안내 문구로 되돌린다. */
    void showCount() {
        long dirs = model.files().stream().filter(RemoteFile::directory).count();
        showInfo(summaryText(dirs, model.files().size() - dirs, footerNote), false);
    }

    /** 요약 줄 글자: "폴더 1개 · 파일 3개 · 안내". 안내가 없으면 앞부분만. */
    static String summaryText(long dirs, long files, String note) {
        String counts = "폴더 " + dirs + "개 · 파일 " + files + "개";
        return note == null || note.isEmpty() ? counts : counts + " · " + note;
    }

    /** 숨김 파일 토글에 따라 표에 보일 항목. 숨김은 점(.)으로 시작하는 이름이다. */
    static List<RemoteFile> visible(List<RemoteFile> files, boolean showHidden) {
        if (showHidden) {
            return files;
        }
        return files.stream().filter(f -> !f.name().startsWith(".")).toList();
    }

    /** 숨김 토글이 바뀌었다: 같은 폴더를 다시 읽지 않고 보이는 항목만 바꾼다. */
    private void applyView() {
        if (cwd == null) {
            return;
        }
        model.setFiles(visible(allFiles, showHidden), source != null && source.hasPermissions());
        showCount();
        updateButtons();
    }

    private void show(String directory, List<RemoteFile> list) {
        cwd = directory;
        if (startDir == null) {
            startDir = directory;
            startChip.setToolTipText(directory);
        }
        pathField.setText(directory);
        boolean permissions = source != null && source.hasPermissions();
        allFiles = List.copyOf(list);
        model.setFiles(visible(allFiles, showHidden), permissions);
        javax.swing.table.TableColumn column = table.getColumnModel().getColumn(3);
        column.setMinWidth(permissions ? 60 : 0);
        column.setMaxWidth(permissions ? 200 : 0);
        column.setPreferredWidth(permissions ? 90 : 0);
        showCount();
        updateButtons();
    }

    private void popup(MouseEvent e) {
        if (!e.isPopupTrigger() || source == null) {
            return;
        }
        int row = table.rowAtPoint(e.getPoint());
        if (row >= 0 && !table.isRowSelected(row)) {
            table.setRowSelectionInterval(row, row);
        } else if (row < 0) {
            table.clearSelection();
        }
        JPopupMenu menu = menuProvider.get();
        if (menu != null && menu.getComponentCount() > 0) {
            menu.show(table, e.getX(), e.getY());
        }
    }

    private void activateSelected() {
        RemoteFile f = single();
        if (f == null || !ready()) {
            return;
        }
        if (f.directory()) {
            open(f.path());
        } else {
            onActivate.accept(f);
        }
    }

    private void updateButtons() {
        boolean ready = ready();
        up.setEnabled(ready && cwd != null && !source.isRoot(cwd));
        refresh.setEnabled(ready);
        pathField.setEnabled(ready);
        table.setEnabled(source != null);
        newFolder.setEnabled(ready);
        int selected = table.getSelectedRowCount();
        selectionBar.setVisible(selected > 0);
        selectionLabel.setText(selected + "개 선택");
        onSelection.run();
    }

    static String size(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        String[] units = {"KB", "MB", "GB", "TB"};
        double v = bytes;
        int u = -1;
        while (v >= 1024 && u < units.length - 1) {
            v /= 1024;
            u++;
        }
        return String.format(Locale.ROOT, v >= 100 ? "%.0f %s" : "%.1f %s", v, units[u]);
    }

    /** 표 머리: 컨테이너 표와 같은 흐린 글자에 아래 선. 크기·권한은 오른쪽·왼쪽 정렬을 칸에 맞춘다. */
    private static final class HeaderRenderer implements javax.swing.table.TableCellRenderer {
        private final JLabel label = new JLabel();

        HeaderRenderer() {
            label.setOpaque(true);
            label.setBackground(Theme.PANEL_BG);
            label.setForeground(Theme.TEXT_MUTED);
            label.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object v, boolean sel, boolean focus, int row, int col) {
            label.setText(String.valueOf(v));
            label.setHorizontalAlignment(col == 1 ? SwingConstants.RIGHT : SwingConstants.LEFT);
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.DIVIDER),
                    BorderFactory.createEmptyBorder(0, col == 0 ? 16 : 8, 0, col == 1 ? 12 : 8)));
            return label;
        }
    }

    private static final class FileTableModel extends AbstractTableModel {
        private List<RemoteFile> files = List.of();
        private boolean permissions;

        void setFiles(List<RemoteFile> files, boolean permissions) {
            this.files = List.copyOf(files);
            this.permissions = permissions;
            fireTableDataChanged();
        }

        List<RemoteFile> files() {
            return files;
        }

        RemoteFile get(int row) {
            return files.get(row);
        }

        @Override
        public int getRowCount() {
            return files.size();
        }

        @Override
        public int getColumnCount() {
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "이름";
                case 1 -> "크기";
                case 2 -> "수정한 날짜";
                default -> "권한";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            RemoteFile f = files.get(row);
            return switch (column) {
                case 0 -> f;
                case 1 -> f.directory() ? "—" : size(f.size());
                case 2 -> f.modified() == null ? "" : DATE.format(f.modified());
                default -> permissions ? f.permissionText() : "";
            };
        }
    }

    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            RemoteFile f = (RemoteFile) value;
            super.getTableCellRendererComponent(t, f.name() + (f.symlink() ? "  →" : ""), sel, focus, row, col);
            setIcon(Icons.get(f.directory() ? "folder" : "file", 15, f.directory() ? Theme.ACCENT : Theme.TEXT_MUTED));
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
            return this;
        }
    }
}
