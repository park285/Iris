package party.qwer.iris

// KakaoTalk 채팅방 타입 판별 유틸리티
internal object KakaoRoomType {
    fun isDirectChat(type: String?): Boolean = type == "DirectChat"

    fun isOpenChat(type: String?): Boolean = type?.startsWith("O") == true
}
