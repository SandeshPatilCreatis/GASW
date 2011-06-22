/* Copyright CNRS-CREATIS
 *
 * Rafael Silva
 * rafael.silva@creatis.insa-lyon.fr
 * http://www.creatis.insa-lyon.fr/~silva
 *
 * This software is a grid-enabled data-driven workflow manager and editor.
 *
 * This software is governed by the CeCILL  license under French law and
 * abiding by the rules of distribution of free software.  You can  use,
 * modify and/ or redistribute the software under the terms of the CeCILL
 * license as circulated by CEA, CNRS and INRIA at the following URL
 * "http://www.cecill.info".
 *
 * As a counterpart to the access to the source code and  rights to copy,
 * modify and redistribute granted by the license, users are provided only
 * with a limited warranty  and the software's author,  the holder of the
 * economic rights,  and the successive licensors  have only  limited
 * liability.
 *
 * In this respect, the user's attention is drawn to the risks associated
 * with loading,  using,  modifying and/or developing or reproducing the
 * software by the user in light of its specific status of free software,
 * that may mean  that it is complicated to manipulate,  and  that  also
 * therefore means  that it is reserved for developers  and  experienced
 * professionals having in-depth computer knowledge. Users are therefore
 * encouraged to load and test the software's suitability as regards their
 * requirements in conditions enabling the security of their systems and/or
 * data to be ensured and,  more generally, to use and operate it in the
 * same conditions as regards security.
 *
 * The fact that you are presently reading this means that you have had
 * knowledge of the CeCILL license and that you accept its terms.
 */
package fr.insalyon.creatis.gasw;

import fr.insalyon.creatis.gasw.dao.DAOFactory;
import fr.insalyon.creatis.gasw.executor.Executor;
import fr.insalyon.creatis.gasw.executor.ExecutorFactory;
import fr.insalyon.creatis.gasw.monitor.MonitorFactory;
import fr.insalyon.creatis.gasw.output.OutputUtilFactory;
import fr.insalyon.creatis.gasw.release.EnvVariable;
import grool.access.GridUserCredentials;
import grool.proxy.Proxy;
import grool.proxy.ProxyConfiguration;
import grool.proxy.myproxy.CLIGlobusMyproxy;
import grool.proxy.myproxy.GlobusMyproxy;
import grool.server.MyproxyServer;
import grool.server.VOMSServer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;

/**
 *
 * @author Rafael Silva
 */
public class Gasw {
    
    private static final Logger log = Logger.getLogger(Gasw.class);
    private static Gasw instance;
    private GaswNotification notification;
    private Object client;
    private volatile Map<String, Proxy> finishedJobs;
    private volatile boolean gettingOutputs;
    private String version;
    private String target;

    /**
     * Gets an instance of GASW
     * 
     * @return Instance of GASW
     */
    public synchronized static Gasw getInstance() throws GaswException {
        if (instance == null) {
            instance = new Gasw();
        }
        return instance;
    }

    /**
     * Gets an instance of GASW
     * 
     * @return Instance of GASW
     */
    public synchronized static Gasw getInstance(String version, String target) throws GaswException {
        if (instance == null) {
            instance = new Gasw(version, target);
        }
        return instance;
    }

    private Gasw() throws GaswException {
        this(Constants.VERSION_GRID, Constants.GRID_DIRAC);
    }

    private Gasw(String version, String target) throws GaswException {
        PropertyConfigurator.configure(
                Gasw.class.getClassLoader().getResource("gaswLog4j.properties"));
        Configuration.setUp();
        ProxyConfiguration.initConfiguration();
        this.version = version;
        this.target = target;
        finishedJobs = new HashMap<String, Proxy>();
        notification = new GaswNotification();
        notification.start();
        gettingOutputs = false;
    }

    /**
     *
     * @param client
     * @param gaswInput
     * @return
     */
    public synchronized String submit(Object client, GaswInput gaswInput) throws GaswException {
        return submit(client, gaswInput, null, null, null);
    }

    /**
     * 
     * @param client
     * @param gaswInput
     * @param proxy user's proxy
     * @return
     */
    public synchronized String submit(Object client, GaswInput gaswInput, GridUserCredentials credentials,
                                      MyproxyServer myproxyServer, VOMSServer vomsServer) throws GaswException {

        if (this.client == null) {
            this.client = client;
        }
        // if the jigsaw descriptor contains a target infrastruture, this overides the default target
        String ltarget = this.target;
        for (EnvVariable v : gaswInput.getRelease().getConfigurations()) {
            if (v.getCategory() == EnvVariable.Category.SYSTEM
                    && v.getName().equals("gridTarget")) {
                ltarget = v.getValue();
            }
        }
        Executor executor = ExecutorFactory.getExecutor(version, ltarget, gaswInput);
        executor.preProcess();

        if (credentials != null) {
            Proxy userProxy = null;
            if (credentials.getLogin() != null && credentials.getPassword() != null
                    && !credentials.getLogin().isEmpty() && !credentials.getPassword().isEmpty()) {
                // getting proxy and appending voms extension by login/password using globus/glite API
                userProxy = new GlobusMyproxy(credentials, myproxyServer, vomsServer);
            } else {
                // getting proxy and appending voms extension by user DN using command line
                userProxy = new CLIGlobusMyproxy(credentials, myproxyServer, vomsServer);
            }
            executor.setUserProxy(userProxy);
        }
        return executor.submit();
    }

    /**
     * 
     * @param finishedJobs
     */
    public synchronized void addFinishedJob(Map<String, Proxy> finishedJobs) {
        this.finishedJobs.putAll(finishedJobs);
    }

    /**
     * Gets the list of output objects of all finished jobs
     * 
     * @return List of output objects of finished jobs.
     */
    public synchronized List<GaswOutput> getFinishedJobs() {

        gettingOutputs = true;
        List<GaswOutput> outputsList = new ArrayList<GaswOutput>();
        List<String> jobsToRemove = new ArrayList<String>();

        if (finishedJobs != null) {
            for (String jobID : finishedJobs.keySet()) {
                String vversion = jobID.contains("Local-") ? "LOCAL" : "GRID";
                int startTime = MonitorFactory.getMonitor(vversion).getStartTime();
                outputsList.add(OutputUtilFactory.getOutputUtil(
                        vversion, startTime).getOutputs(jobID.split("--")[0], finishedJobs.get(jobID)));

                jobsToRemove.add(jobID);
            }
            for (String jobID : jobsToRemove) {
                finishedJobs.remove(jobID);
            }
        }

        return outputsList;
    }

    public synchronized void waitForNotification() {
        gettingOutputs = false;
    }

    public synchronized void terminate() {
        MonitorFactory.terminate();
        notification.terminate();
        DAOFactory.getDAOFactory().close();
    }

    private class GaswNotification extends Thread {

        private boolean stop = false;

        @Override
        public void run() {
            while (!stop) {
                if (!gettingOutputs) {
                    if (finishedJobs != null && finishedJobs.size() > 0) {
                        synchronized (client) {
                            client.notify();
                        }
                    }
                }
                try {
                    sleep(10000);
                } catch (InterruptedException ex) {
                    log.error(ex);
                    if (log.isDebugEnabled()) {
                        for (StackTraceElement stack : ex.getStackTrace()) {
                            log.debug(stack);
                        }
                    }
                }
            }
        }

        public void terminate() {
            stop = true;
        }
    }
}
