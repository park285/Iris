package party.qwer.iris.util

import org.apache.commons.pool2.impl.GenericObjectPoolConfig
import redis.clients.jedis.Connection

object RedisPoolConfig {
    private const val DEFAULT_MAX_TOTAL = 32
    private const val DEFAULT_MAX_IDLE = 16
    private const val DEFAULT_MIN_IDLE = 8

    fun createPoolConfig(
        maxTotal: Int = DEFAULT_MAX_TOTAL,
        maxIdle: Int = DEFAULT_MAX_IDLE,
        minIdle: Int = DEFAULT_MIN_IDLE,
    ): GenericObjectPoolConfig<Connection> {
        return GenericObjectPoolConfig<Connection>().apply {
            this.maxTotal = maxTotal
            this.maxIdle = maxIdle
            this.minIdle = minIdle
            setTestOnBorrow(true)
            setTestWhileIdle(true)
            setJmxEnabled(false)
        }
    }
}
