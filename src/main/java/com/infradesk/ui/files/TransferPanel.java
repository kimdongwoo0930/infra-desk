package com.infradesk.ui.files;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.TransferQueue;
import com.infradesk.ssh.TransferQueue.Job;
import com.infradesk.ssh.TransferQueue.State;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/** SFTP 창 아래의 전송 대기열: 작업마다 진행률과 상태를 보여주고 취소·다시 시도·지우기를 한다. */
final class TransferPanel extends JPanel {

    private final TransferQueue queue;
    private final JobTableModel model = new JobTableModel();
    private final JTable table = new JTable(model);
    private final JButton cancel = Buttons.secondary("취소", "x");
    private final JButton retry = Buttons.secondary("다시 시도", "refresh");
    private final JButton clear = Buttons.secondary("지우기", "trash");
    private final JButton clearDone = Buttons.ghost("끝난 항목 모두 지우기");
    private final JButton close = Buttons.icon("x", "닫기", 28);
    private final Runnable onChange;

    /**
     * @param onChange 지우기로 목록이 바뀌었을 때(EDT). 목록이 비었으면 창이 이 패널을 접는다
     * @param onClose  X로 닫았을 때(EDT). 목록과 진행 중인 전송은 그대로 둔다
     */
    TransferPanel(TransferQueue queue, Runnable onChange, Runnable onClose) {
        super(new BorderLayout(0, 6));
        this.queue = queue;
        this.onChange = onChange;
        setOpaque(false);
        setBorder(BorderFactory.createEmptyBorder(8, 12, 8, 12));

        JLabel title = new JLabel("전송");
        title.putClientProperty(FlatClientProperties.STYLE, "font: bold +0");
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(cancel);
        buttons.add(retry);
        buttons.add(clear);
        buttons.add(clearDone);
        buttons.add(close);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.add(title, BorderLayout.WEST);
        head.add(buttons, BorderLayout.EAST);

        table.setRowHeight(26);
        table.setShowGrid(false);
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setBackground(Theme.APP_BG);
        table.getColumnModel().getColumn(0).setPreferredWidth(300);
        table.getColumnModel().getColumn(0).setCellRenderer(new ItemRenderer());
        table.getColumnModel().getColumn(1).setPreferredWidth(220);
        table.getColumnModel().getColumn(1).setCellRenderer(new ProgressRenderer());
        table.getColumnModel().getColumn(2).setPreferredWidth(420);
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        table.getSelectionModel().addListSelectionListener(e -> updateButtons());
        JScrollPane scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 1, 0, Theme.DIVIDER));
        scroll.getViewport().setBackground(Theme.APP_BG);

        cancel.addActionListener(e -> selectedJobs().forEach(queue::cancel));
        retry.addActionListener(e -> selectedJobs().forEach(queue::retry));
        clear.addActionListener(e -> {
            selectedJobs().forEach(queue::remove);
            changed();
        });
        clearDone.addActionListener(e -> {
            removeFinished();
            changed();
        });
        // 닫기는 패널만 접는다. 목록과 진행 중인 전송은 그대로이고, 아래 "전송 목록 보기"로 다시 연다.
        close.addActionListener(e -> onClose.run());

        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        updateButtons();
    }

    private void removeFinished() {
        for (Job j : queue.jobs()) {
            if (j.state().finished()) {
                queue.remove(j);
            }
        }
    }

    private void changed() {
        refresh();
        onChange.run();
    }

    boolean isEmpty() {
        return queue.jobs().isEmpty();
    }

    /** 끝나지 않은(대기 중이거나 전송 중인) 작업 수. */
    int activeCount() {
        int n = 0;
        for (Job j : queue.jobs()) {
            if (!j.state().finished()) {
                n++;
            }
        }
        return n;
    }

    /** 대기열이 바뀌었을 때(EDT에서). 선택은 유지한다. */
    void refresh() {
        Set<Long> selected = new HashSet<>();
        for (Job j : selectedJobs()) {
            selected.add(j.id());
        }
        boolean sameRows = model.update(queue.jobs());
        if (!sameRows) {
            table.clearSelection();
            for (int i = 0; i < model.getRowCount(); i++) {
                if (selected.contains(model.get(i).id())) {
                    table.addRowSelectionInterval(i, i);
                }
            }
        }
        updateButtons();
    }

    private List<Job> selectedJobs() {
        List<Job> result = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            result.add(model.get(table.convertRowIndexToModel(row)));
        }
        return result;
    }

    private void updateButtons() {
        List<Job> selected = selectedJobs();
        cancel.setEnabled(selected.stream().anyMatch(j -> !j.state().finished()));
        retry.setEnabled(selected.stream().anyMatch(j -> j.state() == State.FAILED || j.state() == State.CANCELLED));
        clear.setEnabled(selected.stream().anyMatch(j -> j.state().finished()));
        clearDone.setEnabled(queue.jobs().stream().anyMatch(j -> j.state().finished()));
    }

    static String statusText(Job job) {
        return switch (job.state()) {
            case QUEUED -> "대기 중";
            case RUNNING -> job.totalBytes() < 0 ? "파일 목록을 읽는 중…"
                    : FilePane.size(job.doneBytes()) + " / " + FilePane.size(job.totalBytes());
            case DONE, FAILED, CANCELLED -> job.message();
        };
    }

    private static final class JobTableModel extends AbstractTableModel {
        private List<Job> jobs = List.of();

        /** @return 행이 그대로(같은 작업, 같은 순서)면 true. 이때는 선택이 유지된다 */
        boolean update(List<Job> next) {
            boolean same = next.size() == jobs.size();
            for (int i = 0; same && i < next.size(); i++) {
                same = next.get(i).id() == jobs.get(i).id();
            }
            jobs = next;
            if (same) {
                if (!jobs.isEmpty()) {
                    fireTableRowsUpdated(0, jobs.size() - 1);
                }
            } else {
                fireTableDataChanged();
            }
            return same;
        }

        Job get(int row) {
            return jobs.get(row);
        }

        @Override
        public int getRowCount() {
            return jobs.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "항목";
                case 1 -> "진행";
                default -> "상태";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            return jobs.get(row);
        }
    }

    private static final class ProgressRenderer implements TableCellRenderer {
        private final JProgressBar bar = new JProgressBar(0, 100);

        ProgressRenderer() {
            bar.setStringPainted(true);
            bar.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            bar.setIndeterminate(job.state() == State.RUNNING && job.totalBytes() < 0);
            bar.setValue(job.percent());
            bar.setString(job.state() == State.QUEUED ? "" : job.percent() + "%");
            bar.setForeground(job.state() == State.FAILED ? Theme.DANGER_TEXT : Theme.ACCENT);
            return bar;
        }
    }

    private static final class ItemRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            super.getTableCellRendererComponent(t, itemText(job), sel, focus, row, col);
            setToolTipText(IpPrivacy.mask(job.source()) + " → " + IpPrivacy.mask(job.targetDir()));
            setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
            return this;
        }
    }

    private static final class StatusRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            String text = IpPrivacy.mask(statusText(job));
            super.getTableCellRendererComponent(t, text, sel, focus, row, col);
            setToolTipText(text);
            setForeground(sel ? t.getSelectionForeground()
                    : job.state() == State.FAILED ? Theme.DANGER_TEXT : Theme.TEXT_MUTED);
            return this;
        }
    }

    /** 첫 열: 방향 화살표와 이름(원본 경로는 툴팁). */
    static String itemText(Job job) {
        return (job.direction() == TransferQueue.Direction.UPLOAD ? "↑ " : "↓ ") + job.name();
    }
}
