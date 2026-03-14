package com.example.gunkanjima

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class MonitorTarget(
    val date: String,               // "M月d日" 形式 例: "3月20日"
    val am: Boolean,
    val pm: Boolean,
    val selectedCompanies: List<String> = emptyList()  // 空 = 全社
)

class PreferencesManager(context: Context) {

    private val prefs = context.getSharedPreferences("gunkanjima_prefs", Context.MODE_PRIVATE)

    // ---- 監視ターゲット ----

    fun getMonitorTargets(): MutableList<MonitorTarget> {
        val json = prefs.getString("monitor_targets", "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { i ->
            val obj = array.getJSONObject(i)
            val companiesArr = obj.optJSONArray("companies") ?: JSONArray()
            MonitorTarget(
                date              = obj.getString("date"),
                am                = obj.optBoolean("am", true),
                pm                = obj.optBoolean("pm", true),
                selectedCompanies = (0 until companiesArr.length()).map { companiesArr.getString(it) }
            )
        }.toMutableList()
    }

    fun saveMonitorTargets(targets: List<MonitorTarget>) {
        val array = JSONArray()
        targets.forEach { t ->
            array.put(JSONObject().apply {
                put("date", t.date)
                put("am", t.am)
                put("pm", t.pm)
                put("companies", JSONArray().also { arr -> t.selectedCompanies.forEach { arr.put(it) } })
            })
        }
        prefs.edit().putString("monitor_targets", array.toString()).apply()
    }

    // ---- 前回の取得状態 ----

    fun getPreviousState(): Map<String, String> {
        val json = prefs.getString("previous_state", "{}") ?: "{}"
        val obj = JSONObject(json)
        return obj.keys().asSequence().associateWith { obj.getString(it) }
    }

    fun savePreviousState(state: Map<String, String>) {
        val obj = JSONObject()
        state.forEach { (k, v) -> obj.put(k, v) }
        prefs.edit().putString("previous_state", obj.toString()).apply()
    }

    // ---- 既知の会社リスト（スクレイプ後に更新）----

    fun getKnownCompanies(): List<String> {
        val json = prefs.getString("known_companies", "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }
    }

    fun saveKnownCompanies(companies: List<String>) {
        val array = JSONArray()
        companies.forEach { array.put(it) }
        prefs.edit().putString("known_companies", array.toString()).apply()
    }

    // ---- 選択中の会社（空 = 全社）----

    fun getSelectedCompanies(): Set<String> {
        val json = prefs.getString("selected_companies", "[]") ?: "[]"
        val array = JSONArray(json)
        return (0 until array.length()).map { array.getString(it) }.toSet()
    }

    fun saveSelectedCompanies(companies: Set<String>) {
        val array = JSONArray()
        companies.forEach { array.put(it) }
        prefs.edit().putString("selected_companies", array.toString()).apply()
    }

    // ---- 監視ON/OFF ----

    fun isMonitoring(): Boolean = prefs.getBoolean("is_monitoring", false)

    fun setMonitoring(value: Boolean) {
        prefs.edit().putBoolean("is_monitoring", value).apply()
    }
}
