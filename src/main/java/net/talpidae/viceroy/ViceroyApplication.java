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
import io.undertow.server.handlers.CanonicalPathHandler;
import io.undertow.server.handlers.ResponseCodeHandler;
import io.undertow.server.handlers.proxy.ProxyHandler;
import lombok.extern.slf4j.Slf4j;
import lombok.val;
import net.talpidae.base.insect.Slave;
import net.talpidae.base.insect.config.SlaveSettings;
import net.talpidae.base.server.Server;
import net.talpidae.base.server.ServerConfig;
import net.talpidae.base.util.Application;
import net.talpidae.viceroy.proxy.InsectProxyClient;

import javax.inject.Inject;
import javax.servlet.ServletException;
import java.io.IOException;
import java.net.InetSocketAddress;

import static java.lang.System.exit;


@Singleton
@Slf4j
public class ViceroyApplication implements Application
{

    private final ServerConfig serverConfig;

    private final Server server;

    private final SlaveSettings slaveSettings;

    private final Slave slave;

    private final InsectProxyClient proxyClient;


    @Inject
    public ViceroyApplication(ServerConfig serverConfig, Server server, SlaveSettings slaveSettings, Slave slave, InsectProxyClient proxyClient)
    {
        this.serverConfig = serverConfig;
        this.server = server;
        this.slaveSettings = slaveSettings;
        this.slave = slave;
        this.proxyClient = proxyClient;
    }


    @Override
    public void run()
    {
        // disable jersey framework (we are just a proxy, for now)
        serverConfig.setJerseyResourcePackages(new String[0]);

        // proxying is all we do
        serverConfig.setRootHandlerWrapper(handler -> new CanonicalPathHandler(new ProxyHandler(proxyClient, 30000, ResponseCodeHandler.HANDLE_404)));

        // TODO Config SSL certs
        // TODO Make this configurable via command line (--server.port)
        serverConfig.setPort(443);

        // make sure we don't accept X-Forwarded-For and such headers
        serverConfig.setBehindProxy(false);

        try
        {
            server.start();

            val bindAddress = new InetSocketAddress(serverConfig.getHost(), serverConfig.getPort());
            log.info("server started on {}", bindAddress.toString());

            slaveSettings.setBindAddress(bindAddress);
            slaveSettings.setRoute(ViceroyApplication.class.getName());
            try
            {
                slave.run();

                server.waitForShutdown();
            }
            finally
            {
                try
                {
                    slave.close();
                }
                catch (IOException e)
                {
                    // never happens
                }
            }
        }
        catch (ServletException e)
        {
            log.error("failed to start server: {}", e.getMessage());
            exit(1);
        }
    }
}
