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

            // 監視対象のスロットで「空きが増えた」ものを収集
            val alerts = mutableListOf<String>()

            for (target in targets) {
                for (avail in scrapeResult.availabilities) {
                    // 日付マッチ
                    if (!CalendarScraper.dateMatches(avail.date, target.date)) continue
                    // AM/PMフィルタ
                    if (avail.period == "AM" && !target.am) continue
                    if (avail.period == "PM" && !target.pm) continue
                    // 会社フィルタ（空 = 全社）
                    if (target.selectedCompanies.isNotEmpty() &&
                        avail.company !in target.selectedCompanies) continue

                    val key        = "${avail.date}__${avail.company}__${avail.period}"
                    val prevStatus = previousState[key]
                    val newStatus  = avail.status

                    // キャンセル/未知 → 空きあり or 限定的 に変化した場合だけ通知
                    val wasUnavailable = prevStatus == "cancel" || prevStatus == null
                    val isAvailable    = newStatus == "ok" || newStatus == "limited"

                    if (wasUnavailable && isAvailable) {
                        val label = if (newStatus == "ok") "空きあり ○" else "空き限定 △"
                        alerts.add("${avail.date} ${avail.period}  ${avail.company}  $label")
                    }
                }
            }

            if (alerts.isNotEmpty()) {
                NotificationHelper.sendNotification(
                    applicationContext,
                    "軍艦島ツアーに空きが出ました！",
                    alerts.joinToString("\n")
                )
            }

            prefs.savePreviousState(newState)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
