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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.codehaus.plexus.util.IOUtil;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.resolution.ArtifactRequest;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.IO;

public class JettyHomeFinder
{
    public static Path getJettyHome(String jettyVersion) throws Exception
    {
        String mavenRepoPath = System.getProperty("user.home") + "/.m2/repository";
        return  getJettyHome(jettyVersion, mavenRepoPath);
    }

    public static Path getJettyHome(String version, String mavenRepoPath) throws Exception
    {
        String coordinates = "org.eclipse.jetty:jetty-home:zip:" + version;

        RepositorySystem repositorySystem = MavenRepositorySystemUtils.newServiceLocator().getService(RepositorySystem.class);
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        LocalRepository localRepo = new LocalRepository(mavenRepoPath);
        session.setLocalRepositoryManager(repositorySystem.newLocalRepositoryManager(session, localRepo));

        Artifact artifact = new DefaultArtifact(coordinates);
        ArtifactRequest artifactRequest = new ArtifactRequest();
        artifactRequest.setArtifact(artifact);
        ArtifactResult artifactResult = repositorySystem.resolveArtifact(session, artifactRequest);

        File artifactFile = artifactResult.getArtifact().getFile();
        File tmpDir = MavenTestingUtils.getTargetTestingDir();
        unzip(artifactFile, tmpDir);
        Path jettyHomePath = tmpDir.toPath().resolve("jetty-home-" + version);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> IO.delete(jettyHomePath.toFile())));
        return jettyHomePath;
    }

    private static void unzip(File zipFile, File output) throws IOException
    {
        try (InputStream fileInputStream = Files.newInputStream(zipFile.toPath());
             ZipInputStream zipInputStream = new ZipInputStream(fileInputStream))
        {
            ZipEntry entry = zipInputStream.getNextEntry();
            while (entry != null)
            {
                if (entry.isDirectory())
                {
                    File dir = new File(output, entry.getName());
                    if (!Files.exists(dir.toPath()))
                    {
                        Files.createDirectories(dir.toPath());
                    }
                }
                else
                {
                    // Read zipEntry and write to a file.
                    File file = new File(output, entry.getName());
                    if (!Files.exists(file.getParentFile().toPath()))
                    {
                        Files.createDirectories(file.getParentFile().toPath());
                    }
                    try (OutputStream outputStream = Files.newOutputStream(file.toPath()))
                    {
                        IOUtil.copy(zipInputStream, outputStream);
                    }
                }
                // Get next entry
                entry = zipInputStream.getNextEntry();
            }
        }
    }
}
