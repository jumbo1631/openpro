package com.gopro.quikengine.model.assetstore;

import java.util.Arrays;

public final class Media {
    private final Content content;
    private final Label label;
    private final Quality quality;
    private final Location[] locations;
    private final String token;
    private final Media[] resources;
    private final LabelData labelData;

    public Media(Content content, Label label, Quality quality, Location[] locations, String token,
                 Media[] resources, LabelData labelData) {
        this.content = content;
        this.label = label;
        this.quality = quality;
        this.locations = locations;
        this.token = token;
        this.resources = resources;
        this.labelData = labelData;
    }

    public final Content getContent() {
        return content;
    }

    public final Label getLabel() {
        return label;
    }

    public final Quality getQuality() {
        return quality;
    }

    public final Location[] getLocations() {
        return locations;
    }

    public final String getToken() {
        return token;
    }

    public final Media[] getResources() {
        return resources;
    }

    public final LabelData getLabelData() {
        return labelData;
    }

    @Override
    public String toString() {
        return "Media(content=" + content + ", label=" + label + ", quality=" + quality
                + ", locations=" + Arrays.toString(locations) + ", token=" + token
                + ", resources=" + Arrays.toString(resources) + ", labelData=" + labelData + ")";
    }
}
