package com.example.gunkanjima

import org.json.JSONObject
import java.net.URL

data class TourAvailability(
    val date: String,
    val company: String,
    val period: String,   // "AM" or "PM"
    val status: String    // "ok", "limited", "cancel", "unknown"
)

data class ScrapeResult(
    val availabilities: List<TourAvailability>,
    val companies: List<String>
)

object CalendarScraper {

    // GitHubユーザー名を自分のものに変更してください
    private const val DATA_URL =
        "https://raw.githubusercontent.com/qunel/GunkanjimaReservationAlert/main/data.json"

    fun scrape(): ScrapeResult {
        val json = URL(DATA_URL).readText()
        val root = JSONObject(json)

        val companies = mutableListOf<String>()
        val companiesArray = root.getJSONArray("companies")
        for (i in 0 until companiesArray.length()) {
            companies.add(companiesArray.getString(i))
        }

        val availabilities = mutableListOf<TourAvailability>()
        val avArray = root.getJSONArray("availabilities")
        for (i in 0 until avArray.length()) {
            val obj = avArray.getJSONObject(i)
            availabilities.add(
                TourAvailability(
                    date    = obj.getString("date"),
                    company = obj.getString("company"),
                    period  = obj.getString("period"),
                    status  = obj.getString("status")
                )
            )
        }

        return ScrapeResult(availabilities = availabilities, companies = companies)
    }

    /**
     * ページ上の日付文字列がユーザー指定の日付に一致するか判定。
     * targetDate は "M月d日" 形式（例: "4月13日"）
     * pageDate は "2026-04-13"、"4月13日(月)"、"4/13(月)" など表記ゆれあり
     */
    fun dateMatches(pageDate: String, targetDate: String): Boolean {
        val normalized = pageDate.replace(Regex("\\s"), "")

        if (normalized.contains(targetDate)) return true

        val m = Regex("(\\d{1,2})月(\\d{1,2})日").find(targetDate) ?: return false
        val month = m.groupValues[1].toInt()
        val day   = m.groupValues[2].toInt()

        if (normalized.contains("-%02d-%02d".format(month, day))) return true
        if (normalized.contains("$month/$day")) return true

        return false
    }
}
