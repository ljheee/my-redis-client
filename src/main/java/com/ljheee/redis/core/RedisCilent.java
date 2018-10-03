package com.ljheee.redis.core;

/**
 * API层
 */
public class RedisCilent {


    private Connection connection;

    public RedisCilent(String host, int port) {
        connection = new Connection(host, port);
    }


    public String set(String key, String value) {
        connection.sendCommand(RedisProtocol.Command.SET, key.getBytes(), value.getBytes());
        return connection.getStatusReply();
    }


    public String get(String key) {
        connection.sendCommand(RedisProtocol.Command.GET, key.getBytes());
        return connection.getStatusReply();
    }


    public static void main(String[] args) {
        RedisCilent cilent = new RedisCilent("127.0.0.1", 6379);
        System.out.println(cilent.set("mt", "2011"));
        System.out.println(cilent.get("mt"));
    }
}
