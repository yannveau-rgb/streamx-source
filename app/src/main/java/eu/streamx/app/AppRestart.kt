package eu.streamx.app

import android.content.Context
import android.content.Intent
import android.os.Process

object AppRestart {
    /** Relance complètement l'application (nouveau processus) : évite de débrancher la box quand le lecteur reste bloqué. */
    fun restart(c: Context) {
        val i = c.packageManager.getLaunchIntentForPackage(c.packageName)?.apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        } ?: return
        val pi = android.app.PendingIntent.getActivity(c, 0, i, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_CANCEL_CURRENT)
        (c.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager)
            .set(android.app.AlarmManager.RTC, System.currentTimeMillis() + 400, pi)
        Process.killProcess(Process.myPid())
    }
}
