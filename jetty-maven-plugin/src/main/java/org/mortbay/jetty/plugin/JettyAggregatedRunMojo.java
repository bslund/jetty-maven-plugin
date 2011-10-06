package org.mortbay.jetty.plugin;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.execution.ProjectDependencyGraph;
import org.apache.maven.project.MavenProject;
import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.util.Scanner;

/**
 * Created by bitter on 2011-08-31
 *
 * @aggregator
 * @goal run-all
 * @requiresDependencyResolution runtime
 * @description Runs embedded jetty and deploys war submodules
 */
public class JettyAggregatedRunMojo
    extends AbstractEmbeddedJettyMojo
{
    final WebApplicationScanBuilder scanBuilder = new WebApplicationScanBuilder();
    final WebApplicationConfigBuilder configBuilder = new WebApplicationConfigBuilder();

    final List<Scanner> scanners = new ArrayList<Scanner>();

    /**
     * @parameter expression="${session}"
     * @required
     */
    private MavenSession session;

    @Override
    public void deployWebApplications()
        throws Exception
    {
        scanners.clear();

        for (MavenProject subProject : session.getProjects()) {
            if ("war".equals(subProject.getPackaging())) {
                final JettyWebAppContext webAppConfig = configBuilder.configureWebApplication(subProject, getLog());

                getLog().info("\n=========================================================================="
                            + "\nInjecting : " + subProject.getName() + "\n\n" +  configBuilder.toInfoString(webAppConfig)
                            + "\n==========================================================================");

                getServer().addWebApplication(webAppConfig);

                // TODO:
                //scanList.addAll(getExtraScanTargets());

                List<File> dependencyOutputLocations = new ArrayList<File>();
                Set<Artifact> artifacts = subProject.getArtifacts();

                for (Artifact artifact : artifacts) {
                    MavenProject artifactProject = getLocalDownstreamProjectForDependency(artifact, getProject());

                    if (artifactProject != null) {
                        dependencyOutputLocations.add(new File(artifactProject.getBuild().getOutputDirectory()));
                    }
                }

                List<File> files =
                        scanBuilder.setupScannerFiles(webAppConfig,
                                                      Arrays.asList(subProject.getFile()),
                                                      Collections.<String>emptyList());

                List<File> allFiles = new ArrayList(dependencyOutputLocations);

                allFiles.addAll(files);
                allFiles.addAll(webAppConfig.getWebInfClasses());

                webAppConfig.setClassPathFiles(allFiles);

                getLog().info("Scanning: " + allFiles);

                Scanner scanner = new Scanner();
                scanner.addListener(new Scanner.BulkListener() {
                    public void filesChanged(List changes)
                    {
                        try {
                            getLog().info("Detected changes: " + changes);

                            webAppConfig.stop();
                            webAppConfig.start();
                        } catch (Exception e) {
                            getLog().error("Error reconfiguring/restarting webapp after change in watched files", e);
                        }
                    }
                });

                scanner.setReportExistingFilesOnStartup(false);
                scanner.setScanInterval(2);
                scanner.setScanDirs(allFiles);
                scanner.setRecursive(true);

                scanner.start();
                scanners.add(scanner);
            }
        }
    }

    private MavenProject getLocalDownstreamProjectForDependency(final Artifact artifact,
                                                                final MavenProject topProject)
    {
        ProjectDependencyGraph projectDependencyGraph = session.getProjectDependencyGraph();
        List<MavenProject> downstreamProjects = projectDependencyGraph.getDownstreamProjects(topProject, true);

        for (MavenProject mavenProject : downstreamProjects) {
            if (mavenProject.getPackaging().equals("jar")) {
                if (artifact.getArtifactId().equals(mavenProject.getArtifactId()) &&
                    artifact.getGroupId().equals(mavenProject.getGroupId()) &&
                    artifact.getVersion().equals(mavenProject.getVersion())) {

                    return mavenProject;
                }
            }
        }

        return null;
    }

    @Override
    public void restartWebApplications(boolean reconfigureScanner)
        throws Exception
    {
        Handler context = getServer().getHandler();

        context.stop();
        context.start();
    }
}
