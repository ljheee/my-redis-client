
Redis底层协议RESP详解
#### RESP 
> 文章开始前，先放出两道面试题
1.Redis底层，使用的什么协议?
2.RESP是什么，在Redis怎么体现的?

带着这两个问题，来一探究竟。

#### 什么是 RESP？
是基于TCP的应用层协议 RESP(REdis Serialization Protocol)；
RESP底层采用的是TCP的连接方式，通过tcp进行数据传输，然后根据解析规则解析相应信息,

Redis 的客户端和服务端之间采取了一种独立名为 RESP(REdis Serialization Protocol) 的协议，作者主要考虑了以下几个点：
- 容易实现
- 解析快
- 人类可读
RESP可以序列化不同的数据类型，如整数，字符串，数组。还有一种特定的错误类型。请求从客户端发送到Redis服务器，作为表示要执行的命令的参数的字符串数组。Redis使用特定于命令的数据类型进行回复。
RESP是二进制安全的，不需要处理从一个进程传输到另一个进程的批量数据，因为它使用前缀长度来传输批量数据。
注意：RESP 虽然是为 Redis 设计的，但是同样也可以用于其他 C/S 的软件。Redis Cluster使用不同的二进制协议(gossip)，以便在节点之间交换消息。

关于协议的具体描述，官方文档 https://redis.io/topics/protocol


#### RESP协议说明
RESP协议是在Redis 1.2中引入的，但它成为了与Redis 2.0中的Redis服务器通信的标准方式。这是所有Redis客户端都要遵循的协议，我们甚至可以基于此协议，开发实现自己的Redis客户端。
RESP实际上是一个支持以下数据类型的序列化协议：简单字符串，错误类型，整数，批量字符串和数组。

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

看了RESP的协议说明，我们该如何验证呢？

#### RESP验证
既然我们知道，Redis客户端与server端通信，本身就是基于tcp的一个Request/Response模式。并且jedis与redis底层通信基于socket，是遵循resp通信协议。
我们不妨用网络抓包工具，拦截客户端与server端传输的数据、一探究竟。
实验：使用jedis客户端向server端发送命令，拦截TCP数据传输(Redis 6379端口)，深度探究RESP协议。
这里使用的工具自行发挥，tcpdump、wireshark均可。目标就是抓到6379端口的传输数据。

我这里选择了一种取巧的方式：
因为我们经常使用redis-cli客户端操作Redis，既然redis-cli作为官方提供的Redis客户端，必然遵循了RESP协议；
我们使用redis-cli操作redis-server，大胆推断肯定是redis-cli基于RESP和redis-server的6379端口进行了通信。
那么，我们使用一个demo程序(SocketListener)，监听本地的6379端口，接收到数据就打印；
另外，使用redis-cli客户端执行set key value，看看向6379端口发送了什么。
ps：SocketListener先运行，再在redis-cli窗口执行命令。

当用redis-cli客户端发送命令时会打印
```
public class SocketListener {
    public static void main(String[] args) throws IOException {
        ServerSocket serverSocket = new ServerSocket(6379);
        Socket socket = serverSocket.accept();
        byte[] bytes = new byte[1024];
        socket.getInputStream().read(bytes);
        System.out.println(new String(bytes));
    }
}
```
如果没有TCP监控工具的同学，可以通过这种方式验证。


最后给出拦截到的TCP数据：
SET key value #对应的resp通信协议串
```
*3
$3
SET
$3
key
$5
value
```
第一次看到这个通信协议串，看不懂不必担心，我们按照RESP的协议说明慢慢看，并且下文会有详细的讲解。
这里大概翻译一下这段传输的数据含义：
- 第一行*3表示这条发给Redis server的命令是数组，数组有3个元素(其实就是SET、key、value这仨字符串)；
后面的6行数据，分别是对数组三个元素的表示，每个元素用两行；
- 数组第一个元素：`$3 SET` $3代表Bulk Strings字符串长度为3，内容是SET。
- 数组第二个元素：`$3 key` $3代表Bulk Strings字符串长度为3，key。
- 数组第三个元素：`$5 value` $5代表Bulk Strings字符串长度为5，内容是value。

是不是很简单呢。RESP协议传输的数据，不仅人类可读、容易实现，还解析快。
我们继续验证，Redis最常用的客户端工具jedis是否也是同样的格式呢？
```
    public static void main(String[] args) {
        Jedis jedis = new Jedis("127.0.0.1", 6379);
        System.out.println(jedis.set("abc", "110"));
        System.out.println(jedis.get("mt"));
    }
```
结果是肯定的，使用TCP抓包工具、获取到Jedis客户端发给Redis server的数据也是上面的格式。

###### Jedis客户端小结
Jedis跟redis通过socket建立通信。
Jedis与redis服务进行交互通信，本质是通过socke（长连接），发送由resp协议规定的指令集。
AOF持久化方式：就是存储了resp指令。可以查看.aof持久化文件。

---

下面我们一一来看RESP支持的序列化数据类型：简单字符串，错误类型，整数，批量字符串和数组。
说明：下面的类型说明中，`\r\n`都是显示的添加上去的，是为了让大家理解RESP协议实际数据传输的格式，在redis-cli客户端中，命令执行返回的响应，是

####　简单字符串Simple Strings
简单字符串按以下方式编码：`+`字符，后跟不能包含CR或LF字符的字符串（不允许换行），由CRLF终止（即“\r\n”）。
Simple Strings用于以最小的开销、传输非二进制安全字符串。例如，许多Redis命令在成功时仅回复“OK”，因为RESP Simple String使用以下5个字节进行编码：
`+OK\r\n`
当Redis使用Simple String回复时，该字符串由'+'之后的第一个字符组成，直到字符串结尾，不包括最终的CRLF字节。


####　RESP错误
RESP具有特定的错误数据类型。实际上错误与RESP Simple Strings完全相同，但第一个字符是减`-`字符而不是加号。RESP中简单字符串和错误之间的真正区别在于客户端将错误视为异常，组成错误类型的字符串是错误消息本身。
基本格式是：`-ERR errorMsg\r\n`
错误回复仅在发生错误时发送，例如，如果你尝试对错误的数据类型执行操作，或者命令不存在等等。收到错误答复时，库客户端应引发异常。
以下是错误回复的示例：
```
-ERR unknown command 'foobar'
-WRONGTYPE Operation against a key holding the wrong kind of value
```
“-”之后的第一个单词，直到第一个空格或换行符，表示返回的错误类型。这只是Redis使用的约定，不是RESP错误格式的一部分。
例如，ERR是一般错误，而WRONGTYPE更具体的错误意味着客户端尝试对错误的数据类型执行操作。这称为错误前缀，是一种允许客户端理解服务器返回的错误类型的方法，而不依赖于给定的确切消息，这可能随时间而变化。
下面是几个使用redis-cli的实际错误的例子：
```
127.0.0.1:6379> TaoBeier
-ERR unknown command 'TaoBeier'\r\n  # 服务端实际返回, 下同
---
(error) ERR unknown command 'TaoBeier'  # redis-cli 客户端显示, 下同
127.0.0.1:6379> set name TaoBeier love
-ERR syntax error\r\n
---
(error) ERR syntax error
```
客户端实现可以针对不同的错误返回不同类型的异常，或者可以通过直接将错误名称作为字符串提供给调用者来提供捕获错误的通用方法。
但是，错误类型很少有用，并且有限的客户端实现可能只是返回一般的错误条件，例如false。


####　整数类型
此类型只是一个CRLF终止的字符串，表示一个以`：`字节为前缀的整数。例如`:0 \r\n`或`:1000 \r\n`是整数回复。

很多Redis命令返回RESP整数类型，比如INCR，LLEN和LASTSAVE。
返回的整数没有特殊含义，它只是INCR的增量数，LASTSAVE的UNIX时间等等。但是，返回的整数保证在有符号的64位整数范围内。

整数回复也被广泛使用以返回真或假。例如，EXISTS或SISMEMBER之类的命令将返回1表示true，0表示false表示。
如果操作实际执行，其他命令如SADD，SREM和SETNX将返回1，否则返回0。
下面的命令都是整数类型回复：SETNX，DEL， EXISTS，INCR，INCRBY，DECR，DECRBY，DBSIZE，LASTSAVE， RENAMENX，MOVE，LLEN，SADD，SREM，SISMEMBER，SCARD。


####　Bulk Strings类型
翻译过来，是指批量、多行字符串。
Bulk Strings用于表示长度最大为512MB的单个二进制安全字符串。
批量字符串按以下方式编码：
- 一个“$”字节后跟组成字符串的字节数（一个前缀长度），由CRLF终止。
- 实际的字符串数据。
- 最终的CRLF。
所以字符串“foobar”的编码如下：
```
$6\r\n
foobar\r\n"
```
一个完整的Bulk Strings，主要包括两行：
第一行，$后面跟上字符串长度；
第二行，就是实际的字符串。
如下面执行set、get的例子：

```
127.0.0.1:6379> set site ljheee
+OK\r\n  # 服务端实际返回, 下同
---
OK   # redis-cli 客户端显示, 下同
127.0.0.1:6379> get site
$6\r\
ljheee\r\n
---
"ljheee"
```
在执行`set site value`时，客户端给Redis server发送RESP命令后，Redis server返回的是simple strings类型`+OK\r\n`，redis-cli命令行客户端给我们只显示了有效字符、省略了最后的CRLF。
在执行`get site`时，Redis server返回的是Bulk Strings类型，第一行`$6`代表site对应的value值length为6，第二行是实际value值。

当只是一个空字符串时，表示为：`$0\r\n`
Bulk Strings也可用于使用用于表示Null值的特殊格式来表示值的不存在。在这种特殊格式中，长度为-1，并且没有数据，因此Null表示为：`$-1\r\n`，这称为Null Bulk String。

####　数组类型
客户端使用数组、将命令发送到Redis服务器。类似地，某些Redis命令将元素集合返回给客户端使用数组类型回复。如LRANGE命令，它返回元素列表其实就是数组类型。


RESP数组使用以下格式发送：
- 它以 “*” 开头，后面跟着返回元素的个数，后跟CRLF。
- 然后就是数组中各元素自己的类型了，数组每个元素可以是任意的RESP类型。

最典型的是 `LRRANGE` 命令，返回的就是数组类型
```
LRANGE info 0 -1
*2\r\n
$3\r\
abc\r\n
$6\r\n
ljheee\r\n
--- # 实际redis-cli显示
1) "abc"
2) "ljheee"

```
返回的结果，*2代表数组长度为2，数组的第一个元素$3是长度为三的字符串abc；数组的第二个元素$6是长度为6的字符串ljheee。

好了，到这里我们了解了Redis底层通信协议的各个类型，基于此，我们可以实现自己的Redis客户端。
（手写Redis客户端，请看下文）

