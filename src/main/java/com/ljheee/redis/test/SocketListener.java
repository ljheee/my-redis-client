package com.ljheee.redis.test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * SocketListener 配合与JedisTest 一起使用
 * SocketListener 先启动，读取并打印Jedis客户端发来的数据
 */
public class SocketListener {

    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(6379);
        Socket socket = serverSocket.accept();
        byte[] bytes = new byte[1024];
        socket.getInputStream().read(bytes);
        System.out.println(new String(bytes));
    }

}
