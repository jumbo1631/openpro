package com.gopro.quikengine.model.assetstore;

import com.gopro.quikengine.model.AssetStoreError;

public abstract class AssetStoreResult {
    private AssetStoreResult() {
    }

    public static final class Fail extends AssetStoreResult {
        private final AssetStoreError error;

        public Fail(AssetStoreError error) {
            this.error = error;
        }

        public final AssetStoreError getError() {
            return error;
        }
    }

    public static final class Success extends AssetStoreResult {
        private final Source source;

        public Success(Source source) {
            this.source = source;
        }

        public final Source getSource() {
            return source;
        }
    }
}
