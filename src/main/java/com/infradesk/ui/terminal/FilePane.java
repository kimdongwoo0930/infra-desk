package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.LocalFiles;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ui.Async;
import com.infradesk.ui.Icons;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
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
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

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

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final FileTableModel model = new FileTableModel();
    private final JTable table = new JTable(model);
    private final JTextField pathField = new JTextField();
    private final JLabel info = new JLabel(" ");
    private final JButton up = Buttons.icon("arrow-up", "상위 폴더", 30);
    private final JButton refresh = Buttons.icon("refresh", "새로고침", 30);
    private final JPanel actions = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));

    private Source source;
    private String cwd;
    private boolean loading;
    private boolean locked;
    private boolean reloadRequested;
    private Consumer<RemoteFile> onActivate = f -> { };
    private Runnable onSelection = () -> { };
    private Supplier<JPopupMenu> menuProvider = () -> null;

    FilePane(String title, String accessibleName) {
        super(new BorderLayout());
        setOpaque(false);

        JLabel heading = new JLabel(title);
        heading.putClientProperty(FlatClientProperties.STYLE, "font: bold +0");
        heading.setBorder(BorderFactory.createEmptyBorder(0, 2, 8, 0));

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

        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        buttons.setOpaque(false);
        buttons.add(up);
        buttons.add(refresh);
        JPanel nav = new JPanel(new BorderLayout(6, 0));
        nav.setOpaque(false);
        nav.add(buttons, BorderLayout.WEST);
        nav.add(pathField, BorderLayout.CENTER);

        JPanel top = new JPanel(new BorderLayout());
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
        top.add(heading, BorderLayout.NORTH);
        top.add(nav, BorderLayout.CENTER);

        table.setRowHeight(28);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setBackground(Theme.APP_BG);
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
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.DIVIDER));
        scroll.getViewport().setBackground(Theme.APP_BG);

        info.setForeground(Theme.TEXT_MUTED);
        info.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        // 상태 문구가 길어도 버튼 위로 넘치지 않게 따로 한 줄을 쓰고, 넘치면 …으로 줄인다.
        info.setMinimumSize(new java.awt.Dimension(0, 0));
        actions.setOpaque(false);
        JPanel bottom = new JPanel(new BorderLayout(0, 6));
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        bottom.add(actions, BorderLayout.NORTH);
        bottom.add(info, BorderLayout.SOUTH);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
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

    /** 폴더 패널 아래에 줄지어 놓을 동작 버튼. */
    void addAction(JButton button) {
        actions.add(button);
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
        for (RemoteFile f : model.files()) {
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
        run("불러오는 중…", () -> s.list(directory), list -> show(directory, list), () -> pathField.setText(cwd == null ? "" : cwd));
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
            onSuccess.accept(result);
            updateButtons();
            runPendingReload();
        }, err -> {
            loading = false;
            onFailure.run();
            showInfo(friendly(err), true);
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

    /** 서버·파일 시스템이 주는 영어 오류를 알아보기 쉬운 한국어로 바꾼다. */
    static String friendly(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            String m = String.valueOf(c.getMessage());
            if (c instanceof java.nio.file.AccessDeniedException || m.contains("PERMISSION_DENIED")) {
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
        String text = IpPrivacy.mask(message);
        info.setText(text);
        info.setToolTipText(text.length() > 40 ? text : null);
        info.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_MUTED);
    }

    /** 폴더 안의 항목 수 같은 기본 안내 문구로 되돌린다. */
    void showCount() {
        long dirs = model.files().stream().filter(RemoteFile::directory).count();
        showInfo("폴더 " + dirs + "개 · 파일 " + (model.files().size() - dirs) + "개", false);
    }

    private void show(String directory, List<RemoteFile> list) {
        cwd = directory;
        pathField.setText(directory);
        boolean permissions = source != null && source.hasPermissions();
        model.setFiles(list, permissions);
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
