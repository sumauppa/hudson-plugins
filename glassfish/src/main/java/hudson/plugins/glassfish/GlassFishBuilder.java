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

import hudson.model.Computer;
import hudson.Launcher;
import hudson.Extension;
import hudson.util.FormValidation;
import hudson.model.AbstractBuild;
import hudson.model.BuildListener;
import hudson.model.AbstractProject;
import hudson.tasks.Builder;
import hudson.tasks.BuildStepDescriptor;

import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;

import javax.servlet.ServletException;
import java.io.IOException;
import java.io.PrintStream;
import java.io.File;
import java.net.URL;

/**
 * Adds a Build Step to Setup GlassFish Cluster.
 *
 * @author Harshad Vilekar
 *
 */
public class GlassFishBuilder extends Builder {

    private final String zipBundleURL, clusterName, clusterSize, instanceNamePrefix;
    private final boolean installGlassFish, createCluster, startCluster;

    // Fields in config.jelly match the parameter names in the "DataBoundConstructor"
    @DataBoundConstructor
    public GlassFishBuilder(
            boolean installGlassFish,
            String zipBundleURL,
            boolean createCluster,
            String clusterSize,
            String clusterName,
            String instanceNamePrefix,
            boolean startCluster) {
        this.installGlassFish = installGlassFish;
        this.zipBundleURL = zipBundleURL.trim();
        this.createCluster = createCluster;
        this.clusterSize = clusterSize.trim();
        this.clusterName = clusterName.trim();
        this.instanceNamePrefix = instanceNamePrefix.trim();
        this.startCluster = startCluster;
    }

    
    public boolean getInstallGlassFish() {
        return installGlassFish;
    }

    public String getZipBundleURL() {
        return zipBundleURL;
    }

    public boolean getCreateCluster() {
        return createCluster;
    }

    public String getClusterSize() {
        return clusterSize;
    }

    public String getClusterName() {
        return clusterName;
    }

    public String getInstanceNamePrefix() {
        return instanceNamePrefix;
    }

    public boolean getStartCluster() {
        return startCluster;
    }

    public int numInstances() {
        try {
            return Integer.parseInt(clusterSize);
        } catch (NumberFormatException e) {
            e.printStackTrace();
            return -1;
        }
    }

    /**
     * This is where we 'build' the project.
     * 1. Install GlassFish (optional)
     * 2. Run the Command(s) that create GlassFish Cluster
     */
    @Override
    public boolean perform(AbstractBuild build, Launcher launcher, BuildListener listener) {

        PrintStream logger = listener.getLogger();
        String nodeName = Computer.currentComputer().getNode().getNodeName();

        logger.println("Build launched on: " + nodeName);

        String msg = "installGlassFish = " + getInstallGlassFish()
                + ", startCluster = " + getStartCluster() + "\n";
        msg = msg + "GlassFish Cluster '" + clusterName + "' will be created with " + clusterSize
                + " instances: ";

        if (numInstances() < 0) {
            logger.println("ERROR: Invalid Cluster Size:" + clusterSize + "Build Aborted!");
            return false;
        }

        for (int i = 1; i <= numInstances(); i++) {
            msg = msg + getInstanceNamePrefix() + i + " ";
        }

        logger.println(msg);

        if (installGlassFish) {
            GlassFishInstaller gfi = new GlassFishInstaller(build, logger);
            if (!gfi.installGlassFishFromZipBundle(zipBundleURL)) {
                logger.println("ERROR: GlassFish Install Step Failed. Aborting!");
                return false;
            }
        }

        if (createCluster) {
            GlassFishCluster gfc = new GlassFishCluster(build, launcher, logger, this);
            if (!gfc.createGFCluster()) {
                logger.println("ERROR: GlassFish Cluster Creation Failed. Aborting!");
                return false;
            }
        }

        return true;
    }

    /**
     * Descriptor for {@link GlassFishBuilder}. Used as a singleton.
     */
    @Extension
    public static final class DescriptorImpl extends BuildStepDescriptor<Builder> {

        /**
         * Performs on-the-fly validation of the form fields.
         *
         * @param value
         *      This parameter receives the value that the user has typed.
         * @return
         *      Indicates the outcome of the validation. This is sent to the browser.
         */
        public FormValidation doCheckZipBundleURL(@QueryParameter String value)
                throws IOException, ServletException {

            value = value.trim();
            if (value.length() == 0) {
                return FormValidation.warning("INFO: No URL set");
            }
            String fileName = new File(new URL(value).getPath()).getName();

            // The file name is expected to end with ".zip"
            // Just check the file name lenght. We are not checking for file type here.
            if ((fileName == null) || (fileName.length() <= 4)) {
                return FormValidation.warning("WARNING: The URL looks incorrect!");
            }
            // todo: add additional checks for Cluster Name validation here
            return FormValidation.ok();
        }

        public FormValidation doCheckClusterName(@QueryParameter String value)
                throws IOException, ServletException {
            value = value.trim();
            if (value.length() == 0) {
                return FormValidation.error("Please set the Cluster Name");
            }
            if (value.length() > 99) {
                return FormValidation.warning("Warning: Cluster Name is very long.");
            }
            // todo: add additional checks for Cluster Name validation here
            return FormValidation.ok();
        }

        public FormValidation doCheckInstanceNamePrefix(@QueryParameter String value)
                throws IOException, ServletException {
            value = value.trim();
            if (value.length() == 0) {
                return FormValidation.error("Please set the Instance Name Prefix");
            }
            if (value.length() > 99) {
                return FormValidation.warning("Warning: Instance Name Prefix is very long.");
            }
            // todo: add additional checks for Instance Name Prefix validation here
            return FormValidation.ok();
        }

        public FormValidation doCheckClusterSize(@QueryParameter String value)
                throws IOException, ServletException {
            if (value.length() == 0) {
                return FormValidation.error("Please set the Cluster Size");
            }

            int size = -1;
            try {
                size = Integer.parseInt(value.trim());
            } catch (NumberFormatException e) {
            }

            if (size > 99) {
                return FormValidation.warning("Are you sure ?");
            }

            if (size < 1) {
                return FormValidation.error("Invalid Cluster Size!");
            }

            // todo: add additional checks for cluster name validation here
            return FormValidation.ok();
        }

        public boolean isApplicable(Class<? extends AbstractProject> aClass) {
            // indicates that this builder can be used with all kinds of project types 
            return true;
        }

        /**
         * This human readable name is used in the configuration screen.
         */
        public String getDisplayName() {
            return "GlassFish Cluster";
        }
    }
}
