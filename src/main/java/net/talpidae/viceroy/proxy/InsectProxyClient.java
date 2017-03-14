package net.talpidae.viceroy.proxy;

import io.undertow.server.HttpServerExchange;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import lombok.Getter;
import lombok.val;
import net.talpidae.base.insect.Slave;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.concurrent.TimeUnit;


@Singleton
public class InsectProxyClient implements ProxyClient
{
    @Getter
    private final Slave slave;

    @Getter
    private final ProxyConfig config;


    @Inject
    public InsectProxyClient(Slave slave, ProxyConfig proxyConfig)
    {
        this.slave = slave;
        this.config = proxyConfig;
    }


    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange)
    {
        return config.findRouteByPrefix(exchange.getRelativePath());
    }

    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit)
    {
        val slaveAddress = slave.findService(routeMatch.getRoute());
        }


        InsectProxyTarget.builder()
                .matchedPathPrefix()
        return slave.findService();
    }
}
