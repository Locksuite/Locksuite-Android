package androidx.work;
public final class Constraints {
    public static final class Builder {
        public Builder() {}
        public Builder setRequiredNetworkType(NetworkType t) { return this; }
        public Builder setRequiresBatteryNotLow(boolean b) { return this; }
        public Builder setRequiresCharging(boolean b) { return this; }
        public Constraints build() { return null; }
    }
}
