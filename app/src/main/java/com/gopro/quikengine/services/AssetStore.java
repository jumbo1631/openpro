package com.gopro.quikengine.services;

import com.gopro.quikengine.model.assetstore.AssetStoreResult;
import com.gopro.quikengine.model.assetstore.Identifier;
import com.gopro.quikengine.model.assetstore.Location;
import com.gopro.quikengine.model.assetstore.Media;
import com.gopro.quikengine.model.assetstore.Shot;
import com.gopro.quikengine.model.assetstore.Source;

/** The engine resolves every media_id in an edit list through the registered Api. */
public final class AssetStore {
    public interface Api {
        Media registerResourceForMedia(Media parent, Media newMedia, Source newSource);

        Shot registerResourceForShot(Identifier identifier, Media newMedia, Source newSource);

        Shot resolveIdentifier(Identifier identifier);

        Identifier resolveSource(Source source);

        AssetStoreResult resolveToken(String token, Location location);
    }

    private AssetStore() {
    }

    public static void set(Api api) {
        setInternal(api);
    }

    public static void reset() {
        resetInternal();
    }

    public static Shot resolveIdentifier(Identifier identifier) {
        return resolveIdentifierInternal(identifier);
    }

    public static Identifier resolveSource(Source source) {
        return resolveSourceInternal(source);
    }

    public static AssetStoreResult resolveToken(String token, Location location) {
        return resolveTokenInternal(token, location);
    }

    private static native void resetInternal();

    private static native Shot resolveIdentifierInternal(Identifier identifier);

    private static native Identifier resolveSourceInternal(Source source);

    private static native AssetStoreResult resolveTokenInternal(String token, Location location);

    private static native void setInternal(Api api);
}
