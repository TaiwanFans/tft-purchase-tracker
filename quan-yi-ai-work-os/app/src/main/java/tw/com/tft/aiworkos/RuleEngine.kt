package tw.com.tft.aiworkos

import java.util.concurrent.TimeUnit

data class LeakWarning(val caseId: Long, val title: String, val reason: String, val suggestion: String, val severity: Int)

object RuleEngine {
    private val waitingStatuses = setOf("等客戶", "等廠商", "等老闆", "等內部", "待追蹤")

    fun findWarnings(cases: List<CaseItem>, now: Long = System.currentTimeMillis()): List<LeakWarning> {
        val out = mutableListOf<LeakWarning>()
        cases.filter { it.status !in setOf("已完成", "封存", "暫停") }.forEach { c ->
            val days = TimeUnit.MILLISECONDS.toDays((now - c.lastActivityAt).coerceAtLeast(0))
            if (c.dueAt != null && c.dueAt < now) {
                out += LeakWarning(c.id, c.title, "期限已過", "立即確認目前進度與新的完成時間", 100)
            }
            if (c.priority == "urgent" && days >= 1) {
                out += LeakWarning(c.id, c.title, "急件超過 ${days} 天未更新", "今天優先處理", 95)
            }
            if (c.status in waitingStatuses && days >= 2) {
                val who = c.waitingFor.ifBlank { c.status.removePrefix("等") }
                out += LeakWarning(c.id, c.title, "${days} 天沒有新進度", "追蹤${who.ifBlank { "對方" }}", 80)
            } else if (days >= 4) {
                out += LeakWarning(c.id, c.title, "${days} 天未更新", "確認是否漏掉下一步", 60)
            }
            if ((c.type.contains("報價") || c.type.contains("詢價")) && c.status != "已完成" && c.nextAction.isBlank()) {
                out += LeakWarning(c.id, c.title, "業務案件沒有下一步", "補上報價、型錄、照片、電話或追蹤動作", 70)
            }
            if (c.type.contains("採購") && c.status != "已完成" && c.nextAction.isBlank()) {
                out += LeakWarning(c.id, c.title, "採購案件沒有下一步", "確認簽回、交期、生產、到貨或驗收", 70)
            }
        }
        return out.distinctBy { "${it.caseId}:${it.reason}" }.sortedByDescending { it.severity }
    }
}
