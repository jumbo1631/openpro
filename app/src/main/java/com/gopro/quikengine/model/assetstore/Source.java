package com.gopro.quikengine.model.assetstore;

import java.util.HashMap;

public final class Source {
    private final String url;
    private final HashMap<String, String> headers;

    public Source(String url, HashMap<String, String> headers) {
        this.url = url;
        this.headers = headers;
    }

    public final String getUrl() {
        return url;
    }

    public final HashMap<String, String> getHeaders() {
        return headers;
    }

    @Override
    public String toString() {
        return "Source(url=" + url + ", headers=" + headers.keySet() + ")";
    }
}
