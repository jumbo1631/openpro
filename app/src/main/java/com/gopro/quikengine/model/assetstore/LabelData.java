package com.gopro.quikengine.model.assetstore;

public abstract class LabelData {
    private LabelData() {
    }

    public static final class None extends LabelData {
        public static final None INSTANCE = new None();

        private None() {
        }

        @Override
        public String toString() {
            return "None";
        }
    }

    public static final class TaskData extends LabelData {
        private final String name;

        public TaskData(String name) {
            this.name = name;
        }

        public final String getName() {
            return name;
        }

        @Override
        public String toString() {
            return "TaskData(name=" + name + ")";
        }
    }
}
