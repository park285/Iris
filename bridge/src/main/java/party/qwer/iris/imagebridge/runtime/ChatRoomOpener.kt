package party.qwer.iris.imagebridge.runtime

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

internal class ChatRoomOpener(
    context: Context,
    private val mainHandler: Handler = Handler(Looper.getMainLooper()),
    private val chatRoomTypeResolver: (Long) -> String? = { null },
) {
    private val appContext = context.applicationContext

    fun open(roomId: Long) {
        require(roomId > 0L) { "roomId must be positive" }
        val task = Runnable {
            appContext.startActivity(buildChatRoomIntent(roomId))
        }

        if (Looper.myLooper() == Looper.getMainLooper()) {
            task.run()
            return
        }

        val failure = AtomicReference<Throwable?>()
        val latch = CountDownLatch(1)
        val posted =
            mainHandler.post {
                try {
                    task.run()
                } catch (error: Throwable) {
                    failure.set(error)
                } finally {
                    latch.countDown()
                }
            }
        check(posted) { "chatroom open dispatch rejected" }
        check(latch.await(DISPATCH_TIMEOUT_SECONDS, TimeUnit.SECONDS)) { "chatroom open dispatch timed out" }
        failure.get()?.let { throw it }
    }

    private fun buildChatRoomIntent(roomId: Long): Intent =
        Intent()
            .setClassName(KAKAO_PACKAGE, CHATROOM_HOLDER_ACTIVITY)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .apply {
                putExtra(CHAT_ROOM_ID_EXTRA_KEY, roomId)
                chatRoomTypeResolver(roomId)?.takeIf { it.isNotBlank() }?.let { chatRoomType ->
                    putExtra(CHAT_ROOM_TYPE_EXTRA_KEY, chatRoomType)
                }
            }

    private companion object {
        private const val KAKAO_PACKAGE = "com.kakao.talk"
        private const val CHATROOM_HOLDER_ACTIVITY = "com.kakao.talk.activity.chatroom.ChatRoomHolderActivity"
        private const val DISPATCH_TIMEOUT_SECONDS = 5L
        private const val CHAT_ROOM_ID_EXTRA_KEY = "chatRoomId"
        private const val CHAT_ROOM_TYPE_EXTRA_KEY = "chatRoomType"
    }
}
