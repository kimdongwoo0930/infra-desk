package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.service.TerminalService;
import com.infradesk.ssh.RemoteFile;
import com.infradesk.ssh.RemoteFiles;
import com.infradesk.ui.Async;
import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.awt.Window;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextField;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;

/** 서버 하나의 SFTP 파일 브라우저: 탐색, 업로드, 다운로드, 삭제. 모달이 아니다. */
public final class SftpDialog extends JDialog {

    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault());

    private final TerminalService service;
    private final Server server;
    private final FileTableModel model = new FileTableModel();
    private final JTable table = new JTable(model);
    private final JTextField pathField = new JTextField();
    private final JLabel status = new JLabel(" ");
    private final JButton up = Buttons.icon("arrow-up", "상위 폴더", 30);
    private final JButton refresh = Buttons.icon("refresh", "새로고침", 30);
    private final JButton upload = Buttons.secondary("업로드…", "upload");
    private final JButton download = Buttons.secondary("다운로드…", "download");
    private final JButton delete = Buttons.danger("삭제", "trash");

    private RemoteFiles files;
    private String cwd;
    private boolean busy;

    public SftpDialog(Window owner, TerminalService service, Server server) {
        super(owner, "SFTP · " + server.name(), ModalityType.MODELESS);
        this.service = service;
        this.server = server;

        pathField.setFont(Theme.monoFont(12.5f));
        pathField.putClientProperty(FlatClientProperties.STYLE, "background: #1E1F22");
        pathField.getAccessibleContext().setAccessibleName("원격 경로");
        pathField.addActionListener(e -> navigate(pathField.getText().strip()));
        up.addActionListener(e -> navigate(RemoteFiles.parent(cwd)));
        refresh.addActionListener(e -> navigate(cwd));
        upload.addActionListener(e -> upload());
        download.addActionListener(e -> download());
        delete.addActionListener(e -> delete());

        JPanel nav = new JPanel(new BorderLayout(6, 0));
        nav.setOpaque(false);
        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navButtons.setOpaque(false);
        navButtons.add(up);
        navButtons.add(refresh);
        nav.add(navButtons, BorderLayout.WEST);
        nav.add(pathField, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(upload);
        actions.add(download);
        actions.add(delete);
        JPanel top = new JPanel(new BorderLayout(12, 0));
        top.setOpaque(false);
        top.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        top.add(nav, BorderLayout.CENTER);
        top.add(actions, BorderLayout.EAST);

        table.setRowHeight(30);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setBackground(Theme.APP_BG);
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(0).setCellRenderer(new NameRenderer());
        DefaultTableCellRenderer right = new DefaultTableCellRenderer();
        right.setHorizontalAlignment(SwingConstants.RIGHT);
        table.getColumnModel().getColumn(1).setCellRenderer(right);
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelected();
                }
            }
        });
        table.getInputMap().put(javax.swing.KeyStroke.getKeyStroke("ENTER"), "open");
        table.getActionMap().put("open", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                openSelected();
            }
        });
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.DIVIDER));

        status.setForeground(Theme.TEXT_MUTED);
        status.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        status.setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(top, BorderLayout.NORTH);
        root.add(scroll, BorderLayout.CENTER);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        setSize(820, 540);
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
        connect();
    }

    private void connect() {
        setBusy(true, "SFTP 연결 중…");
        Async.run(() -> {
            RemoteFiles f = service.openFiles(server, new HostKeyDialog(this));
            String home = f.home();
            return new Object[] {f, home, f.list(home)};
        }, result -> {
            files = (RemoteFiles) result[0];
            @SuppressWarnings("unchecked")
            List<RemoteFile> list = (List<RemoteFile>) result[2];
            show((String) result[1], list);
        }, err -> {
            setBusy(false, null);
            showError(Async.message(err));
            upload.setEnabled(false);
        });
    }

    private void navigate(String path) {
        if (files == null || busy || path.isEmpty()) {
            return;
        }
        String target = path;
        run("불러오는 중…", () -> files.list(target), list -> show(target, list));
    }

    private void show(String path, List<RemoteFile> list) {
        cwd = path;
        pathField.setText(path);
        model.setFiles(list);
        long dirs = list.stream().filter(RemoteFile::directory).count();
        setBusy(false, "폴더 " + dirs + "개 · 파일 " + (list.size() - dirs) + "개");
        table.requestFocusInWindow();
    }

    private void openSelected() {
        RemoteFile f = selected();
        if (f == null) {
            return;
        }
        if (f.directory()) {
            navigate(f.path());
        } else {
            download();
        }
    }

    private void upload() {
        if (files == null || busy) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(cwd + "에 업로드");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        File local = chooser.getSelectedFile();
        String remote = RemoteFiles.join(cwd, local.getName());
        String dir = cwd;
        run("확인 중…", () -> files.exists(remote), exists -> {
            if (exists && JOptionPane.showConfirmDialog(this, "'" + local.getName() + "'이(가) 이미 있어요. 덮어쓸까요?",
                    "업로드", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
                return;
            }
            long total = Math.max(1, local.length());
            run(local.getName() + " 업로드 중…", () -> {
                files.upload(local.toPath(), remote, done -> progress("업로드", local.getName(), done, total));
                return files.list(dir);
            }, list -> {
                show(dir, list);
                status.setText(local.getName() + " 업로드 완료");
            });
        });
    }

    private void download() {
        RemoteFile f = selected();
        if (files == null || busy || f == null || f.directory()) {
            return;
        }
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle(f.name() + " 저장");
        chooser.setSelectedFile(new File(RemoteFiles.safeLocalName(f.name())));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path local = chooser.getSelectedFile().toPath();
        if (local.toFile().exists() && JOptionPane.showConfirmDialog(this, local.getFileName() + "이(가) 이미 있어요. 덮어쓸까요?",
                "다운로드", JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE) != JOptionPane.OK_OPTION) {
            return;
        }
        long total = Math.max(1, f.size());
        run(f.name() + " 다운로드 중…", () -> {
            files.download(f.path(), local, done -> progress("다운로드", f.name(), done, total));
            return null;
        }, ignored -> setBusy(false, f.name() + " 다운로드 완료 → " + local));
    }

    private void delete() {
        RemoteFile f = selected();
        if (files == null || busy || f == null) {
            return;
        }
        String what = f.directory() ? "폴더(비어 있어야 해요)" : "파일";
        Object[] options = {"삭제", "취소"};
        if (JOptionPane.showOptionDialog(this, "'" + f.path() + "' " + what + "을 서버에서 삭제할까요?\n되돌릴 수 없어요.",
                "삭제", JOptionPane.DEFAULT_OPTION, JOptionPane.WARNING_MESSAGE, null, options, options[1]) != 0) {
            return;
        }
        String dir = cwd;
        run("삭제 중…", () -> {
            files.delete(f);
            return files.list(dir);
        }, list -> {
            show(dir, list);
            status.setText(f.name() + " 삭제함");
        });
    }

    private void progress(String verb, String name, long done, long total) {
        int percent = (int) Math.min(100, done * 100 / total);
        SwingUtilities.invokeLater(() -> status.setText(name + " " + verb + " 중… " + percent + "%"));
    }

    private <T> void run(String message, Callable<T> task, Consumer<T> onSuccess) {
        setBusy(true, message);
        Async.run(task, onSuccess, err -> {
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
        updateButtons();
    }

    private void showError(String message) {
        status.setText(com.infradesk.ui.IpPrivacy.mask(message));
        status.setForeground(Theme.DANGER_TEXT);
    }

    private void updateButtons() {
        RemoteFile f = selected();
        boolean ready = files != null && !busy;
        up.setEnabled(ready && cwd != null && !cwd.equals("/"));
        refresh.setEnabled(ready);
        upload.setEnabled(ready);
        download.setEnabled(ready && f != null && !f.directory());
        delete.setEnabled(ready && f != null);
        pathField.setEnabled(ready);
    }

    private RemoteFile selected() {
        int row = table.getSelectedRow();
        return row < 0 ? null : model.get(table.convertRowIndexToModel(row));
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
