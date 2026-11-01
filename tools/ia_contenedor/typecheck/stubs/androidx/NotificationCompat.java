package androidx.core.app;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
public class NotificationCompat {
    public static final int PRIORITY_DEFAULT = 0;
    public static final int DEFAULT_ALL = -1;
    public static final int PRIORITY_LOW = -1;
    public static final int PRIORITY_HIGH = 1;
    public static final int PRIORITY_MAX = 2;
    public static final int PRIORITY_MIN = -2;
    public static final int VISIBILITY_PUBLIC = 1;
    public static final int VISIBILITY_PRIVATE = 0;
    public static final int VISIBILITY_SECRET = -1;
    public static final String CATEGORY_SERVICE = "service";
    public static final String CATEGORY_PROGRESS = "progress";
    public static final String CATEGORY_STATUS = "status";
    public static final String CATEGORY_ALARM = "alarm";
    public static final String CATEGORY_MESSAGE = "msg";
    public static abstract class Style {}
    public static class BigTextStyle extends Style {
        public BigTextStyle() {}
        public BigTextStyle bigText(CharSequence t) { return this; }
        public BigTextStyle setBigContentTitle(CharSequence t) { return this; }
    }
    public static class Builder {
        public Builder(Context c, String channelId) {}
        public Builder setSmallIcon(int i) { return this; }
        public Builder setContentTitle(CharSequence t) { return this; }
        public Builder setContentText(CharSequence t) { return this; }
        public Builder setStyle(Style s) { return this; }
        public Builder setPriority(int p) { return this; }
        public Builder setOngoing(boolean b) { return this; }
        public Builder setAutoCancel(boolean b) { return this; }
        public Builder setOnlyAlertOnce(boolean b) { return this; }
        public Builder setProgress(int max, int p, boolean indeterminate) { return this; }
        public Builder setContentIntent(PendingIntent i) { return this; }
        public Builder setFullScreenIntent(PendingIntent i, boolean b) { return this; }
        public Builder setVisibility(int v) { return this; }
        public Builder setCategory(String c) { return this; }
        public Builder setSilent(boolean b) { return this; }
        public Builder setTimeoutAfter(long ms) { return this; }
        public Builder setWhen(long w) { return this; }
        public Builder setShowWhen(boolean b) { return this; }
        public Builder setColor(int c) { return this; }
        public Builder setLargeIcon(android.graphics.Bitmap b) { return this; }
        public Builder addAction(int icon, CharSequence title, PendingIntent i) { return this; }
        public Builder setDefaults(int d) { return this; }
        public Builder setSound(android.net.Uri u) { return this; }
        public Builder setVibrate(long[] v) { return this; }
        public Builder setForegroundServiceBehavior(int b) { return this; }
        public Notification build() { return null; }
    }
}
