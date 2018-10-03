package com.ljheee.redis.core;

import java.io.IOException;
import java.io.OutputStream;

/**
 * redis 消息协议层
 */
public class RedisProtocol {

    public static final String DOLLAR_BYTE = "$";
    public static final String ASTERISK_BYTE = "*";
    public static final String BLANK_STRING = "\r\n";

    /**
     * redis操作命令 枚举
     */
    public static enum Command {
        PING, SET, GET
    }

    /**
     * 发送命令
     *
     * @param os
     * @param command
     * @param args
     */
    public static void sendCommand(OutputStream os, Command command, byte[]... args) {
        StringBuffer sb = new StringBuffer();
        sb.append(ASTERISK_BYTE).append(args.length + 1).append(BLANK_STRING);
        sb.append(DOLLAR_BYTE).append(command.name().length()).append(BLANK_STRING);
        sb.append(command.name()).append(BLANK_STRING);

        for (byte[] arg : args) {
            sb.append(DOLLAR_BYTE).append(arg.length).append(BLANK_STRING);
            sb.append(new String(arg)).append(BLANK_STRING);
        }

        try {
            os.write(sb.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


}
