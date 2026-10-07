package com.panos.aiscreencheck;

public final class VisibleTextStore {
    private static volatile String text = "";
    private static volatile String packageName = "";
    private static volatile long updatedAt = 0L;

    private VisibleTextStore() {}

    public static void update(String value, String pkg) {
        text = value == null ? "" : value;
        packageName = pkg == null ? "" : pkg;
        updatedAt = System.currentTimeMillis();
    }

    public static Snapshot get() {
        return new Snapshot(text, packageName, updatedAt);
    }

    public static final class Snapshot {
        public final String text;
        public final String packageName;
        public final long updatedAt;

        public Snapshot(String text, String packageName, long updatedAt) {
            this.text = text;
            this.packageName = packageName;
            this.updatedAt = updatedAt;
        }

        public boolean isFresh(long maxAgeMs) {
            return updatedAt > 0 && System.currentTimeMillis() - updatedAt <= maxAgeMs;
        }
    }
}
