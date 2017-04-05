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
import io.undertow.server.handlers.proxy.ProxyConnectionPoolConfig;
import lombok.Getter;
import lombok.Setter;
import lombok.val;
import net.talpidae.base.util.BaseArguments;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.NavigableMap;
import java.util.TreeMap;


@Singleton
@Getter
public class ProxyConfig implements ProxyConnectionPoolConfig
{
    /**
     * Map of path prefixes to routes. Only include routes here that are supposed to be exported to the outside world.
     */
    private final NavigableMap<String, RouteMatch> pathPrefixToRoute = new TreeMap<>();

    @Setter
    private int problemServerRetry = 15;

    @Setter
    private int maxCachedConnections = 20;

    @Setter
    private int maxConnections = 20;

    @Setter
    private int maxQueueSize = 0;

    @Setter
    private int sMaxConnections = 10;

    /**
     * Maximum time to live for connections above the limit of connectionsPerThread.
     */
    @Setter
    private long ttl = 60 * 1000; // 60s, possibly set to -1


    @Inject
    public ProxyConfig(BaseArguments baseArguments)
    {
        val parser = baseArguments.getOptionParser();
        val mapOption = parser.accepts("viceroy.map").withRequiredArg();
        val options = baseArguments.parse();

        for (val map : options.valuesOf(mapOption))
        {
            val mapParts = map.split("=");
            try
            {
                val prefix = mapParts[0];
                val route = mapParts[1];
                if (!Strings.isNullOrEmpty(prefix) && !Strings.isNullOrEmpty(route))
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
    }


    /**
     * Don't call this while the configuration is in use by the proxy.
     */
    public void addPathPrefixToRouteMapping(String prefix, RouteMatch routeMatch)
    {
        pathPrefixToRoute.put(prefix, routeMatch);
    }


    /**
     * Locate a route by a request path prefix.
     */
    public RouteMatch findRouteByPathPrefix(String prefix)
    {
        val mappings = pathPrefixToRoute;
        if (mappings != null)
        {
            val entry = mappings.ceilingEntry(prefix);

            if (entry != null && entry.getKey().startsWith(prefix))
            {
                return entry.getValue();
            }
        }

        return null;
    }
}
