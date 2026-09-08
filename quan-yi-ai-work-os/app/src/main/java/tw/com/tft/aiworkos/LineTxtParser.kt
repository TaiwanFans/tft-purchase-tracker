package tw.com.tft.aiworkos

object LineTxtParser {
    data class Chunk(val groupName: String, val text: String)

    fun parse(fileName: String, raw: String, maxMessagesPerChunk: Int = 40): List<Chunk> {
        val lines = raw.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val normalized = mutableListOf<String>()
        var currentDate = ""
        val timeRegex = Regex("^(上午|下午)?\\s*\\d{1,2}:\\d{2}")
        val dateRegex = Regex("^\\d{4}[./-]\\d{1,2}[./-]\\d{1,2}")

        lines.forEach { original ->
            val line = original.trimEnd()
            if (line.isBlank()) return@forEach
            if (dateRegex.containsMatchIn(line) && !timeRegex.containsMatchIn(line)) {
                currentDate = line.trim()
                return@forEach
            }
            val tab = line.split('\t')
            if (tab.size >= 3 && (timeRegex.containsMatchIn(tab[0]) || Regex("^\\d{1,2}:\\d{2}").containsMatchIn(tab[0]))) {
                val time = tab[0].trim()
                val sender = tab[1].trim()
                val message = tab.drop(2).joinToString(" ").trim()
                normalized += listOf(currentDate, time, sender, message).filter { it.isNotBlank() }.joinToString("｜")
            } else {
                normalized += if (currentDate.isBlank()) line else "$currentDate｜$line"
            }
        }

        if (normalized.isEmpty()) return listOf(Chunk(fileName.substringBeforeLast('.'), raw.take(12000)))
        return normalized.chunked(maxMessagesPerChunk).mapIndexed { index, chunk ->
            Chunk(
                groupName = fileName.substringBeforeLast('.'),
                text = "【LINE TXT 匯入 第${index + 1}段】\n" + chunk.joinToString("\n")
            )
        }
    }
}
