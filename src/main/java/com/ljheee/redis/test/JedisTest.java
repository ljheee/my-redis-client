package com.ljheee.redis.test;

import redis.clients.jedis.Jedis;

/**
 * 和 SocketListener 配合使用
 * SocketListener启动后，运行JedisTest
 */
public class JedisTest {

    static Jedis jedis = new Jedis("127.0.0.1", 6379);

    public static void main(String[] args) {

        System.out.println(jedis.set("abc", "110"));
        System.out.println(jedis.get("mt"));

    }

}
