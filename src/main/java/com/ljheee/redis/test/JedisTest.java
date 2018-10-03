package com.ljheee.redis.test;

import redis.clients.jedis.Jedis;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

/**
 * Created by lijianhua04 on 2018/9/7.
 */
public class JedisTest {

    static Jedis jedis = new Jedis("127.0.0.1", 6379);

    public static void main(String[] args) {

        System.out.println(jedis.set("abc", "110"));
        System.out.println(jedis.get("mt"));


    }

}
