package com.infradesk.ui.files;

import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.TerminalService;
import com.infradesk.ui.Theme;
import com.infradesk.ui.components.Buttons;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Component;
import java.awt.FlowLayout;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;

/**
 * 서버 화면의 "파일" 탭 본문 (디자인 v2). 서버마다 {@link FilesTab}을 하나씩 만들어 들고 있다가 보고 있는
 * 서버의 것을 보여 준다.
 *
 * <p>서버 화면은 서버 하나를 위한 화면을 서버를 바꿔 가며 재사용한다. 파일 탭까지 그렇게 하면 서버를 바꿀 때마다
 * 연결이 끊기고 전송 목록과 편집 중인 파일이 사라진다. 그래서 이 탭만은 서버별로 따로 두고, 탭을 만든 뒤에는
 * 연결을 계속 유지한다(닫는 때는 {@link #closeAll()}, {@link #retainOnly(Set)}). 파일 탭을 처음 여는 서버에서만
 * 연결하므로 서버를 고르기만 해서는 SSH 접속이 늘지 않는다.
 *
 * <p>SSH 설정이 없거나 서버가 꺼져 있어서 연결할 수 없는 서버는 탭을 만들지 않고 안내를 보여 준다.
 */
public final class FilesHost extends JPanel {

    private static final String NOTICE = "\0notice";

    private final CardLayout cards = new CardLayout();
    private final Map<String, FilesTab> tabs = new HashMap<>();
    private final JLabel noticeText = new JLabel(" ");
    private final JButton noticeButton = Buttons.primary("SSH 설정", null);
    private TerminalService service;
    private Consumer<Server> onConfigure = s -> { };
    /** 안내 화면에서 [SSH 설정]을 눌렀을 때 대상이 되는 서버. */
    private Server noticeServer;
    /** 보여 주려고 한 서버. 설정을 마친 뒤 다시 시도하는 데 쓴다. */
    private Server wanted;
    private Runnable onTransfersChanged = () -> { };

    public FilesHost() {
        super();
        setLayout(cards);
        setOpaque(false);
        add(notice(), NOTICE);
        noticeButton.addActionListener(e -> {
            if (noticeServer != null) {
                onConfigure.accept(noticeServer);
            }
        });
    }

    /**
     * 연결할 서비스와, 안내 화면의 [SSH 설정]을 눌렀을 때 할 일을 정한다. 서비스는 화면을 만든 뒤에
     * 정해지므로 생성자가 아니라 따로 받는다.
     */
    public void init(TerminalService service, Consumer<Server> onConfigure) {
        this.service = service;
        this.onConfigure = onConfigure;
    }

    private JPanel notice() {
        JPanel box = new JPanel();
        box.setOpaque(false);
        box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
        noticeText.setForeground(Theme.TEXT_MUTED);
        noticeText.setAlignmentX(Component.CENTER_ALIGNMENT);
        JPanel buttonRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        buttonRow.setOpaque(false);
        buttonRow.add(noticeButton);
        box.add(Box.createVerticalGlue());
        box.add(noticeText);
        box.add(Box.createVerticalStrut(12));
        box.add(buttonRow);
        box.add(Box.createVerticalGlue());
        JPanel outer = new JPanel(new BorderLayout());
        outer.setOpaque(false);
        outer.setBorder(BorderFactory.createEmptyBorder(24, 28, 24, 28));
        outer.add(box, BorderLayout.CENTER);
        return outer;
    }

    /**
     * 이 서버의 파일 탭을 보여 준다. 처음이면 만들면서 연결한다. 여러 번 불러도 되도록 이미 있으면
     * 카드만 바꾼다(서버 목록을 새로 읽을 때마다 불린다).
     */
    public void show(Server server) {
        wanted = server;
        FilesTab existing = tabs.get(server.id());
        if (existing != null) {
            cards.show(this, server.id());
            return;
        }
        if (service == null) {
            return;
        }
        noticeServer = server;
        if (server.status() != ServerStatus.RUNNING) {
            showNotice("서버가 켜져 있을 때 파일을 볼 수 있어요.", false);
        } else if (!service.isConfigured(server.id())) {
            showNotice("파일을 보려면 이 서버의 SSH 설정이 필요해요.", true);
        } else {
            FilesTab created = new FilesTab(service, server);
            created.onTransfersChanged(() -> onTransfersChanged.run());
            tabs.put(server.id(), created);
            add(created, server.id());
            cards.show(this, server.id());
        }
    }

    /** SSH 설정을 마쳤다: 보려던 서버를 다시 시도한다. */
    public void retry() {
        if (wanted != null) {
            show(wanted);
        }
    }

    private void showNotice(String text, boolean withButton) {
        noticeText.setText(text);
        noticeButton.setVisible(withButton);
        cards.show(this, NOTICE);
    }

    /** 이 서버의 파일 탭. 아직 만들지 않았으면 null. */
    public FilesTab tabOf(String serverId) {
        return tabs.get(serverId);
    }

    /** 어느 서버의 전송이든 목록이 바뀌면 불린다(EDT). 창 아래 상태 막대가 전송 수를 보여 주는 데 쓴다. */
    public void onTransfersChanged(Runnable listener) {
        this.onTransfersChanged = listener;
    }

    /** 모든 서버의 진행 중인 전송을 합친 진행 정도. */
    public FilesTab.Progress transferProgress() {
        int active = 0;
        long done = 0;
        long total = 0;
        for (FilesTab t : tabs.values()) {
            FilesTab.Progress p = t.transferProgress();
            active += p.active();
            done += p.doneBytes();
            total += p.totalBytes();
        }
        return new FilesTab.Progress(active, done, total);
    }

    /** 진행 중이거나 기다리는 전송 수 전체(서버 모두 합친 값). */
    public int activeTransfers() {
        return tabs.values().stream().mapToInt(FilesTab::activeTransfers).sum();
    }

    /** 서버에 올리지 못한 편집 내용이 있는 파일 수 전체. */
    public int unsavedEdits() {
        return tabs.values().stream().mapToInt(FilesTab::unsavedEdits).sum();
    }

    /** 앱을 끌 때: 모든 연결과 전송 대기열을 닫는다. */
    public void closeAll() {
        for (FilesTab t : tabs.values()) {
            t.close();
        }
        tabs.clear();
    }

    /** 목록에서 사라진 서버(삭제한 계정)의 탭을 닫는다. */
    public void retainOnly(Set<String> serverIds) {
        tabs.entrySet().removeIf(e -> {
            if (serverIds.contains(e.getKey())) {
                return false;
            }
            remove(e.getValue());
            e.getValue().close();
            return true;
        });
    }
}
