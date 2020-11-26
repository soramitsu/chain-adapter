# Chain adapter
## Service overview 
Chain adapter is a service used for fetching Iroha blocks very safely. Unlike the default Iroha blockchain listener implementation, the service is capable of publishing missing blocks. 

The service is backed by RabbitMQ. All outgoing Iroha blocks are stored in the RabbitMQ exchange called `iroha` that fanouts all items to all bound queues. That implies that different services must use different queue names to avoid block stealing.

Chain-adapter functionality may be used by utilizing the `com.d3.chainadapter.client.ReliableIrohaChainListener` class. The class and the service itself are written in Kotlin programming language that has great interoperability with Java.

The service is fail-fast, i.e it dies whenever Iroha or RabbitMQ goes offline.
## Server configuration file overview
Chain adapter uses `chain-adapter.properties` as a default configuration file that is located in the `resources` folder inside the project(not in the project where the chain-adapter is used). However, every configuration item could be changed through environmental variables.
- `chain-adapter.rmqHost` - RabbitMQ host
- `chain-adapter.rmqPort` - RabbitMQ port
- `chain-adapter.username` - RabbitMQ username(optional)
- `chain-adapter.password` - RabbitMQ password(optional)
- `chain-adapter.virtualHost` - RabbitMQ virtual host(optional)
- `chain-adapter.irohaExchange` - exchange name that is used to publish blocks
- `chain-adapter.lastReadBlockFilePath` - the file that chain adapter uses to save the last read block height. This file is needed to publish missing blocks after restart. It's important to highlight that blocks that have lower height values won't be published.
- `chain-adapter.healthCheckPort` - health check port
- `chain-adapter.queuesToCreate` - queues that chain adapter creates on a service startup. Queue names must be separated by the comma symbol. The value is optional.
- `chain-adapter.iroha` - Iroha host and port configuration
- `chain-adapter.dropLastReadBlock` - as it was mentioned before, chain adapter saves the last read block height. It's possible to drop height to zero on a service startup by setting this value to `true`. Good for testing purposes. 
- `chain-adapter.irohaCredential` - credentials of the account that will be used by the service to listen to Iroha blocks. The account must have `can_get_blocks` permission.
- `chain-adapter.clusterEnabled` - `true` if expected to run multiple instances of the service and `false` otherwise. If `true` Iroha blocks will be deduplicated. Default value is `false`.
- `chain-adapter.clusterHazelcastMembers` - comma separated list of Hazelcast's node addresses in format `host1:port1,host2:port2`. Required only if `chain-adapter.clusterEnabled` is `true`.

The transformation to environmental variable is quite straightforward: uppercase every letter and change dot symbol(`.`) to underscore(`_`). For example, `chain-adapter.rmqHost` transforms to `CHAIN-ADAPTER_RMQHOST`.

## How to run the service
Chain-adapter may be run as a docker container using the following `docker-compose` instructions:

```
chain-adapter:
  image: nexus.iroha.tech:19004/soramitsu/chain-adapter:develop
  container_name: chain-adapter
  restart: on-failure
  depends_on:
    - iroha
    - rmq
  volumes:
    - {chain adapter storage}:/deploy/chain-adapter

rmq:
  image: rabbitmq:3-management
  container_name: rmq
  hostname: rmq
  volumes:
    - {rabbitmq data storage}:/var/lib/rabbitmq/mnesia/
  ports:
    - 15672:15672
    - 5672:5672
```
### Ports and volumes
The service uses port `15672` for RabbitMQ web-client(password:guest, login:guest) and port `5672` as the main AMQP port.
You also have to specify two volumes:`rabbitmq data storage` and `chain adapter storage`. 
The first volume is used to persist RabbitMQ messages, while the latter is used to save the very last block number processed by the chain adapter. 
  
## How to use the client
`com.d3.chainadapter.client.ReliableIrohaChainListener` is the class used as a client for the service. The class may be obtained via [Jitpack](https://jitpack.io/#soramitsu/chain-adapter):

```groovy
compile "com.github.soramitsu.chain-adapter:chain-adapter-client:$chain_adapter_client_version"
``` 

Typical workflow looks like this:

1) First, you must create an instance of `ReliableIrohaChainListener` object. 
2) Then you have to call the `getBlockObservable()` function that returns `Observable<Pair<BlockOuterClass.Block, () -> Unit>>` wrapped in `Result`(see [github.com/kittinunf/Result](https://github.com/kittinunf/Result)). The returned object may be used to register multiple subscribers.
The first component of `Pair` is an Iroha block itself. The second component is a function that must be called to acknowledge Iroha block delivery. 
The function won't have any effect if the "auto acknowledgment" mode is on. If the "auto acknowledgment" mode is off, then EVERY block must be acknowledged manually.  
3) And finally, you have to invoke `listen()` function that starts fetching Iroha blocks. Without this call, no block will be read.

If you are not into Kotlin, there is a wrapper class written in Java called `ReliableIrohaChainListener4J`. It works exactly the same. 

### Examples
Kotlin
```
val listener = ReliableIrohaChainListener(rmqConfig, "queue", autoAck = false)
listener.getBlockObservable().map { observable ->
    observable.subscribe { (block, ack) ->
        // Do something with block
        // ...
        // Acknowledge
        ack()
    }
}.flatMap {
    listener.listen()
}.fold(
    {
        // On listen() success 
    },
    { ex ->
        // On listen() failure
    })
```
Java
```
boolean autoAck = false;
ReliableIrohaChainListener4J listener = new ReliableIrohaChainListener4J(rmqConfig, "queue", autoAck);
listener.getBlockObservable().subscribe((subscription) -> {
    BlockOuterClass.Block block = subscription.getBlock();
    // Do something with block
    BlockAcknowledgment acknowledgment = subscription.getAcknowledgment();
    acknowledgment.ack();
});
try {
    listener.listen();
} catch (IllegalStateException ex) {
    ex.printStackTrace();
    System.exit(1);
}
```
It's important to emphasize the order of the calls. Calling `listen()` before defining subscribers will lead to missing blocks. 
