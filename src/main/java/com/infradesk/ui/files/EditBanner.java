package com.infradesk.ui.files;

import com.infradesk.ui.Icons;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.RoundedPanel;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 파일 탭 위쪽의 "편집 중" 배너 (디자인 v2):
 * {@code ✎ 편집 중 1개 · /etc/nginx/nginx.conf — 편집기에서 저장하면 서버에 자동으로 올려요 · 마지막 업로드 15:28  [편집기 열기] [편집 끝내기]}
 *
 * <p>서버 파일을 편집하는 동안만 보이고, 편집하는 파일이 없으면 숨긴다. 이 부품은 글자와 버튼만 가진다:
 * 편집 세션을 알고 처리하는 것은 {@link FilesTab}이고, 버튼을 누르면 알려 줄 뿐이다.
 */
final class EditBanner extends RoundedPanel {

    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault());

    private final JLabel text = new JLabel(" ");
    private final JButton open = Buttons.secondary("편집기 열기", null);
    private final JButton end = Buttons.ghost("편집 끝내기");

    EditBanner() {
        super(new BorderLayout(12, 0), Theme.INFO_BG, Theme.INFO_BORDER, Theme.ARC_CARD);
        setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 12));
        JLabel icon = new JLabel(Icons.get("pencil", 16, Theme.ACCENT_TEXT));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.add(open);
        buttons.add(end);
        add(icon, BorderLayout.WEST);
        add(text, BorderLayout.CENTER);
        add(buttons, BorderLayout.EAST);
        setVisible(false);
    }

    /**
     * 편집 중인 파일이 있으면 보여 주고 없으면 숨긴다.
     *
     * @param count      편집 중인 파일 수 (0이면 숨김)
     * @param latestPath 가장 최근에 연 파일의 서버 경로
     * @param lastUpload 마지막으로 서버에 올린 시각. 아직 올린 적 없으면 null
     */
    void update(int count, String latestPath, Instant lastUpload) {
        setVisible(count > 0);
        if (count > 0) {
            String plain = plainText(count, latestPath, lastUpload);
            text.setText(html(count, latestPath, lastUpload));
            text.setToolTipText(plain);
        }
    }

    void onOpen(Runnable listener) {
        open.addActionListener(e -> listener.run());
    }

    void onEnd(Runnable listener) {
        end.addActionListener(e -> listener.run());
    }

    JButton openButton() {
        return open;
    }

    // ── 문구 (순수 함수, 테스트용으로 열어 둔다) ────────────────

    /** 글자만 본 배너 문구. 툴팁과 테스트에서 쓴다. */
    static String plainText(int count, String latestPath, Instant lastUpload) {
        return "편집 중 " + count + "개 · " + latestPath + (count > 1 ? " 외 " + (count - 1) + "개" : "")
                + " — 편집기에서 저장하면 서버에 자동으로 올려요" + uploadedAt(lastUpload);
    }

    /** 마지막 업로드 시각 문구. 올린 적이 없으면 빈 글자. */
    static String uploadedAt(Instant lastUpload) {
        return lastUpload == null ? "" : " · 마지막 업로드 " + CLOCK.format(lastUpload);
    }

    /** 제목은 굵게, 경로는 고정폭으로 그리려고 HTML로 만든다. 경로의 특수 문자는 글자로 보이도록 바꾼다. */
    private static String html(int count, String latestPath, Instant lastUpload) {
        return "<html><b>편집 중 " + count + "개</b> · <span style=\"font-family:monospaced\">" + escape(latestPath)
                + "</span>" + (count > 1 ? " 외 " + (count - 1) + "개" : "")
                + " — 편집기에서 저장하면 서버에 자동으로 올려요" + uploadedAt(lastUpload) + "</html>";
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
