package com.infradesk.app;

import com.formdev.flatlaf.util.SystemInfo;
import com.infradesk.ui.MainFrame;
import com.infradesk.ui.Theme;

import javax.swing.SwingUtilities;

/** Application entry point. */
public final class InfraDeskApp {

    private InfraDeskApp() {
    }

    public static void main(String[] args) {
        if (SystemInfo.isMacOS) {
            System.setProperty("apple.awt.application.name", "InfraDesk");
            System.setProperty("apple.awt.application.appearance", "NSAppearanceNameDarkAqua");
            System.setProperty("apple.laf.useScreenMenuBar", "true");
        }
        SwingUtilities.invokeLater(() -> {
            Theme.install();
            new MainFrame().setVisible(true);
        });
    }
}
