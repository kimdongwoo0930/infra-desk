package com.infradesk.core;

/**
 * Where an {@link Account}'s servers come from. Used to pick the {@link CloudProvider} implementation.
 * {@link #SSH} is not a cloud: one account is one machine reached directly over SSH (a home server,
 * a Mac mini), so it has no power control or cloud metrics.
 */
public enum ProviderType {
    ORACLE("Oracle Cloud", true),
    AWS("AWS", false),
    GCP("GCP", false),
    SSH("직접 연결", true);

    private final String displayName;
    private final boolean supported;

    ProviderType(String displayName, boolean supported) {
        this.displayName = displayName;
        this.supported = supported;
    }

    public String displayName() {
        return displayName;
    }

    /** A cloud with an API (not a directly connected machine). */
    public boolean isCloud() {
        return this != SSH;
    }

    /** Start / stop / reboot through the provider. */
    public boolean hasPowerControl() {
        return isCloud();
    }

    /** CPU/memory/network history from the provider (otherwise only live mode over SSH). */
    public boolean hasCloudMetrics() {
        return isCloud();
    }

    /** Whether a provider implementation exists yet. */
    public boolean isSupported() {
        return supported;
    }
}
