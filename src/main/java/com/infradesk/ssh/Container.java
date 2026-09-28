package com.infradesk.ssh;

/**
 * One Docker container on a server, from {@code docker ps} and {@code docker stats}.
 *
 * @param id         full container id (hex)
 * @param state      "running", "exited", "paused", "restarting", "created", "dead"
 * @param status     human status from Docker, e.g. "Up 14 days", "Exited (0) 2 hours ago"
 * @param cpuPercent CPU %, or -1 when not running / unknown
 * @param memUsage   e.g. "120MiB / 23.4GiB", or "" when unknown
 * @param memPercent memory %, or -1
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
