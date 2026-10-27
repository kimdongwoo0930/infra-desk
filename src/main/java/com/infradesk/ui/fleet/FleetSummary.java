package com.infradesk.ui.fleet;

import com.infradesk.core.Account;
import com.infradesk.core.ServerStatus;
import com.infradesk.service.AccountInventory;
import com.infradesk.service.FleetService;
import com.infradesk.ssh.DockerCommands;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * 개요 맨 위 요약 네 칸의 값과 부제 (디자인 v2). 화면 없이 시험할 수 있게 값만 계산한다.
 *
 * @param servers      서버 수
 * @param running      실행 중인 서버 수
 * @param cloudAccounts 클라우드 계정 수
 * @param direct       직접 연결 서버 수
 * @param averageCpu   실행 중 서버의 평균 CPU %. 하나도 모르면 -1
 * @param containersRunning 실행 중인 컨테이너 수(읽은 서버만)
 * @param containersTotal   전체 컨테이너 수(읽은 서버만). 읽은 서버가 없으면 -1
 */
record FleetSummary(int servers, int running, int cloudAccounts, int direct, double averageCpu,
                    int containersRunning, int containersTotal) {

    static FleetSummary of(List<AccountInventory> inventory, Map<String, Double> cpu,
                           Function<String, Optional<FleetService.Reading>> readings) {
        int servers = 0;
        int running = 0;
        int cloud = 0;
        int direct = 0;
        double cpuSum = 0;
        int cpuCount = 0;
        int cRunning = 0;
        int cTotal = 0;
        boolean cKnown = false;
        for (AccountInventory inv : inventory) {
            Account account = inv.account();
            if (account.provider().isCloud()) {
                cloud++;
            }
            for (var s : inv.servers()) {
                servers++;
                if (!account.provider().isCloud()) {
                    direct++;
                }
                if (s.status() != ServerStatus.RUNNING) {
                    continue;
                }
                running++;
                Double c = cpu.get(s.id());
                if (c != null) {
                    cpuSum += c;
                    cpuCount++;
                }
                DockerCommands.Listing listing = readings.apply(s.id()).map(FleetService.Reading::containers).orElse(null);
                if (listing != null && listing.status() == DockerCommands.Listing.Status.OK) {
                    cKnown = true;
                    cTotal += listing.containers().size();
                    cRunning += (int) listing.containers().stream().filter(x -> x.isRunning()).count();
                }
            }
        }
        return new FleetSummary(servers, running, cloud, direct, cpuCount == 0 ? -1 : cpuSum / cpuCount,
                cRunning, cKnown ? cTotal : -1);
    }

    /** "서버 6대 · 클라우드 계정 3개 · 직접 연결 1대". 없는 것은 뺀다. */
    String subtitle() {
        StringBuilder sb = new StringBuilder("서버 ").append(servers).append("대");
        if (cloudAccounts > 0) {
            sb.append(" · 클라우드 계정 ").append(cloudAccounts).append("개");
        }
        if (direct > 0) {
            sb.append(" · 직접 연결 ").append(direct).append("대");
        }
        return sb.toString();
    }

    String averageCpuText() {
        return averageCpu < 0 ? "—" : Math.round(averageCpu) + "%";
    }

    String containersText() {
        return containersTotal < 0 ? "—" : String.valueOf(containersRunning);
    }

    String containersSub() {
        return containersTotal < 0 ? "" : "/ " + containersTotal + "개";
    }
}
