package net.talpidae.viceroy.proxy;

import com.google.common.base.Strings;
import lombok.Getter;
import lombok.val;
import net.talpidae.base.util.BaseArguments;

import javax.inject.Inject;
import javax.inject.Singleton;
import java.util.Collections;
import java.util.NavigableMap;
import java.util.TreeMap;


@Singleton
public class ProxyConfig
{
    /**
     * Map of path prefixes to routes. Only include routes here that are supposed to be exported to the outside world.
     */
    @Getter
    private NavigableMap<String, RouteMatch> pathPrefixToRoute;


    @Inject
    public ProxyConfig(BaseArguments baseArguments)
    {
        val parser = baseArguments.getOptionParser();
        val mapOption = parser.accepts("viceroy.map").withRequiredArg();
        val options = baseArguments.parse();

        val mappings = new TreeMap<String, RouteMatch>();
        for (val map : options.valuesOf(mapOption))
        {
            val mapParts = map.split("=");
            try
            {
                val prefix = mapParts[0];
                val route = mapParts[1];
                if (!Strings.isNullOrEmpty(prefix) && !Strings.isNullOrEmpty(route))
                {
                    mappings.put(prefix, new RouteMatch(prefix, route));
                    continue;
                }
            }
            catch (ArrayIndexOutOfBoundsException e)
            {
                // throw below
            }

            throw new IllegalArgumentException("invalid PREFIX=ROUTE mapping specified: " + map);
        }

        setPathPrefixToRoute(mappings);
    }


    public void setPathPrefixToRoute(NavigableMap<String, RouteMatch> pathPrefixToRoute)
    {
        this.pathPrefixToRoute = Collections.unmodifiableNavigableMap(pathPrefixToRoute);
    }


    /**
     * Locate a route by a request path prefix.
     */
    public RouteMatch findRouteByPrefix(String prefix)
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
