package tw.com.tft.aiworkos

data class CaseItem(
    val id: Long,
    val title: String,
    val company: String,
    val person: String,
    val role: String,
    val type: String,
    val summary: String,
    val status: String,
    val priority: String,
    val nextAction: String,
    val assignedTo: String,
    val dueAt: Long?,
    val waitingFor: String,
    val confidence: Double,
    val createdAt: Long,
    val updatedAt: Long,
    val lastActivityAt: Long
)

data class InboxItem(
    val id: Long,
    val source: String,
    val text: String,
    val groupName: String,
    val sender: String,
    val attachmentPath: String?,
    val mimeType: String?,
    val status: String,
    val analysisJson: String?,
    val createdAt: Long
)

data class ChecklistItem(
    val id: Long,
    val caseId: Long,
    val label: String,
    val done: Boolean,
    val position: Int,
    val evidence: String
)

data class SearchHit(val kind: String, val id: Long, val title: String, val subtitle: String)


data class AttachmentItem(
    val id: Long,
    val caseId: Long?,
    val inboxId: Long?,
    val path: String,
    val mimeType: String,
    val displayName: String,
    val createdAt: Long
)
