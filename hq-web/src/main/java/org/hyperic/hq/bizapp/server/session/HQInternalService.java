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

package org.hyperic.hq.bizapp.server.session;

import java.util.List;

import org.hyperic.hq.appdef.shared.AgentManager;
import org.hyperic.hq.appdef.shared.PlatformManager;
import org.hyperic.hq.application.HQApp;
import org.hyperic.hq.measurement.server.session.CollectionSummary;
import org.hyperic.hq.measurement.server.session.ReportStatsCollector;
import org.hyperic.hq.measurement.shared.MeasurementManager;
import org.hyperic.hq.zevents.ZeventEnqueuer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Service;
@Service
@ManagedResource("hyperic.jmx:name=HQInternal")
public class HQInternalService implements HQInternalServiceMBean {
    
    
    private AgentManager agentManager;
    private MeasurementManager measurementManager;
    private HQApp hqApp;
    private PlatformManager platformManager;
    private ZeventEnqueuer zEventManager;
    
    
    @Autowired
    public HQInternalService(AgentManager agentManager, MeasurementManager measurementManager, HQApp hqApp,
                             PlatformManager platformManager, ZeventEnqueuer zEventManager) {
        this.agentManager = agentManager;
        this.measurementManager = measurementManager;
        this.hqApp = hqApp;
        this.platformManager = platformManager;
        this.zEventManager = zEventManager;
    }

    public double getMetricInsertsPerMinute() {
        double val = ReportStatsCollector.getInstance()
                        .getCollector().valPerTimestamp(); 

        return val * 1000.0 * 60.0;
    }

    public int getAgentCount() {
        return agentManager.getAgentCountUsed();
    }

    public double getMetricsCollectedPerMinute() { 
        List<CollectionSummary> vals = measurementManager
                        .findMetricCountSummaries();
        double total = 0.0;
        
        for (CollectionSummary s : vals ) {
            total += (float)s.getTotal() / (float)s.getInterval();
        }
        
        return total;
    }

    public int getPlatformCount() {
        return platformManager.getPlatformCount().intValue();
    }

    public long getTransactionCount() {
        return hqApp.getTransactions();
    }

    public long getTransactionFailureCount() {
        return hqApp.getTransactionsFailed();
    }

    public long getAgentRequests() {
        return agentManager.getTotalConnectedAgents();
    }
    
    public int getAgentConnections() {
        return agentManager.getNumConnectedAgents();
    }

    public long getZeventMaxWait() {
        return zEventManager.getMaxTimeInQueue();
    }

    public long getZeventsProcessed() {
        return zEventManager.getZeventsProcessed();
    }

    public long getZeventQueueSize() {
        return zEventManager.getQueueSize();
    }
}
