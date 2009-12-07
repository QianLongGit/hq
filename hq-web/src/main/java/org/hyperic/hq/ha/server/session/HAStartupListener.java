/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004, 2005, 2006], Hyperic, Inc.
 * This file is part of HQ.
 *
 * HQ is free software; you can redistribute it and/or modify
 * it under the terms version 2 of the GNU General Public License as
 * published by the Free Software Foundation. This program is distributed
 * in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more
 * details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 * USA.
 */

package org.hyperic.hq.ha.server.session;

import javax.management.MBeanServer;
import javax.management.ObjectName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hq.application.StartupListener;
import org.hyperic.hq.context.Bootstrap;
import org.hyperic.hq.ha.HAUtil;
import org.hyperic.hq.measurement.server.session.MeasurementStartupListener;

public class HAStartupListener
    implements StartupListener, org.hyperic.hq.ha.HAService
{
    private Log log = LogFactory.getLog(HAStartupListener.class);

    public void hqStarted() {
        MBeanServer server = Bootstrap.getBean(MBeanServer.class);

        log.info("Starting services");

        startConfigService(server);
       
        startHAService(server);
        MeasurementStartupListener.startDataPurgeWorker();
        HAUtil.setHAService(this);
    }
    
    public boolean isMasterNode() {
        // HA not implemented in .org
        return true;
    }

    private void startHAService(MBeanServer server)
    {
        try {
            ObjectName o =
                new ObjectName("hyperic.jmx:type=Service,name=HAService");
            

            server.invoke(o, "startSingleton", new Object[] {}, new String[] {});
        } catch (Exception e) {
            log.info("Unable to start service: "+e);
        }
    }

    private void startConfigService(MBeanServer server)
    {
        try {
            ObjectName o =
                new ObjectName("hyperic.jmx:type=Service,name=ProductConfig");
           

            server.invoke(o, "start", new Object[] {}, new String[] {});
        } catch (Exception e) {
            log.info("Unable to start service: "+e);
        }
    }
}
