package com.infradesk.ui.components;

import org.junit.jupiter.api.Test;

import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.image.BufferedImage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WrappingLabelTest {

    private static FontMetrics metrics() {
        var g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB).createGraphics();
        return g.getFontMetrics(new Font(Font.MONOSPACED, Font.PLAIN, 10));
    }

    @Test
    void breaksOnlyAtSpacesKeepingKoreanWordsWhole() {
        FontMetrics fm = metrics();
        int width = fm.stringWidth("서버에 등록한 공개키와");
        String wrapped = WrappingLabel.wrap("서버에 등록한 공개키와 짝이 맞아야 해요.", fm, width);
        for (String line : wrapped.split("\n")) {
            assertTrue(fm.stringWidth(line) <= width, line);
            assertTrue(!line.startsWith(" "), "no leading space: '" + line + "'");
        }
        assertEquals("서버에 등록한 공개키와 짝이 맞아야 해요.", wrapped.replace("\n", " "));
    }

    @Test
    void splitsATokenLongerThanTheLine() {
        FontMetrics fm = metrics();
        int width = fm.stringWidth("abcde");
        assertEquals("abcde\nfghij\nk", WrappingLabel.wrap("abcdefghijk", fm, width));
    }

    @Test
    void keepsExplicitNewlines() {
        FontMetrics fm = metrics();
        assertEquals("a\nb", WrappingLabel.wrap("a\nb", fm, 1000));
    }
}
