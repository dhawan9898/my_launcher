package dev.omalauncher.widgets.runtime

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Settings
import dev.omalauncher.system.registerExported
import dev.omalauncher.widgets.lang.Null
import dev.omalauncher.widgets.lang.Num
import dev.omalauncher.widgets.lang.Obj
import dev.omalauncher.widgets.lang.Str
import dev.omalauncher.widgets.lang.Value
import dev.omalauncher.widgets.lang.bool
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** The next alarm the user set in any clock app, for `source a = android.alarm`. Needs no permission. */
class Alarm(
    private val context: Context,
) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun value(): Value {
        val next = alarms?.nextAlarmClock
        val isUserAlarm = next?.let { info ->
            val pkg = info.showIntent?.creatorPackage ?: ""
            pkg.isEmpty() || pkg.contains("clock", ignoreCase = true) ||
                pkg.contains("alarm", ignoreCase = true) ||
                pkg.contains("sleep", ignoreCase = true) ||
                pkg.contains("deskclock", ignoreCase = true) ||
                pkg.contains("sec", ignoreCase = true)
        } ?: false
        val genuineAlarm = if (isUserAlarm) next else null

        val fallbackFormatted =
            try {
                Settings.System.getString(context.contentResolver, Settings.System.NEXT_ALARM_FORMATTED)
                    ?.takeIf { it.isNotBlank() }
            } catch (_: Exception) {
                null
            }

        val hasAlarm = genuineAlarm != null || fallbackFormatted != null
        val triggerTime = genuineAlarm?.triggerTime?.let { Math.floor(it / 1000.0) }

        return Obj(
            mapOf(
                "set" to bool(hasAlarm),
                // Unix seconds, like clock, so fmtTime and `at` take it as is.
                "time" to (triggerTime?.let(::Num) ?: Null),
                "formatted" to (fallbackFormatted?.let(::Str) ?: Null),
                // The clock app that set it, when it says; androidApp(a.app) opens it.
                "app" to (genuineAlarm?.showIntent?.creatorPackage?.let(::Str) ?: Null),
            ),
        )
    }

    /** Emits whenever an alarm is set, changed, dismissed or goes off, or time ticks. */
    fun updates(): Flow<Value> =
        callbackFlow {
            val filter =
                IntentFilter().apply {
                    addAction(AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED)
                    addAction(Intent.ACTION_TIME_TICK)
                    addAction(Intent.ACTION_TIME_CHANGED)
                    addAction(Intent.ACTION_TIMEZONE_CHANGED)
                }
            val receiver = context.registerExported(filter) { trySend(Unit) }
            trySend(Unit)
            awaitClose { context.unregisterReceiver(receiver) }
        }.conflate().map { value() }.distinctUntilChanged()
}
