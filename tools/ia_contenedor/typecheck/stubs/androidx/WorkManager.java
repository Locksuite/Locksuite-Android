package androidx.work;
public abstract class WorkManager {
    public static WorkManager getInstance(android.content.Context c) { return null; }
    public abstract void enqueueUniquePeriodicWork(String name, ExistingPeriodicWorkPolicy p, PeriodicWorkRequest r);
    public abstract void cancelUniqueWork(String name);
}
