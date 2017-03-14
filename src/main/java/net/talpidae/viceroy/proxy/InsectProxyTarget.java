package net.talpidae.viceroy.proxy;

import io.undertow.server.handlers.proxy.ProxyClient;
import lombok.Builder;

import java.net.InetSocketAddress;


@Builder
public class InsectProxyTarget implements ProxyClient.ProxyTarget
{
    private final RouteMatch matchedPathPrefix;

    private final InetSocketAddress destination;
}
