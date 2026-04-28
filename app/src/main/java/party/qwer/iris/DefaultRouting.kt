package party.qwer.iris

const val CHATBOTGO_ROUTE = "chatbotgo"
const val SETTLEMENT_ROUTE = "settlement"
const val IMAGE_MESSAGE_TYPE_PHOTO = "2"

val DEFAULT_COMMAND_ROUTE_PREFIXES: Map<String, List<String>> =
    linkedMapOf(
        SETTLEMENT_ROUTE to
            listOf(
                "!정산",
                "!정산완료",
            ),
        CHATBOTGO_ROUTE to
            listOf(
                "!질문",
                "!프로필",
                "!누구",
                "!세션",
                "!모델",
                "!온도",
                "!이미지",
                "!그림",
                "!리셋",
                "!관리자",
                "!한강",
                "!시뮬",
                "!법령",
                "!변환",
            ),
    )

val DEFAULT_IMAGE_MESSAGE_TYPE_ROUTES: Map<String, List<String>> =
    linkedMapOf(
        CHATBOTGO_ROUTE to listOf(IMAGE_MESSAGE_TYPE_PHOTO),
    )
