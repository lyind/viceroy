package net.talpidae.viceroy.proxy;

import lombok.AllArgsConstructor;
import lombok.Getter;


@AllArgsConstructor
@Getter
public class RouteMatch
{
    private final String prefix;

    private final String route;
}
