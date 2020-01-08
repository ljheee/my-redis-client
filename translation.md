Redis事务,你真的了解吗
#### Redis事务
redis提供了简单的“事务”能力,multi,exec,discard,watch/unwatch指令用来操作事务。
- mutil：开启事务，此后所有的操作将会添加到当前链接的事务“操作队列”中。
- exec：提交事务
- discard：取消事务，记住，此指令不是严格意义上的“事务回滚”，只是表达了“事务操作被取消”的语义，将会导致事务的操作队列中的操作不会被执行，且事务关闭。
- watch/unwatch：“观察”，这个操作也可以说是redis的特殊功能，但是也可说是redis不能提供“绝对意义上”的事务能力而增加的一个“补充特性”（比如事务隔离，多事务中操作冲突解决等）；在事务开启前，可以对某个key注册“watch”，如果在事务提交后，将会首先检测“watch”列表中的key集合是否被其他客户端修改，如果任意一个key 被修改，都将会导致事务直接被“discard”；即使事务中没有操作某个watch key，如果此key被外部修改，仍然会导致事务取消。事务执行成功或者被discard，都将会导致watch key被“unwatch”，因此事务之后，你需要重新watch。watch需要在事务开启之前执行。

###### Watch
WATCH所注册的KEY，事实上无论是被其他Client修改还是当前Client修改，如果不重新WATCH，都将无法在事务中正确执行。WATCH指令本身就是为事务而生，你或许不会在其他场景下使用WATCH；例如：
```
String key = "transaction-key";  
jedis.set(key, "20");  
jedis.watch(key);//注册key,此后key将会被监控，如果在事务执行前被修改，则导致事务被DISCARD。  
jedis.incr(key);//此key被修改，即使是自己，也会导致watch在事务中执行失效  
jedis.unwatch();//取消注册  
jedis.watch(key);//重新注册,在重新注册前，必须unwatch  
Transaction tx = jedis.multi();//开启事务，开启事务前进行watch
……
```

#### Redis事务底层原理
Redis中，如果一个事务被提交，那么事务中的所有操作将会被顺序执行，且在事务执行期间，其他client的操作将会被阻塞；Redis采取了这种简单而“粗鲁”的方式来确保事务的执行更加的快速和更少的外部干扰因素。

EXEC指令将会触发事务中所有的操作被写入AOF文件（如果开启了AOF），然后开始在内存中实施这些数据变更操作；Redis将会尽力确保事务中所有的操作都能够执行，如果redis环境故障，有可能导致事务未能成功执行，那么需要在redis重启后增加额外的校验工作。
如果在EXEC指令被提交之前，Redis-server即检测到提交的某个指令存在语法错误，那么此事务将会被提前标记DISCARD，此后事务提交也将直接被驳回；但是如果在EXEC提交后，在实施数据变更时（Redis将不会预检测数据类型，比如你对一个“非数字”类型的key执行INCR操作），某个操作导致了ERROR，那么redis仍然不会回滚此前已经执行成功的操作，而且也不会中断ERROR之后的其他操作继续执行。对于开发者而言，你务必关注事务执行后返回的结果（结果将是一个集合，按照操作提交的顺序排列，对于执行失败的操作，结果将是一个ERROR）。
Redis的事务之所以如此设计，它为了确保本身的性能，同时不引入“关系型数据库”的设计复杂度；你不能完全希望Redis能为你交付完美的事务操作，只能说，你选择了错误的工具。
```
    public void transaction(){  
        String key = "transaction-key";  
        jedis.set(key, "20");  
        jedis.watch(key);  
        Transaction tx = jedis.multi();  
        tx.incr(key);  
        tx.incr(key);  
        tx.incr(key);  
        List<Object> result = tx.exec();  
        if(result == null || result.isEmpty()){  
            System.out.println("Transaction error...");//可能是watch-key被外部修改，或者是数据操作被驳回  
            return;  
        }  
        for(Object rt : result){  
            System.out.println(rt.toString());  
        }  
    }  
```

Redis 在接收到 MULTI 命令后便会开启一个事务，这之后的所有读写命令都会保存在队列中但并不执行，直到接收到 EXEC 命令后，Redis 会把队列中的所有命令连续顺序执行，并以数组形式返回每个命令的返回结果。
可以使用 DISCARD 命令放弃当前的事务，将保存的命令队列清空。需要注意的是，Redis 事务不支持回滚。

###### Redis事务能回滚吗
如果一个事务中的命令出现了语法错误，大部分客户端驱动会返回错误，2.6.5 版本以上的 Redis 也会在执行 EXEC 时检查队列中的命令是否存在语法错误，如果存在，则会自动放弃事务(提前标记DISCARD)并返回错误。
但如果一个事务中的命令有非语法类的错误(比如对 String 执行 HSET 操作)，无论客户端驱动还是 Redis 都无法在真正执行这条命令之前发现，所以事务中的所有命令仍然会被依次执行。
在这种情况下，会出现一个事务中部分命令成功部分命令失败的情况，然而与 RDBMS 不同，Redis 不提供事务回滚的功能，所以只能通过其他方法进行数据的回滚。


#### 在事务和非事务状态下执行命令
当客户端进入事务状态之后， 客户端发送的命令就会被放进事务队列里。
但exec 、 discard 、 multi 和 watch 这四个命令 —— 当这四个命令从客户端发送到服务器时， 它们会像客户端处于非事务状态一样， 直接被服务器执行：

无论在事务状态下， 还是在非事务状态下， Redis 命令都由同一个函数执行， 所以它们共享很多服务器的一般设置， 比如 AOF 的配置、RDB 的配置，以及内存限制，等等。
不过事务中的命令和普通命令在执行上还是有一点区别的，其中最重要的两点是：
- 非事务状态下的命令以单个命令为单位执行，前一个命令和后一个命令的客户端不一定是同一个；
    而事务状态则是以一个事务为单位，执行事务队列中的所有命令：除非当前事务执行完毕，否则服务器不会中断事务，也不会执行其他客户端的其他命令。

- 在非事务状态下，执行命令所得的结果会立即被返回给客户端；
    而事务则是将所有命令的结果集合到回复队列，再作为 EXEC 命令的结果返回给客户端。
 

#### 通过事务+watch实现 CAS
http://database.51cto.com/art/201903/594054.htm
Redis 提供了 WATCH 命令与事务搭配使用，实现 CAS 乐观锁的机制。
WATCH 的机制是：在事务 EXEC 命令执行时，Redis 会检查被 WATCH 的 Key，只有被 WATCH 的 Key 从 WATCH 起始时至今没有发生过变更，EXEC 才会被执行。
如果 WATCH 的 Key 在 WATCH 命令到 EXEC 命令之间发生过变化，则 EXEC 命令会返回失败。


#### Redis事务的替代品
**Lua脚本Scripting**
通过 EVAL 与 EVALSHA 命令，可以让 Redis 执行 LUA 脚本。这就类似于 RDBMS 的存储过程一样，可以把客户端与 Redis 之间密集的读/写交互放在服务端进行，避免过多的数据交互，提升性能。
Scripting 功能是作为事务功能的替代者诞生的，事务提供的所有能力 Scripting 都可以做到。Redis 官方推荐使用 LUA Script 来代替事务，Scripting的效率和便利性都超过了事务。



