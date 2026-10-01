package com.infradesk.tools;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.GradientPaint;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * 앱 아이콘을 그리고 패키징 에셋을 쓴다. 개발 도구: {@code ./gradlew generateIcons}.
 *
 * <ul>
 *   <li>src/packaging/macos/InfraDesk.iconset/*.png (iconutil이 InfraDesk.icns로 변환)</li>
 *   <li>src/packaging/windows/InfraDesk.ico (PNG 압축 항목)</li>
 *   <li>src/main/resources/com/infradesk/app/icon-{64,128,256,512}.png (실행 중 Dock / 창 아이콘)</li>
 * </ul>
 */
public final class IconGenerator {

    private static final Color BG_TOP = new Color(0x34373C);
    private static final Color BG_BOTTOM = new Color(0x1A1B1E);
    private static final Color RACK = new Color(0xDFE1E5);
    private static final Color RACK_FILL = new Color(0x2B2D30);
    private static final Color GREEN = new Color(0x5FB865);
    private static final Color BLUE = new Color(0x3B73E0);

    private IconGenerator() {
    }

    public static void main(String[] args) throws IOException {
        Path root = Path.of(args.length > 0 ? args[0] : ".");
        Path iconset = Files.createDirectories(root.resolve("src/packaging/macos/InfraDesk.iconset"));
        int[] sizes = {16, 32, 128, 256, 512};
        for (int s : sizes) {
            ImageIO.write(render(s, true), "png", iconset.resolve("icon_" + s + "x" + s + ".png").toFile());
            ImageIO.write(render(s * 2, true), "png", iconset.resolve("icon_" + s + "x" + s + "@2x.png").toFile());
        }
        Path windows = Files.createDirectories(root.resolve("src/packaging/windows"));
        writeIco(windows.resolve("InfraDesk.ico"), new int[] {16, 24, 32, 48, 64, 128, 256});
        Path resources = Files.createDirectories(root.resolve("src/main/resources/com/infradesk/app"));
        for (int s : new int[] {64, 128, 256, 512}) {
            ImageIO.write(render(s, true), "png", resources.resolve("icon-" + s + ".png").toFile());
        }
        ImageIO.write(render(1024, true), "png", root.resolve("src/packaging/InfraDesk-1024.png").toFile());
        System.out.println("Icons written under " + root.toAbsolutePath());
    }

    /**
     * macOS 스타일 아이콘: 캔버스 가장자리에서 안쪽으로 들여 놓은 둥근 사각형 타일(Big Sur 그리드, 약 80%),
     * 어두운 그라데이션, 상태 LED가 달린 서버 랙 두 개.
     *
     * @param inset 타일 둘레에 macOS 여백을 둘지 여부(false면 캔버스를 가득 채운다)
     */
    static BufferedImage render(int size, boolean inset) {
        BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = img.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
            float s = size / 1024f;
            float margin = inset ? 100 * s : 0;
            float tile = size - 2 * margin;
            float arc = tile * 0.45f;

            // 타일 아래의 부드러운 그림자.
            if (inset && size >= 64) {
                for (int i = 6; i >= 1; i--) {
                    g.setColor(new Color(0, 0, 0, 10));
                    float spread = i * 4 * s;
                    g.fill(new RoundRectangle2D.Float(margin - spread / 2, margin + 10 * s, tile + spread, tile + spread / 2,
                            arc + spread, arc + spread));
                }
            }
            var shape = new RoundRectangle2D.Float(margin, margin, tile, tile, arc, arc);
            g.setPaint(new GradientPaint(0, margin, BG_TOP, 0, margin + tile, BG_BOTTOM));
            g.fill(shape);
            g.setColor(new Color(255, 255, 255, 28));
            g.setStroke(new BasicStroke(Math.max(1f, 4 * s)));
            g.draw(new RoundRectangle2D.Float(margin + 2 * s, margin + 2 * s, tile - 4 * s, tile - 4 * s, arc, arc));

            // 랙 두 개.
            float rackW = tile * 0.60f;
            float rackH = tile * 0.20f;
            float gap = tile * 0.07f;
            float x = margin + (tile - rackW) / 2;
            float y0 = margin + (tile - (2 * rackH + gap)) / 2;
            float stroke = Math.max(1.2f, tile * 0.035f);
            Color[] leds = {GREEN, BLUE};
            for (int i = 0; i < 2; i++) {
                float y = y0 + i * (rackH + gap);
                var rack = new RoundRectangle2D.Float(x, y, rackW, rackH, rackH * 0.5f, rackH * 0.5f);
                g.setColor(RACK_FILL);
                g.fill(rack);
                g.setColor(RACK);
                g.setStroke(new BasicStroke(stroke, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g.draw(rack);
                float led = rackH * 0.28f;
                float ledX = x + rackH * 0.36f;
                float ledY = y + (rackH - led) / 2;
                if (size >= 32) {
                    g.setColor(new Color(leds[i].getRed(), leds[i].getGreen(), leds[i].getBlue(), 90));
                    g.fill(new Ellipse2D.Float(ledX - led * 0.5f, ledY - led * 0.5f, led * 2, led * 2));
                }
                g.setColor(leds[i]);
                g.fill(new Ellipse2D.Float(ledX, ledY, led, led));
                if (size >= 48) {
                    // 각 랙 오른쪽의 드라이브 슬롯.
                    g.setColor(new Color(0x8C8F94));
                    g.setStroke(new BasicStroke(stroke * 0.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                    float slotY = y + rackH / 2;
                    for (int k = 0; k < 3; k++) {
                        float sx = x + rackW * (0.55f + k * 0.12f);
                        g.draw(new java.awt.geom.Line2D.Float(sx, slotY - rackH * 0.18f, sx, slotY + rackH * 0.18f));
                    }
                }
            }
        } finally {
            g.dispose();
        }
        return img;
    }

    /** PNG 압축 항목이 있는 ICO(Windows Vista부터 지원). */
    private static void writeIco(Path file, int[] sizes) throws IOException {
        byte[][] pngs = new byte[sizes.length][];
        for (int i = 0; i < sizes.length; i++) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(render(sizes[i], false), "png", out);
            pngs[i] = out.toByteArray();
        }
        int headerSize = 6 + 16 * sizes.length;
        int total = headerSize;
        for (byte[] p : pngs) {
            total += p.length;
        }
        ByteBuffer buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN);
        buf.putShort((short) 0).putShort((short) 1).putShort((short) sizes.length);
        int offset = headerSize;
        for (int i = 0; i < sizes.length; i++) {
            buf.put((byte) (sizes[i] >= 256 ? 0 : sizes[i])).put((byte) (sizes[i] >= 256 ? 0 : sizes[i]));
            buf.put((byte) 0).put((byte) 0).putShort((short) 1).putShort((short) 32);
            buf.putInt(pngs[i].length).putInt(offset);
            offset += pngs[i].length;
        }
        for (byte[] p : pngs) {
            buf.put(p);
        }
        Files.write(file, buf.array());
    }
}
