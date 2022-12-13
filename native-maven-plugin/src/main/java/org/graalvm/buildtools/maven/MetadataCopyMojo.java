/*
 * Copyright (c) 2020, 2022 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * The Universal Permissive License (UPL), Version 1.0
 *
 * Subject to the condition set forth below, permission is hereby granted to any
 * person obtaining a copy of this software, associated documentation and/or
 * data (collectively the "Software"), free of charge and under any and all
 * copyright rights in the Software, and any and all patent rights owned or
 * freely licensable by each licensor hereunder covering either (i) the
 * unmodified Software as contributed to or provided by such licensor, or (ii)
 * the Larger Works (as defined below), to deal in both
 *
 * (a) the Software, and
 *
 * (b) any piece of software and/or hardware listed in the lrgrwrks.txt file if
 * one is included with the Software each a "Larger Work" to which the Software
 * is contributed by such licensors),
 *
 * without restriction, including without limitation the rights to copy, create
 * derivative works of, display, perform, and distribute the Software and make,
 * use, sell, offer for sale, import, export, have made, and have sold the
 * Software and the Larger Work(s), and to sublicense the foregoing rights on
 * either these or other terms.
 *
 * This license is subject to the following condition:
 *
 * The above copyright notice and either this complete permission notice or at a
 * minimum a reference to the UPL must be included in all copies or substantial
 * portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package org.graalvm.buildtools.maven;

import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.graalvm.buildtools.agent.StandardAgentMode;
import org.graalvm.buildtools.maven.config.AbstractMergeAgentFilesMojo;
import org.graalvm.buildtools.maven.config.agent.AgentConfiguration;
import org.graalvm.buildtools.maven.config.agent.MetadataCopyConfiguration;
import org.graalvm.buildtools.utils.NativeImageConfigurationUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import static org.graalvm.buildtools.utils.LoggerUtils.traverseDir;

@Mojo(name = "metadata-copy", defaultPhase = LifecyclePhase.PREPARE_PACKAGE)
public class MetadataCopyMojo extends AbstractMergeAgentFilesMojo {

    private static final String DEFAULT_OUTPUT_DIRECTORY = "/META-INF/native-image";
    private static final List<String> FILES_REQUIRED_FOR_MERGE = Arrays.asList("reflect-config.json", "jni-config.json", "proxy-config.json", "resource-config.json");

    @Parameter(alias = "agent")
    private AgentConfiguration agentConfiguration;

    @Parameter(defaultValue = "${project}", readonly = true, required = true)
    private MavenProject project;

    @Override
    public void execute() throws MojoExecutionException {
        if (agentConfiguration != null && agentConfiguration.isEnabled()) {
            // in direct mode user is fully responsible for agent configuration, and we will not execute anything besides line that user provided
            if (agentConfiguration.getDefaultMode().equalsIgnoreCase("direct")) {
                logger.info("You are running agent in direct mode. Skipping both merge and metadata copy tasks.");
                logger.info("In direct mode, user takes full responsibility for agent configuration.");
                return;
            }

            MetadataCopyConfiguration config = agentConfiguration.getMetadataCopyConfiguration();
            if (config == null) {
                getLog().info("Metadata copy config not provided. Skipping this task.");
                return;
            }

            String buildDirectory = project.getBuild().getDirectory() + "/native/agent-output/";
            String destinationDir = config.getOutputDirectory();
            if (destinationDir == null) {
                destinationDir = project.getBuild().getOutputDirectory() + DEFAULT_OUTPUT_DIRECTORY;
            }

            if (!Files.isDirectory(Paths.get(destinationDir))) {
                throw new MojoExecutionException("Directory specified in metadata copy configuration dose not exists.");
            }

            Path nativeImageExecutable = NativeImageConfigurationUtils.getNativeImage(logger);
            tryInstallMergeExecutable(nativeImageExecutable);
            executeCopy(buildDirectory, destinationDir);
            getLog().info("Metadata copy process finished.");
        }
    }

    private void executeCopy(String buildDirectory, String destinationDir) throws MojoExecutionException {
        MetadataCopyConfiguration config = agentConfiguration.getMetadataCopyConfiguration();
        List<String> sourceDirectories = getSourceDirectories(config.getDisabledStages(), buildDirectory);

        // in case we have both main and test phase disabled, we don't need to copy anything
        if (sourceDirectories.isEmpty()) {
            logger.warn("Skipping metadata copy task. Both main and test stages are disabled in metadata copy configuration.");
            return;
        }

        // In case user wants to merge agent-output files with some existing files in output directory, we need to check if there are some
        // files in outputDirectory that can be merged. If the output directory is empty, we ignore user instruction to merge files.
        if (config.shouldMerge() && !isDirectoryEmpty(destinationDir)) {
            //  If output directory contains some files, we need to check if the directory contains all necessary files for merge
            if (!dirContainsFilesForMerge(destinationDir)) {
                List<String> destinationDirContent = Arrays.stream(Objects.requireNonNull(new File(destinationDir).listFiles())).map(File::getName).collect(Collectors.toList());
                List<String> missingFiles = getListDiff(FILES_REQUIRED_FOR_MERGE, destinationDirContent);

                throw new MojoExecutionException("There are missing files for merge in output directory. If you want to merge agent files with" +
                        "existing files in output directory, please make sure that output directory contains all of the following files: " +
                        "reflect-config.json, jni-config.json, proxy-config.json, resource-config.json. Currently the output directory is " +
                        "missing: " + missingFiles);
            }

            sourceDirectories.add(destinationDir);
        }

        String sourceDirsInfo = sourceDirectories.stream().map(File::new).map(File::getName).collect(Collectors.joining(", "));
        logger.info("Copying files from: " + sourceDirsInfo);

        List<String> nativeImageConfigureOptions = new StandardAgentMode().getNativeImageConfigureOptions(sourceDirectories, Collections.singletonList(destinationDir));
        nativeImageConfigureOptions.add(0, mergerExecutable.getAbsolutePath());
        ProcessBuilder processBuilder = new ProcessBuilder(nativeImageConfigureOptions);

        try {
            Process start = processBuilder.start();
            int retCode = start.waitFor();
            if (retCode != 0) {
                getLog().error("Metadata copy process failed with code: " + retCode);
                throw new MojoExecutionException("Metadata copy process failed.");
            }
        } catch (IOException | InterruptedException e) {
            throw new RuntimeException(e);
        }
    }

    private List<String> getSourceDirectories(List<String> disabledStages, String buildDirectory) {
        List<String> sourceDirectories = new ArrayList<>();

        sourceDirectories.add(buildDirectory + NativeExtension.Context.main);
        sourceDirectories.add(buildDirectory + NativeExtension.Context.test);

        for (String disabledStage : disabledStages) {
            sourceDirectories.remove(buildDirectory + disabledStage);
        }

        return sourceDirectories;
    }

    private boolean isDirectoryEmpty(String dirName) {
        File directory = new File(dirName);
        File[] content = directory.listFiles();

        return content == null || content.length == 0;
    }

    //check if we have all files needed for native-image-configure generate tool
    private boolean dirContainsFilesForMerge(String dir) {
        File baseDir = new File(dir);
        File[] content = baseDir.listFiles();
        if (content == null) {
            return false;
        }
        List<String> dirContent = Arrays.stream(content).map(File::getName).collect(Collectors.toList());

        return getListDiff(FILES_REQUIRED_FOR_MERGE, dirContent).isEmpty();
    }

    private List<String> getListDiff(List<String> list1, List<String> list2) {
        List<String> diff = new ArrayList<>(list1);
        diff.removeAll(list2);
        return diff;
    }

}
