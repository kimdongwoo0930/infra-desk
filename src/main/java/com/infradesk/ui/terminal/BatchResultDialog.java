package com.infradesk.ui.terminal;

import com.formdev.flatlaf.FlatClientProperties;
import com.infradesk.core.Server;
import com.infradesk.ssh.ExecResult;
import com.infradesk.ui.Theme;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.DefaultListCellRenderer;
import javax.swing.DefaultListModel;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JSplitPane;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;

/** Shows per-server results of a batch command as they arrive. Non-modal. */
final class BatchResultDialog extends JDialog {

    private record Row(Server server, ExecResult result) {
    }

    private final DefaultListModel<Row> model = new DefaultListModel<>();
    private final JList<Row> list = new JList<>(model);
    private final JTextArea output = new JTextArea();
    private final JLabel summary = new JLabel();
    private final Map<String, Integer> index = new LinkedHashMap<>();

    BatchResultDialog(Window owner, String command, List<Server> servers) {
        super(owner, "일괄 실행 결과", ModalityType.MODELESS);
        for (Server s : servers) {
            index.put(s.id(), model.size());
            model.addElement(new Row(s, null));
        }

        JLabel cmd = new JLabel("$ " + command);
        cmd.setFont(Theme.monoFont(12.5f));
        cmd.setForeground(Theme.TEXT);
        summary.setForeground(Theme.TEXT_MUTED);
        summary.putClientProperty(FlatClientProperties.STYLE, "font: -1");
        JPanel header = new JPanel(new BorderLayout(0, 4));
        header.setBorder(BorderFactory.createEmptyBorder(14, 16, 10, 16));
        header.setOpaque(false);
        header.add(cmd, BorderLayout.NORTH);
        header.add(summary, BorderLayout.SOUTH);

        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setCellRenderer(new RowRenderer());
        list.addListSelectionListener(e -> showOutput());
        list.setFixedCellHeight(40);
        output.setEditable(false);
        output.setFont(Theme.monoFont(12.5f));
        output.setBackground(Theme.TERMINAL_BG);
        output.setForeground(new Color(0xC9CCD1));
        output.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));

        JScrollPane left = new JScrollPane(list);
        left.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 1, Theme.DIVIDER));
        JScrollPane right = new JScrollPane(output);
        right.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Theme.DIVIDER));
        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        split.setDividerLocation(230);
        split.setBorder(null);

        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.PANEL_BG);
        root.add(header, BorderLayout.NORTH);
        root.add(split, BorderLayout.CENTER);
        setContentPane(root);
        setSize(new Dimension(820, 460));
        setLocationRelativeTo(owner);
        updateSummary();
        list.setSelectedIndex(0);
    }

    /** Call on the EDT when one server finishes. */
    void setResult(Server server, ExecResult result) {
        Integer i = index.get(server.id());
        if (i == null) {
            return;
        }
        model.set(i, new Row(server, result));
        updateSummary();
        if (list.getSelectedIndex() == i) {
            showOutput();
        }
    }

    private void updateSummary() {
        int done = 0;
        int ok = 0;
        for (int i = 0; i < model.size(); i++) {
            ExecResult r = model.get(i).result();
            if (r != null) {
                done++;
                if (r.succeeded()) {
                    ok++;
                }
            }
        }
        int total = model.size();
        summary.setText(done < total
                ? total + "대 중 " + done + "대 완료 · 실행 중…"
                : total + "대 완료 · 성공 " + ok + " · 실패 " + (total - ok));
    }

    private void showOutput() {
        Row row = list.getSelectedValue();
        if (row == null) {
            output.setText("");
            return;
        }
        ExecResult r = row.result();
        if (r == null) {
            output.setText("실행 중…");
        } else {
            StringBuilder sb = new StringBuilder(r.output());
            if (r.truncated()) {
                sb.append("\n… (출력이 길어 뒷부분을 생략했어요)");
            }
            if (r.error() != null) {
                sb.append(sb.isEmpty() ? "" : "\n\n").append("⚠ ").append(com.infradesk.ui.IpPrivacy.mask(r.error()));
            }
            output.setText(sb.isEmpty() ? "(출력 없음)" : sb.toString());
        }
        output.setCaretPosition(0);
    }

    private static final class RowRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> l, Object value, int i, boolean sel, boolean focus) {
            Row row = (Row) value;
            ExecResult r = row.result();
            String state = r == null ? "실행 중…"
                    : r.error() != null ? "실패 · " + com.infradesk.ui.IpPrivacy.mask(r.error())
                    : r.exitCode() == 0 ? "성공" : "종료 코드 " + r.exitCode();
            super.getListCellRendererComponent(l, "<html>" + escape(row.server().name())
                    + "<br><span style='font-size:90%'>" + escape(state) + "</span></html>", i, sel, focus);
            setIcon(new StatusMark(r));
            setIconTextGap(10);
            setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 8));
            return this;
        }

        private static String escape(String s) {
            return s.replace("&", "&amp;").replace("<", "&lt;");
        }
    }

    /** Status dot + text in the row: green success, red failure, amber running. Never color alone. */
    private record StatusMark(ExecResult result) implements javax.swing.Icon {
        @Override
        public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
            var g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING, java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(result == null ? Theme.WARNING : result.succeeded() ? Theme.RUNNING_DOT : Theme.DANGER_TEXT);
            g2.fillOval(x, y, 8, 8);
            g2.dispose();
        }

        @Override
        public int getIconWidth() {
            return 8;
        }

        @Override
        public int getIconHeight() {
            return 8;
        }
    }
}
