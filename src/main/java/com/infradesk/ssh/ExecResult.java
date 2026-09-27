package com.infradesk.ssh;

/**
 * Outcome of running one command on one server.
 *
 * @param exitCode exit status, or -1 when unknown (timeout, connection failure)
 * @param output   combined stdout/stderr, possibly truncated
 * @param error    user-facing failure message, or null if the command ran
 */
public record ExecResult(int exitCode, String output, String error, boolean truncated) {

    public boolean succeeded() {
        return error == null && exitCode == 0;
    }

    public static ExecResult failure(String error) {
        return new ExecResult(-1, "", error, false);
    }
}
