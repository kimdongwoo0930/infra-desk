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
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/**
 * SFTP 창의 폴더 패널 하나: 제목, 경로 입력, 상위 폴더·새로고침, 파일 표. 이 컴퓨터와 서버가
 * 같은 모양으로 쓴다. 어느 쪽인지는 {@link Source}만 다르다. 폴더 목록은 EDT 밖에서 읽는다.
 */
final class FilePane extends JPanel {

    /** 폴더 목록을 주는 쪽(이 컴퓨터 또는 서버). 블로킹이다. */
    interface Source {
        List<RemoteFile> list(String directory) throws IOException;

        String parent(String directory);

        String join(String directory, String name);

        boolean isRoot(String directory);
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
    private Consumer<RemoteFile> onActivate = f -> { };
    private Runnable onSelection = () -> { };

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
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(Theme.APP_BG);
        table.getColumnModel().getColumn(0).setPreferredWidth(240);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.getColumnModel().getColumn(2).setPreferredWidth(130);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    activateSelected();
                }
            }
        });
        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "open");
        table.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                activateSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.DIVIDER));

        info.setForeground(Theme.TEXT_MUTED);
        info.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        actions.setOpaque(false);
        JPanel bottom = new JPanel(new BorderLayout(8, 0));
        bottom.setOpaque(false);
        bottom.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
        bottom.add(actions, BorderLayout.WEST);
        bottom.add(info, BorderLayout.EAST);

        add(top, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        add(bottom, BorderLayout.SOUTH);
        updateButtons();
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

    RemoteFile selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.get(table.convertRowIndexToModel(row));
    }

    boolean ready() {
        return source != null && !loading && !locked;
    }

    /** 전송 중처럼 밖에서 패널 조작을 막아야 할 때. */
    void setLocked(boolean locked) {
        this.locked = locked;
        updateButtons();
    }

    void reload() {
        if (cwd != null) {
            open(cwd);
        }
    }

    /** 폴더를 EDT 밖에서 읽어서 보여준다. 실패하면 이전 폴더를 그대로 두고 오류를 알린다. */
    void open(String directory) {
        if (source == null || loading || locked || directory.isEmpty()) {
            return;
        }
        Source s = source;
        loading = true;
        showInfo("불러오는 중…", false);
        updateButtons();
        Async.run(() -> s.list(directory), list -> {
            loading = false;
            show(directory, list);
        }, err -> {
            loading = false;
            pathField.setText(cwd == null ? "" : cwd);
            showInfo(Async.message(err), true);
            updateButtons();
        });
    }

    void showInfo(String message, boolean error) {
        info.setText(IpPrivacy.mask(message));
        info.setForeground(error ? Theme.DANGER_TEXT : Theme.TEXT_MUTED);
    }

    private void show(String directory, List<RemoteFile> list) {
        cwd = directory;
        pathField.setText(directory);
        model.setFiles(list);
        long dirs = list.stream().filter(RemoteFile::directory).count();
        showInfo("폴더 " + dirs + "개 · 파일 " + (list.size() - dirs) + "개", false);
        updateButtons();
    }

    private void activateSelected() {
        RemoteFile f = selected();
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

        void setFiles(List<RemoteFile> files) {
            this.files = List.copyOf(files);
            fireTableDataChanged();
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
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "이름";
                case 1 -> "크기";
                default -> "수정한 날짜";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            RemoteFile f = files.get(row);
            return switch (column) {
                case 0 -> f;
                case 1 -> f.directory() ? "—" : size(f.size());
                default -> f.modified() == null ? "" : DATE.format(f.modified());
            };
        }
    }

    private static final class NameRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            RemoteFile f = (RemoteFile) value;
            super.getTableCellRendererComponent(t, f.name(), sel, focus, row, col);
            setIcon(Icons.get(f.directory() ? "folder" : "file", 15, f.directory() ? Theme.ACCENT : Theme.TEXT_MUTED));
            setIconTextGap(8);
            setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));
            return this;
        }
    }
}
