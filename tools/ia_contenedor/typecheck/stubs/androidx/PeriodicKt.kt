package androidx.work
import java.util.concurrent.TimeUnit
inline fun <reified W : ListenableWorker> PeriodicWorkRequestBuilder(repeatInterval: Long, repeatIntervalTimeUnit: TimeUnit): PeriodicWorkRequest.Builder = throw NotImplementedError()
