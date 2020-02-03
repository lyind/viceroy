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
import io.undertow.util.AttachmentList;
import io.undertow.util.Headers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.val;
import net.talpidae.base.insect.Slave;
import net.talpidae.base.insect.state.ServiceState;
import net.talpidae.base.util.random.AtomicXorShiftRandom;
import org.xnio.OptionMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType.AVAILABLE;
import static io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType.FULL;
import static io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType.FULL_QUEUE;
import static io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType.PROBLEM;
import static org.xnio.IoUtils.safeClose;


/**
 * Closely aligned to LoadBalancingProxyClient but with insect.Slave support.
 */
@Singleton
public class InsectProxyClient implements ProxyClient
{
    private static final AttachmentKey<AttachmentList<InetSocketAddress>> TRIED_SERVICES_KEY = AttachmentKey.createList(InetSocketAddress.class);

    // we set upgraded HTTP(S) connections aside
    private static final ExclusivityChecker EXCLUSIVITY_CHECKER = exchange -> exchange.getRequestHeaders().contains(Headers.UPGRADE);

    // associates a ProxyConnection with the HttpServerExchange
    private final AttachmentKey<ConnectionHolder> connectionKey = AttachmentKey.create(ConnectionHolder.class);

    private static final AtomicXorShiftRandom CHEAP_RANDOM = new AtomicXorShiftRandom();

    private static final OptionMap DEFAULT_HTTP2_BACKEND_OPTIONS = OptionMap.builder()
            .set(UndertowOptions.BUFFER_PIPELINED_DATA, true)
            .set(UndertowOptions.ENABLE_HTTP2, true)
            .set(UndertowOptions.HTTP2_SETTINGS_ENABLE_PUSH, false)
            .getMap();

    @Getter
    private final Slave slave;

    @Getter
    private final ProxyConfig config;

    private final UndertowClient client = UndertowClient.getInstance();

    private final ConcurrentHashMap<InetSocketAddress, TargetPool> serviceToState = new ConcurrentHashMap<>();


    @Inject
    public InsectProxyClient(Slave slave, ProxyConfig proxyConfig)
    {
        this.slave = slave;
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
        try
        {
            val connectionHolder = exchange.getConnection().getAttachment(connectionKey);
            if (connectionHolder != null
                    && connectionHolder.route.equals(routeMatch.getRoute())
                    && connectionHolder.connection.getConnection().isOpen())
            {
                // we already got a connection on the correct route, use it
                callback.completed(exchange, connectionHolder.connection);
                return;
            }

            val services = slave.findServices(routeMatch.getRoute(), timeUnit.toMillis(timeout));
            val selectedService = chooseService(services, exchange);
            if (selectedService != null)
            {
                exchange.addToAttachmentList(TRIED_SERVICES_KEY, selectedService.getSocketAddress());

                // rewrite exchange path (remove prefix)
                exchange.setRequestURI(stripPrefix(exchange.getRequestURI(), routeMatch.getPrefix()));

                val connectionPool = selectedService.getConnectionPool();
                if (connectionHolder != null || EXCLUSIVITY_CHECKER.isExclusivityRequired(exchange))
                {
                    val proxyCallbackWrapper = new ConnectionProxyCallbackWrapper(selectedService, connectionHolder, callback, routeMatch.getRoute());
                    connectionPool.connect(target, exchange, proxyCallbackWrapper, timeout, timeUnit, true);
                }
                else
                {
                    connectionPool.connect(target, exchange, callback, timeout, timeUnit, false);
                }

                // successfully forwarded connection
                return;
            }
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
        }

        callback.couldNotResolveBackend(exchange);
    }


    private TargetPool chooseService(List<? extends ServiceState> services, HttpServerExchange exchange)
    {
        TargetPool candidateFull = null;   // host reached connection limit, still possible
        TargetPool candidateIssues = null; // host got issues before, may be usable now

        val attemptedServices = exchange.getAttachment(TRIED_SERVICES_KEY);
        val size = services.size();
        if (size > 0)
        {
            val startIndex = CHEAP_RANDOM.nextInt(size);
            for (int i = 0; i < size; ++i)
            {
                val serviceState = services.get((startIndex + i) % size);
                if (attemptedServices == null || !attemptedServices.contains(serviceState.getSocketAddress()))
                {
                    val service = getTargetPool(serviceState.getSocketAddress());
                    val availability = service.getConnectionPool().available();
                    if (availability == AVAILABLE)
                    {
                        return service;
                    }
                    else if (candidateFull == null && availability == FULL)
                    {
                        candidateFull = service;
                    }
                    else if (candidateIssues == null && (availability == PROBLEM || availability == FULL_QUEUE))
                    {
                        candidateIssues = service;
                    }
                }
            }
        }

        return (candidateFull != null) ? candidateFull : candidateIssues;
    }


    private TargetPool getTargetPool(InetSocketAddress targetServiceAddress)
    {
        return serviceToState.computeIfAbsent(targetServiceAddress, TargetPool::new);
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
        private final InetSocketAddress socketAddress;

        private final ProxyConnectionPool connectionPool;


        private TargetPool(InetSocketAddress socketAddress)
        {
            this.socketAddress = socketAddress;

            val uri = URI.create("http://" + socketAddress.getHostString() + ":" + socketAddress.getPort());
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
