package com.kakao.talk.connection

import android.os.Parcel
import android.os.Parcelable

// 카카오톡 내부 CommunityThreadShareInfo와 동일한 패키지/클래스명
// 쓰레드 이미지 전송을 위해 ACTION_SEND Intent에 Parcelable로 전달
data class CommunityThreadShareInfo(
    val chatId: Long,
    val threadId: Long,
    val referrer: String,
) : Parcelable {
    override fun writeToParcel(
        dest: Parcel,
        flags: Int,
    ) {
        dest.writeLong(chatId)
        dest.writeLong(threadId)
        dest.writeString(referrer)
    }

    override fun describeContents(): Int = 0

    companion object CREATOR : Parcelable.Creator<CommunityThreadShareInfo> {
        override fun createFromParcel(parcel: Parcel): CommunityThreadShareInfo =
            CommunityThreadShareInfo(
                parcel.readLong(),
                parcel.readLong(),
                parcel.readString() ?: "",
            )

        override fun newArray(size: Int): Array<CommunityThreadShareInfo?> = arrayOfNulls(size)
    }
}
