package com.infradesk.ui.files;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.ssh.TransferQueue;
import com.infradesk.ssh.TransferQueue.Direction;
import com.infradesk.ssh.TransferQueue.Job;
import com.infradesk.ssh.TransferQueue.State;
import com.infradesk.ui.Icons;
import com.infradesk.ui.IpPrivacy;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.JTableHeader;
import javax.swing.table.TableCellRenderer;

/**
 * 파일 탭 아래의 "전송" 목록 (디자인 v2): 머리에 개수 요약과 [끝난 항목 지우기]·접기, 행마다 방향 아이콘, 이름과
 * 보내는 곳·받은 양·속도, 진행 막대, 상태 글자, [취소]/[다시 시도].
 *
 * <p>전송 목록은 큐({@link TransferQueue})의 것을 그대로 보여 줄 뿐이다. 접으면 머리 줄만 남고 목록과 진행 중인
 * 전송은 그대로다. 새 전송이 시작되거나 실패가 생기면 {@link FilesTab}이 다시 펼친다.
 */
public final class TransferPanel extends JPanel {

    /** 행 높이와, 목록에 한꺼번에 보여 주는 행 수(넘으면 스크롤). */
    private static final int ROW_HEIGHT = 52;
    private static final int MAX_VISIBLE_ROWS = 4;
    private static final int HEAD_HEIGHT = 46;
    /** 행 끝 버튼 칸. */
    private static final int ACTION_COLUMN = 3;
    private static final int ACTION_BUTTON_WIDTH = 92;
    private static final int ACTION_BUTTON_HEIGHT = 32;
    private static final int ACTION_RIGHT_PAD = 28;

    private final TransferQueue queue;
    private final JobTableModel model = new JobTableModel();
    private final JTable table = new JTable(model);
    private final TransferRate rate = new TransferRate();
    private final JLabel summary = new JLabel(" ");
    private final JButton clearDone = Buttons.ghost("끝난 항목 지우기");
    private final JButton fold = Buttons.icon("chevron-down", "접기", 28);
    private final JScrollPane scroll;
    private final Runnable onChange;
    private boolean expanded = true;

    /**
     * @param onChange 지우기로 목록이 바뀌었을 때(EDT). 목록이 비었으면 탭이 이 패널을 숨긴다
     */
    TransferPanel(TransferQueue queue, Runnable onChange) {
        super(new BorderLayout());
        this.queue = queue;
        this.onChange = onChange;
        setBackground(Theme.APP_BG);
        setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));

        JLabel title = new JLabel("전송");
        title.putClientProperty(FlatClientProperties.STYLE, "font: bold +1");
        summary.setForeground(Theme.TEXT_MUTED);
        // GridBagLayout은 한 줄로 놓으면서 세로 가운데에 맞춘다(FlowLayout은 위쪽에 붙는다).
        JPanel left = new JPanel(new java.awt.GridBagLayout());
        left.setOpaque(false);
        left.add(title, new java.awt.GridBagConstraints());
        java.awt.GridBagConstraints gap = new java.awt.GridBagConstraints();
        gap.insets = new java.awt.Insets(0, 12, 0, 0);
        left.add(summary, gap);
        JPanel right = new JPanel(new java.awt.GridBagLayout());
        right.setOpaque(false);
        right.add(clearDone, new java.awt.GridBagConstraints());
        java.awt.GridBagConstraints foldGap = new java.awt.GridBagConstraints();
        foldGap.insets = new java.awt.Insets(0, 8, 0, 0);
        right.add(fold, foldGap);
        JPanel head = new JPanel(new BorderLayout());
        head.setOpaque(false);
        head.setBorder(BorderFactory.createEmptyBorder(0, 28, 0, 20));
        head.setPreferredSize(new Dimension(0, HEAD_HEIGHT));
        head.add(left, BorderLayout.WEST);
        head.add(right, BorderLayout.EAST);

        table.setRowHeight(ROW_HEIGHT);
        table.setShowGrid(false);
        table.setShowHorizontalLines(true);   // 행 사이 가는 선
        table.setGridColor(Theme.DIVIDER);
        table.setIntercellSpacing(new Dimension(0, 1));
        table.setFillsViewportHeight(true);
        table.setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        table.setBackground(Theme.APP_BG);
        JTableHeader header = table.getTableHeader();
        header.setVisible(false);
        header.setPreferredSize(new Dimension(0, 0));
        table.getColumnModel().getColumn(0).setPreferredWidth(360);
        table.getColumnModel().getColumn(0).setCellRenderer(new ItemRenderer());
        table.getColumnModel().getColumn(1).setPreferredWidth(300);
        table.getColumnModel().getColumn(1).setCellRenderer(new BarRenderer());
        table.getColumnModel().getColumn(2).setPreferredWidth(240);
        table.getColumnModel().getColumn(2).setCellRenderer(new StatusRenderer());
        table.getColumnModel().getColumn(ACTION_COLUMN).setPreferredWidth(150);
        table.getColumnModel().getColumn(ACTION_COLUMN).setMaxWidth(190);
        table.getColumnModel().getColumn(ACTION_COLUMN).setCellRenderer(new ActionRenderer());
        scroll = new JScrollPane(table);
        scroll.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));
        scroll.getViewport().setBackground(Theme.APP_BG);

        clearDone.addActionListener(e -> {
            removeFinished();
            changed();
        });
        // 접으면 머리 줄만 남는다. 목록과 진행 중인 전송은 그대로이고, 다시 누르면 펼친다.
        fold.addActionListener(e -> setExpanded(!expanded));
        // 끝난 행 하나만 지우고 싶을 때: 행을 고르고 Delete. 진행 중인 행은 지우지 않는다.
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_DELETE, 0), "remove-finished");
        table.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_BACK_SPACE, 0), "remove-finished");
        table.getActionMap().put("remove-finished", new javax.swing.AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                selectedJobs().stream().filter(j -> j.state().finished()).forEach(queue::remove);
                changed();
            }
        });
        installRowActions();

        add(head, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
        updateView();
    }

    // ── 행 끝 버튼 ──────────────────────────────────────────────

    /** 행 끝 버튼이 하는 일. 끝난 행에는 버튼이 없다. */
    enum RowAction {
        CANCEL("취소"), RETRY("다시 시도");

        final String label;

        RowAction(String label) {
            this.label = label;
        }
    }

    /** 이 상태의 행에 놓는 버튼. 없으면 null. */
    static RowAction actionFor(State state) {
        return switch (state) {
            case QUEUED, RUNNING -> RowAction.CANCEL;
            case FAILED, CANCELLED -> RowAction.RETRY;
            case DONE -> null;
        };
    }

    /** 칸 안에서 버튼이 차지하는 영역(오른쪽 끝에 붙이고 세로로 가운데). */
    static java.awt.Rectangle buttonBounds(int cellWidth, int cellHeight) {
        return new java.awt.Rectangle(cellWidth - ACTION_RIGHT_PAD - ACTION_BUTTON_WIDTH,
                (cellHeight - ACTION_BUTTON_HEIGHT) / 2, ACTION_BUTTON_WIDTH, ACTION_BUTTON_HEIGHT);
    }

    private void installRowActions() {
        table.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                Job job = jobAt(e);
                if (job == null) {
                    return;
                }
                RowAction action = actionFor(job.state());
                if (action != null && overButton(e)) {
                    if (action == RowAction.CANCEL) {
                        queue.cancel(job);
                    } else {
                        queue.retry(job);
                    }
                }
            }
        });
        table.addMouseMotionListener(new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent e) {
                Job job = jobAt(e);
                boolean hand = job != null && actionFor(job.state()) != null && overButton(e);
                table.setCursor(Cursor.getPredefinedCursor(hand ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            }
        });
    }

    /** 눌린 위치의 행. 버튼 칸이 아니거나 빈 곳이면 null. */
    private Job jobAt(MouseEvent e) {
        int row = table.rowAtPoint(e.getPoint());
        int column = table.columnAtPoint(e.getPoint());
        if (row < 0 || column != ACTION_COLUMN) {
            return null;
        }
        return model.get(table.convertRowIndexToModel(row));
    }

    private boolean overButton(MouseEvent e) {
        java.awt.Rectangle cell = table.getCellRect(table.rowAtPoint(e.getPoint()), ACTION_COLUMN, false);
        return buttonBounds(cell.width, cell.height).contains(e.getX() - cell.x, e.getY() - cell.y);
    }

    // ── 목록 ────────────────────────────────────────────────────

    private void removeFinished() {
        for (Job j : queue.jobs()) {
            if (j.state().finished()) {
                queue.remove(j);
                rate.forget(j.id());
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

    /** 목록이 펼쳐져 있는지(접으면 머리 줄만 보인다). */
    public boolean isExpanded() {
        return expanded;
    }

    void setExpanded(boolean expanded) {
        if (this.expanded == expanded) {
            return;
        }
        this.expanded = expanded;
        fold.setIcon(Icons.get(expanded ? "chevron-down" : "chevron-up", 16));
        fold.setToolTipText(expanded ? "접기" : "펼치기");
        updateView();
        revalidate();
        repaint();
        onChange.run();
    }

    /** 대기열이 바뀌었을 때(EDT에서). 선택은 유지한다. */
    void refresh() {
        Set<Long> selected = new HashSet<>();
        for (Job j : selectedJobs()) {
            selected.add(j.id());
        }
        long now = System.nanoTime();
        List<Job> jobs = queue.jobs();
        for (Job j : jobs) {
            if (j.state() == State.RUNNING && j.totalBytes() >= 0) {
                speedOf.put(j.id(), rate.update(j.id(), j.doneBytes(), now));
            }
        }
        boolean sameRows = model.update(jobs);
        if (!sameRows) {
            table.clearSelection();
            for (int i = 0; i < model.getRowCount(); i++) {
                if (selected.contains(model.get(i).id())) {
                    table.addRowSelectionInterval(i, i);
                }
            }
        }
        updateView();
        revalidate();
    }

    /** 작업마다 마지막으로 어림한 속도(바이트/초). 렌더러가 읽는다. */
    private final java.util.Map<Long, Double> speedOf = new java.util.HashMap<>();

    private List<Job> selectedJobs() {
        List<Job> result = new ArrayList<>();
        for (int row : table.getSelectedRows()) {
            result.add(model.get(table.convertRowIndexToModel(row)));
        }
        return result;
    }

    private void updateView() {
        List<Job> jobs = queue.jobs();
        long finished = jobs.stream().filter(j -> j.state().finished()).count();
        summary.setText(summaryText(jobs.size() - (int) finished, (int) finished));
        clearDone.setEnabled(finished > 0);
        scroll.setVisible(expanded);
    }

    /** 머리의 개수 요약: "1개 진행 중 · 2개 끝남". 없는 쪽은 적지 않는다. */
    static String summaryText(int active, int finished) {
        if (active == 0 && finished == 0) {
            return " ";
        }
        if (finished == 0) {
            return active + "개 진행 중";
        }
        return active == 0 ? finished + "개 끝남" : active + "개 진행 중 · " + finished + "개 끝남";
    }

    @Override
    public Dimension getPreferredSize() {
        int rows = Math.min(Math.max(model.getRowCount(), 1), MAX_VISIBLE_ROWS);
        int height = 1 + HEAD_HEIGHT + (expanded ? 1 + rows * ROW_HEIGHT : 0);
        return new Dimension(super.getPreferredSize().width, height);
    }

    // ── 문구 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    /**
     * 이름 아래 줄: 어디서 어디로, 얼마나 받았는지, 속도.
     * 예) {@code 서버 → ~/Downloads · 30.4 MB / 48.2 MB · 12.4 MB/s}
     */
    static String detailText(Direction direction, String source, String targetDir, long done, long total,
            double speed, boolean running) {
        String route = direction == Direction.DOWNLOAD ? "서버 → " + targetDir : parentOf(source) + " → " + targetDir;
        StringBuilder text = new StringBuilder(route);
        if (total >= 0) {
            text.append(" · ");
            if (running) {
                text.append(FilePane.size(done)).append(" / ");
            }
            text.append(FilePane.size(total));
        }
        if (running && speed > 0) {
            text.append(" · ").append(FilePane.size(Math.round(speed))).append("/s");
        }
        return text.toString();
    }

    private static final String HOME = System.getProperty("user.home", "");

    /** 이 컴퓨터의 홈 폴더 아래 경로는 앞을 "~"로 줄여 보여 준다(서버 경로에는 쓰지 않는다). */
    static String tilde(String path, String home) {
        if (home == null || home.isEmpty() || path == null) {
            return path;
        }
        if (path.equals(home)) {
            return "~";
        }
        String prefix = home.endsWith("/") || home.endsWith("\\") ? home : home + java.io.File.separator;
        return path.startsWith(prefix) ? "~/" + path.substring(prefix.length()).replace('\\', '/') : path;
    }

    private static String parentOf(String path) {
        int slash = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        return slash > 0 ? path.substring(0, slash) : path;
    }

    /** 오른쪽 상태 글자. 진행 중이면 "63% · 2초 남음", 끝나면 "끝남 · …", 실패하면 "실패 · …". */
    static String statusText(State state, String message, int percent, String remaining, boolean sizeKnown) {
        return switch (state) {
            case QUEUED -> "대기 중";
            case RUNNING -> !sizeKnown ? "파일 목록을 읽는 중…"
                    : remaining.isEmpty() ? percent + "%" : percent + "% · " + remaining;
            case DONE -> message.startsWith("완료") ? "끝남" + message.substring("완료".length()) : message;
            case FAILED -> "실패 · " + message;
            case CANCELLED -> message;
        };
    }

    static String statusText(Job job, double speed) {
        String remaining = job.totalBytes() < 0 ? "" : TransferRate.remaining(job.totalBytes() - job.doneBytes(), speed);
        return statusText(job.state(), job.message(), job.percent(), remaining, job.totalBytes() >= 0);
    }

    // ── 표 ──────────────────────────────────────────────────────

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
            return 4;
        }

        @Override
        public String getColumnName(int column) {
            return switch (column) {
                case 0 -> "항목";
                case 1 -> "진행";
                case 2 -> "상태";
                default -> "";
            };
        }

        @Override
        public Object getValueAt(int row, int column) {
            return jobs.get(row);
        }
    }

    /** 첫 열: 방향 아이콘, 이름(고정폭), 그 아래 흐린 글자로 어디서 어디로. */
    private final class ItemRenderer implements TableCellRenderer {
        private final JPanel cell = new JPanel(new BorderLayout(14, 0));
        private final JLabel icon = new JLabel();
        private final JLabel name = new JLabel();
        private final JLabel detail = new JLabel();

        ItemRenderer() {
            cell.setOpaque(true);
            cell.setBorder(BorderFactory.createEmptyBorder(0, 28, 0, 8));
            name.setFont(Theme.monoFont(13f));
            detail.setForeground(Theme.TEXT_MUTED);
            detail.putClientProperty(FlatClientProperties.STYLE, "font: -1");
            JPanel lines = new JPanel();
            lines.setOpaque(false);
            lines.setLayout(new BoxLayout(lines, BoxLayout.Y_AXIS));
            lines.add(Box.createVerticalGlue());
            lines.add(name);
            lines.add(Box.createVerticalStrut(2));
            lines.add(detail);
            lines.add(Box.createVerticalGlue());
            icon.setPreferredSize(new Dimension(18, 18));
            cell.add(icon, BorderLayout.WEST);
            cell.add(lines, BorderLayout.CENTER);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            cell.setBackground(sel ? t.getSelectionBackground() : Theme.APP_BG);
            icon.setIcon(Icons.get(job.direction() == Direction.UPLOAD ? "upload" : "download", 16, Theme.TEXT_MUTED));
            name.setText(job.name());
            name.setForeground(sel ? t.getSelectionForeground() : Theme.TEXT);
            String from = job.direction() == Direction.UPLOAD ? tilde(job.source(), HOME) : job.source();
            String to = job.direction() == Direction.DOWNLOAD ? tilde(job.targetDir(), HOME) : job.targetDir();
            String detailText = IpPrivacy.mask(detailText(job.direction(), from, to,
                    job.doneBytes(), job.totalBytes(), speedOf.getOrDefault(job.id(), 0.0), job.state() == State.RUNNING));
            detail.setText(detailText);
            cell.setToolTipText(IpPrivacy.mask(job.source()) + " → " + IpPrivacy.mask(job.targetDir()));
            return cell;
        }
    }

    /** 둘째 열: 얇은 둥근 진행 막대. 끝나면 초록, 실패하면 빨강, 진행 중이면 파랑. */
    private static final class BarRenderer extends JComponent implements TableCellRenderer {
        private static final int BAR_HEIGHT = 6;
        private double fraction;
        private Color fill = Theme.ACCENT;
        private boolean indeterminate;

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            indeterminate = job.state() == State.RUNNING && job.totalBytes() < 0;
            fraction = job.state() == State.DONE ? 1 : job.percent() / 100.0;
            fill = switch (job.state()) {
                case DONE -> Theme.RUNNING_DOT;
                case FAILED -> Theme.DANGER_TEXT;
                case CANCELLED -> Theme.TEXT_MUTED;
                default -> Theme.ACCENT;
            };
            return this;
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int w = getWidth() - 24;
            int y = (getHeight() - BAR_HEIGHT) / 2;
            g2.setColor(Theme.DIVIDER);
            g2.fillRoundRect(12, y, w, BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
            g2.setColor(fill);
            // 전체 크기를 모를 때는 일정한 길이의 막대만 보여 준다.
            int filled = indeterminate ? w / 5 : (int) Math.round(w * Math.max(0, Math.min(1, fraction)));
            if (filled > 0) {
                g2.fillRoundRect(12, y, Math.max(filled, BAR_HEIGHT), BAR_HEIGHT, BAR_HEIGHT, BAR_HEIGHT);
            }
            g2.dispose();
        }
    }

    /** 셋째 열: 진행·끝남·실패 글자. 오른쪽에 붙이고 색으로 구분한다. */
    private final class StatusRenderer implements TableCellRenderer {
        private final JLabel label = new JLabel();

        StatusRenderer() {
            label.setOpaque(true);
            label.setHorizontalAlignment(SwingConstants.LEFT);
            label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            String text = IpPrivacy.mask(statusText(job, speedOf.getOrDefault(job.id(), 0.0)));
            label.setText(text);
            label.setToolTipText(text);
            label.setBackground(sel ? t.getSelectionBackground() : Theme.APP_BG);
            label.setForeground(switch (job.state()) {
                case DONE -> Theme.RUNNING_BADGE_TEXT;
                case FAILED -> Theme.DANGER_TEXT;
                case CANCELLED -> Theme.TEXT_MUTED;
                default -> Theme.TEXT;
            });
            return label;
        }
    }

    /** 넷째 열: [취소] 또는 [다시 시도] 버튼 모양. 눌림은 표의 마우스 처리가 위치로 판단한다. */
    private static final class ActionRenderer implements TableCellRenderer {
        private final JPanel cell = new JPanel(null);
        private final JButton cancel = Buttons.secondary(RowAction.CANCEL.label, null);
        private final JButton retry = Buttons.secondary(RowAction.RETRY.label, null);

        ActionRenderer() {
            cell.setOpaque(true);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean sel, boolean focus, int row, int col) {
            Job job = (Job) value;
            cell.setBackground(sel ? t.getSelectionBackground() : Theme.APP_BG);
            cell.removeAll();
            RowAction action = actionFor(job.state());
            if (action != null) {
                JButton button = action == RowAction.CANCEL ? cancel : retry;
                // 칸 폭은 렌더러가 그려지는 때에 정해지므로 표가 알려 주는 열 폭으로 놓는다.
                int width = t.getColumnModel().getColumn(col).getWidth();
                button.setBounds(buttonBounds(width, t.getRowHeight()));
                cell.add(button);
            }
            return cell;
        }
    }
}
