package party.qwer.iris

interface ProfileRepository {
    fun upsertObservedProfile(identity: KakaoNotificationIdentity)
}
