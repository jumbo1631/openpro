package com.gopro.quikengine.model.assetstore;

import java.util.Objects;

public final class Identifier {
    private final Provider provider;
    private final String key;

    public Identifier(Provider provider, String key) {
        this.provider = provider;
        this.key = key;
    }

    public final Provider getProvider() {
        return provider;
    }

    public final String getKey() {
        return key;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Identifier
                && ((Identifier) other).provider == provider
                && Objects.equals(((Identifier) other).key, key);
    }

    @Override
    public int hashCode() {
        return Objects.hash(provider, key);
    }

    @Override
    public String toString() {
        return "Identifier(provider=" + provider + ", key=" + key + ")";
    }
}
