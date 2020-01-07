#### Redis持久化
Redis常用作KV缓存，热点数据都在内存、访问速度极快。
但谁也不能保证服务的100%可用，意外宕机内存中的数据就没了，对一些数据敏感的业务势必会造成影响。
因此，Redis提供了持久化，目的就是将内存中的数据尽量保存到磁盘上来，同时最大可能的不影响原本高性能的读写操作。

1、Redis提供了两种持久化的方式，分别是RDB（Redis DataBase）和AOF（Append Only File）。
	Redis 默认开启了rdb。启动redis-server后，自动生成dump.rdb文件(默认在Redis启动目录下)。
2、RDB，简而言之，就是在不同的时间点，将redis存储的数据生成快照并存储到磁盘等介质上；就像Word文档定时自动保存功能，防止数据丢失。
3、AOF，则是换了一个角度来实现持久化，那就是将redis执行过的所有写指令记录下来，在下次redis重新启动时，只要把这些写指令执行一遍，就可以实现数据恢复了。
4、其实RDB和AOF两种方式也可以同时使用，在这种情况下，如果redis重启的话，则会优先采用AOF方式来进行数据恢复，这是因为AOF方式的数据恢复完整度更高。

#### RDB
Redis 默认开启了rdb持久化。启动redis-server后，自动生成dump.rdb快照文件(默认在Redis启动目录下)。
redis启动时，先加载dump.rdb文件，将磁盘快照加载到Redis内存中。
redis-server启动时 指定redis.conf时，redis.conf内配置的dir选项，指定了.rdb快照文件的路径。

##### 什么时候，会触发rdb持久化?
1、shutdown(正常关闭)时，如果没有开启aof，会触发；
    `kill -9` 意外宕机不会触发rdb持久化；
2、redis.conf默认配置(此配置时针对bgsave):
```
	save 900 1	#900s检查一次，增量的数据变更命令超过1，就触发；
	save 300 10	#300s 更改10次
	sava 60 10000 #60s 更改命令1w条，就触发；
```
> 根据这个默认配置，会丢数据；意外宕机的情况下，丢失最后一次持久化后的数据

ps：如果rdb和aof都开启，一般留第一条开启就行(开启aof时，rdb快照没必要这么频繁)。
前两种都是redis自动触发，底层都是bgsave；

3、执行命令save或者bgsave 
	save是只管保存，其他不管，全部阻塞(持久化是用的主进程，不会fork子进程)；
	bgsave redis会在后台异步进行快照操作(fork子进程)，同时可以响应客户端的请求；
4、执行flushall命令，清空rdb(Redis默认16个库都清空)；但是里面是空的，无意义


##### 数据迁移，就是基于.rdb快照文件；slave获取到主库的.rdb文件后，如何加载？
1、cp拷贝过来的dump500000.rdb，改名成默认dump.rdb；自动bgsave就加载了该rdb文件。
2、修改redis.conf配置 dbfilename dump.rdb;将文件名配置成成cp来的；


`演示1：`手动执行bgsave, 同时在另一个终端立即`ps -ef|grep redis` 查看进程，可以看到fork子进程redis-rdb-bgsave；


`演示2：`手动执行save，手动触发rdb持久化；另一个redis-cli执行set 写请求 会被阻塞(3.9s才返回)。
因为save是主进程，没有fork子进程。

**结论：**
正常rdb持久化(自动触发) 会fork一个子进程进行；
所以rdb持久化不会阻塞用户请求；只会在fork()系统调用时，阻塞一瞬间；

##### 关于dump.rdb文件
.rdb文件，是保存的二进制数据文件，人是看不懂的。二进制文件较为紧凑，500w个简单kv的.rdb文件约90M。
触发rdb持久化时，会生成新的临时dump-xxx.rdb文件，执行完成再替换旧的。
Redis调优，就是根据业务情况，选择合理的rdb触发频率，而非一味的进行持久化。

##### rdb持久化与Redis主从同步
redis主从复制，rdb持久化不能关闭；
从机是基于主机的rdb实现主从复制。


##### RDB优缺点
优点：
二进制、备份和还原速度快；适合大量数据的同步迁移、备份（异地跨机房数据迁移）；
自动触发rdb保存快照，会fork子进程，不会阻塞用户请求；
缺点：意外宕机时，丢失数据有点多。意外宕机的情况下，丢失最后一次持久化后的数据。

#### AOF
aof保存每条指令的RESP协议。什么是RESP，参见：
aof文件是可以看得懂的，因为RESP指令近似明文的文本。

##### aof触发机制(根据redis.conf配置项)
- no：表示等操作系统进行数据缓存同步到磁盘（快，持久化没保证） 
- always：同步持久化，每次发生数据变更时，立即记录到磁盘（慢，安全） 
- everysec：表示每秒同步一次（默认值,很快，但可能会丢失一秒以内的数据）

> always选项，在prod几乎不会用。
redis-server启动后，1秒钟，就会产生appendonly.aof文件，因为默认everysec；

##### AOF重写
appendonly.aof 不断不断变大，文件变大会使io效率降低。
要解决这个问题，出现重写(bgrewriteaof)，就是给aof文件瘦身。

##### bgrewriteaof 瘦身-触发时机
1、自动触发重写
```
auto-aof-rewrite-min-size 64m	默认超过64m就重写，prod一般配成几个G。重写也是耗性能的。
auto-aof-rewrite-percentage 100
```
2、手动执行bgrewriteaof 瘦身命令;

ps：prod环境，一般auto-aof-rewrite-min-size 不会小于3G；
aof重写也是fork子进程的，没必要产生这块性能开销，Redis调优就是调这些地方。


##### aof重写，是怎样识别这个命令有没有用呢？
比如一串命令操作：
```
set abc 2019
set abc 2020
set abc 2021

set bcd 2019
set bcd 2020
set bcd 2021
```
我们知道，aof重写之后，最终aof文件只保留了最终的数据，也就是abc=2021，bcd=2021，其他被覆盖的无效命令就去除了。
这个重写过程，如何知道去除哪些指令、和保留哪些呢？
Redis也没有高超的办法，就是和内存的数据比对(保留哪些指令)，这是aof重写消耗性能的根源。



##### RDB或AOF文件意外损坏可以恢复吗
可以使用Redis安装目录下的工具：redis-check-aof/redis-check-rdb


##### Redis4.0+混合持久化机制
```
bgrewriteaof重写之后，appendonly.aof文件变成：
-bitsÀ@ú^EctimeÂíÛ×]ú^Hused-memÂ°^E^M^@ú^Laof-preambleÀ^Aþ^@û^A^@^@^Bk2^Bv1ÿR^B^TÑQ;ED
```
appendonly.aof文件，变成不可读的了？？？这是怎么回事，前面不是说aof文件保存的是每一条RESP指令，是可读的吗？
这和版本有关，5.0之后默认开启混合持久化；aof重写后、可能包含rdb二进制数据。
重写完成后的appendonly.aof，后续增量命令，还会以aof方式追加。


###### 开启混合持久化
4.0版本的混合持久化默认关闭的，通过`aof-use-rdb-preamble`配置参数控制，yes则表示开启，no表示禁用，5.0之后默认开启。
混合持久化是通过bgrewriteaof完成的，不同的是当开启混合持久化时，fork出的子进程先将共享的内存副本全量的以RDB方式写入aof文件，然后在将重写缓冲区的增量命令以AOF方式写入到文件，写入完成后通知主进程更新统计信息，并将新的含有RDB格式和AOF格式的AOF文件替换旧的的AOF文件。简单的说：新的AOF文件前半段是RDB格式的全量数据后半段是AOF格式的增量数据，

###### 混合持久化优缺点
优点：混合持久化结合了RDB持久化 和 AOF 持久化的优点, 由于绝大部分都是RDB格式，加载速度快，同时结合AOF，增量的数据以AOF方式保存了，数据更少的丢失。
缺点：兼容性差，一旦开启了混合持久化，在4.0之前版本都不识别该aof文件，同时由于前部分是RDB格式，阅读性较差
