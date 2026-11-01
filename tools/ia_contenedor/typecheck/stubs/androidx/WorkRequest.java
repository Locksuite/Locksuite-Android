package androidx.work;
public abstract class WorkRequest {
    public abstract static class Builder<B extends Builder<?, ?>, W extends WorkRequest> {
        public B setConstraints(Constraints c) { return null; }
        public B setInitialDelay(long d, java.util.concurrent.TimeUnit u) { return null; }
        public B addTag(String t) { return null; }
        public W build() { return null; }
    }
}
