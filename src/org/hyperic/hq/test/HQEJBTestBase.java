package org.hyperic.hq.test;

import org.hibernate.Session;
import org.hyperic.dao.DAOFactory;
import org.hyperic.hibernate.Util;
import org.hyperic.hq.events.server.session.RegisteredTriggerManagerEJBImpl;
import org.mockejb.SessionBeanDescriptor;

public abstract class HQEJBTestBase    
    extends MockBeanTestBase 
{
    private static boolean _initialized = false;
    private Session _session;
    
    public HQEJBTestBase(String testName) {
        super(testName);
    }
    
    private Class[] getUsedSessionBeans() {
        return new Class[] { RegisteredTriggerManagerEJBImpl.class };
    }

    public void setUp() 
        throws Exception
    {
        if (_initialized) {
            _session = Util.getSessionFactory().openSession();
            DAOFactory.setMockSession(_session);
            return;
        }
        
        super.setUp();

        // Deploy the session beans into the MockEJB converter.  We have to
        // jump through some hoops here to get the right class names.
        Class[] sessBeans = getUsedSessionBeans();
        for (int i=0; i<sessBeans.length; i++) {
            String simpleName = sessBeans[i].getName();
            String baseName, jndi;
            Class local, localHome; 
            
            if (!simpleName.endsWith("EJBImpl")) {
                throw new IllegalArgumentException("getUsedSessionBeans() " + 
                                                   "needs EJBImpl classes");
            }
            
            baseName = simpleName.replaceFirst("server", "shared");
            baseName = baseName.substring(0, simpleName.length() - 
                                          "EJBImpl".length());
            baseName = baseName.replaceFirst(".session.", ".");
            local     = Class.forName(baseName + "Local");
            localHome = Class.forName(baseName + "LocalHome");

            jndi = localHome.getDeclaredField("JNDI_NAME").get(null).toString();
            deploySessionBean(new SessionBeanDescriptor(jndi, localHome, local,
                                                        sessBeans[i]));
        }

        _session = Util.getSessionFactory().openSession();
        DAOFactory.setMockSession(_session);
    }

    public void tearDown() throws Exception {
        _session.disconnect();
    }
}
