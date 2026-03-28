package party.qwer.iris

interface ProfileRepository {
    fun upsertObservedProfile(identity: KakaoNotificationIdentity)

    fun learnObservedProfileUserMappings(
        chatId: Long,
        userDisplayNames: Map<Long, String>,
    )

    fun resolveObservedDisplayName(
        userId: Long,
        chatId: Long? = null,
    ): String?
}
