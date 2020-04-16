/*
 * Copyright (C) 2017  Jonas Zeiger <jonas.zeiger@talpidae.net>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.talpidae.viceroy.proxy;

import io.undertow.UndertowLogger;
import io.undertow.UndertowOptions;
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.proxy.ConnectionPoolErrorHandler;
import io.undertow.server.handlers.proxy.ConnectionPoolManager;
import io.undertow.server.handlers.proxy.ExclusivityChecker;
import io.undertow.server.handlers.proxy.ProxyCallback;
import io.undertow.server.handlers.proxy.ProxyClient;
import io.undertow.server.handlers.proxy.ProxyConnection;
import io.undertow.server.handlers.proxy.ProxyConnectionPool;
import io.undertow.util.AttachmentKey;
import io.undertow.util.Headers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.val;
import org.xnio.OptionMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.URI;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.xnio.IoUtils.safeClose;


/**
 * Closely aligned to LoadBalancingProxyClient but with insect.Slave support.
 */
@Singleton
public class DnsLookupProxyClient implements ProxyClient
{
    // we set upgraded HTTP(S) connections aside
    private static final ExclusivityChecker EXCLUSIVITY_CHECKER = exchange -> exchange.getRequestHeaders().contains(Headers.UPGRADE);

    // associates a ProxyConnection with the HttpServerExchange
    private final AttachmentKey<ConnectionHolder> connectionKey = AttachmentKey.create(ConnectionHolder.class);

    private static final OptionMap DEFAULT_HTTP2_BACKEND_OPTIONS = OptionMap.builder()
            .set(UndertowOptions.BUFFER_PIPELINED_DATA, true)
            .set(UndertowOptions.ENABLE_HTTP2, true)
            .set(UndertowOptions.HTTP2_SETTINGS_ENABLE_PUSH, false)
            .getMap();

    @Getter
    private final ProxyConfig config;

    private final UndertowClient client = UndertowClient.getInstance();

    private final ConcurrentHashMap<String, TargetPool> routeToPool = new ConcurrentHashMap<>();


    @Inject
    public DnsLookupProxyClient(ProxyConfig proxyConfig)
    {
        this.config = proxyConfig;
    }

    private static String stripPrefix(String s, String prefix)
    {
        if (s.startsWith(prefix))
        {
            val stripped = s.substring(prefix.length());

            return (prefix.endsWith("/")) ? "/" + stripped : stripped;
        }

        return s;
    }

    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange)
    {
        return config.findRouteByPathPrefix(exchange.getRelativePath());
    }


    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit)
    {
        val routeMatch = (RouteMatch) target;
        if (routeMatch == null)
        {
            callback.couldNotResolveBackend(exchange);
            return;
        }

        val connectionHolder = exchange.getConnection().getAttachment(connectionKey);
        if (connectionHolder != null
                && connectionHolder.route.equals(routeMatch.getRoute())
                && connectionHolder.connection.getConnection().isOpen())
        {
            // we already got a connection on the correct route, use it
            callback.completed(exchange, connectionHolder.connection);
            return;
        }

        // rewrite exchange path (remove prefix)
        exchange.setRequestURI(stripPrefix(exchange.getRequestURI(), routeMatch.getPrefix()));

        val targetPool = getTargetPool(routeMatch.getRoute());
        if (connectionHolder != null || EXCLUSIVITY_CHECKER.isExclusivityRequired(exchange))
        {
            val proxyCallbackWrapper = new ConnectionProxyCallbackWrapper(targetPool, connectionHolder, callback, routeMatch.getRoute());
            targetPool.getConnectionPool().connect(target, exchange, proxyCallbackWrapper, timeout, timeUnit, true);
        }
        else
        {
            targetPool.getConnectionPool().connect(target, exchange, callback, timeout, timeUnit, false);
        }
    }


    private TargetPool getTargetPool(String route)
    {
        return routeToPool.computeIfAbsent(route, TargetPool::new);
    }


    @AllArgsConstructor
    private static class ConnectionHolder implements ServerConnection.CloseListener
    {
        private final String route;

        private ProxyConnection connection;


        @Override
        public void closed(ServerConnection connection)
        {
            val clientConnection = this.connection.getConnection();
            if (clientConnection.isOpen())
            {
                safeClose(clientConnection);
            }
        }
    }


    @Getter
    private class TargetPool extends ConnectionPoolErrorHandler.SimpleConnectionPoolErrorHandler implements ConnectionPoolManager
    {
        private final ProxyConnectionPool connectionPool;


        private TargetPool(String route)
        {
            val uri = URI.create("http://" + route);
            val optionMap = OptionMap.builder().addAll(DEFAULT_HTTP2_BACKEND_OPTIONS)
                    .getMap();

            this.connectionPool = new ProxyConnectionPool(this, uri, client, optionMap);
        }

        @Override
        public int getProblemServerRetry()
        {
            return config.getProblemServerRetry();
        }

        @Override
        public int getMaxConnections()
        {
            return config.getMaxConnections();
        }

        @Override
        public int getMaxCachedConnections()
        {
            return config.getMaxCachedConnections();
        }

        @Override
        public int getSMaxConnections()
        {
            return config.getSMaxConnections();
        }

        @Override
        public long getTtl()
        {
            return config.getTtl();
        }

        @Override
        public int getMaxQueueSize()
        {
            return config.getMaxQueueSize();
        }
    }


    @AllArgsConstructor
    private class ConnectionProxyCallbackWrapper implements ProxyCallback<ProxyConnection>
    {
        private final TargetPool serviceState;

        private final ConnectionHolder connectionHolder;

        private final ProxyCallback<ProxyConnection> callback;

        /**
         * We need the matched route to know when we should NOT re-use a sticky connection (HTTP/2, WebSocket).
         */
        private final String route;


        @Override
        public void completed(HttpServerExchange exchange, ProxyConnection result)
        {
            if (connectionHolder != null)
            {
                connectionHolder.connection = result;
            }
            else
            {
                val connectionHolder = new ConnectionHolder(route, result);
                val connection = exchange.getConnection();
                connection.putAttachment(connectionKey, connectionHolder);
                connection.addCloseListener(connectionHolder);
            }

            callback.completed(exchange, result);
        }

        @Override
        public void queuedRequestFailed(HttpServerExchange exchange)
        {
            callback.queuedRequestFailed(exchange);
        }

        @Override
        public void failed(HttpServerExchange exchange)
        {
            UndertowLogger.PROXY_REQUEST_LOGGER.proxyFailedToConnectToBackend(exchange.getRequestURI(), serviceState.getConnectionPool().getUri());
            callback.failed(exchange);
        }

        @Override
        public void couldNotResolveBackend(HttpServerExchange exchange)
        {
            callback.couldNotResolveBackend(exchange);
        }
    }
}
