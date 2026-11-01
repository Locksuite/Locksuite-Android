package androidx.core.app;
import android.app.Notification;
import android.content.Context;
public class NotificationManagerCompat {
    public static NotificationManagerCompat from(Context c) { return null; }
    public void notify(int id, Notification n) {}
    public void notify(String tag, int id, Notification n) {}
    public void cancel(int id) {}
    public void cancel(String tag, int id) {}
    public void cancelAll() {}
    public boolean areNotificationsEnabled() { return true; }
}
