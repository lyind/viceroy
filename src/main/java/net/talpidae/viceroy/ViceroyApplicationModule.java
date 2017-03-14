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


import com.google.inject.AbstractModule;
import lombok.extern.slf4j.Slf4j;
import net.talpidae.base.Base;
import net.talpidae.base.insect.AsyncQueen;
import net.talpidae.base.insect.Queen;
import net.talpidae.base.insect.Slave;
import net.talpidae.base.insect.SyncSlave;
import net.talpidae.base.insect.config.DefaultQueenSettings;
import net.talpidae.base.insect.config.DefaultSlaveSettings;
import net.talpidae.base.insect.config.QueenSettings;
import net.talpidae.base.insect.config.SlaveSettings;
import net.talpidae.base.server.DefaultServerConfig;
import net.talpidae.base.server.ServerConfig;
import net.talpidae.base.util.Application;
import net.talpidae.base.util.auth.Authenticator;
import net.talpidae.base.util.session.SessionService;
import net.talpidae.centipede.util.auth.LocalAuthenticator;
import net.talpidae.centipede.util.session.LocalSessionService;


@Slf4j
public class ViceroyApplicationModule extends AbstractModule
{
    public static void main(String[] args)
    {
        Base.initializeApp(args, new CentipedeApplicationModule()).run();
    }


    @Override
    protected void configure()
    {
        bind(Application.class).to(CentipedeApplication.class);

        bind(QueenSettings.class).to(DefaultQueenSettings.class);
        bind(Queen.class).to(AsyncQueen.class);

        bind(SlaveSettings.class).to(DefaultSlaveSettings.class);
        bind(Slave.class).to(SyncSlave.class);  // we don't use it

        bind(ServerConfig.class).to(DefaultServerConfig.class);

        bind(Authenticator.class).to(LocalAuthenticator.class);
        bind(SessionService.class).to(LocalSessionService.class);
    }
}
