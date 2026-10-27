package com.infradesk.ui.containers;

import com.infradesk.ssh.LogLine;
import com.infradesk.ssh.LogLine.Level;
import com.infradesk.ui.Theme;

import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.JViewport;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.TableCellRenderer;

/**
 * 로그 줄을 "시각 · 수준 · 내용" 세 열로 보여 주는 표 (디자인 v2 컨테이너 탭의 로그 본문).
 *
 * <p>모양(시안): 배경 {@code TERMINAL_BG}, 고정폭 12, 줄 높이 20, 좌우 여백 14, 열 사이 12.
 * 오류 줄은 붉은 배경과 붉은 글자, 경고 줄은 노란 글자, 정보 줄은 기본 글자색이다.
 *
 * <p>표를 쓴 이유: 줄을 선택해서 복사(Ctrl/Cmd+C)하는 기능을 Swing이 그대로 주기 때문이다.
 * 그래서 {@link #getValueAt}는 화면에 보이는 글자를 돌려준다(시각, "정보/경고/오류", 내용).
 * 줄의 수준(색을 정하는 데 필요)은 모델에서 따로 꺼낸다.
 */
final class LogTable extends JTable {

    /** 오류 줄 배경: 시안의 rgba(168, 66, 60, 0.12)를 {@code TERMINAL_BG} 위에 올린 색. */
    static final Color ERROR_ROW_BG = new Color(0x2A1F20);
    /** 정보 줄 글자색과 수준 글자색(시안 #8A8D93에 가장 가까운 기존 색). */
    private static final Color INFO_TEXT = Theme.TEXT;
    private static final Color LEVEL_INFO = Theme.TEXT_MUTED;

    private static final int ROW = 20;
    private static final int PAD_X = 14;
    private static final int GAP = 12;
    /** 수준 열의 글자 폭("정보", "경고", "오류"가 들어가는 칸). */
    private static final int LEVEL_TEXT_WIDTH = 34;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final Renderer renderer = new Renderer();
    private boolean wrap;
    /** 줄바꿈을 끈 상태에서 가장 긴 내용의 폭. 이보다 창이 좁으면 가로로 스크롤한다. */
    private int widest;
    /** 줄바꿈을 켠 상태에서 마지막으로 높이를 계산한 표의 폭. 폭이 바뀔 때만 다시 계산한다. */
    private int measuredWidth = -1;

    LogTable() {
        super(new Model());
        setFont(Theme.monoFont(12f));
        setBackground(Theme.TERMINAL_BG);
        setForeground(INFO_TEXT);
        setRowHeight(ROW);
        setShowGrid(false);
        setIntercellSpacing(new Dimension(0, 0));
        setFillsViewportHeight(true);
        setTableHeader(null);
        setRowSelectionAllowed(true);
        setSelectionMode(ListSelectionModel.MULTIPLE_INTERVAL_SELECTION);
        // 줄바꿈을 끈 상태: 창이 넓으면 마지막 열(내용)이 늘어나고, 좁으면 가로 스크롤이 생긴다.
        setAutoResizeMode(AUTO_RESIZE_LAST_COLUMN);
        for (int i = 0; i < 3; i++) {
            getColumnModel().getColumn(i).setCellRenderer(renderer);
        }
        setColumnWidths();
    }

    /** 보여 줄 줄을 바꾼다. */
    void setLines(List<LogLine> lines) {
        ((Model) getModel()).set(lines);
        FontMetrics fm = getFontMetrics(getFont());
        widest = 0;
        for (LogLine line : lines) {
            widest = Math.max(widest, fm.stringWidth(line.message()));
        }
        setColumnWidths();
        measuredWidth = -1;
        if (wrap) {
            updateRowHeights();
        }
        revalidate();
        repaint();
    }

    int lineCount() {
        return getModel().getRowCount();
    }

    boolean isWrap() {
        return wrap;
    }

    /** 긴 줄을 접어서 보여 줄지(true) 한 줄로 두고 가로 스크롤할지(false). */
    void setWrap(boolean wrap) {
        this.wrap = wrap;
        measuredWidth = -1;
        if (wrap) {
            updateRowHeights();
        } else {
            setRowHeight(ROW);
        }
        revalidate();
        repaint();
    }

    /** 맨 아래 줄이 보이게 스크롤한다. 배치가 끝난 뒤에 해야 해서 한 박자 늦춘다. */
    void scrollToEnd() {
        SwingUtilities.invokeLater(() -> {
            int last = getRowCount() - 1;
            if (last >= 0) {
                scrollRectToVisible(getCellRect(last, 0, true));
            }
        });
    }

    private int timeColumnWidth() {
        return PAD_X + getFontMetrics(getFont()).stringWidth("00:00:00") + GAP;
    }

    private void setColumnWidths() {
        getColumnModel().getColumn(0).setPreferredWidth(timeColumnWidth());
        getColumnModel().getColumn(0).setMinWidth(timeColumnWidth());
        getColumnModel().getColumn(0).setMaxWidth(timeColumnWidth());
        int level = LEVEL_TEXT_WIDTH + GAP;
        getColumnModel().getColumn(1).setPreferredWidth(level);
        getColumnModel().getColumn(1).setMinWidth(level);
        getColumnModel().getColumn(1).setMaxWidth(level);
        getColumnModel().getColumn(2).setPreferredWidth(widest + PAD_X);
    }

    // ── 스크롤 영역 안에서의 폭 ─────────────────────────────────

    /**
     * 줄바꿈을 켜면 항상 창 폭에 맞춘다. 끄면, 가장 긴 줄이 창보다 좁을 때만 창 폭에 맞추고 그렇지 않으면
     * 가장 긴 줄의 폭을 유지해서 가로 스크롤이 생기게 한다.
     */
    @Override
    public boolean getScrollableTracksViewportWidth() {
        return wrap || (getParent() instanceof JViewport v && v.getWidth() > getPreferredSize().width);
    }

    /** 줄바꿈이 켜져 있으면 폭이 바뀔 때(창 크기 조절) 줄마다 필요한 높이를 다시 잰다. */
    @Override
    public void doLayout() {
        super.doLayout();
        if (wrap && getWidth() != measuredWidth && getWidth() > 0) {
            updateRowHeights();
        }
    }

    private void updateRowHeights() {
        measuredWidth = getWidth();
        if (measuredWidth <= 0) {
            return;
        }
        int textWidth = Math.max(1, getColumnModel().getColumn(2).getWidth() - PAD_X);
        JTextArea probe = renderer.area;
        probe.setSize(textWidth, Short.MAX_VALUE);
        for (int row = 0; row < getRowCount(); row++) {
            probe.setText(((Model) getModel()).line(row).message());
            probe.setSize(textWidth, Short.MAX_VALUE);
            setRowHeight(row, Math.max(ROW, probe.getPreferredSize().height));
        }
    }

    // ── 모델 ────────────────────────────────────────────────────

    private static final class Model extends AbstractTableModel {

        private List<LogLine> lines = List.of();

        void set(List<LogLine> lines) {
            this.lines = List.copyOf(lines);
            fireTableDataChanged();
        }

        LogLine line(int row) {
            return lines.get(row);
        }

        @Override
        public int getRowCount() {
            return lines.size();
        }

        @Override
        public int getColumnCount() {
            return 3;
        }

        @Override
        public Object getValueAt(int row, int col) {
            LogLine line = lines.get(row);
            return switch (col) {
                case 0 -> line.time() == null ? "" : TIME.format(line.time());
                case 1 -> line.level().label();
                default -> line.message();
            };
        }
    }

    // ── 셀 그리기 ───────────────────────────────────────────────

    /** 시각과 수준 열은 라벨로, 내용 열은 줄바꿈을 켰을 때만 여러 줄을 그릴 수 있는 글상자로 그린다. */
    private final class Renderer implements TableCellRenderer {

        private final JLabel label = new JLabel();
        final JTextArea area = new JTextArea();

        Renderer() {
            label.setOpaque(true);
            label.setFont(Theme.monoFont(12f));
            area.setOpaque(true);
            area.setLineWrap(true);
            area.setWrapStyleWord(false);
            area.setFont(Theme.monoFont(12f));
            area.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, PAD_X));
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus,
                                                       int row, int col) {
            LogLine line = ((Model) table.getModel()).line(row);
            Color bg = selected ? Theme.SELECTION_BG : line.level() == Level.ERROR ? ERROR_ROW_BG : Theme.TERMINAL_BG;
            Color messageColor = switch (line.level()) {
                case ERROR -> Theme.DANGER_TEXT;
                case WARN -> Theme.WARNING;
                case INFO -> INFO_TEXT;
            };
            String text = String.valueOf(value);

            if (col == 2 && wrap) {
                area.setText(text);
                area.setBackground(bg);
                area.setForeground(messageColor);
                return area;
            }
            label.setText(text);
            label.setBackground(bg);
            switch (col) {
                case 0 -> {
                    label.setForeground(Theme.TEXT_MUTED);
                    label.setBorder(BorderFactory.createEmptyBorder(0, PAD_X, 0, 0));
                }
                case 1 -> {
                    label.setForeground(line.level() == Level.INFO ? LEVEL_INFO : messageColor);
                    // 글자(11)가 시각(12)보다 작아서 가운데 정렬하면 기준선이 위로 뜬다: 위쪽 여백으로 살짝 내린다.
                    label.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
                    label.setFont(Theme.monoFont(11f));
                }
                default -> {
                    label.setForeground(messageColor);
                    label.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, PAD_X));
                }
            }
            if (col != 1) {
                label.setFont(Theme.monoFont(12f));
            }
            return label;
        }
    }
}
