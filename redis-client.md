#### 手写Redis客户端-实现自己的Jedis
上一篇，我们详细研究了Redis底层使用的协议——RESP(REdis Serialization Protocol)，这篇我们在基于理解了RESP原理的基础上，手写一个Redis客户端，实现一个自己的Jedis（mini版）。
还没了解Redis底层RESP协议 的同学，可以先移步，对RESP做一个大致的了解。

Redis分为服务器端/客户端，并且客户端除了CLI终端命令行方式，还支持大多数主流编程语言；那么可以大胆推测，这些跨语言的客户端的实现，都是遵循RESP协议规范的；也都是通过网络通信、与服务器端进行连接和传输数据的。

#### RESP回顾
RESP在Redis中用作请求-响应协议的方式如下：
- 客户端将命令作为Bulk Strings的RESP数组发送到Redis服务器。
- 服务器根据命令实现回复一种RESP类型。

在RESP中，某些数据的类型取决于第一个字节：
- “+”代表简单字符串Simple Strings
- “+”代表错误类型
- “:”代表整数
- “$”代表Bulk Strings
- “*”代表数组
此外，RESP能够使用稍后指定的Bulk Strings或Array的特殊变体来表示Null值。
在RESP中，协议的不同部分始终以“\r\n”（CRLF）结束。

通过拦截TCP传输的数据，我们在redis-cli客户端执行SET key value ;
拦截到对应的resp通信协议串
```
*3
$3
SET
$3
key
$5
value
```

基于上篇对RESP的分析，我们应该能快速推断，如果要执行SET abc 1234，对应的resp通信协议串为：
```
*3
$3
SET
$3
abc
$4
1234
```
原理其实很简单；我们实现的客户端，只要将这个协议串，基于socket发送给redis-server，进完成了一个set操作，在redis-server执行了SET abc 1234;
按resp协议的请求-响应模型，如果redis-server执行成功，会返回+OK\r\n；

#### 简洁版Jedis
下面，我们基于以上推断，快速实现一个简洁版Redis客户端；

##### 分析：
1、建立连接
SimpleJedis的构造方法，完成建立连接；
我们平时使用Jedis时，其实本质也是一样的实现；
```
Jedis jedis = new Jedis("172.17.19.22");//创建client
jedis.set("key", "value");//创建socket连接，发送socket流
```

2、set 命令
SimpleJedis#set(K, V)方法执行set命令；
客户端发给redis-server的序列串，就是按前面的分析，分别替换K的长度和实际K内容，以及V的长度和V的内容；

3、get 命令
get(Key)命令，应该给redis-server发送什么样的协议串呢？
这个需要按 上篇文章那样，去拦截TCP传输的数据，以此来窥探。拦截get命令的TCP数据，就不具体演示了，可以参考上一篇文章。
这里我直接给出结论；
当执行 get abc;命令时，发送给redis-server的协议串是：
```
*2
$3
GET
$3
abc
```
第一行*2表示这条发给Redis server的命令是数组，数组有2个元素(其实就是get、abc、这俩字符串)；
后面的4行数据，分别是对数组2个元素的表示，每个元素用两行；具体含义可以参见上一篇文章。

最后给出完整示例：
```
/**
 * 简洁版-Redis客户端
 * A new redis client for Java.
 */
public class SimpleJedis {

    private Socket socket = null;

    public SimpleJedis() {
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

        InputStream inputStream = socket.getInputStream();
        byte b[] = new byte[inputStream.available()];
        inputStream.read(b);
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

        InputStream inputStream = socket.getInputStream();
        byte b[] = new byte[1024];
        int len = inputStream.read(b);
        return new String(b, 0, len).split("\r\n")[1];
    }


    public static void main(String[] args) throws IOException {
        SimpleJedis myJedis = new SimpleJedis();

        System.out.println(myJedis.set("mt", "2020"));//+OK
        System.out.println(myJedis.get("mt"));// "2020"
    }

}
```
启动redis-server后，运行程序，就能验证结果了。




#### 进化版
朝着架构师的目标，向前迈一步。一个可复用的客户端组件，应该怎样设计、实现呢！

建立连接后 传输数据，是redis规范约定的“协议”。
我们在使用Jedis客户端时，直接操作的是API；执行set、get操作，并没有让我们自己进行按“协议”拼装数据；
同时客户端传输数据到server端，对我们使用者来说，也是无感知的。
因此我们不妨也分层来实现。

要实现一个redis客户端，需要考虑
- 传输层 Connection
- 协议层 RedisProtocol
- API层 RedisClient

以下是对应的实现:
###### 传输层 Connection
负责连接的建立，数据发送与接收
```
/**
 * 传输层
 * 负责连接的建立，数据发送与接收
 */
public class Connection {


    private Socket socket;
    private String host;
    private int port;
    private OutputStream outputStream;
    private InputStream inputStream;

    public Connection(String host, int port) {
        this.host = host;
        this.port = port;
    }

    public Connection connection() {
        try {
            socket = new Socket(host, port);
            outputStream = socket.getOutputStream();
            inputStream = socket.getInputStream();
        } catch (IOException e) {
            e.printStackTrace();
        }
        return this;
    }

    public Connection sendCommand(RedisProtocol.Command command, byte[]... args) {
        connection();
        RedisProtocol.sendCommand(outputStream, command, args);
        return this;
    }

    public String getStatusReply() {
        try {
            byte[] bytes = new byte[1024];
            inputStream.available();
            int len = inputStream.read(bytes);
            return new String(bytes, 0, len);
        } catch (IOException e) {
            e.printStackTrace();
        }
        return null;
    }
}
```

###### 协议层 RedisProtocol
负责RESP协议串的拼装；
```
/**
 * redis客户端
 * 消息协议层
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
```

###### API层 RedisClient
顶层API，供用户使用；
```
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
```

#### 举一反三
我们经常使用Jedis，对Redis进行各种操作。一番探究下来，其实本质就是客户端程序和服务端建立连接，在指定的端口传输“指令数据”；
只是“指令数据”，需要按照约定的格式。

Jedis客户端和redis-server之间就是普通的网络通信。特殊之处，就是Redis采用的序列化协议RESP是非二进制、人类可读的。

其实，其他组件的客户端，也是通过类似的模式；
1、MySQL客户端，和mysql-server进行交互，也是通过TCP，默认在3306端口；
2、Zookeeper客户端，和server端交互、传输指令，也是通过TCP连接，默认在2181端口；
3、......
这些客户端和对应server的交互，本身就是基于tcp的一个Request/Response模式。
是不是，可以列举很多呢。其实了解了底层原理，一通百通。

我们实现的mini版 Jedis，只是实现了最核心的set、get操作；然而对于操作Redis来说，虽然这是最基础、最核心的，但真正完备的Redis客户端，还要改支持 诸如断开重连、pipeline管道操作等。
但本文的最终目的并不是重复造一个轮子，而是通过对底层原理的研究，做到反推、和验证，进而举一反三。
（偷偷告诉你，进阶版Jedis更像Jedis的源码哦~）


>本文首发于公众号 架构道与术，欢迎关注、共同进步~