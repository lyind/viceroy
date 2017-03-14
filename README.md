# Viceroy

Simple proxy server based on Undertow exporting services discovered via Insect discovery protocol.


## Usage

Configure path prefix to route (PREFIX=ROUTE) mappings via command line options:
```
--viceroy.map /app/help=help-service
--viceroy.map /item=item
```

Requests to the path ```/app/help/item3``` would be proxied to the service ```help-service``` and path ```/item3```.
