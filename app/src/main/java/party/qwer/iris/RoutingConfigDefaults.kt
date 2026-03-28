package party.qwer.iris

internal val DEFAULT_COMMAND_ROUTE_PREFIXES: Map<String, List<String>> =
    mapOf(
        "settlement" to listOf("!정산", "!정산완료"),
        "chatbotgo" to listOf("!질문", "!이미지", "!그림", "!리셋", "!관리자", "!한강"),
    )

internal val DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES: Map<String, List<String>> =
    mapOf(
        "chatbotgo" to listOf("2"),
    )
