// ConfigPageDocumentProvider.java
package party.qwer.iris

class PageRenderer {
    companion object {
        // 템플릿은 변경되지 않으므로 lazy 캐싱
        private val dashboardTemplate: String by lazy {
            AssetManager.readFile("dashboard.html")
        }

        private fun escapeHtml(value: String): String =
            buildString(value.length) {
                value.forEach { ch ->
                    when (ch) {
                        '&' -> append("&amp;")
                        '<' -> append("&lt;")
                        '>' -> append("&gt;")
                        '"' -> append("&quot;")
                        '\'' -> append("&#39;")
                        else -> append(ch)
                    }
                }
            }

        fun renderDashboard(): String {
            return dashboardTemplate
                .replace("CURRENT_BOT_NAME", escapeHtml(Configurable.botName))
                .replace("CURRENT_WEB_ENDPOINT", escapeHtml(Configurable.defaultWebhookEndpoint))
                .replace("CURRENT_DB_RATE", Configurable.dbPollingRate.toString())
                .replace("CURRENT_SEND_RATE", Configurable.messageSendRate.toString())
                .replace("CURRENT_BOT_PORT", Configurable.botSocketPort.toString())
        }
    }
}
