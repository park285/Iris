package party.qwer.iris

internal object KakaoRoomType {
    fun isDirectChat(type: String?): Boolean = type == "DirectChat"

    fun isOpenChat(type: String?): Boolean = type?.startsWith("O") == true
}
