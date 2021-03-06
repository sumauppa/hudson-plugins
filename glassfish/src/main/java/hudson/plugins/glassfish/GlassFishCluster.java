/*
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS HEADER.
 *
 * Copyright 1997-2010 Sun Microsystems, Inc. All rights reserved.
 *
 * The contents of this file are subject to the terms of either the GNU
 * General Public License Version 2 only ("GPL") or the Common Development
 * and Distribution License("CDDL") (collectively, the "License").  You
 * may not use this file except in compliance with the License. You can obtain
 * a copy of the License at https://glassfish.dev.java.net/public/CDDL+GPL.html
 * or glassfish/bootstrap/legal/LICENSE.txt.  See the License for the specific
 * language governing permissions and limitations under the License.
 *
 * When distributing the software, include this License Header Notice in each
 * file and include the License file at glassfish/bootstrap/legal/LICENSE.txt.
 * Sun designates this particular file as subject to the "Classpath" exception
 * as provided by Sun in the GPL Version 2 section of the License file that
 * accompanied this code.  If applicable, add the following below the License
 * Header, with the fields enclosed by brackets [] replaced by your own
 * identifying information: "Portions Copyrighted [year]
 * [name of copyright owner]"
 *
 * Contributor(s):
 *
 * If you wish your version of this file to be governed by only the CDDL or
 * only the GPL Version 2, indicate your decision by adding "[Contributor]
 * elects to include this software in this distribution under the [CDDL or GPL
 * Version 2] license."  If you don't indicate a single choice of license, a
 * recipient has the option to distribute your version of this file under
 * either the CDDL, the GPL Version 2 or to extend the choice of license to
 * its licensees as provided above.  However, if you add GPL Version 2 code
 * and therefore, elected the GPL Version 2 license, then the option applies
 * only if the new code is made subject to such option by the copyright
 * holder.
 */
package hudson.plugins.glassfish;

import java.util.HashMap;
import java.util.Map.Entry;
import java.util.Properties;
import java.io.StringReader;
import hudson.model.BuildListener;
import hudson.FilePath;
import hudson.model.Computer;
import hudson.model.Hudson;
import hudson.model.Label;
import hudson.model.Node;
import hudson.Launcher;
import hudson.model.AbstractBuild;
import java.util.Map;
import java.io.IOException;
import java.io.PrintStream;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Properties;
import hudson.remoting.VirtualChannel;

/**
 * Represents GlassFish Application Server Cluster and it's Instances.
 * @author Harshad Vilekar
 */
@SuppressWarnings("deprecation")
public class GlassFishCluster {

    private AbstractBuild build;
    private Launcher launcher;
    private PrintStream logger;
    BuildListener listener;
    private GlassFishBuilder gfbuilder;
    private int basePort;
    // Number of nodes (hosts) that run the cluster instances.
    int numNodes;
    static final int dasAdminPort = 4848, dasHttpPort = 8080, dasNodetNum = 1;
    String dasNodeName;
    ArrayList<GlassFishClusterNode> clusterNodes = new ArrayList();
    String clusterName;
    Map<String, GlassFishInstance> clusterMap = new HashMap<String, GlassFishInstance>();
    ArrayList<Node> selectedSlaves;
    String nodeSelectionLabel;

    public GlassFishCluster(AbstractBuild build,
            Launcher launcher,
            PrintStream logger,
            BuildListener listener,
            GlassFishBuilder gfbuilder,
            String numNodes,
            int basePort,
            String clusterName,
            String nodeSelectionLabel) {
        this.build = build;
        this.launcher = launcher;
        this.listener = listener;
        this.logger = logger;
        this.gfbuilder = gfbuilder;
        this.numNodes = Integer.parseInt(numNodes);
        this.basePort = basePort;
        this.clusterName = clusterName;
        this.nodeSelectionLabel = nodeSelectionLabel;
        this.dasNodeName = Computer.currentComputer().getNode().getNodeName();
        // first node is the current computer
        clusterNodes.add(new GlassFishClusterNode(Computer.currentComputer().getNode(), build, logger, listener));

    }

    public int getDasAdminPort() {
        return dasAdminPort;
    }

    public String getDasNodeName() {
        return dasNodeName;
    }

    boolean verifyDasPortAvailability() {

        if (GlassFishClusterNode.getAvailablePort(Computer.currentComputer().getNode(), dasAdminPort, "DAS_ADMIN_PORT") != dasAdminPort) {
            logger.println("INFO: DAS_ADMIN_PORT " + dasAdminPort + " is not available!");

            return false;
        }

        if (GlassFishClusterNode.getAvailablePort(Computer.currentComputer().getNode(), dasHttpPort, "DAS_HTTP_PORT") != dasHttpPort) {
            logger.println("INFO: DAS_HTTP_PORT " + dasHttpPort + " is not available!");
            return false;
        }

        return true;
    }

    public GlassFishClusterNode getDasClusterNode() {

        if (clusterNodes.isEmpty()) {
            return null;
        }
        return clusterNodes.get(0);

    }

    GlassFishClusterNode getClusterNode(int hostNum) {
        if (clusterNodes.isEmpty() || (hostNum > numNodes) || (hostNum <= 0)) {
            logger.println("ERROR: Invalid Host Number:" + hostNum);
            return null;
        } else {
            return clusterNodes.get(hostNum - 1);
        }
    }

    // auto assign default values
    public void createAutoAssignedClusterMap(String instanceNamePrefix, int numInstances) {
        int base_port = this.basePort;
        for (int i = 1; i <= numInstances; i++) {
            String instanceName = instanceNamePrefix + i;
            GlassFishInstance gfi = new GlassFishInstance(this, logger, instanceName, base_port);
            clusterMap.put(instanceName, gfi);
            base_port = base_port + 0x100;
        }
    }

    public void listInstances() {
        for (GlassFishInstance in : clusterMap.values()) {
            logger.println(in.toStr(false));
        }
    }

    // get values from Custom Instance Textbox,
    // form key value pairs of instance name and base port
    // and add those instances to the cluster map.
    public boolean updateClusterMapPerUserPrefs(boolean verbose) {

        boolean retVal = true;
        // load the instance name and value pairs from customInstanceText field
        Properties p = new Properties();

        try {
            p.load(new StringReader(gfbuilder.getCustomInstanceText()));
        } catch (IOException e) {
            logger.println("ERROR Loading customInstanceText: "
                    + gfbuilder.getCustomInstanceText());
            e.printStackTrace(logger);
            return false;
        }

        for (Entry<Object, Object> entry : p.entrySet()) {
            String instance_name;
            int base_port;
            try {
                instance_name = entry.getKey().toString();
                base_port = new Integer(entry.getValue().toString()).intValue();
                GlassFishInstance gfi = new GlassFishInstance(this, logger, instance_name, base_port);
                if (verbose) {
                    if (clusterMap.containsKey(instance_name)) {
                        logger.println("Updated: " + instance_name + ":" + base_port);
                    } else {
                        logger.println("Added: " + instance_name + ":" + base_port);
                    }
                }
                clusterMap.put(instance_name, gfi);

            } catch (NumberFormatException nfe) {
                logger.println("ERROR: Invalid Entry: "
                        + entry.getKey().toString() + " " + entry.getKey().toString());
                retVal = false;
            }
        }
        return retVal;
    }

    // host1 is reserved for DAS
    // instance1..instance(numInstances) are deployed on host1..host(numNodes) in
    // round robbin
    public boolean assignClusterNodesToInstances() {
        int node_num = 0;

        // create a list of Nodes
        ArrayList<Node> slaveNodes = selectSlaveNodesForInstanceDeployment(numNodes, nodeSelectionLabel);
        // we can't continue if there are not enough nodes available for instance deployment
        if (slaveNodes.size() + 1 < numNodes) {
            logger.println("ERROR: Not enough nodes available for GlassFish Instance deployment. (Required:"
                    + numNodes + ", Available:" + (slaveNodes.size() + 1) + ", Label:" + nodeSelectionLabel + ")");
            return false;
        }

        for (Node node : slaveNodes) {
            clusterNodes.add(new GlassFishClusterNode(node, build, logger, listener));
        }

        for (GlassFishInstance in : clusterMap.values()) {
            in.clusterNode = getClusterNode(++node_num);
            in.nodeName = in.clusterNode.getNode().getNodeName();
            in.s1as_home = in.clusterNode.getInstaller().GFHOME_DIR;

            node_num = node_num % numNodes;
        }

        return true;
    }

    // install GlassFish on DAS node only
    boolean installGlassFishOnDasNode(String zipBundleURL) {
        if (!installGlassFish(getDasClusterNode(), zipBundleURL)) {
            return false;
        }

        return true;
    }

    // copy over the files required for user Tasks
    boolean copyUserTaskFiles(String userTaskFilesURL) {
        // separate out space separate URL string, and copy over each file separately
        String[] urls = userTaskFilesURL.split(" ");
        for (int i = 0; i < urls.length; i++) {
            String url = urls[i];
            if (url.length() == 0) {
                continue;
            }
            if (url.toLowerCase().endsWith(".zip")) {
                //copy and unzip the file
                if (!getDasClusterNode().getInstaller().remoteUnzip(true, url)) {
                    return false;
                }
            } else {
                // simply copy the file
                if (!getDasClusterNode().getInstaller().remoteCopyFile(true, url)) {
                    return false;
                }
            }
        }

        return true;
    }

    // install GlassFish on all (subslave) nodes - except DAS node
    boolean installGlassFishOnNonDasNodes(String zipBundleURL) {
        for (GlassFishClusterNode gfcNode : clusterNodes) {
            if (!gfcNode.getNode().getNodeName().equals(getDasClusterNode().getNode().getNodeName())) {
                if (!installGlassFish(gfcNode, zipBundleURL)) {
                    return false;
                }
            }
        }
        return true;
    }

    boolean installGlassFish(GlassFishClusterNode gfcNode, String zipBundleURL) {

        logger.println(GlassFishPluginUtils.getLogDate() + gfcNode.getNode().getNodeName() + ":Installing GlassFish Bundle " + zipBundleURL);
        if (!gfcNode.getInstaller().installGlassFishFromZipBundle(zipBundleURL)) {
            logger.println(gfcNode.getNode().getNodeName() + ": ERROR: GlassFish Installation Failed. ");
            // if the installer fails on one of the nodes, then we can not continue
            // TODO: Cleanup: remove successful and partially completed glassfish installtion
            //       since we are aborting at this point
            return false;
        }

        return true;
    }

    boolean deleteInstall() {
        boolean returnVal = true;
        if (clusterNodes.isEmpty()) {
            logger.println("deleteInstall: skipped. No GlassFish Installation was deleted!");
        }
        for (GlassFishClusterNode gfcNode : clusterNodes) {
            logger.println(gfcNode.getNode().getNodeName() + ":Deleting GlassFish Installation ");
            if (!gfcNode.getInstaller().deleteInstall()) {
                logger.println(gfcNode.getNode().getNodeName() + ":ERROR: Couldn't Delete GlassFish Installation ");
                returnVal = false;
            }
        }
        return true;
    }

    public void updateClusterMapPerPortAvailability() {
        for (GlassFishInstance in : clusterMap.values()) {
            in.updatePerPortAvailability();
        }
    }

    /**
     * Cluster Map initialization.
     * Create a map of all the instances in the cluster.
     * Override auto assigned ports with user defined - if any.
     */
    boolean initClusterMap(String instanceNamePrefix, int numInstances) {


        createAutoAssignedClusterMap(instanceNamePrefix, numInstances);

        if (!updateClusterMapPerUserPrefs(false)) {
            logger.println("ERROR: Couldn't load customInstanceProperties, Build Aborted!");
            return false;
        }

        // Max number of nodes we need is limited by number of instances (one instance per node)
        // limit numNodes to numInstances, no need to reserve more nodes, even if
        // those are requested by the user.
        if (numNodes > clusterMap.size()) {
            numNodes = clusterMap.size();
        }

        return true;
    }

    /**
     * Assign nodes to instances.
     * Update port values based  upon which ports are actually free on the system
     */
    boolean updateClusterMap() {

        // Instance -> node mapping. Instances will be later installed on the assigned nodes.
        if (!assignClusterNodesToInstances()) {
            return false;
        }

        updateClusterMapPerPortAvailability();

        return true;
    }

    ////// Cluster Properties file initialization. /////////////
    boolean createClusterProperties() {

        if (!updateClusterMap()) {
            return false;
        }

        if (!createClusterPropsFiles()) {
            return false;
        }
        return true;
    }

    /**
     * Get "random" list of all the Hudson slaves which match the specified label.
     * The label is used to group the nodes for a specific task.
     * For example, label "GFCluster" may indicate GlassFish instances may
     * be deployed on this node.
     * @param label
     * @return
     */
    private ArrayList<Node> getAvailableSlaveNodes(String label) {

        List<Node> allSlaves = Hudson.getInstance().getNodes();
        ArrayList<Node> slaves = new ArrayList<Node>();
        boolean verbose = false;

        for (Node n : allSlaves) {
            String computerName = n.getNodeName();
            if (n.toComputer().isOffline()) {
                println(verbose, "Skipped: " + computerName + " (Node is offline)");
                continue;
            }
            if (n.toComputer().getNumExecutors() <= 0) {
                println(verbose, "Skipped: " + computerName + " (No executors)");
                continue;
            }

            Set<Label> labelSet = n.getAssignedLabels();
            boolean labelMatched = false;
            String thislabel = "";
            for (Label l : labelSet) {

                if (l.getName().equalsIgnoreCase(label)) {
                    labelMatched = true;
                    thislabel = l.getName();
                    slaves.add(n);
                    break;
                }
            }
            if (labelMatched) {
                println(verbose, "Node " + computerName + " is available (Label=" + thislabel + ")");
            } else {
                println(verbose, "Node " + computerName + " is ignored (No Label Matched: " + label + ")");

            }

        }
        Collections.shuffle(slaves);
        return slaves;
    }

    void println(boolean verbose, String msg) {
        if (verbose) {
            logger.println(msg);
        }
    }

    /**
     * From the list of available nodes, randomly selects "numNodes" nodes for deploying GlassFish instances.
     * First node is always currentComputer that is running the build. So, in case of single node
     * cluster (num_nodes = 1), other slaves are not used
     *
     */
    ArrayList<Node> selectSlaveNodesForInstanceDeployment(int num_nodes, String label) {
        ArrayList<Node> randomSlaves = getAvailableSlaveNodes(label);

        // The node that is running on this build is the first element of ArrayList.
        // DAS and first instance is started on that node.
        // Rest of the GlassFish Instances are started on the rest of the nodes.
        Node currentNode = Computer.currentComputer().getNode();
        ArrayList<Node> selected_slaves = new ArrayList<Node>(num_nodes);
        int i = 0;
        //selected_slaves.add(i++, currentNode);
        for (Node n : randomSlaves) {
            if (i >= num_nodes) {
                break;
            }
            if (n.equals(currentNode)) {
                // this node is already added to the ArrayList at position 0.
                //println(verbose, "Current Node " + computerName + " is marked for running GlassFish DAS and Instance1");
                continue;
            }
            selected_slaves.add(i++, n);
        }
        return selected_slaves;
    }

    // non standard local contract for some ant scripts
    // this may be removed in future
    public boolean createPropsFileForAntS() {

        String clusterStr =
                "s1as.home=" + clusterNodes.get(0).gfi.GFHOME_DIR + "\n"
                + "cluster.name=" + clusterName + "\n";

        String instanceStr = "";
        for (GlassFishInstance in : clusterMap.values()) {

            if (instanceStr.length() > 0) {
                // add a comma, to seperate from earlier instance
                instanceStr = instanceStr + ",";
            } else {
                // this is beginning of the line
                instanceStr = "instancelist=";
            }

            instanceStr = instanceStr + in.getPropsForAntS();
        }

        return createFile(false, "ant/cluster.properties", clusterStr + instanceStr);
    }

    /** Copy over server logs for DAS and each instance to the given subdirectory
     *  of the current workspace on the build machine.
     * @param dirName
     * @return
     */
    public boolean copyGFServerLogsTo(String dirName) {

        if (clusterMap == null || clusterMap.isEmpty() || getDasClusterNode() == null) {
            logger.println("Skipped: No Server logs to Archive!");
            return true;
        }
        FilePath target = new FilePath(build.getProject().getWorkspace(), dirName);

        try {
            // remove old directory contents
            target.deleteRecursive();
            // first get DAS logs
            target = new FilePath(build.getProject().getWorkspace(), dirName + "/das_" + getDasNodeName());
            FilePath src = getDasClusterNode().getInstaller().domain1LogsDir;

            logger.println("Copying DAS server logs " + src.toString() + " to: " + target.toString());
            target.mkdirs();
            src.copyRecursiveTo(target);

            for (GlassFishInstance in : clusterMap.values()) {
                src = in.getInstanceLogs();
                if (src == null) {
                    logger.println(in.instanceName + " skipped: No server logs found at " + in.getClusterNode().getNodeName());
                } else {
                    target = new FilePath(build.getProject().getWorkspace(), dirName + "/" + in.instanceName + "_" + in.getClusterNode().getNodeName());
                    target.mkdirs();
                    logger.println("Copying Instance (" + in.instanceName + ") server logs "
                            + in.getClusterNode().getNodeName() + ":" + src.toString() + " to: " + target.toString());
                    VirtualChannel channel = in.getClusterNode().getNode().getChannel();
                    FilePath remoteFile = new FilePath(channel, src.toString() + "/server.log");
                    FilePath localFile = new FilePath(target, "server.log");
                    if (remoteFile.exists()) {
                        remoteFile.copyTo(localFile);
                    }
                    remoteFile = new FilePath(channel, src.toString() + "/jvm.log");
                    localFile = new FilePath(target, "jvm.log");
                    if (remoteFile.exists()) {
                        remoteFile.copyTo(localFile);
                    }
                    //src.copyRecursiveTo(target);
                }
            }
        } catch (IOException e) {
            e.printStackTrace(logger);
            logger.println("IOException: Failed to Copy logs to " + target.toString());
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace(logger);
            logger.println("InterruptedException: Failed to Copy logs to " + target.toString());
            return false;
        }
        return true;
    }

    public boolean createClusterPropsFiles() {

        String clusterStr =
                "cluster_name=" + clusterName
                + "\ncluster_numNodes=" + numNodes
                + "\ncluster_numInstances=" + clusterMap.size()
                + "\ndas_node=" + getDasNodeName()
                + "\ndas_port=" + getDasAdminPort()
                + "\n";

        int i = 0;
        for (GlassFishInstance in : clusterMap.values()) {
            clusterStr = clusterStr + in.getProps(++i);
        }

        if (!createFile(true, "cluster.props", clusterStr)) {
            return false;
        }
        logger.println("=================================================");
        logger.println(clusterStr);
        logger.println("=================================================");

        if (!createPropsFileForAntS()) {
            return false;
        }

        return true;
    }

    /**
     * This is used to execute the plugin on the pre-existing installation,
     * when install glassfish option is not selected.
     * @param fileName
     * All the cluster map is rebuild from the specified properties file.
     * The file cluster.props need to be already present in WORKSPACE.
     * @return
     */
    public boolean loadClusterPropertiesFile(String fileName) {
        FilePath projectWorkspace = build.getProject().getWorkspace();
        FilePath propsFile = new FilePath(projectWorkspace, fileName);
        int numInstances = -1;
        boolean success = true;

        Properties props = new Properties();
        try {
            logger.println("Reading properties file: " + propsFile.toString());

            props.load(propsFile.read());

            if ((clusterName = getStrProperty(props, "cluster_name")) == null) {
                success = false;
            }

            if ((numNodes = getIntProperty(props, "cluster_numNodes", 1)) == -1) {
                success = false;
            }

            if ((numInstances = getIntProperty(props, "cluster_numInstances", 1)) == -1) {
                success = false;
            }

            if (!success) {
                return false;
            }

            for (int i = 1; i <= numInstances; i++) {
                String instanceN_name, instanceN_node, instanceN_s1as_home;
                String instanceN = "instance" + i;
                if ((instanceN_name = getStrProperty(props, instanceN + "_name")) == null) {
                    success = false;
                }

                if ((instanceN_node = getStrProperty(props, instanceN + "_node")) == null) {
                    success = false;
                }
                if ((instanceN_s1as_home = getStrProperty(props, instanceN + "_s1as_home")) == null) {
                    success = false;
                }

                int instanceN_HTTP_LISTENER_PORT,
                        instanceN_HTTP_SSL_LISTENER_PORT,
                        instanceN_IIOP_LISTENER_PORT,
                        instanceN_IIOP_SSL_LISTENER_PORT,
                        instanceN_IIOP_SSL_MUTUALAUTH_PORT,
                        instanceN_JMX_SYSTEM_CONNECTOR_PORT,
                        instanceN_JMS_PROVIDER_PORT,
                        instanceN_ASADMIN_LISTENER_PORT,
                        instanceN_GMS_LISTENER_PORT;
                if ((instanceN_HTTP_LISTENER_PORT = getIntProperty(props, instanceN + "_HTTP_LISTENER_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_HTTP_SSL_LISTENER_PORT = getIntProperty(props, instanceN + "_HTTP_SSL_LISTENER_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_IIOP_LISTENER_PORT = getIntProperty(props, instanceN + "_IIOP_LISTENER_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_IIOP_SSL_LISTENER_PORT = getIntProperty(props, instanceN + "_IIOP_SSL_LISTENER_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_IIOP_SSL_MUTUALAUTH_PORT = getIntProperty(props, instanceN + "_IIOP_SSL_MUTUALAUTH_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_JMX_SYSTEM_CONNECTOR_PORT = getIntProperty(props, instanceN + "_JMX_SYSTEM_CONNECTOR_PORT", 1)) == -1) {
                    success = false;
                }

                if ((instanceN_JMS_PROVIDER_PORT = getIntProperty(props, instanceN + "_JMS_PROVIDER_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_ASADMIN_LISTENER_PORT = getIntProperty(props, instanceN + "_ASADMIN_LISTENER_PORT", 1)) == -1) {
                    success = false;
                }
                if ((instanceN_GMS_LISTENER_PORT = getIntProperty(props, instanceN + "_GMS_LISTENER_PORT", 1)) == -1) {
                    success = false;
                }
                if (!success) {
                    return false;
                }
                GlassFishInstance gfi = new GlassFishInstance(
                        this,
                        logger,
                        instanceN_name,
                        instanceN_node,
                        instanceN_s1as_home,
                        instanceN_HTTP_LISTENER_PORT,
                        instanceN_HTTP_SSL_LISTENER_PORT,
                        instanceN_IIOP_LISTENER_PORT,
                        instanceN_IIOP_SSL_LISTENER_PORT,
                        instanceN_IIOP_SSL_MUTUALAUTH_PORT,
                        instanceN_JMX_SYSTEM_CONNECTOR_PORT,
                        instanceN_JMS_PROVIDER_PORT,
                        instanceN_ASADMIN_LISTENER_PORT,
                        instanceN_GMS_LISTENER_PORT);
                clusterMap.put(instanceN_name, gfi);
            }

        } //catch exception in case properties file does not exist
        catch (IOException e) {
            logger.println("ERROR: Couldn't load properties file: "
                    + propsFile.toString());
            e.printStackTrace(logger);
            return false;
        }

        // Rebuild the data structures from the values in the properties file,
        // Populate instances and cluster Nodes.
        // We assume that those nodes are still online and available.
        // The cluster creation will fail if one of the nodes becomes unavailable.

        for (GlassFishInstance in : clusterMap.values()) {
            Node node = Hudson.getInstance().getNode(in.nodeName);
            GlassFishClusterNode clusterNode = new GlassFishClusterNode(node, build, logger, listener);
            in.clusterNode = clusterNode;
            clusterNodes.add(clusterNode);

        }
        return true;
    }

    // returns null if String value for the property is not found
    String getStrProperty(Properties props, String propName) {
        String str = props.getProperty(propName);
        if (str == null) {
            logger.println("ERROR: Couldn't load property: " + propName);
            return null;
        }
        return str.trim();
    }

    // returns -1 if int value for the property is not found
    int getIntProperty(Properties props, String propName, int minValue) {
        String str = props.getProperty(propName);
        int propValue = -1;
        if (str == null) {
            logger.println("ERROR: Couldn't load property: " + propName);
            return -1;
        } else {
            try {
                propValue = Integer.parseInt(str);
                if (propValue < minValue) {
                    logger.println("Invalid value: " + propName + "=" + propValue + " (must be >=" + minValue);
                    return -1;
                }
            } catch (NumberFormatException e) {
                logger.println("Invalid integer value: " + propName + "=" + str);
                e.printStackTrace(logger);
                return -1;
            }
        }
        return propValue;
    }

    public boolean createFile(boolean verbose, String fileName, String fileContents) {

        FilePath projectWorkspace = build.getProject().getWorkspace();
        FilePath propsFile = new FilePath(projectWorkspace, fileName);

        try {
            propsFile.write(fileContents, null);
            if (verbose) {
                logger.println("Created " + propsFile.toURI().toString());
            }

        } catch (IOException e) {
            e.printStackTrace(logger);
            return false;
        } catch (InterruptedException e) {
            e.printStackTrace(logger);
            return false;
        }

        return true;
    }
}
