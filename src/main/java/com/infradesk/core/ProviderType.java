package com.infradesk.core;

/** Cloud vendors an {@link Account} can belong to. Used to pick the {@link CloudProvider} implementation. */
public enum ProviderType {
    ORACLE("Oracle Cloud", true),
    AWS("AWS", false),
    GCP("GCP", false);

    private final String displayName;
    private final boolean supported;

    ProviderType(String displayName, boolean supported) {
        this.displayName = displayName;
        this.supported = supported;
    }

    public String displayName() {
        return displayName;
    }

    /** Whether a provider implementation exists yet. */
    public boolean isSupported() {
        return supported;
    }
}
