package com.infradesk.ui;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/**
 * Dev tool: renders the main window off-screen to a PNG so UI changes can be checked without
 * screen-recording permission. Run with {@code ./gradlew snapshot}.
 */
public final class UiSnapshot {

    private UiSnapshot() {
    }

    public static void main(String[] args) throws InterruptedException, InvocationTargetException, java.io.IOException {
        File out = new File(args.length > 0 ? args[0] : "build/snapshots/main.png");
        out.getParentFile().mkdirs();
        BufferedImage[] image = new BufferedImage[1];
        SwingUtilities.invokeAndWait(() -> {
            Theme.install();
            JFrame frame = new MainFrame();
            frame.addNotify();
            frame.getContentPane().setSize(1280, 800);
            frame.getContentPane().validate();
            layoutAll(frame.getContentPane());
            BufferedImage img = new BufferedImage(1280 * 2, 800 * 2, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = img.createGraphics();
            g.scale(2, 2);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            frame.getContentPane().paint(g);
            g.dispose();
            image[0] = img;
            frame.dispose();
        });
        ImageIO.write(image[0], "png", out);
        System.out.println("Snapshot written to " + out.getAbsolutePath());
        System.exit(0);
    }

    private static void layoutAll(java.awt.Container c) {
        c.doLayout();
        for (java.awt.Component child : c.getComponents()) {
            if (child instanceof java.awt.Container cc) {
                layoutAll(cc);
            }
        }
    }
}
