package com.example.gunkanjima

import android.content.Context
import androidx.work.Worker
import androidx.work.WorkerParameters

class MonitorWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        val prefs   = PreferencesManager(applicationContext)
        val targets = prefs.getMonitorTargets()
        if (targets.isEmpty()) return Result.success()

        return try {
            val scrapeResult  = CalendarScraper.scrape()
            prefs.saveKnownCompanies(scrapeResult.companies)

            val previousState = prefs.getPreviousState()

            // 最新の状態マップを構築（全社・全日付）
            val newState = scrapeResult.availabilities.associate { a ->
                "${a.date}__${a.company}__${a.period}" to a.status
            }

            // 監視対象のスロットで「空きが増えた」ものを収集（会社ごと）
            // Pair: message to company
            val alerts = mutableListOf<Pair<String, String>>()

            for (target in targets) {
                for (avail in scrapeResult.availabilities) {
                    if (!CalendarScraper.dateMatches(avail.date, target.date)) continue
                    if (avail.period == "AM" && !target.am) continue
                    if (avail.period == "PM" && !target.pm) continue
                    if (target.selectedCompanies.isNotEmpty() &&
                        avail.company !in target.selectedCompanies) continue

                    val key        = "${avail.date}__${avail.company}__${avail.period}"
                    val prevStatus = previousState[key]
                    val newStatus  = avail.status

                    val wasUnavailable = prevStatus == "cancel" || prevStatus == null
                    val isAvailable    = newStatus == "ok" || newStatus == "limited"

                    if (wasUnavailable && isAvailable) {
                        val label = if (newStatus == "ok") "空きあり ○" else "空き限定 △"
                        val msg = "${avail.date} ${avail.period}  $label\nタップして予約する"
                        alerts.add(msg to avail.company)
                    }
                }
            }

            for ((message, company) in alerts) {
                NotificationHelper.sendNotification(
                    applicationContext,
                    "【${company}】予約空きが出ました！",
                    message,
                    company
                )
            }

            prefs.savePreviousState(newState)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
