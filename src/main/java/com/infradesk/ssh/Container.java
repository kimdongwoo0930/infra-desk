package com.infradesk.ssh;

/**
 * 서버의 Docker 컨테이너 하나. {@code docker ps}와 {@code docker stats}에서 얻는다.
 *
 * @param id         전체 컨테이너 id(16진수)
 * @param state      "running", "exited", "paused", "restarting", "created", "dead"
 * @param status     Docker가 주는 사람용 상태. 예: "Up 14 days", "Exited (0) 2 hours ago"
 * @param cpuPercent CPU %. 실행 중이 아니거나 알 수 없으면 -1
 * @param memUsage   예: "120MiB / 23.4GiB". 알 수 없으면 ""
 * @param memPercent 메모리 %. 알 수 없으면 -1
 */
public record Container(String id, String name, String image, String state, String status, String ports,
                        double cpuPercent, String memUsage, double memPercent) {

    public boolean isRunning() {
        return "running".equals(state);
    }

    public String shortId() {
        return id.length() > 12 ? id.substring(0, 12) : id;
    }
}
