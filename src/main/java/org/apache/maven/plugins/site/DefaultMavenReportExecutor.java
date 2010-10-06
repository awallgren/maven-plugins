package org.apache.maven.plugins.site;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.maven.artifact.repository.DefaultRepositoryRequest;
import org.apache.maven.artifact.repository.RepositoryRequest;
import org.apache.maven.artifact.resolver.filter.ExclusionSetFilter;
import org.apache.maven.execution.MavenSession;
import org.apache.maven.lifecycle.LifecycleExecutor;
import org.apache.maven.model.Model;
import org.apache.maven.model.Plugin;
import org.apache.maven.plugin.MavenPluginManager;
import org.apache.maven.plugin.Mojo;
import org.apache.maven.plugin.MojoExecution;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoNotFoundException;
import org.apache.maven.plugin.PluginConfigurationException;
import org.apache.maven.plugin.PluginContainerException;
import org.apache.maven.plugin.descriptor.MojoDescriptor;
import org.apache.maven.plugin.descriptor.PluginDescriptor;
import org.apache.maven.plugin.version.DefaultPluginVersionRequest;
import org.apache.maven.plugin.version.PluginVersionRequest;
import org.apache.maven.plugin.version.PluginVersionResolutionException;
import org.apache.maven.plugin.version.PluginVersionResolver;
import org.apache.maven.plugin.version.PluginVersionResult;
import org.apache.maven.project.MavenProject;
import org.apache.maven.reporting.MavenReport;
import org.codehaus.classworlds.ClassRealm;
import org.codehaus.plexus.component.annotations.Component;
import org.codehaus.plexus.component.annotations.Requirement;
import org.codehaus.plexus.configuration.PlexusConfiguration;
import org.codehaus.plexus.logging.Logger;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.codehaus.plexus.util.xml.Xpp3DomUtils;
import org.mortbay.log.Log;
import org.sonatype.aether.repository.RemoteRepository;
import org.sonatype.aether.util.filter.ExclusionsDependencyFilter;

/**
 * <p>
 *   This component will build some {@link MavenReportExecution} from {@link MavenReportExecutorRequest}.
 *   If a {@link MavenReport} need to fork a lifecycle, this fork is executed here. 
 *   It will ask the core to get some informations in order to correctly setup {@link MavenReport}.
 * </p>
 * <p>
 *   <b>Note</b> if no version is defined in the report plugin the version will be search 
 *   with method {@link #getPluginVersion(ReportPlugin, RepositoryRequest, MavenReportExecutorRequest)}
 *   Steps to find a plugin version stop after each step if a non <code>null</code> has been found
 *   <ul>
 *     <li>use the one defined in the reportPlugin configuration</li>
 *     <li>search similar (same groupId and artifactId) mojo in the build/plugins section of the pom</li>
 *     <li>search similar (same groupId and artifactId) mojo in the build/pluginManagement section of the pom</li>
 *     <li>ask {@link PluginVersionResolver} to get a version and display a warning as it's not a recommended use</li>  
 *   </ul>
 * </p>
 * <p>
 *   Following steps are done
 *   <ul>
 *     <li>get {@link PluginDescriptor} from the {@link MavenPluginManager#getPluginDescriptor(Plugin, RepositoryRequest)}</li>
 *     <li>setup a {@link ClassLoader} with the Mojo Site plugin {@link ClassLoader} as parent for the report execution. 
 *       You must note some classes are imported from the current Site Mojo {@link ClassRealm} see {@link #IMPORTS}.
 *       The artifact resolution excludes the following artifacts (with using an {@link ExclusionSetFilter} : 
 *       doxia-site-renderer, doxia-sink-api.
 *       done using {@link MavenPluginManager#setupPluginRealm(PluginDescriptor, org.apache.maven.execution.MavenSession, ClassLoader, List, org.apache.maven.artifact.resolver.filter.ArtifactFilter)}
 *     </li>
 *     <li>
 *       setup the mojo using {@link MavenPluginManager#getConfiguredMojo(Class, org.apache.maven.execution.MavenSession, MojoExecution)}
 *     </li>
 *     <li>
 *       verify with {@link LifecycleExecutor#calculateForkedExecutions(MojoExecution, org.apache.maven.execution.MavenSession)}
 *       if any forked execution is needed : if yes executes the forked execution here
 *     </li>
 *   </ul>
 * </p>
 * @author Olivier Lamy
 * @since 3.0-beta-1
 */
@Component( role = MavenReportExecutor.class )
public class DefaultMavenReportExecutor
    implements MavenReportExecutor
{
    @Requirement
    private Logger logger;

    @Requirement
    protected MavenPluginManager mavenPluginManager;

    @Requirement
    protected LifecycleExecutor lifecycleExecutor;

    @Requirement
    protected PluginVersionResolver pluginVersionResolver;
    
    private static final List<String> IMPORTS = Arrays.asList( "org.apache.maven.reporting.MavenReport",
                                                               "org.apache.maven.reporting.MavenMultiPageReport",
                                                               "org.apache.maven.doxia.siterenderer.Renderer",
                                                               "org.apache.maven.doxia.sink.SinkFactory",
                                                               "org.codehaus.doxia.sink.Sink",
                                                               "org.apache.maven.doxia.sink.Sink",
                                                               "org.apache.maven.doxia.sink.SinkEventAttributes" );

    private static final Set<String> EXCLUDES = new HashSet<String>( Arrays.asList( "doxia-site-renderer",
                                                                                    "doxia-sink-api", "maven-reporting-api" ) );

    public List<MavenReportExecution> buildMavenReports( MavenReportExecutorRequest mavenReportExecutorRequest )
        throws MojoExecutionException
    {
        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "DefaultMavenReportExecutor.buildMavenReports()" );
        }
        ExclusionsDependencyFilter exclusionSetFilter = new ExclusionsDependencyFilter( EXCLUDES );

        RepositoryRequest repositoryRequest = new DefaultRepositoryRequest();
        repositoryRequest.setLocalRepository( mavenReportExecutorRequest.getLocalRepository() );
        repositoryRequest.setRemoteRepositories( mavenReportExecutorRequest.getProject().getPluginArtifactRepositories() );

        MavenSession session = mavenReportExecutorRequest.getMavenSession();
        
        try
        {
            List<MavenReportExecution> reports = new ArrayList<MavenReportExecution>();

            for ( ReportPlugin reportPlugin : mavenReportExecutorRequest.getReportPlugins() )
            {
                Plugin plugin = new Plugin();
                plugin.setGroupId( reportPlugin.getGroupId() );
                plugin.setArtifactId( reportPlugin.getArtifactId() );
                plugin.setVersion( getPluginVersion( reportPlugin, repositoryRequest, mavenReportExecutorRequest ) );
                mergePluginToReportPlugin( mavenReportExecutorRequest, plugin, reportPlugin );
               
                if ( logger.isInfoEnabled() )
                {
                    logger.info( "configuring report plugin " + plugin.getId() );
                }

                Set<String> goals = new HashSet<String>();

                List<RemoteRepository> remoteRepositories = session.getCurrentProject().getRemotePluginRepositories();
               
                PluginDescriptor pluginDescriptor = mavenPluginManager
                    .getPluginDescriptor( plugin, remoteRepositories, session.getRepositorySession() );
                
                if ( reportPlugin.getReportSets().isEmpty() && reportPlugin.getReports().isEmpty() )
                {
                    List<MojoDescriptor> mojoDescriptors = pluginDescriptor.getMojos();
                    for ( MojoDescriptor mojoDescriptor : mojoDescriptors )
                    {
                        goals.add( mojoDescriptor.getGoal() );
                    }
                }
                else
                {
                    if ( reportPlugin.getReportSets() != null )
                    {
                        for ( ReportSet reportSet : reportPlugin.getReportSets() )
                        {
                            goals.addAll( reportSet.getReports() );
                        }
                    }
                    if ( !reportPlugin.getReports().isEmpty() )
                    {
                        goals.addAll( reportPlugin.getReports() );
                    }
                }

                for ( String goal : goals )
                {
                    MojoDescriptor mojoDescriptor = pluginDescriptor.getMojo( goal );
                    if ( mojoDescriptor == null )
                    {
                        throw new MojoNotFoundException( goal, pluginDescriptor );
                    }

                    MojoExecution mojoExecution = new MojoExecution( plugin, goal, "report:" + goal );

                    mojoExecution.setConfiguration( convert( mojoDescriptor ) );
                    
                    if ( reportPlugin.getConfiguration() != null )
                    {
                        Xpp3Dom reportConfiguration = convert( reportPlugin.getConfiguration() );

                        Xpp3Dom mergedConfiguration =
                            Xpp3DomUtils.mergeXpp3Dom( reportConfiguration, convert( mojoDescriptor ) );

                        Xpp3Dom cleanedConfiguration = new Xpp3Dom( "configuration" );
                        if ( mergedConfiguration.getChildren() != null )
                        {
                            for ( Xpp3Dom parameter : mergedConfiguration.getChildren() )
                            {
                                if ( mojoDescriptor.getParameterMap().containsKey( parameter.getName() ) )
                                {
                                    cleanedConfiguration.addChild( parameter );
                                }
                            }
                        }
                        if ( getLog().isDebugEnabled() )
                        {
                            getLog().debug( "mojoExecution mergedConfiguration: " + mergedConfiguration );
                            getLog().debug( "mojoExecution cleanedConfiguration: " + cleanedConfiguration );
                        }

                        mojoExecution.setConfiguration( cleanedConfiguration );
                    }                    

                    mojoExecution.setMojoDescriptor( mojoDescriptor );
                    
                    mavenPluginManager.setupPluginRealm( pluginDescriptor,
                                                         mavenReportExecutorRequest.getMavenSession(),
                                                         Thread.currentThread().getContextClassLoader(), IMPORTS,
                                                         exclusionSetFilter );
                    MavenReport mavenReport =
                        getConfiguredMavenReport( mojoExecution, pluginDescriptor, mavenReportExecutorRequest );

                    if ( mavenReport == null )
                    {
                        continue;
                    }
                    MavenReportExecution mavenReportExecution =
                        new MavenReportExecution( mojoExecution.getPlugin(), mavenReport,
                                                  pluginDescriptor.getClassRealm() );

                    lifecycleExecutor.calculateForkedExecutions( mojoExecution,
                                                                 mavenReportExecutorRequest.getMavenSession() );

                    if ( !mojoExecution.getForkedExecutions().isEmpty() )
                    {
                        lifecycleExecutor.executeForkedExecutions( mojoExecution,
                                                                   mavenReportExecutorRequest.getMavenSession() );
                    }

                    if ( canGenerateReport( mavenReport, mojoExecution ) )
                    {
                        reports.add( mavenReportExecution );
                    }
                }
            }
            return reports;
        }
        catch ( Exception e )
        {
            throw new MojoExecutionException( "failed to get Reports ", e );
        }
    }
    
    private boolean canGenerateReport( MavenReport mavenReport, MojoExecution mojoExecution )
    {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            Thread.currentThread().setContextClassLoader( mojoExecution.getMojoDescriptor().getRealm() );
            
            return mavenReport.canGenerateReport();
        }
        catch ( AbstractMethodError e )
        {
            // the canGenerateReport() has been added just before the 2.0 release and will cause all the reporting
            // plugins with an earlier version to fail (most of the org.codehaus mojo now fails)
            // be nice with them, output a warning and don't let them break anything

            getLog().warn(
                           "Error loading report " + mavenReport.getClass().getName()
                               + " - AbstractMethodError: canGenerateReport()" );
            return true;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( originalClassLoader );
        } 
    }

    private MavenReport getConfiguredMavenReport( MojoExecution mojoExecution, PluginDescriptor pluginDescriptor,
                                                  MavenReportExecutorRequest mavenReportExecutorRequest )
        throws PluginContainerException, PluginConfigurationException
    {
        try
        {
            if ( !isMavenReport( mojoExecution, pluginDescriptor ) )
            {
                return null;
            }

            Mojo mojo = mavenPluginManager.getConfiguredMojo( Mojo.class,
                                                              mavenReportExecutorRequest.getMavenSession(),
                                                              mojoExecution );
            
            return (MavenReport) mojo;
        }
        catch ( ClassCastException e )
        {
            getLog().warn( "skip ClassCastException " + e.getMessage() );
            return null;
        }
        catch ( PluginContainerException e )
        {
            /**
             * ignore old plugin which are using removed PluginRegistry
             * [INFO] Caused by: java.lang.NoClassDefFoundError: org/apache/maven/plugin/registry/PluginRegistry
             */
            if ( e.getCause() != null && e.getCause() instanceof NoClassDefFoundError
                && e.getMessage().contains( "PluginRegistry" ) )
            {
                getLog().warn( "skip NoClassDefFoundError with PluginRegistry " );
                // too noisy, only in debug mode + e.getMessage() );
                if ( getLog().isDebugEnabled() )
                {
                    Log.debug( e.getMessage(), e );
                }
                return null;
            }
            throw e;
        }
    }

    private boolean isMavenReport( MojoExecution mojoExecution, PluginDescriptor pluginDescriptor )
    {
        ClassLoader originalClassLoader = Thread.currentThread().getContextClassLoader();
        Class<?> mojoClass;
        Thread.currentThread().setContextClassLoader( mojoExecution.getMojoDescriptor().getRealm() );
        try
        {
            mojoClass =
                pluginDescriptor.getClassRealm().loadClass( mojoExecution.getMojoDescriptor().getImplementation() );
        }
        catch ( ClassNotFoundException e )
        {
            getLog().warn( "skip ClassNotFoundException mojoExecution.goal : " + mojoExecution.getGoal() + " : "
                               + e.getMessage(), e );
            return false;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( originalClassLoader );
        }       

        try
        {
            Thread.currentThread().setContextClassLoader( mojoExecution.getMojoDescriptor().getRealm() );
            MojoDescriptor mojoDescriptor = pluginDescriptor.getMojo( mojoExecution.getGoal() );

            boolean isMavenReport = MavenReport.class.isAssignableFrom( mojoClass );

            if ( getLog().isDebugEnabled() )
            {
                if ( mojoDescriptor != null && mojoDescriptor.getImplementationClass() != null )
                {
                    getLog().debug( "class " + mojoDescriptor.getImplementationClass().getName() + " isMavenReport: "
                                        + isMavenReport );
                }

                if ( !isMavenReport )
                {
                    getLog().debug( "skip non MavenReport " + mojoExecution.getMojoDescriptor().getId() );
                }
            }

            return isMavenReport;
        }
        catch ( LinkageError e )
        {
            getLog().warn( "skip LinkageError mojoExecution.goal : " + mojoExecution.getGoal() + " : " + e.getMessage(),
                           e );
            return false;
        }
        finally
        {
            Thread.currentThread().setContextClassLoader( originalClassLoader );
        }
    }

    private Xpp3Dom convert( MojoDescriptor mojoDescriptor )
    {
        PlexusConfiguration config = mojoDescriptor.getMojoConfiguration();
        return ( config != null ) ? convert( config ) : new Xpp3Dom( "configuration" );
    }

    private Xpp3Dom convert( PlexusConfiguration config )
    {
        if ( config == null )
        {
            return null;
        }

        Xpp3Dom dom = new Xpp3Dom( config.getName() );
        dom.setValue( config.getValue( null ) );

        for ( String attrib : config.getAttributeNames() )
        {
            dom.setAttribute( attrib, config.getAttribute( attrib, null ) );
        }

        for ( int n = config.getChildCount(), i = 0; i < n; i++ )
        {
            dom.addChild( convert( config.getChild( i ) ) );
        }

        return dom;
    }

    private Logger getLog()
    {
        return logger;
    }

    /**
     * 
     * @param reportPlugin
     * @param repositoryRequest
     * @param mavenReportExecutorRequest
     * @return the plugin version
     * @throws PluginVersionResolutionException
     */
    protected String getPluginVersion( ReportPlugin reportPlugin, RepositoryRequest repositoryRequest,
                                       MavenReportExecutorRequest mavenReportExecutorRequest )
        throws PluginVersionResolutionException
    {
        String reportPluginKey = reportPlugin.getGroupId() + ':' + reportPlugin.getArtifactId();

        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "resolving version for " + reportPluginKey );
        }
        if ( reportPlugin.getVersion() != null )
        {
            if ( getLog().isDebugEnabled() )
            {
                logger.debug( "resolved " + reportPluginKey + " version from the reporting.plugins section: "
                    + reportPlugin.getVersion() );
            }
            return reportPlugin.getVersion();
        }

        MavenProject project = mavenReportExecutorRequest.getProject();

        // search in the build section
        if ( project.getBuild() != null )
        {
            Plugin plugin = find( reportPlugin, project.getBuild().getPlugins() );

            if ( plugin != null && plugin.getVersion() != null )
            {
                if ( getLog().isDebugEnabled() )
                {
                    logger.debug( "resolved " + reportPluginKey + " version from the build.plugins section: "
                        + plugin.getVersion() );
                }
                return plugin.getVersion();
            }
        }

        // search in pluginManagement section
        if ( project.getBuild() != null && project.getBuild().getPluginManagement() != null )
        {
            Plugin plugin = find( reportPlugin, project.getBuild().getPluginManagement().getPlugins() );

            if ( plugin != null && plugin.getVersion() != null )
            {
                if ( getLog().isDebugEnabled() )
                {
                    logger.debug( "resolved " + reportPluginKey
                        + " version from the build.pluginManagement.plugins section: " + plugin.getVersion() );
                }
                return plugin.getVersion();
            }
        }


        logger.warn( "Report plugin " + reportPluginKey + " has an empty version." );
        logger.warn( "" );
        logger.warn( "It is highly recommended to fix these problems"
            + " because they threaten the stability of your build." );
        logger.warn( "" );
        logger.warn( "For this reason, future Maven versions might no"
            + " longer support building such malformed projects." );

        Plugin plugin = new Plugin();
        plugin.setGroupId( reportPlugin.getGroupId() );
        plugin.setArtifactId( reportPlugin.getArtifactId() );
        
        PluginVersionRequest pluginVersionRequest = new DefaultPluginVersionRequest( plugin , mavenReportExecutorRequest.getMavenSession() );
        //pluginVersionRequest.setOffline( mavenReportExecutorRequest.getMavenSession().getRequest().isOffline() );

        //pluginVersionRequest.setForceUpdate( mavenReportExecutorRequest.getMavenSession().getRequest().isUpdateSnapshots() );

        pluginVersionRequest.setGroupId( reportPlugin.getGroupId() );
        pluginVersionRequest.setArtifactId( reportPlugin.getArtifactId() );
        PluginVersionResult result = pluginVersionResolver.resolve( pluginVersionRequest );
        if ( getLog().isDebugEnabled() )
        {
            getLog().debug( "resolved " + reportPluginKey + " version from repository: " + result.getVersion() );
        }
        return result.getVersion();
    }

    private Plugin find( ReportPlugin reportPlugin, List<Plugin> plugins )
    {
        if ( plugins == null )
        {
            return null;
        }
        for ( Plugin plugin : plugins )
        {
            if ( StringUtils.equals( plugin.getArtifactId(), reportPlugin.getArtifactId() )
                && StringUtils.equals( plugin.getGroupId(), reportPlugin.getGroupId() ) )
            {
                return plugin;
            }
        }
        return null;
    }
    
    /**
     * TODO other stuff to merge ?
     * <p>
     * this method will "merge" some part of the plugin declaration existing in the build section 
     * to the fake plugin build for report execution :
     * <ul>
     *   <li>dependencies</li>
     * </ul>
     * </p>
     * @param mavenReportExecutorRequest
     * @param buildPlugin
     * @param reportPlugin
     */
    private void mergePluginToReportPlugin( MavenReportExecutorRequest mavenReportExecutorRequest, Plugin buildPlugin,
                                            ReportPlugin reportPlugin )
    {
        Plugin configuredPlugin = find( reportPlugin, mavenReportExecutorRequest.getProject().getBuild().getPlugins() );
        if ( configuredPlugin != null )
        {
            if ( !configuredPlugin.getDependencies().isEmpty() )
            {
                buildPlugin.getDependencies().addAll( configuredPlugin.getDependencies() );
            }
        }
    }
}