package com.ljheee.redis.core;


import java.io.IOException;
import java.net.Socket;

/**
 * A new redis client for Java.
 */
public class MyJedis {

    private Socket socket = null;

    public MyJedis() {
        try {
            socket = new Socket("127.0.0.1", 6379);

            socket.setReuseAddress(true);
            socket.setKeepAlive(true);
            socket.setTcpNoDelay(true);
            socket.setSoLinger(true, 0);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    public String set(final String key, String value) throws IOException {

        StringBuilder stringBuilder = new StringBuilder();

        stringBuilder.append("*3").append("\r\n");

        stringBuilder.append("$3").append("\r\n");

        stringBuilder.append("SET").append("\r\n");

        stringBuilder.append("$").append(key.length()).append("\r\n");

        stringBuilder.append(key).append("\r\n");

        stringBuilder.append("$").append(value.length()).append("\r\n");

        stringBuilder.append(value).append("\r\n");
        socket.getOutputStream().write(stringBuilder.toString().getBytes());

        byte b[] = new byte[1024];
        socket.getInputStream().read(b);
        return new String(b);
    }

    public String get0(final String key) throws IOException {

        StringBuilder sb = new StringBuilder();

        sb.append("*2").append("\r\n");

        sb.append("$3").append("\r\n");

        sb.append("GET").append("\r\n");

        sb.append("$6").append("\r\n");

        sb.append("wukong").append("\r\n");

        socket.getOutputStream().write(sb.toString().getBytes());

        byte b[] = new byte[1024];
        socket.getInputStream().read(b);
        return new String(b);
    }

    public String get(final String key) throws IOException {

        StringBuilder sb = new StringBuilder();

        sb.append("*2").append("\r\n");

        sb.append("$3").append("\r\n");

        sb.append("GET").append("\r\n");

        sb.append("$").append(key.length()).append("\r\n");

        sb.append(key).append("\r\n");

        socket.getOutputStream().write(sb.toString().getBytes());

        byte b[] = new byte[1024];
        socket.getInputStream().read(b);
        return new String(b).split("\r\n")[1];
    }


    public static void main(String[] args) throws IOException {
        MyJedis myJedis = new MyJedis();


        System.out.println(myJedis.set("mt","2019"));
        System.out.println(myJedis.get("mt"));


    }

}
