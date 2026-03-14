package com.example.gunkanjima

import android.Manifest
import android.app.DatePickerDialog
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.work.*
import com.example.gunkanjima.databinding.ActivityMainBinding
import java.util.Calendar
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: PreferencesManager
    private lateinit var adapter: TargetAdapter
    private val targets = mutableListOf<MonitorTarget>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        NotificationHelper.createNotificationChannel(this)
        requestNotificationPermission()

        prefs = PreferencesManager(this)
        targets.addAll(prefs.getMonitorTargets())

        adapter = TargetAdapter(
            targets,
            onDelete = { position ->
                targets.removeAt(position)
                prefs.saveMonitorTargets(targets)
                adapter.notifyItemRemoved(position)
                updateEmptyView()
            },
            onEditCompany = { position -> showCompanyDialog(position) }
        )
        binding.recyclerViewTargets.apply {
            layoutManager = LinearLayoutManager(this@MainActivity)
            adapter = this@MainActivity.adapter
            addItemDecoration(DividerItemDecoration(context, DividerItemDecoration.VERTICAL))
        }

        updateEmptyView()

        // 起動時に常時監視を確保（初回 or 強制終了・再起動後の復元）
        ensureMonitoring()

        // 日付追加ボタン
        binding.btnAddDate.setOnClickListener {
            val cal = Calendar.getInstance()
            DatePickerDialog(
                this,
                { _, _year, month, day ->
                    val dateStr = "${month + 1}月${day}日"
                    if (targets.any { it.date == dateStr }) {
                        Toast.makeText(this, "「$dateStr」は既に追加されています", Toast.LENGTH_SHORT).show()
                        return@DatePickerDialog
                    }
                    showAddDialog(dateStr)
                },
                cal.get(Calendar.YEAR),
                cal.get(Calendar.MONTH),
                cal.get(Calendar.DAY_OF_MONTH)
            ).show()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
    }

    /** 初回起動・復帰時に監視を確実に動かす */
    private fun ensureMonitoring() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        // 初回のみ：前回状態をリセットして即時チェック
        if (!prefs.isMonitoring()) {
            prefs.savePreviousState(emptyMap())
            WorkManager.getInstance(this).enqueue(
                OneTimeWorkRequestBuilder<MonitorWorker>().setConstraints(constraints).build()
            )
            prefs.setMonitoring(true)
        }

        // 定期チェック（すでに動いていれば何もしない）
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "gunkanjima_monitor",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<MonitorWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()
        )
    }

    private fun updateEmptyView() {
        binding.textEmpty.visibility = if (targets.isEmpty()) View.VISIBLE else View.GONE
    }

    /** 追加直後にその日付の空きを確認して通知 */
    private fun checkNewTargetImmediately(target: MonitorTarget) {
        Thread {
            try {
                val scrapeResult = CalendarScraper.scrape()
                prefs.saveKnownCompanies(scrapeResult.companies)

                val available = mutableListOf<String>()
                val stateUpdates = mutableMapOf<String, String>()

                for (avail in scrapeResult.availabilities) {
                    if (!CalendarScraper.dateMatches(avail.date, target.date)) continue
                    if (avail.period == "AM" && !target.am) continue
                    if (avail.period == "PM" && !target.pm) continue
                    if (target.selectedCompanies.isNotEmpty() &&
                        avail.company !in target.selectedCompanies) continue

                    val key = "${avail.date}__${avail.company}__${avail.period}"
                    stateUpdates[key] = avail.status

                    if (avail.status == "ok" || avail.status == "limited") {
                        val label = "${avail.company}（${avail.period}）"
                        if (label !in available) available.add(label)
                    }
                }

                // MonitorWorker が重複通知しないよう state を先に保存
                val state = prefs.getPreviousState().toMutableMap()
                state.putAll(stateUpdates)
                prefs.savePreviousState(state)

                if (available.isNotEmpty()) {
                    val message = available.joinToString("\n") { "$it で予約が可能です。" }
                    NotificationHelper.sendNotification(
                        applicationContext,
                        "${target.date} 予約可能！",
                        message
                    )
                }
            } catch (_: Exception) { }
        }.start()
    }

    /** 新規追加ダイアログ（AM/PM ＋ ツアー会社を一画面で選択） */
    private fun showAddDialog(dateStr: String) {
        val view = layoutInflater.inflate(R.layout.dialog_add_target, null)
        val checkAM = view.findViewById<android.widget.CheckBox>(R.id.dialogCheckAM)
        val checkPM = view.findViewById<android.widget.CheckBox>(R.id.dialogCheckPM)

        val known = prefs.getKnownCompanies()
        val companyChecked = BooleanArray(known.size) { true }

        // 会社リストを動的に追加
        val companyGroup = view.findViewById<android.widget.LinearLayout>(R.id.companyGroup)
        val companyCheckBoxes = known.map { name ->
            android.widget.CheckBox(this).apply {
                text = name
                isChecked = true
                companyGroup.addView(this)
            }
        }

        AlertDialog.Builder(this)
            .setTitle("${dateStr} を追加")
            .setView(view)
            .setPositiveButton("追加") { _, _ ->
                val am = checkAM.isChecked
                val pm = checkPM.isChecked
                if (!am && !pm) {
                    Toast.makeText(this, "AM / PM どちらかを選択してください", Toast.LENGTH_SHORT).show()
                    return@setPositiveButton
                }
                val selected = known.filterIndexed { i, _ -> companyCheckBoxes[i].isChecked }
                val companies = if (selected.size == known.size) emptyList() else selected
                val target = MonitorTarget(dateStr, am, pm, companies)
                targets.add(target)
                prefs.saveMonitorTargets(targets)
                adapter.notifyItemInserted(targets.size - 1)
                updateEmptyView()
                checkNewTargetImmediately(target)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }

    /** 既存ターゲットの会社選択変更ダイアログ */
    private fun showCompanyDialog(position: Int) {
        val known = prefs.getKnownCompanies()
        if (known.isEmpty()) {
            Toast.makeText(this, "先に監視を開始してツアー会社リストを取得してください", Toast.LENGTH_SHORT).show()
            return
        }
        val target   = targets[position]
        val selected = target.selectedCompanies
        val checked  = BooleanArray(known.size) { selected.isEmpty() || known[it] in selected }

        AlertDialog.Builder(this)
            .setTitle("${target.date}のツアー会社を選択")
            .setMultiChoiceItems(known.toTypedArray(), checked) { _, which, isChecked ->
                checked[which] = isChecked
            }
            .setPositiveButton("OK") { _, _ ->
                val newSelected = known.filterIndexed { i, _ -> checked[i] }
                val companies = if (newSelected.size == known.size) emptyList() else newSelected
                targets[position] = target.copy(selectedCompanies = companies)
                prefs.saveMonitorTargets(targets)
                adapter.notifyItemChanged(position)
            }
            .setNegativeButton("キャンセル", null)
            .show()
    }
}

// ---- RecyclerView Adapter ----

class TargetAdapter(
    private val targets: MutableList<MonitorTarget>,
    private val onDelete: (Int) -> Unit,
    private val onEditCompany: (Int) -> Unit
) : RecyclerView.Adapter<TargetAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val textDate:    TextView    = view.findViewById(R.id.textDate)
        val textPeriod:  TextView    = view.findViewById(R.id.textPeriod)
        val textCompany: TextView    = view.findViewById(R.id.textCompany)
        val btnDelete:   ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_monitor_target, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val target = targets[position]
        holder.textDate.text = target.date
        holder.textPeriod.text = listOfNotNull(
            "AM".takeIf { target.am },
            "PM".takeIf { target.pm }
        ).joinToString(" / ")
        holder.textCompany.text = if (target.selectedCompanies.isEmpty()) {
            "全ツアー会社"
        } else {
            target.selectedCompanies.joinToString("・")
        }
        holder.btnDelete.setOnClickListener { onDelete(holder.bindingAdapterPosition) }
        holder.itemView.setOnClickListener  { onEditCompany(holder.bindingAdapterPosition) }
    }

    override fun getItemCount() = targets.size
}
