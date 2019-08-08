# Chain adapter
## Service overview 
Chain adapter is a service used for fetching Iroha blocks very safely. Unlike the default Iroha blockchain listener implementation, the service is capable of publishing missing blocks. 

The service is backed by RabbitMQ. All outgoing Iroha blocks are stored in the RabbitMQ exchange called `iroha` that fanouts all items to all bound queues. That implies that different services must use different queue names to avoid block stealing.

Chain-adapter functionality may be used by utilizing the `com.d3.chainadapter.client.ReliableIrohaChainListener` class. The class and the service itself are written in Kotlin programming language that has great interoperability with Java.

The service is fail-fast, i.e it dies whenever Iroha or RabbitMQ goes offline.
## Configuration file overview
Chain adapter uses `chain-adapter.properties` as a default configuration file that is located in `resources` folder inside the project. However, every configuration item could be changed through environmental variables. 

- `chain-adapter.rmqHost` - RabbitMQ host
- `chain-adapter.rmqPort` - RabbitMQ port
- `chain-adapter.irohaExchange` - exchange name that is used to publish blocks
- `chain-adapter.lastReadBlockFilePath` - the file that chain adapter uses to save the last read block height. This file is needed to publish missing blocks after restart. It's important to highlight that blocks that have lower height values won't be published.
- `chain-adapter.healthCheckPort` - health check port
- `chain-adapter.queuesToCreate` - queues that chain adapter creates on a service startup. Queue names must be separated by the comma symbol. The value is optional.
- `chain-adapter.iroha` - Iroha host and port configuration
- `chain-adapter.dropLastReadBlock` - as it was mentioned before, chain adapter saves the last read block height. It's possible to drop height to zero on a service startup by setting this value to `true`. Good for testing purposes. 
- `chain-adapter.irohaCredential` - credentials of the account that will be used by the service to listen to Iroha blocks. The account must have `can_get_blocks` permission.

## How to run
Chain-adapter may be run as a docker container using the following `docker-compose` instructions:

```
rmq:
  image: rabbitmq:3-management
  container_name: rmq
  ports:
    - 8181:15672
    - 5672:5672

chain-adapter:
  image: nexus.iroha.tech:19002/d3-deploy/chain-adapter:develop
  container_name: chain-adapter
  restart: on-failure
  depends_on:
    - iroha
    - rmq
  volumes:
    - ../your change adapter storage/:/deploy/chain-adapter
```
  
## How to use
`com.d3.chainadapter.client.ReliableIrohaChainListener` is the class used as a client for the service. The class may be obtained via JitPack:

```groovy
compile "com.github.d3ledger.chain-adapter:chain-adapter-client:$chain_adapter_client_version"
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