package wjw.shiro.redis;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.apache.shiro.cache.Cache;
import org.apache.shiro.cache.CacheException;
import org.apache.shiro.cache.CacheManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RedisCacheManager implements CacheManager {

  private static final Logger logger = LoggerFactory.getLogger(RedisCacheManager.class);

  // fast lookup by name map
  @SuppressWarnings("rawtypes")
  private final ConcurrentMap<String, Cache> caches = new ConcurrentHashMap<String, Cache>();

  private RedisManager redisManager;

  /**
   * The Redis key prefix for caches
   */
  private String keyPrefix = "shiro_cache:";

  /**
   * Returns the Redis cache keys prefix.
   * 
   * @return The prefix
   */
  public String getKeyPrefix() {
    return keyPrefix;
  }

  /**
   * Sets the Redis cache key prefix.
   * 
   * @param keyPrefix
   *          The prefix
   */
  public void setKeyPrefix(String keyPrefix) {
    this.keyPrefix = keyPrefix;
  }

  @Override
  @SuppressWarnings("unchecked")
  public <K, V> Cache<K, V> getCache(String name) throws CacheException {
    logger.debug("��ȡ����Ϊ: " + name + " ��RedisCacheʵ��");

    Cache<K, V> c = caches.get(name);

    if (c == null) {

      // initialize the Redis manager instance
      redisManager.init();

      // create a new cache instance
      c = new RedisCache<K, V>(redisManager, keyPrefix);

      // add it to the cache collection
      caches.put(name, c);
    }
    return c;
  }

  public RedisManager getRedisManager() {
    return redisManager;
  }

  public void setRedisManager(RedisManager redisManager) {
    this.redisManager = redisManager;
  }

}