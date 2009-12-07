/*
 * NOTE: This copyright does *not* cover user programs that use HQ
 * program services by normal system calls through the application
 * program interfaces provided as part of the Hyperic Plug-in Development
 * Kit or the Hyperic Client Development Kit - this is merely considered
 * normal use of the program, and does *not* fall under the heading of
 * "derived work".
 *
 * Copyright (C) [2004-2009], Hyperic, Inc.
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

package org.hyperic.hq.product.server.mbean;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import javax.management.Attribute;
import javax.management.AttributeChangeNotification;
import javax.management.ListenerNotFoundException;
import javax.management.MBeanNotificationInfo;
import javax.management.MalformedObjectNameException;
import javax.management.Notification;
import javax.management.NotificationBroadcaster;
import javax.management.NotificationBroadcasterSupport;
import javax.management.NotificationFilter;
import javax.management.NotificationListener;
import javax.management.ObjectName;
import javax.naming.NamingException;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.hyperic.hibernate.Util;
import org.hyperic.hibernate.dialect.HQDialect;
import org.hyperic.hq.application.HQApp;
import org.hyperic.hq.bizapp.server.session.SystemAudit;
import org.hyperic.hq.common.SystemException;
import org.hyperic.hq.common.shared.HQConstants;
import org.hyperic.hq.context.Bootstrap;
import org.hyperic.hq.hqu.RenditServer;
import org.hyperic.hq.measurement.MeasurementConstants;
import org.hyperic.hq.measurement.shared.MeasTabManagerUtil;
import org.hyperic.hq.product.PluginException;
import org.hyperic.hq.product.PluginInfo;
import org.hyperic.hq.product.ProductPlugin;
import org.hyperic.hq.product.ProductPluginManager;
import org.hyperic.hq.product.server.session.ProductStartupListener;
import org.hyperic.hq.product.shared.ProductManager;
import org.hyperic.hq.stats.ConcurrentStatsCollector;
import org.hyperic.util.file.FileUtil;
import org.hyperic.util.jdbc.DBUtil;
import org.jboss.deployment.DeploymentException;
import org.jboss.deployment.DeploymentInfo;
import org.jboss.deployment.SubDeployerSupport;
import org.jboss.system.server.ServerConfig;
import org.jboss.system.server.ServerConfigLocator;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jmx.export.annotation.ManagedAttribute;
import org.springframework.jmx.export.annotation.ManagedMetric;
import org.springframework.jmx.export.annotation.ManagedOperation;
import org.springframework.jmx.export.annotation.ManagedResource;
import org.springframework.stereotype.Service;

/**
 * ProductPlugin deployer.
 * We accept $PLUGIN_DIR/*.{jar,xml}
 *
 * 
 */
@ManagedResource("hyperic.jmx:type=Service,name=ProductPluginDeployer")
@Service
public class ProductPluginDeployer
    extends SubDeployerSupport
    implements NotificationBroadcaster,
               NotificationListener,
               ProductPluginDeployerMBean,
               Comparator<String>
{
    private static final String READY_MGR_NAME =
        "hyperic.jmx:service=NotReadyManager";
    private static final String SERVER_NAME =
        "jboss.system:type=Server";
    private static final String URL_SCANNER_NAME =
        "hyperic.jmx:type=DeploymentScanner,flavor=URL";
    private static final String READY_ATTR = "Ready";
    private static final String PRODUCT = "HQ";
    private static final String PLUGIN_DIR = "hq-plugins";
    private static final String HQU = "hqu";
    
    private static final String TAB_DATA = MeasurementConstants.TAB_DATA,
    MEAS_VIEW = MeasTabManagerUtil.MEAS_VIEW;

    private HQApp hqApp;
    private DBUtil dbUtil;
    private RenditServer renditServer;

    private Log _log = LogFactory.getLog(ProductPluginDeployer.class);

    private ProductPluginManager _ppm;
    private List<String>                 _plugins = new ArrayList<String>();
    private boolean              _isStarted = false;
    private ObjectName           _readyMgrName;
    private ObjectName           _serverName;
    private String               _pluginDir = PLUGIN_DIR;
    private String               _hquDir;

    private NotificationBroadcasterSupport _broadcaster =
        new NotificationBroadcasterSupport();

    private long _notifSequence = 0;

    private static final String PLUGIN_REGISTERED =
        NOTIF_TYPE("registered");

    private static final String PLUGIN_DEPLOYED =
        NOTIF_TYPE("deployed");

    private static final String PLUGIN_UNDEPLOYED =
        NOTIF_TYPE("undeployed");

    private static final String DEPLOYER_READY =
        NOTIF_TYPE("deployer.ready");

    private static final String DEPLOYER_CLEARED =
        NOTIF_TYPE("deployer.cleared");

    private static final String DEPLOYER_SUSPENDED =
        NOTIF_TYPE("deployer.suspended");

    private static final String[] NOTIF_TYPES = new String[] {
        DEPLOYER_READY,
        DEPLOYER_SUSPENDED,
        DEPLOYER_CLEARED,
        PLUGIN_REGISTERED,
        PLUGIN_DEPLOYED,
        PLUGIN_UNDEPLOYED,
    };

    private static String NOTIF_TYPE(String type) {
        return PRODUCT + ".plugin." + type;
    }

    @Autowired
    public ProductPluginDeployer(HQApp hqApp, DBUtil dbUtil, RenditServer renditServer) {
        super();
        this.hqApp = hqApp;
        this.dbUtil = dbUtil;
        this.renditServer = renditServer;

        //XXX un-hardcode these paths.
        String ear =
            System.getProperty("jboss.server.home.dir") +
            "/deploy/hq.ear";

        //native libraries are deployed into another directory
        //which is not next to sigar.jar, so we drop this hint
        //to find it.
        System.setProperty("org.hyperic.sigar.path",
                           ear + "/sigar_bin/lib");

        _hquDir = ear + "/hq.war/" + HQU;

        // Initialize database
        initDatabase();

        File propFile = ProductPluginManager.PLUGIN_PROPERTIES_FILE;
        _ppm = new ProductPluginManager(propFile);
        _ppm.setRegisterTypes(true);

        if (propFile.canRead()) {
            _log.info("Loaded custom properties from: " + propFile);
        }

        try {
            _readyMgrName = new ObjectName(READY_MGR_NAME);
            _serverName   = new ObjectName(SERVER_NAME);
        } catch (MalformedObjectNameException e) {
            //notgonnahappen
            _log.error(e);
        }
    }
    
    private void initDatabase() {
        Connection conn = null;

        try {
         
            conn = dbUtil.getConnection();

            DatabaseRoutines[] dbrs = getDBRoutines(conn);

            for (int i = 0; i < dbrs.length; i++) {
                dbrs[i].runRoutines(conn);
            }
        } catch (SQLException e) {
            log.error("SQLException creating connection to " +
                      HQConstants.DATASOURCE, e);
        } catch (NamingException e) {
            log.error("NamingException creating connection to " +
                      HQConstants.DATASOURCE, e);
        } finally {
            DBUtil.closeConnection(ProductPluginDeployer.class, conn);
        }
    }
    
    interface DatabaseRoutines {
        public void runRoutines(Connection conn) throws SQLException;
    }

    private DatabaseRoutines[] getDBRoutines(Connection conn)
        throws SQLException {
        ArrayList<CommonRoutines> routines = new ArrayList<CommonRoutines>(2);

        routines.add(new CommonRoutines());

        return (DatabaseRoutines[]) routines.toArray(new DatabaseRoutines[0]);
    }

    class CommonRoutines implements DatabaseRoutines {
        public void runRoutines(Connection conn) throws SQLException {
            final String UNION_BODY =
                "SELECT * FROM HQ_METRIC_DATA_0D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_0D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_1D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_1D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_2D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_2D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_3D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_3D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_4D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_4D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_5D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_5D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_6D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_6D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_7D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_7D_1S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_8D_0S UNION ALL " +
                "SELECT * FROM HQ_METRIC_DATA_8D_1S";

            final String HQ_METRIC_DATA_VIEW =
                "CREATE VIEW "+MEAS_VIEW+" AS " + UNION_BODY;

            final String EAM_METRIC_DATA_VIEW =
                "CREATE VIEW "+TAB_DATA+" AS " + UNION_BODY +
                " UNION ALL SELECT * FROM HQ_METRIC_DATA_COMPAT";

            Statement stmt = null;
            try {
                HQDialect dialect = Util.getHQDialect();
                stmt = conn.createStatement();
                if (!dialect.viewExists(stmt, TAB_DATA))
                    stmt.execute(EAM_METRIC_DATA_VIEW);
                if (!dialect.viewExists(stmt, MEAS_VIEW))
                    stmt.execute(HQ_METRIC_DATA_VIEW);
            } catch (SQLException e) {
                log.debug("Error Creating Metric Data Views", e);
            } finally {
                DBUtil.closeStatement(ProductPluginDeployer.class, stmt);
            }
        }
    }

    /**
     * This is called when the full server startup has occurred, and you
     * get the "Started in 30s:935ms" message.
     *
     * We load all startup classes, then initialize the plugins.  Currently
     * this is necesssary, since startup classes need to initialize the
     * application (creating callbacks, etc.), and plugins can't hit the
     * app until that's been done.  Unfortunately, it also means that any
     * startup listeners that depend on plugins loaded through the deployer
     * won't work.  So far that doesn't seem to be a problem, but if it
     * ends up being one, we can split the plugin loading into more stages so
     * that everyone has access to everyone.
     *
     * 
     */
    @ManagedOperation
    public void handleNotification(Notification n, Object o) {
        loadConfig();
        Bootstrap.loadEJBApplicationContext();
        loadStartupClasses();
     

        pluginNotify("deployer", DEPLOYER_READY);

        Collections.sort(_plugins, this);

        ProductManager pm = getProductManager();

        for (String pluginName : _plugins) {
          
            try {
                deployPlugin(pluginName, pm);
            } catch(DeploymentException e) {
                _log.error("Unable to deploy plugin [" + pluginName + "]", e);
            }
        }

        ProductStartupListener
            .getPluginsDeployedCaller().pluginsDeployed(_plugins);

        _plugins.clear();
        startConcurrentStatsCollector();

        //generally means we are done deploying plugins at startup.
        //but we are not "done" since a plugin can be dropped into
        //hq-plugins at anytime.
        pluginNotify("deployer", DEPLOYER_CLEARED);

       

        setReady(true);

        if (n != null && n.getType().equals("org.jboss.system.server.started")) {
            SystemAudit.createUpAudit(((Number)n.getUserData()).longValue());
        }
    }

    private void startConcurrentStatsCollector() {
       
        try {
            ConcurrentStatsCollector c = ConcurrentStatsCollector.getInstance();
            c.register(
                ConcurrentStatsCollector.RUNTIME_PLATFORM_AND_SERVER_MERGER);
            c.register(ConcurrentStatsCollector.AVAIL_MANAGER_METRICS_INSERTED);
            c.register(ConcurrentStatsCollector.DATA_MANAGER_INSERT_TIME);
            c.register(ConcurrentStatsCollector.JMS_TOPIC_PUBLISH_TIME);
            c.register(ConcurrentStatsCollector.JMS_QUEUE_PUBLISH_TIME);
            c.register(ConcurrentStatsCollector.METRIC_DATA_COMPRESS_TIME);
            c.register(ConcurrentStatsCollector.DB_ANALYZE_TIME);
            c.register(ConcurrentStatsCollector.PURGE_EVENT_LOGS_TIME);
            c.register(ConcurrentStatsCollector.PURGE_MEASUREMENTS_TIME);
            c.register(ConcurrentStatsCollector.MEASUREMENT_SCHEDULE_TIME);
            c.register(ConcurrentStatsCollector.EMAIL_ACTIONS);
            c.register(ConcurrentStatsCollector.ZEVENT_QUEUE_SIZE);
            c.register(ConcurrentStatsCollector.FIRE_ALERT_TIME);
            c.register(ConcurrentStatsCollector.EVENT_PROCESSING_TIME);
            c.register(ConcurrentStatsCollector.TRIGGER_INIT_TIME);
            c.startCollector();
        } catch (Exception e) {
            _log.error("Could not start Concurrent Stats Collector", e);
        }
    }


    protected boolean isDeployable(String name, URL url) {
        boolean isDeployable = super.isDeployable(name, url);
        if (isDeployable && name.endsWith(SubDeployerSupport.nativeSuffix)) {
            //e.g. JBoss will attempt to deploy a .so regardless of linux/solaris/etc
            _log.info("Skipping deployment: " + name);
            return false;
        }
        return isDeployable;
    }

    public void addNotificationListener(NotificationListener listener,
                                        NotificationFilter filter,
                                        Object handback)
    {
        _broadcaster.addNotificationListener(listener, filter, handback);
    }

    public void removeNotificationListener(NotificationListener listener)
        throws ListenerNotFoundException
    {
        _broadcaster.removeNotificationListener(listener);
    }

    public MBeanNotificationInfo[] getNotificationInfo() {
        return new MBeanNotificationInfo[] {
            new MBeanNotificationInfo(NOTIF_TYPES,
                                      Notification.class.getName(),
                                      "Product Plugin Notifications"),
        };
    }

    /**
     * 
     */
    @ManagedAttribute
    public ProductPluginManager getProductPluginManager() {
        return _ppm;
    }

    /**
     * 
     */
    @ManagedAttribute
    public void setPluginDir(String name) {
        _pluginDir = name;
    }

    /**
     * 
     */
    @ManagedAttribute
    public String getPluginDir() {
        return _pluginDir;
    }

    private Set getPluginNames(String type)
        throws PluginException
    {
        return _ppm.getPluginManager(type).getPlugins().keySet();
    }

    /**
     * 
     * List registered plugin names of given type.
     * Intended for use via /jmx-console
     */
    @ManagedAttribute
    public ArrayList getRegisteredPluginNames(String type)
        throws PluginException
    {
        return new ArrayList(getPluginNames(type));
    }

    /**
     * 
     * List registered product plugin names.
     * Intended for use via /jmx-console
     */
    @ManagedAttribute
    public ArrayList getRegisteredPluginNames()
        throws PluginException
    {
        return new ArrayList(_ppm.getPlugins().keySet());
    }

    /**
     * 
     */
    @ManagedMetric
    public int getProductPluginCount()
        throws PluginException
    {
        return _ppm.getPlugins().keySet().size();
    }

    /**
     * 
     */
    @ManagedMetric
    public int getMeasurementPluginCount()
        throws PluginException
    {
        return getPluginNames(ProductPlugin.TYPE_MEASUREMENT).size();
    }

    /**
     * 
     */
    @ManagedMetric
    public int getControlPluginCount()
        throws PluginException
    {
        return getPluginNames(ProductPlugin.TYPE_CONTROL).size();
    }

    /**
     * 
     */
    @ManagedMetric
    public int getAutoInventoryPluginCount()
        throws PluginException
    {
        return getPluginNames(ProductPlugin.TYPE_AUTOINVENTORY).size();
    }

    /**
     * 
     */
    @ManagedMetric
    public int getLogTrackPluginCount()
        throws PluginException
    {
        return getPluginNames(ProductPlugin.TYPE_LOG_TRACK).size();
    }

    /**
     * 
     */
    @ManagedMetric
    public int getConfigTrackPluginCount()
        throws PluginException
    {
        return getPluginNames(ProductPlugin.TYPE_CONFIG_TRACK).size();
    }

    /**
     * 
     */
    @ManagedOperation
    public void setProperty(String name, String value) {
        String oldValue = _ppm.getProperty(name, "null");
        _ppm.setProperty(name, value);
        _log.info("setProperty(" + name + ", " + value + ")");
        attributeChangeNotify("setProperty", name, oldValue, value);
    }

    /**
     * 
     */
    @ManagedOperation
    public String getProperty(String name) {
       return _ppm.getProperty(name);
    }

    /**
     * 
     */
    @ManagedOperation
    public PluginInfo getPluginInfo(String name)
        throws PluginException
    {
        PluginInfo info = _ppm.getPluginInfo(name);

        if (info == null) {
            throw new PluginException("No PluginInfo found for: " + name);
        }

        return info;
    }

    public boolean accepts(DeploymentInfo di) {
        String urlFile = di.url.getFile();

        if (!(urlFile.endsWith("jar") || (urlFile.endsWith("xml")))) {
            return false;
        }

        String urlPath = new File(urlFile).getParent();

        if (urlPath.endsWith(_pluginDir)) {
            _log.debug("accepting plugin=" + urlFile);
            return true;
        }

        return false;
    }

    public int compare(String s1, String s2) {
        int order1 = _ppm.getPluginInfo(s1).deploymentOrder;
        int order2 = _ppm.getPluginInfo(s2).deploymentOrder;

        return order1 - order2;
    }

    private void setReady(boolean ready) {
        if (getServer() != null) {
            try {
                getServer().setAttribute(_readyMgrName,
                                         new Attribute(READY_ATTR,
                                                       ready ? Boolean.TRUE :
                                                       Boolean.FALSE));
            } catch(Exception e) {
                _log.error("Unable to declare application ready", e);
            }
        }
    }

    /**
     *
     */
    @ManagedAttribute
    public boolean isReady() {
        Boolean isReady;
        try {
            isReady = (Boolean)getServer().getAttribute(_readyMgrName,
                                                        READY_ATTR);
        } catch (Exception e) {
            _log.error("Unable to get Application's ready state", e);
            return false;
        }

        return isReady.booleanValue();
    }
    
    private void loadConfig() {
        ServerConfig sc = ServerConfigLocator.locate();
        hqApp.setRestartStorageDir(sc.getHomeDir());
        File deployDir = new File(sc.getServerHomeDir(), "deploy");
        File earDir    = new File(deployDir, "hq.ear");
        hqApp.setResourceDir(earDir);
        File warDir    = new File(earDir, "hq.war");
        hqApp.setWebAccessibleDir(warDir);
    }

    private void loadStartupClasses() {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        InputStream is =
            loader.getResourceAsStream("META-INF/startup_classes.txt");
        List<String> lines;

        try {
            lines = FileUtil.readLines(is);
        } catch(IOException e) {
            throw new SystemException(e);
        }

     
        for (String className : lines) {
            className = className.trim();
            if (className.length() == 0 || className.startsWith("#")) {
                continue;
            }

            hqApp.addStartupClass(className);
        }
        hqApp.runStartupClasses();
    }

    private void pluginNotify(String name, String type) {
        String action = type.substring(type.lastIndexOf(".") + 1);
        String msg = PRODUCT + " plugin " + name + " " + action;

        Notification notif = new Notification(type, this, ++_notifSequence,
                                              msg);

        _log.info(msg);

        _broadcaster.sendNotification(notif);
    }

    private void attributeChangeNotify(String msg, String attr,
                                       Object oldValue, Object newValue) {

        Notification notif =
            new AttributeChangeNotification(this,
                                            ++_notifSequence,
                                            System.currentTimeMillis(),
                                            msg,
                                            attr,
                                            newValue.getClass().getName(),
                                            oldValue,
                                            newValue);

        _broadcaster.sendNotification(notif);
    }

    private ProductManager getProductManager() {
        return Bootstrap.getBean(ProductManager.class);
    }

    private String registerPluginJar(DeploymentInfo di) {
        String pluginJar = di.url.getFile();

        if (!_ppm.isLoadablePluginName(pluginJar)) {
            return null;
        }

        try {
            //di.localCl to find resources such as etc/hq-plugin.xml
            String plugin = _ppm.registerPluginJar(pluginJar, di.localCl);

            pluginNotify(plugin, PLUGIN_REGISTERED);
            return plugin;
        } catch (Exception e) {
            _log.error("Unable to deploy plugin '" + pluginJar + "'", e);
            return null;
        }
    }

    private void deployPlugin(String plugin, ProductManager pm)
        throws DeploymentException {

        try {
            pm.deploymentNotify(plugin);
            pluginNotify(plugin, PLUGIN_DEPLOYED);
        } catch (Exception e) {
            _log.error("Unable to deploy plugin '" + plugin + "'", e);
        }
    }

    private void addCustomPluginURL(File dir) {
        ObjectName urlScanner;

        String msg = "Adding custom plugin dir " + dir;
        _log.info(msg);

        try {
            urlScanner = new ObjectName(URL_SCANNER_NAME);
            server.invoke(urlScanner, "addURL",
                          new Object[] { dir.toURL() },
                          new String[] { URL.class.getName() });
        } catch (Exception e) {
            _log.error(msg, e);
        }
    }

    //check $jboss.home.url/.. and higher for hq-plugins
    private void addCustomPluginDir() {
        URL url;
        String prop = "jboss.home.url";
        String home = System.getProperty(prop);

        if (home == null) {
            return;
        }
        try {
            url = new URL(home);
        } catch (MalformedURLException e) {
            _log.error("Malformed " + prop + "=" + home);
            return;
        }

        File dir = new File(url.getFile()).getParentFile();
        while (dir != null) {
            File pluginDir = new File(dir, PLUGIN_DIR);
            if (pluginDir.exists()) {
                addCustomPluginURL(pluginDir);
                break;
            }
            dir = dir.getParentFile();
        }
    }

    /**
     * MBean Service start method. This method is called when JBoss is deploying
     * the MBean, unfortunately, the dependencies that this has with
     * HighAvailService and with other components is such that the only thing
     * this method does is queue up the plugins that are ready for deployment.
     * The actual deployment occurs when the startDeployer() method is called.
     */
    public void start() throws Exception {
        if(_isStarted)
            return;

        _isStarted = true;

        super.start();

        _ppm.init();

        try {
            //hq.ear contains sigar_bin/lib with the
            //native sigar libraries.  we set sigar.install.home
            //here so plugins which use sigar can find it during Sigar.load()

            String path = getClass().getClassLoader().
                getResource("sigar_bin").getFile();

            _ppm.setProperty("sigar.install.home", path);
        } catch (Exception e) {
            _log.error(e);
        }

        getServer().addNotificationListener(_serverName, this, null, null);

        //turn off ready filter asap at shutdown
        //this.stop() won't run until all files are undeploy()ed
        //which may take several minutes.
        Runtime.getRuntime().addShutdownHook(new Thread() {
            public void run() {
                setReady(false);
            }
        });

        addCustomPluginDir();
    }

    /**
     * 
     */
    @ManagedOperation
    public void stop() {
        super.stop();
        pluginNotify("deployer", DEPLOYER_SUSPENDED);
        setReady(false);
        _plugins.clear();
    }

    private void unpackJar(URL url, File destDir, String prefix)
        throws Exception {

        JarFile jar = new JarFile(url.getFile());
        try {
            for (Enumeration e=jar.entries(); e.hasMoreElements();) {
                JarEntry entry = (JarEntry)e.nextElement();
                String name = entry.getName();

                if (name.startsWith(prefix)) {
                    name = name.substring(prefix.length());
                    if (name.length() == 0) {
                        continue;
                    }
                    File file = new File(destDir, name);
                    if (entry.isDirectory()) {
                        file.mkdirs();
                    }
                    else {
                        FileUtil.copyStream(jar.getInputStream(entry),
                                            new FileOutputStream(file));
                    }
                }
            }
        } finally {
            jar.close();
        }
    }

    private void deployHqu(String plugin, DeploymentInfo di)
        throws Exception {

        final String prefix = HQU + "/";
        URL hqu = di.localCl.findResource(prefix);
        if (hqu == null) {
            return;
        }
        File destDir = new File(_hquDir, plugin);
        boolean exists = destDir.exists();
        _log.info("Deploying " + plugin + " " +
                  HQU + " to: " + destDir);

        unpackJar(di.url, destDir, prefix);

        
        if (renditServer.getSysDir() != null) { //rendit.isReady() ?
            if (exists) {
                //update ourselves to avoid having to delete,sleep,unpack
                renditServer.removePluginDir(destDir.getName());
                renditServer.addPluginDir(destDir);
            } //else Rendit watcher will deploy the new plugin
        }
    }

    public void start(DeploymentInfo di)
        throws DeploymentException
    {
        try {
            start();
        } catch (Exception e) {
            throw new DeploymentException("Bombed", e);
        }

        _log.debug("start: " + di.url.getFile());

        //the plugin jar can be registered at any time
        String plugin = registerPluginJar(di);
        if (plugin == null) {
            return;
        }

        //plugin metadata cannot be deployed until HQ is up
        if (isReady()) {
            ProductManager pm = getProductManager();
            deployPlugin(plugin, pm);
        }
        else {
            _plugins.add(plugin);
        }

        try {
            deployHqu(plugin, di);
        } catch (Exception e) {
            throw new DeploymentException("Failed to deploy " +
                                          plugin + " " + HQU + ": " + e, e);
        }
    }

    public void stop(DeploymentInfo di)
        throws DeploymentException
    {
        _log.debug("stop: " + di.url.getFile());

        try {
            String jar = di.url.getFile();
            _ppm.removePluginJar(jar);
            pluginNotify(new File(jar).getName(), PLUGIN_UNDEPLOYED);
        } catch (Exception e) {
            throw new DeploymentException(e);
        }
    }
}
