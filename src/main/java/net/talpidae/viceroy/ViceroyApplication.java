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

package net.talpidae.viceroy;

import com.google.inject.Singleton;

import net.talpidae.base.server.Server;
import net.talpidae.base.server.ServerConfig;
import net.talpidae.base.util.Application;
import net.talpidae.viceroy.proxy.DnsLookupProxyClient;
import net.talpidae.viceroy.proxy.ProxyConfig;

import java.net.InetSocketAddress;

import javax.inject.Inject;
import javax.servlet.ServletException;

import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import static java.lang.System.exit;


@Singleton
@Slf4j
public class ViceroyApplication implements Application
{
    private final ServerConfig serverConfig;

    private final Server server;

    private final DnsLookupProxyClient proxyClient;

    private final ProxyConfig proxyConfig;


    @Inject
    public ViceroyApplication(ServerConfig serverConfig,
                              Server server,
                              DnsLookupProxyClient proxyClient,
                              ProxyConfig proxyConfig)
    {
        this.serverConfig = serverConfig;
        this.server = server;
        this.proxyClient = proxyClient;
        this.proxyConfig = proxyConfig;
    }


    @Override
    public void run()
    {
        // proxy is all we do
        val proxyHandler = ProxyHandler.builder()
                .setProxyClient(proxyClient)
                .setMaxRequestTime(proxyConfig.getMaxRequestTime())
                .setMaxConnectionRetries(proxyConfig.getMaxRetries())
                .setNext(ResponseCodeHandler.HANDLE_404)
                .build();

        serverConfig.setRootHandlerWrapper(handler -> new CanonicalPathHandler(proxyHandler));

        // make sure we don't accept headers like X-Forwarded-For
        serverConfig.setBehindProxy(false);

        try
        {
            server.start();

            val bindAddress = new InetSocketAddress(serverConfig.getHost(), serverConfig.getPort());
            log.info("server started on {}", bindAddress.toString());

            server.waitForShutdown();
        }
        catch (ServletException e)
        {
            log.error("failed to start server: {}", e.getMessage());
            exit(1);
        }
    }
}
