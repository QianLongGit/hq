/**
 * NOTE: This copyright does *not* cover user programs that use Hyperic
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 *  Copyright (C) [2010], VMware, Inc.
 *  This file is part of Hyperic.
 *
 *  Hyperic is free software; you can redistribute it and/or modify
 *  it under the terms version 2 of the GNU General Public License as
 *  published by the Free Software Foundation. This program is distributed
 *  in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 *  even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 *  PARTICULAR PURPOSE. See the GNU General Public License for more
 *  details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA 02111-1307
 *  USA.
 *
 */
package org.hyperic.hq.plugin.rabbitmq.populate;

import com.rabbitmq.client.Channel;
import org.hyperic.hq.plugin.rabbitmq.configure.ConfigurationManager;
import org.hyperic.hq.plugin.rabbitmq.configure.RabbitTestConfiguration;
import org.hyperic.hq.plugin.rabbitmq.core.HypericChannel;
import org.hyperic.hq.plugin.rabbitmq.core.HypericConnection;
import org.hyperic.hq.plugin.rabbitmq.core.RabbitGateway;
import org.hyperic.hq.plugin.rabbitmq.manage.RabbitManager;
import org.hyperic.hq.product.PluginException;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.admin.QueueInfo;
import org.springframework.amqp.rabbit.admin.RabbitBrokerAdmin;
import org.springframework.amqp.rabbit.connection.SingleConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;

import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertNotNull; 

/**
 * PopulateData can be run to populate the QA rabbitmq servers
 * Not finished yet.
 * @author Helena Edelson
 */
public class PopulateData {

    public static void main(String[] args) throws Exception {
        ConfigurableApplicationContext ctx = new AnnotationConfigApplicationContext(RabbitTestConfiguration.class);
        ConfigurationManager cf = ctx.getBean(ConfigurationManager.class);
        RabbitGateway rabbitGateway = cf.getRabbitGateway();
        RabbitTemplate rabbitTemplate = cf.getRabbitTemplate();
        RabbitBrokerAdmin admin = cf.getRabbitBrokerAdmin();

        Queue marketDataQueue = ctx.getBean("marketDataQueue", Queue.class);
        admin.declareQueue(marketDataQueue);

        rabbitTemplate.setRoutingKey(marketDataQueue.getName());
        rabbitTemplate.setQueue(marketDataQueue.getName());

        refresh(cf.getConnectionFactory(), cf.getRabbitGateway());

        int numMessages = 100;

        ProducerSample producer = new ProducerSample(rabbitTemplate, numMessages);
        producer.sendMessages();

        List<QueueInfo> queues = rabbitGateway.getQueues("/");
        if (queues != null) {
            System.out.println("queues has " + queues.size());
            for (QueueInfo q : queues) {
                System.out.println(q);
            }
        }

        ctx.close();
        System.exit(0);
    }

    /**
     *
     * @param scf
     * @param rabbitGateway 
     * @throws Exception
     */
    private static void refresh(SingleConnectionFactory scf, RabbitGateway rabbitGateway) throws Exception {
        com.rabbitmq.client.Connection conn = scf.createConnection();
        Map<String, Object> props = conn.getServerProperties();
        System.out.println(props);

        conn.createChannel();
        conn.createChannel();

        List<HypericChannel> channels = rabbitGateway.getChannels("/");
        assertNotNull(channels);

        List<HypericConnection> connections = rabbitGateway.getConnections("/");
        assertNotNull(connections);
        
        /** kept it open for a while to show some metrics */
        conn.close();
    }
}