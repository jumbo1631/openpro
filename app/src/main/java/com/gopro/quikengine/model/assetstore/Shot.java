package com.gopro.quikengine.model.assetstore;

import java.util.Arrays;

public final class Shot {
    private final Type type;
    private final Media[] sequence;
    private final Media[] resources;
    private final boolean networkAvailable;

    public Shot(Type type, Media[] sequence, Media[] resources, boolean networkAvailable) {
        this.type = type;
        this.sequence = sequence;
        this.resources = resources;
        this.networkAvailable = networkAvailable;
    }

    public final Type getType() {
        return type;
    }

    public final Media[] getSequence() {
        return sequence;
    }

    public final Media[] getResources() {
        return resources;
    }

    public final boolean getNetworkAvailable() {
        return networkAvailable;
    }

    @Override
    public String toString() {
        return "Shot(type=" + type + ", sequence=" + Arrays.toString(sequence)
                + ", resources=" + Arrays.toString(resources) + ", networkAvailable=" + networkAvailable + ")";
    }
}
