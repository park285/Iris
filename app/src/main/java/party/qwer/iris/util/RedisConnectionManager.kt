package party.qwer.iris.util

import party.qwer.iris.Configurable
import party.qwer.iris.IrisLogger
import redis.clients.jedis.JedisPooled

/**
 * Redis 연결 풀 싱글톤.
 * Producer와 Consumer가 동일한 풀을 공유하여 연결 수를 절반으로 줄임.
 */
object RedisConnectionManager {
    @Volatile
    private var pool: JedisPooled? = null

    /**
     * 공유 JedisPooled 인스턴스 반환.
     * 최초 호출 시 연결 생성.
     */
    fun getPool(): JedisPooled {
        return pool ?: synchronized(this) {
            pool ?: createPool().also { pool = it }
        }
    }

    private fun createPool(): JedisPooled {
        val host = Configurable.mqHost
        val port = Configurable.mqPort
        IrisLogger.info("[RedisConnectionManager] Creating shared pool: $host:$port")
        return JedisPooled(RedisPoolConfig.createPoolConfig(), host, port)
    }

    /**
     * 풀 닫기 (종료 시 호출).
     */
    fun close() {
        synchronized(this) {
            pool?.close()
            pool = null
            IrisLogger.info("[RedisConnectionManager] Shared pool closed")
        }
    }
}
