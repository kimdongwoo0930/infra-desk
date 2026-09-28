package com.infradesk.ui;

import java.awt.BorderLayout;
import java.awt.Window;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.KeyStroke;

/** Shows InfraDesk's MIT license and the generated third-party notices. */
final class LicensesDialog extends JDialog {

    LicensesDialog(Window owner) {
        super(owner, "오픈소스 라이선스", ModalityType.APPLICATION_MODAL);
        JTextArea text = new JTextArea("InfraDesk — MIT License\n\n" + resource("LICENSE.txt")
                + "\n\n────────────────────────────────────────\n\n" + resource("THIRD-PARTY-NOTICES.txt"));
        text.setEditable(false);
        text.setFont(Theme.monoFont(12f));
        text.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        text.setCaretPosition(0);
        JScrollPane scroll = new JScrollPane(text);
        scroll.setBorder(null);
        getContentPane().add(scroll, BorderLayout.CENTER);
        getRootPane().registerKeyboardAction(e -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        setSize(760, 560);
        setLocationRelativeTo(owner);
    }

    private static String resource(String name) {
        try (InputStream in = com.infradesk.app.BuildInfo.class.getResourceAsStream(name)) {
            return in == null ? "(" + name + " 없음 — 빌드에서 생성돼요)" : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "(" + name + "을 읽지 못했어요)";
        }
    }
}
