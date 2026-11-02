package com.infradesk.ui.settings;

import com.infradesk.app.LaunchAtLogin;
import com.infradesk.core.AppSettings;
import com.infradesk.core.Server;
import com.infradesk.core.ServerGrouping;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.AppSettingsService;
import com.infradesk.ui.Async;
import com.infradesk.ui.components.Buttons;
import com.infradesk.ui.components.ToggleSwitch;

import java.awt.FlowLayout;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JPanel;

/**
 * 설정 "일반": 앱이 켜지고 닫힐 때의 동작과 서버를 보여주는 방법.
 *
 * <ul>
 *   <li>로그인할 때 실행: 운영체제에 등록한다.
 *   <li>시작 화면: 앱을 열 때 개요를 보여줄지, 지정한 서버를 바로 열지.
 *   <li>서버 목록 묶기: 사이드바와 개요에서 서버를 묶는 방법(계정별 / 상태별 / 묶지 않음).
 *   <li>단축키: 준비 중.
 * </ul>
 *
 * <p>창을 닫으면 메뉴 막대(트레이)로 숨기는 동작은 항상 켜져 있어서 설정으로 두지 않는다.
 * 시작 화면과 묶기는 바꾸는 즉시 {@link AppSettingsService}를 거쳐 저장되고, 사이드바와 개요가 듣고 있다가 바로 따라간다.
 */
public final class GeneralPage extends SettingsPage {

    public static final String ID = "general";

    private static final String START_OVERVIEW = "개요";
    private static final String START_SERVER = "서버 지정";
    private static final String START_DESCRIPTION = "앱을 열 때 처음 보여줄 화면";

    /** 서버 선택 목록의 항목. 목록은 {@link #toString()}의 글자를 보여 준다. */
    record Choice(String id, String label) {
        @Override
        public String toString() {
            return label;
        }
    }

    private final ToggleSwitch login = Controls.toggle(false);
    private final SettingsRow loginRow;

    private final AppSettingsService settings;
    private final Supplier<List<AccountInventory>> inventory;
    private final JComboBox<String> startKind = Controls.choice(110, START_OVERVIEW, START_SERVER);
    private final JComboBox<Choice> startServer = new JComboBox<>();
    private final JComboBox<String> grouping;
    private final SettingsRow startRow;
    /** 코드가 선택 목록을 맞추는 중이면 true: 그때 일어나는 선택 변경을 사용자가 고른 것으로 오해하지 않기 위해. */
    private boolean syncing;

    /**
     * @param settings  시작 화면과 묶기를 읽고 저장하는 창구
     * @param inventory 지금 불러온 계정과 서버(시작 서버를 고르는 목록에 쓴다). 서버는 나중에 도착하므로 열 때마다 새로 읽는다
     */
    public GeneralPage(LaunchAtLogin launchAtLogin, boolean demoMode, AppSettingsService settings,
                       Supplier<List<AccountInventory>> inventory) {
        super(ID, "일반", "일반", "앱이 켜지고 닫힐 때의 동작");
        this.settings = settings;
        this.inventory = inventory;

        boolean supported = launchAtLogin != null && launchAtLogin.isSupported() && !demoMode;
        login.setSelected(launchAtLogin != null && launchAtLogin.isEnabled());
        login.setEnabled(supported);
        loginRow = new SettingsRow("로그인할 때 InfraDesk 실행",
                loginDescription(demoMode, launchAtLogin == null ? Optional.empty() : launchAtLogin.target()), login);
        login.addActionListener(e -> {
            boolean on = login.isSelected();
            login.setEnabled(false);
            Async.run(() -> {
                launchAtLogin.setEnabled(on);
                return null;
            }, ignored -> login.setEnabled(true), err -> {
                login.setSelected(!on);   // 못 바꿨으면 되돌린다
                login.setEnabled(true);
                loginRow.setDescription(Async.message(err));
            });
        });

        // 시작 화면: 종류(개요 / 서버 지정)와, 서버 지정일 때만 보이는 서버 목록을 한 줄에 놓는다.
        startServer.setPreferredSize(new java.awt.Dimension(200, startKind.getPreferredSize().height));
        startServer.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE, "buttonStyle: button");
        JPanel startControl = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        startControl.setOpaque(false);
        startControl.add(startServer);
        startControl.add(startKind);
        startRow = new SettingsRow("시작 화면", START_DESCRIPTION, startControl);
        startKind.addActionListener(e -> {
            if (!syncing) {
                pickStartKind();
            }
        });
        startServer.addActionListener(e -> {
            if (!syncing && startServer.getSelectedItem() instanceof Choice choice && choice.id() != null) {
                save(s -> s.withStartServerId(choice.id()));
            }
        });

        String[] labels = java.util.Arrays.stream(ServerGrouping.values()).map(ServerGrouping::label).toArray(String[]::new);
        grouping = Controls.choice(120, labels);
        grouping.addActionListener(e -> {
            if (!syncing) {
                save(s -> s.withGrouping(ServerGrouping.fromLabel((String) grouping.getSelectedItem(), s.grouping())));
            }
        });

        SettingsCard card = new SettingsCard();
        card.add(loginRow);
        card.add(startRow);
        card.add(new SettingsRow("서버 목록 묶기", "사이드바와 개요에서 서버를 묶는 방법", grouping));
        JButton shortcuts = Buttons.secondary("단축키 모두 보기", null);
        card.add(new SettingsRow("단축키", "자주 쓰는 동작의 단축키 목록", shortcuts).comingSoon());
        addCard(card);

        // 다른 곳(개요의 "묶기" 목록)에서 값이 바뀌어도 이 화면의 선택 목록이 따라가게 한다.
        settings.onChange(changed -> javax.swing.SwingUtilities.invokeLater(this::refresh));
        refresh();
    }

    /** 설정과 서버 목록을 다시 읽어 선택 목록을 맞춘다. 이 항목을 열 때마다 불린다. */
    void refresh() {
        AppSettings current = settings.current();
        List<Choice> choices = serverChoices();
        String saved = current.startServerId();
        boolean missing = saved != null && choices.stream().noneMatch(c -> saved.equals(c.id()));
        if (missing) {
            // 저장된 서버가 목록에 없다(아직 못 불렀거나 지웠다): 눈에 보이게 남겨 두고, 열 때는 개요로 연다.
            choices.add(0, new Choice(saved, "찾을 수 없는 서버"));
        }
        syncing = true;
        try {
            startServer.removeAllItems();
            choices.forEach(startServer::addItem);
            startKind.setSelectedItem(saved == null ? START_OVERVIEW : START_SERVER);
            startServer.setVisible(saved != null);
            if (saved != null) {
                choices.stream().filter(c -> saved.equals(c.id())).findFirst().ifPresent(startServer::setSelectedItem);
            }
            grouping.setSelectedItem(current.grouping().label());
        } finally {
            syncing = false;
        }
        startServer.getParent().revalidate();   // 서버 목록 칸이 보이거나 숨겨졌다
        startServer.getParent().repaint();
        startRow.setDescription(missing ? "지정한 서버를 찾을 수 없어서 지금은 개요로 열려요." : START_DESCRIPTION);
    }

    /** "개요" / "서버 지정"을 골랐다. 서버 지정이면 일단 첫 서버로 정해 두고, 서버 목록에서 바꾸게 한다. */
    private void pickStartKind() {
        if (START_OVERVIEW.equals(startKind.getSelectedItem())) {
            save(s -> s.withStartServerId(null));
            return;
        }
        List<Choice> choices = serverChoices();
        if (choices.isEmpty()) {
            refresh();   // 고를 서버가 없다: 개요로 되돌린다
            startRow.setDescription("서버가 있어야 정할 수 있어요.");
            return;
        }
        save(s -> s.withStartServerId(choices.get(0).id()));
    }

    private void save(java.util.function.UnaryOperator<AppSettings> change) {
        try {
            settings.update(change);
            refresh();   // 방금 고른 값에 맞게 바로 맞춘다(서버 목록 칸을 보이거나 숨긴다)
        } catch (RuntimeException e) {
            refresh();   // 저장하지 못했다: 화면을 저장된 값으로 되돌리고 이유를 보여 준다
            startRow.setDescription(e.getMessage());
        }
    }

    /** 지금 불러온 서버들. 이름 뒤에 계정 이름을 붙여 같은 이름의 서버를 가린다. */
    private List<Choice> serverChoices() {
        List<Choice> out = new ArrayList<>();
        for (AccountInventory inv : inventory.get()) {
            for (Server s : inv.servers()) {
                out.add(new Choice(s.id(), s.name() + " · " + inv.account().displayName()));
            }
        }
        return out;
    }

    /** "로그인할 때 실행" 설명: 데모, 실행 대상을 찾았을 때, 못 찾았을 때. */
    static String loginDescription(boolean demoMode, Optional<Path> target) {
        if (demoMode) {
            return "데모 모드에서는 바꿀 수 없어요.";
        }
        return target.map(p -> "메뉴 막대에서 조용히 시작해요 · 실행 대상: " + p)
                .orElse("설치한 InfraDesk를 찾지 못했어요 (응용 프로그램 폴더에 설치하면 켤 수 있어요).");
    }

    // ── 테스트에서 화면의 상태를 읽는 데 쓴다 ───────────────────

    ToggleSwitch loginSwitch() {
        return login;
    }

    String loginDescriptionText() {
        return loginRow.descriptionText();
    }

    JComboBox<String> startKindBox() {
        return startKind;
    }

    JComboBox<Choice> startServerBox() {
        return startServer;
    }

    JComboBox<String> groupingBox() {
        return grouping;
    }

    String startDescriptionText() {
        return startRow.descriptionText();
    }
}
