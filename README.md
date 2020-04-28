# Viceroy

Simple proxy server based on Undertow exporting services discovered via Insect discovery protocol.


## Usage

Configure path prefix to route (PREFIX=ROUTE) mappings via command line options.

### Mapping Example
```
--viceroy.map /app/help=help-service
--viceroy.map /item=item-service
--viceroy.map =wildcard-service
```

Requests to the path ```/app/help/item3``` would be proxied to the service ```help-service``` and path ```/item3```.
A request to ```/item``` would be forwarded to ```item-service``` and all other requests would hit ```wildcard-service```
with their full path.  

### Other parameters

```
--viceroy.softMaxConnections  20   # number of IDLE connection to keep even after TTL passed
--viceroy.maxConnections 200       # max connection count per thread
--viceroy.maxQueueSize 40          # number of requests to queue when all connections are busy (else immediately respond with 503) 
--viceroy.maxCachedConnections 40  # maximum number of IDLE connections to keep around
--viceroy.ttl 53000                # IDLE connection TTL in milliseconds
--viceroy.problemServerRetry 2     # how many times/seconds to retry connecting to a failed server (socket errors)
--viceroy.maxRequestTime 30000     # maximum request duration in milliseconds
```

### Parameters inherited from project `base`

```
--server.host 127.0.0.1            # server listen address/name
--server.port 443                  # listen port
```
