package party.qwer.iris.util

import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import redis.clients.jedis.Connection
import java.time.Duration

object RedisPoolConfig {
    // optimized for single Android instance
    private const val DEFAULT_MAX_TOTAL = 8
    private const val DEFAULT_MAX_IDLE = 4
    private const val DEFAULT_MIN_IDLE = 2
    private val EVICTION_RUN_INTERVAL = Duration.ofSeconds(30)

    fun createPoolConfig(
        maxTotal: Int = DEFAULT_MAX_TOTAL,
        maxIdle: Int = DEFAULT_MAX_IDLE,
        minIdle: Int = DEFAULT_MIN_IDLE,
    ): GenericObjectPoolConfig<Connection> {
        return GenericObjectPoolConfig<Connection>().apply {
            this.maxTotal = maxTotal
            this.maxIdle = maxIdle
            this.minIdle = minIdle
            // 요청 시 PING 제거 → 반환 시 검증으로 오버헤드 감소
            setTestOnBorrow(false)
            setTestOnReturn(true)
            setTestWhileIdle(true)
            // 주기적 연결 검증 (30초마다)
            setTimeBetweenEvictionRuns(EVICTION_RUN_INTERVAL)
            setJmxEnabled(false)
        }
    }
}
