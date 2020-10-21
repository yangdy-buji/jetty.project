//
//  ========================================================================
//  Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.webapp;

import java.io.File;
import java.nio.file.Path;
import java.time.Duration;
import java.util.function.Consumer;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.output.OutputFrame;
import org.testcontainers.containers.startupcheck.OneShotStartupCheckStrategy;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class WebAppContextTempDirTest
{
    private static final Logger LOG = Log.getLogger(WebAppContextTempDirTest.class);

    private Server server;
    private WebAppContext webapp;

    @BeforeEach
    public void setup()
    {
        server = new Server();
        ServerConnector connector = new ServerConnector(server);
        server.addConnector(connector);

        File testWebAppDir = MavenTestingUtils.getProjectDir("src/test/webapp");
        webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar(testWebAppDir.getAbsolutePath());
        server.setHandler(webapp);
    }

    @AfterEach
    public void stop() throws Exception
    {
        server.stop();
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testDefaultTempDirectory(boolean persistTempDir) throws Exception
    {
        webapp.setPersistTempDirectory(persistTempDir);

        // Temp Directory Initially isn't set until started.
        File tempDirectory = webapp.getTempDirectory();
        assertNull(tempDirectory);

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        tempDirectory = webapp.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        webapp.stop();
        tempDirectory = webapp.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPreDefinedTempDirectory(boolean persistTempDir) throws Exception
    {
        webapp.setPersistTempDirectory(persistTempDir);

        // The temp directory is defined but has not been created.
        File webappTempDir = MavenTestingUtils.getTargetTestingPath("webappTempDir").toFile();
        IO.delete(webappTempDir);
        webapp.setTempDirectory(webappTempDir);
        assertThat(webapp.getTempDirectory(), is(webappTempDir));
        assertFalse(webappTempDir.exists());

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        File tempDirectory = webapp.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        webapp.stop();
        tempDirectory = webapp.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    public void testPreExistingTempDirectory(boolean persistTempDir) throws Exception
    {
        webapp.setPersistTempDirectory(persistTempDir);

        // The temp directory is defined and has already been created.
        File webappTempDir = MavenTestingUtils.getTargetTestingPath("webappTempDir").toFile();
        IO.delete(webappTempDir);
        if (!webappTempDir.exists())
           assertTrue(webappTempDir.mkdir());
        webapp.setTempDirectory(webappTempDir);
        assertThat(webapp.getTempDirectory(), is(webappTempDir));
        assertTrue(webappTempDir.exists());

        // Once server is started the WebApp temp directory exists and is valid directory.
        server.start();
        File tempDirectory = webapp.getTempDirectory();
        assertNotNull(tempDirectory);
        assertTrue(tempDirectory.exists());
        assertTrue(tempDirectory.isDirectory());

        // Once server is stopped the WebApp temp should be deleted if persistTempDir is false.
        webapp.stop();
        tempDirectory = webapp.getTempDirectory();
        assertThat(tempDirectory != null && tempDirectory.exists(), is(persistTempDir));
    }

    @Test
    public void test() throws Exception
    {
        String jettyVersion = System.getProperty("jettyVersion");
        String mavenRepoPath = System.getProperty("mavenRepoPath");
        Path jettyHome = JettyHomeFinder.getJettyHome(jettyVersion, mavenRepoPath);

        String command = "mkdir /webappTmpDir && " +
            "chmod 750 /webappTmpDir && " +
            "groupadd -r jetty && useradd -r -g jetty jetty && " +
            "mkdir /home/jetty && chown -R jetty:jetty /home/jetty && " +
            "ln -s `which java` /home/jetty/java && " +
            "su -l jetty bash -c '" +
            "./java -jar /jetty-home/start.jar --add-to-startd=http && " +
            "./java -Djava.io.tmpdir=/webappTmpDir -jar /jetty-home/start.jar || true" +
            "'";

        // We need to chown the generated test results so host has permissions to delete files generated by container.
        LogConsumer logConsumer = new LogConsumer();
        GenericContainer<?> container = new GenericContainer<>("openjdk:11-jdk")
            .withCommand("/bin/bash", "-c", command)
            .withStartupCheckStrategy(new OneShotStartupCheckStrategy().withTimeout(Duration.ofMinutes(10)))
            .withLogConsumer(logConsumer);
        container.addFileSystemBind(jettyHome.toString(), "/jetty-home", BindMode.READ_ONLY);

        assertDoesNotThrow(container::start);
        String log = logConsumer.getLog();
        assertThat(log, containsString("java.nio.file.AccessDeniedException"));
    }

    public static class LogConsumer implements Consumer<OutputFrame>
    {
        private final StringBuilder log = new StringBuilder();

        @Override
        public void accept(OutputFrame outputFrame)
        {
            String output = outputFrame.getUtf8String();
            LOG.info(output);
            log.append(output);
        }

        public String getLog()
        {
            return log.toString();
        }
    }
}
