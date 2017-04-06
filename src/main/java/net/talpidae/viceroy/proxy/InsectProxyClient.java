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
import io.undertow.client.UndertowClient;
import io.undertow.server.HttpServerExchange;
import io.undertow.server.ServerConnection;
import io.undertow.server.handlers.proxy.*;
import io.undertow.util.AttachmentKey;
import io.undertow.util.AttachmentList;
import io.undertow.util.Headers;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.val;
import net.talpidae.base.insect.Slave;
import net.talpidae.base.insect.state.ServiceState;
import org.xnio.OptionMap;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.net.InetSocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static io.undertow.server.handlers.proxy.ProxyConnectionPool.AvailabilityType.*;
import static org.xnio.IoUtils.safeClose;


/**
 * Closely aligned to LoadBalancingProxyClient but with insect.Slave support.
 */
@Singleton
public class InsectProxyClient implements ProxyClient
{
    private static final AttachmentKey<AttachmentList<InetSocketAddress>> TRIED_SERVICES = AttachmentKey.createList(InetSocketAddress.class);

    @Getter
    private final Slave slave;

    @Getter
    private final ProxyConfig config;

    // we set upgraded HTTP(S) connections aside
    private final ExclusivityChecker exclusivityChecker = exchange -> exchange.getRequestHeaders().contains(Headers.UPGRADE);

    // associates a ProxyConnection with the HttpServerExchange
    private final AttachmentKey<ConnectionHolder> connectionKey = AttachmentKey.create(ConnectionHolder.class);

    private final UndertowClient client = UndertowClient.getInstance();

    private final ConcurrentHashMap<InetSocketAddress, ProxyConnectionPool> serviceToConnectionPool = new ConcurrentHashMap<>();


    @Inject
    public InsectProxyClient(Slave slave, ProxyConfig proxyConfig)
    {
        this.slave = slave;
        this.config = proxyConfig;
    }

    @Override
    public ProxyTarget findTarget(HttpServerExchange exchange)
    {
        return config.findRouteByPathPrefix(exchange.getRelativePath());
    }


    private static String stripPrefix(String s, String prefix)
    {
        if (s.startsWith(prefix))
        {
            return s.substring(prefix.length());
        }

        return s;
    }


    @Override
    public void getConnection(ProxyTarget target, HttpServerExchange exchange, ProxyCallback<ProxyConnection> callback, long timeout, TimeUnit timeUnit)
    {
        if (target instanceof RouteMatch)
        {
            val routeMatch = (RouteMatch) target;
            try
            {
                val serviceIterator = slave.findServices(routeMatch.getRoute(), timeUnit.toMillis(timeout));

                val connectionHolder = exchange.getConnection().getAttachment(connectionKey);
                if (connectionHolder != null && connectionHolder.connection.getConnection().isOpen())
                {
                    // we already got a connection, use it
                    callback.completed(exchange, connectionHolder.connection);
                    return;
                }

                val selectedService = chooseService(serviceIterator, exchange);
                if (selectedService == null)
                {
                    callback.couldNotResolveBackend(exchange);
                }
                else
                {
                    exchange.addToAttachmentList(TRIED_SERVICES, selectedService.getSocketAddress());

                    // rewrite exchange path (remove prefix)
                    exchange.setRequestURI(stripPrefix(exchange.getRequestURI(), routeMatch.getPrefix()));

                    val connectionPool = selectedService.getConnectionPool();
                    if (connectionHolder != null || exclusivityChecker.isExclusivityRequired(exchange))
                    {
                        val proxyCallbackWrapper = new ConnectionProxyCallbackWrapper(selectedService, connectionHolder, callback);
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
        }

        callback.couldNotResolveBackend(exchange);
    }


    private TargetServiceState chooseService(Iterator<? extends ServiceState> services, HttpServerExchange exchange)
    {
        val attemptedServices = exchange.getAttachment(TRIED_SERVICES);
        if (services == null || !services.hasNext())
        {
            return null;
        }

        // if we want a sort by timestamp we need to iterate over all services for this route anyways,
        // doing that in the loop below would just add more lookups for the ConnectionPool instances
        val sortedServices = new ArrayList<ServiceState>();
        do
        {
            sortedServices.add(services.next());
        }
        while (services.hasNext());

        // the service that most recently contacted us is preferred
        sortedServices.sort(Comparator.comparingLong(ServiceState::getTimestamp).reversed());

        TargetServiceState candidateFull = null;   // host reached connection limit, still possible
        TargetServiceState candidateIssues = null; // host got issues before, may be usable now
        for (val serviceState : sortedServices)
        {
            val service = new TargetServiceState(serviceState, OptionMap.EMPTY);
            if (attemptedServices == null || !attemptedServices.contains(serviceState.getSocketAddress()))
            {
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

        if (candidateFull != null)
        {
            return candidateFull;
        }
        else if (candidateIssues != null)
        {
            return candidateIssues;
        }

        return null;
    }


    @AllArgsConstructor
    private static class ConnectionHolder implements ServerConnection.CloseListener
    {
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
    private class TargetServiceState extends ConnectionPoolErrorHandler.SimpleConnectionPoolErrorHandler implements ConnectionPoolManager, ServiceState
    {
        private final ServiceState serviceState;

        private final OptionMap options;

        // cache connection pool for performance
        private ProxyConnectionPool connectionPool;


        private TargetServiceState(ServiceState serviceState, OptionMap options)
        {
            this.serviceState = serviceState;
            this.options = options;
        }


        /**
         * Get or create connection pool for this target service on-demand.
         */
        ProxyConnectionPool getConnectionPool()
        {
            if (connectionPool == null)
            {
                connectionPool = serviceToConnectionPool.computeIfAbsent(serviceState.getSocketAddress(), socketAddress ->
                {
                    val uri = URI.create("http://" + serviceState.getSocketAddress().getHostString() + ":" + serviceState.getSocketAddress().getPort());
                    return new ProxyConnectionPool(this, uri, client, options);
                });
            }

            return connectionPool;
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

        @Override
        public long getTimestamp()
        {
            return serviceState.getTimestamp();
        }

        @Override
        public InetSocketAddress getSocketAddress()
        {
            return serviceState.getSocketAddress();
        }
    }


    @AllArgsConstructor
    private class ConnectionProxyCallbackWrapper implements ProxyCallback<ProxyConnection>
    {
        private final TargetServiceState serviceState;

        private final ConnectionHolder connectionHolder;

        private final ProxyCallback<ProxyConnection> callback;

        @Override
        public void completed(HttpServerExchange exchange, ProxyConnection result)
        {
            if (connectionHolder != null)
            {
                connectionHolder.connection = result;
            }
            else
            {
                val connectionHolder = new ConnectionHolder(result);
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
