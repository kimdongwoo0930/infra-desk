package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.Server;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.FleetService;
import com.infradesk.ssh.DockerCommands;
import com.infradesk.ssh.HostFacts;
import com.infradesk.ui.Regions;

import java.time.Duration;
import java.util.Optional;

/**
 * 개요의 서버 카드 하나에 보여 줄 값 (디자인 v2). 화면 부품을 만들지 않고 값과 문구만 정하므로 화면 없이 시험한다.
 *
 * <p>값이 없는 것은 {@link #UNKNOWN}(-1)이고, 카드는 막대 없이 "—"로 보여 준다.
 *
 * @param running      실행 중이면 막대와 컨테이너 줄을 보이고, 아니면 안내 글자를 보인다
 * @param cpu          CPU %. 클라우드 지표나 직접 연결의 1분 샘플에서 온다
 * @param memory       메모리 %. SSH로 읽은 값
 * @param disk         디스크(/) %. SSH로 읽은 값
 * @param meta         이름 아래의 한 줄. 예: "계정 A · 춘천 · A1.Flex · 2 OCPU"
 * @param containers   컨테이너 줄의 글자. 예: "컨테이너 3/4"
 * @param stopped      멈춘 컨테이너 수(없으면 0)
 * @param uptime       예: "업타임 9일". 모르면 빈 글자
 * @param note         막대 대신 보이는 안내(읽는 중, 읽지 못함, 정지됨). 없으면 빈 글자
 * @param canStart     카드의 [시작] 버튼을 보일지(전원을 다룰 수 있는 정지 서버)
 */
record CardData(Server server, boolean running, double cpu, double memory, double disk, String meta,
                String containers, int stopped, String uptime, String note, boolean canStart) {

    static final double UNKNOWN = -1;

    /**
     * @param cpu        최근 CPU %. 모르면 null
     * @param reading    SSH로 읽은 결과. 아직 안 읽었으면 비어 있다
     * @param configured 이 서버에 SSH 설정이 있는지
     */
    static CardData of(Server server, Account account, Double cpu, Optional<FleetService.Reading> reading,
                       boolean configured) {
        HostFacts facts = reading.map(FleetService.Reading::facts).orElse(null);
        String meta = meta(server, account, facts);
        if (server.status() != ServerStatus.RUNNING) {
            return new CardData(server, false, UNKNOWN, UNKNOWN, UNKNOWN, meta, "", 0, "", stoppedText(server.status()),
                    server.status() == ServerStatus.STOPPED && account.provider().hasPowerControl());
        }
        double cpuPercent = cpu == null ? UNKNOWN : cpu;
        if (facts == null) {
            String note = !configured ? "SSH를 설정하면 메모리와 디스크가 보여요"
                    : reading.isPresent() ? "읽지 못했어요: " + reading.get().problem()
                    : "서버 정보를 읽는 중…";
            return new CardData(server, true, cpuPercent, UNKNOWN, UNKNOWN, meta, "", 0, "", note, false);
        }
        double disk = facts.diskTotal() > 0 && facts.diskUsed() >= 0 ? 100.0 * facts.diskUsed() / facts.diskTotal() : UNKNOWN;
        DockerCommands.Listing listing = reading.map(FleetService.Reading::containers).orElse(null);
        int stopped = listing != null && listing.status() == DockerCommands.Listing.Status.OK
                ? (int) listing.containers().stream().filter(c -> !c.isRunning()).count() : 0;
        return new CardData(server, true, cpuPercent, facts.memoryPercent(), disk, meta, containersText(listing), stopped,
                facts.uptime() == null ? "" : "업타임 " + uptimeText(facts.uptime()), "", false);
    }

    /** 이름 아래 한 줄. 클라우드는 "계정 · 리전 · 머신 · CPU 수", 직접 연결은 "직접 연결 · OS · SSH". */
    static String meta(Server server, Account account, HostFacts facts) {
        if (!account.provider().isCloud()) {
            return facts != null && facts.osName() != null ? "직접 연결 · " + facts.osName() + " · SSH" : "직접 연결 · SSH";
        }
        StringBuilder sb = new StringBuilder(account.displayName());
        if (server.region() != null) {
            sb.append(" · ").append(Regions.shortName(server.region()));
        }
        if (server.shape() != null) {
            sb.append(" · ").append(shortShape(server.shape()));
        }
        if (server.cpuCount() > 0) {
            double n = server.cpuCount();
            sb.append(" · ").append(n == Math.rint(n) ? String.valueOf((long) n) : String.valueOf(n)).append(" OCPU");
        }
        return sb.toString();
    }

    /** "VM.Standard.A1.Flex" → "A1.Flex". */
    static String shortShape(String shape) {
        return shape.startsWith("VM.Standard.") ? shape.substring("VM.Standard.".length()) : shape;
    }

    static String containersText(DockerCommands.Listing listing) {
        if (listing == null) {
            return "컨테이너 —";
        }
        return switch (listing.status()) {
            case OK -> listing.containers().isEmpty() ? "컨테이너 없음" : "컨테이너 "
                    + listing.containers().stream().filter(c -> c.isRunning()).count() + "/" + listing.containers().size();
            case NO_DOCKER -> "Docker 없음";
            case NO_ACCESS -> "Docker 권한 없음";
            case ERROR -> "컨테이너 —";
        };
    }

    static String stoppedText(ServerStatus status) {
        return switch (status) {
            case STOPPED -> "정지됨 · 시작하면 보통 1~2분 뒤에 연결할 수 있어요.";
            case STARTING -> "시작하는 중이에요. 상태를 5초마다 확인해요.";
            case STOPPING -> "정지하는 중이에요. 상태를 5초마다 확인해요.";
            default -> "상태를 알 수 없어요";
        };
    }

    /** 가장 큰 단위 하나만: 9일, 3시간, 12분. */
    static String uptimeText(Duration d) {
        if (d.toDays() > 0) {
            return d.toDays() + "일";
        }
        if (d.toHours() > 0) {
            return d.toHours() + "시간";
        }
        return Math.max(1, d.toMinutes()) + "분";
    }
}
