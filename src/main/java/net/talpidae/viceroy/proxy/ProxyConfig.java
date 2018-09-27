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

import com.google.common.base.Strings;

import net.talpidae.base.util.BaseArguments;

import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;

import javax.inject.Inject;
import javax.inject.Singleton;

import io.undertow.server.handlers.proxy.ProxyConnectionPoolConfig;
import lombok.Getter;
import lombok.val;


@Singleton
@Getter
public class ProxyConfig implements ProxyConnectionPoolConfig
{
    /**
     * Map of path prefixes to routes. Only include routes here that are supposed to be exported to the outside world.
     */
    private final NavigableMap<String, RouteMatch> pathPrefixToRoute = new TreeMap<>();

    private final RouteMatch defaultRoute;

    private final int problemServerRetry;

    private final int maxCachedConnections;

    private final int maxConnections;

    private final int maxQueueSize;

    private final int sMaxConnections;

    private final int maxRequestTime;

    /**
     * Maximum time to live for connections above the limit of connectionsPerThread.
     */
    private final long ttl; // 60s, possibly set to -1


    @Inject
    public ProxyConfig(BaseArguments baseArguments)
    {
        val parser = baseArguments.getOptionParser();
        val mapOption = parser.accepts("viceroy.map").withRequiredArg();
        val softMaxConnectionsOption = parser.accepts("viceroy.softMaxConnections").withRequiredArg().ofType(Integer.TYPE).defaultsTo(20);
        val maxConnectionsOption = parser.accepts("viceroy.maxConnections").withRequiredArg().ofType(Integer.TYPE).defaultsTo(200);
        val maxQueueSizeOption = parser.accepts("viceroy.maxQueueSize").withRequiredArg().ofType(Integer.TYPE).defaultsTo(40);
        val maxCachedConnectionsOption = parser.accepts("viceroy.maxCachedConnections").withRequiredArg().ofType(Integer.TYPE).defaultsTo(40);
        val ttlOption = parser.accepts("viceroy.ttl").withRequiredArg().ofType(Long.TYPE).defaultsTo(TimeUnit.SECONDS.toMillis(53));
        val problemServerRetryOption = parser.accepts("viceroy.problemServerRetry").withRequiredArg().ofType(Integer.TYPE).defaultsTo(2);
        val maxRequestTimeOption = parser.accepts("viceroy.maxRequestTime").withRequiredArg().ofType(Integer.TYPE).defaultsTo(30000);

        val options = baseArguments.parse();

        for (val map : options.valuesOf(mapOption))
        {
            val mapParts = map.split("=");
            try
            {
                val prefix = mapParts[0];
                val route = mapParts[1];
                if (prefix != null && !Strings.isNullOrEmpty(route))
                {
                    pathPrefixToRoute.put(prefix, new RouteMatch(prefix, route));
                    continue;
                }
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                // throw below
            }

            throw new IllegalArgumentException("invalid PREFIX=ROUTE mapping specified: " + map);
        }

        defaultRoute = pathPrefixToRoute.get("");
        sMaxConnections = softMaxConnectionsOption.value(options);
        maxConnections = maxConnectionsOption.value(options);
        maxQueueSize = maxQueueSizeOption.value(options);
        maxCachedConnections = maxCachedConnectionsOption.value(options);
        ttl = ttlOption.value(options);
        problemServerRetry = problemServerRetryOption.value(options);
        maxRequestTime = maxRequestTimeOption.value(options);
    }


    /**
     * Locate a route by a request path prefix.
     */
    public RouteMatch findRouteByPathPrefix(String path)
    {
        val entry = pathPrefixToRoute.floorEntry(path);
        if (entry != null && path.startsWith(entry.getKey()))
        {
            return entry.getValue();
        }

        return defaultRoute;
    }
}
