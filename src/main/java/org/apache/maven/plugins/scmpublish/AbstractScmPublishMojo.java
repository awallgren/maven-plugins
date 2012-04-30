package org.apache.maven.plugins.scmpublish;

/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

import java.io.File;
import java.util.List;
import java.util.Map;

import org.apache.maven.execution.MavenSession;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.project.MavenProject;
import org.apache.maven.scm.manager.NoSuchScmProviderException;
import org.apache.maven.scm.manager.ScmManager;
import org.apache.maven.scm.provider.ScmProvider;
import org.apache.maven.scm.repository.ScmRepository;
import org.apache.maven.scm.repository.ScmRepositoryException;
import org.apache.maven.settings.Settings;
import org.apache.maven.shared.release.ReleaseExecutionException;
import org.apache.maven.shared.release.config.ReleaseDescriptor;
import org.apache.maven.shared.release.scm.ReleaseScmRepositoryException;
import org.apache.maven.shared.release.scm.ScmRepositoryConfigurator;

/**
 * Base class for the site-scm-publish mojos.
 */
public abstract class AbstractScmPublishMojo
    extends AbstractMojo
{

    /**
     * Location of the inventory file.
     * 
     * @parameter expression="${scmpublish.inventoryFile}"
     *            default-value="${project.build.directory}/scmpublish-inventory.js"
     */
    protected File inventoryFile;

    /**
     * Location of the svn publication tree.
     * 
     * @parameter expression="${scmpublish.pubScmUrl}"
     * @required
     */
    protected String pubScmUrl;

    /**
     * Location of the svn publication tree.
     * 
     * @parameter expression="${scmpublish.checkoutDirectory}"
     *            default-value="${project.build.directory}/scmpublish-checkout"
     */
    protected File checkoutDirectory;

    /**
     * Patterns to exclude from the scm tree.
     * 
     * @parameter
     */
    protected String excludes;

    /**
     * Patterns to include in the scm tree.
     * 
     * @parameter
     */
    protected String includes;
    
    /**
     * List of provider implementations.
     *
     * @parameter
     */
    private Map<String, String> providerImplementations;
    
    /**
     * The SCM manager.
     *
     * @component
     */
    private ScmManager scmManager;

    /**
     * Tool that gets a configured SCM repository from release configuration.
     * 
     * @component
     */
    protected ScmRepositoryConfigurator scmRepositoryConfigurator;

    /**
     * The SCM username to use.
     * 
     * @parameter expression="${username}"
     */
    protected String username;

    /**
     * The SCM password to use.
     * 
     * @parameter expression="${password}"
     */
    protected String password;

    /**
     * @parameter expression="${settings}"
     * @required
     * @readonly
     */
    protected Settings settings;

    /**
     * @parameter expression="${project}"
     * @required
     * @readonly
     */
    protected MavenProject project;

    /**
     * @parameter expression="${reactorProjects}"
     * @required
     * @readonly
     */
    protected List<MavenProject> reactorProjects;

    /**
     * Use a local checkout instead of doing a checkout from the upstream repository. ATTENTION: This will only work
     * with distributed SCMs which support the file:// protocol TODO: we should think about having the defaults for the
     * various SCM providers provided via modello!
     * 
     * @parameter expression="${localCheckout}" default-value="false"
     * @since 2.0
     */
    protected boolean localCheckout;

    /**
     * @parameter expression="${basedir}"
     * @required
     * @readonly
     */
    protected File basedir;

    /**
     * @parameter expression="${session}"
     * @readonly
     * @required
     */
    protected MavenSession session;

    /**
     * The outputEncoding parameter of the site plugin. This plugin will corrupt your site
     * if this does not match the value used by the site plugin.
     * 
     * @parameter expression="${outputEncoding}" default-value="${project.reporting.outputEncoding}"
     */
    protected String siteOutputEncoding;

    protected ScmProvider scmProvider;
    protected ScmRepository scmRepository;

    protected AbstractScmPublishMojo()
    {
        super();
    }

    protected void logInfo( String format, Object... params )
    {
        getLog().info( String.format( format, params ) );
    }

    protected void logError( String format, Object... params )
    {
        getLog().error( String.format( format, params ) );
    }

    protected ReleaseDescriptor setupScm()
        throws ReleaseScmRepositoryException, ReleaseExecutionException
    {
        String scmUrl;
        if ( localCheckout )
        {
            // in the release phase we have to change the checkout URL
            // to do a local checkout instead of going over the network.

            // the first step is a bit tricky, we need to know which provider! like e.g. "scm:jgit:http://"
            // the offset of 4 is because 'scm:' has 4 characters...
            String providerPart = pubScmUrl.substring( 0, pubScmUrl.indexOf( ':', 4 ) );

            // X TODO: also check the information from releaseDescriptor.getScmRelativePathProjectDirectory()
            // X TODO: in case our toplevel git directory has no pom.
            // X TODO: fix pathname once I understand this.
            scmUrl = providerPart + ":file://" + "target/localCheckout";
            logInfo( "Performing a LOCAL checkout from " + scmUrl );
        }

        ReleaseDescriptor releaseDescriptor = new ReleaseDescriptor();
        releaseDescriptor.setInteractive( settings.isInteractiveMode() );

        releaseDescriptor.setScmPassword( password );
        releaseDescriptor.setScmUsername( username );

        releaseDescriptor.setWorkingDirectory( basedir.getAbsolutePath() );
        releaseDescriptor.setLocalCheckout( localCheckout );
        releaseDescriptor.setScmSourceUrl( pubScmUrl );

        if ( providerImplementations != null )
        {
            for ( Map.Entry<String, String> providerEntry : providerImplementations.entrySet() )
            {
                getLog().info( "Change the default '" + providerEntry.getKey() + "' provider implementation to '"
                    + providerEntry.getValue() + "'." );
                scmManager.setScmProviderImplementation( providerEntry.getKey(), providerEntry.getValue() );
            }
        }

        try
        {
            scmRepository = scmRepositoryConfigurator.getConfiguredRepository( releaseDescriptor, settings );

            scmProvider = scmRepositoryConfigurator.getRepositoryProvider( scmRepository );
        }
        catch ( ScmRepositoryException e )
        {
            // TODO: rethink this error pattern.
            logError( e.getMessage() );

            throw new ReleaseScmRepositoryException( e.getMessage(), e.getValidationMessages() );
        }
        catch ( NoSuchScmProviderException e )
        {
            logError( e.getMessage() );

            throw new ReleaseExecutionException( "Unable to configure SCM repository: " + e.getMessage(), e );
        }
        return releaseDescriptor;
    }
}