package androidx.work;
public abstract class ListenableWorker {
    public ListenableWorker(android.content.Context c, WorkerParameters p) {}
    public final android.content.Context getApplicationContext() { return null; }
    public static abstract class Result {
        public static Result success() { return null; }
        public static Result failure() { return null; }
        public static Result retry() { return null; }
    }
}
