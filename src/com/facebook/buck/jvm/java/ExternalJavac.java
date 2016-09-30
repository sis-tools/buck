/*
 * Copyright 2012-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.jvm.java;


import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildTargetSourcePath;
import com.facebook.buck.rules.PathSourcePath;
import com.facebook.buck.rules.RuleKeyObjectSink;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.facebook.buck.zip.Unzip;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Functions;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Predicate;
import com.google.common.base.Strings;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedSet;

import java.io.IOException;
import java.nio.file.Path;

public class ExternalJavac implements Javac {

  private static final JavacVersion DEFAULT_VERSION = JavacVersion.of("unknown version");

  private final Either<Path, SourcePath> pathToJavac;
  private final Supplier<JavacVersion> version;

  public ExternalJavac(final Either<Path, SourcePath> pathToJavac) {
    this.pathToJavac = pathToJavac;

    this.version = Suppliers.memoize(
        new Supplier<JavacVersion>() {
          @Override
          public JavacVersion get() {
            if (pathToJavac.isRight() && pathToJavac.getRight() instanceof BuildTargetSourcePath) {
              return DEFAULT_VERSION;
            }
            ProcessExecutorParams params = ProcessExecutorParams.builder()
                .setCommand(
                    ImmutableList.of(
                        pathToJavac.isLeft() ?
                            pathToJavac.getLeft().toString() :
                            ((PathSourcePath) pathToJavac.getRight()).getRelativePath().toString(),
                        "-version"))
                .build();
            ProcessExecutor.Result result;
            try {
              result = createProcessExecutor().launchAndExecute(params);
            } catch (InterruptedException | IOException e) {
              throw new RuntimeException(e);
            }
            Optional<String> stderr = result.getStderr();
            String output = stderr.or("").trim();
            if (Strings.isNullOrEmpty(output)) {
              return DEFAULT_VERSION;
            } else {
              return JavacVersion.of(output);
            }
          }
        });
  }

  @Override
  public ImmutableCollection<BuildRule> getDeps(SourcePathResolver resolver) {
    return resolver.filterBuildRuleInputs(getInputs());
  }

  @Override
  public ImmutableCollection<SourcePath> getInputs() {
    return pathToJavac.isRight() ?
        ImmutableSortedSet.of(pathToJavac.getRight()) :
        ImmutableSortedSet.<SourcePath>of();
  }

  @Override
  public ImmutableList<String> getCommandPrefix(SourcePathResolver resolver) {
    return ImmutableList.of(
        pathToJavac.isRight() ?
            resolver.getAbsolutePath(pathToJavac.getRight()).toString() :
            pathToJavac.getLeft().toString());
  }

  @Override
  public ImmutableMap<String, String> getEnvironment(SourcePathResolver resolver) {
    return ImmutableMap.of();
  }

  public static Javac createJavac(Either<Path, SourcePath> pathToJavac) {
    return new ExternalJavac(pathToJavac);
  }

  @Override
  public JavacVersion getVersion() {
    return version.get();
  }

  @VisibleForTesting
  ProcessExecutor createProcessExecutor() {
    return new ProcessExecutor(Console.createNullConsole());
  }

  @Override
  public String getDescription(
      ImmutableList<String> options,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList) {
    StringBuilder builder = new StringBuilder(prettyPathToJavac());
    builder.append(" ");
    Joiner.on(" ").appendTo(builder, options);
    builder.append(" ");
    builder.append("@").append(pathToSrcsList);

    return builder.toString();
  }

  @Override
  public String getShortName() {
    return prettyPathToJavac();
  }

  @Override
  public void appendToRuleKey(RuleKeyObjectSink sink) {
    if (DEFAULT_VERSION.equals(getVersion())) {
      // What we really want to do here is use a VersionedTool, however, this will suffice for now.
      sink.setReflectively("javac", prettyPathToJavac());
    } else {
      sink.setReflectively("javac.version", getVersion().toString());
    }
  }

  private String prettyPathToJavac() {
    if (pathToJavac.isLeft()) {
      return pathToJavac.getLeft().toString();
    }
    if (pathToJavac.getRight() instanceof BuildTargetSourcePath) {
      return ((BuildTargetSourcePath) pathToJavac.getRight()).getTarget().toString();
    }
    return ((PathSourcePath) pathToJavac.getRight()).getRelativePath().toString();
  }

  @Override
  public int buildWithClasspath(
      ExecutionContext context,
      ProjectFilesystem filesystem,
      SourcePathResolver resolver,
      BuildTarget invokingRule,
      ImmutableList<String> options,
      ImmutableSet<String> safeAnnotationProcessors,
      ImmutableSortedSet<Path> javaSourceFilePaths,
      Path pathToSrcsList,
      Optional<Path> workingDirectory,
      ClassUsageFileWriter usedClassesFileWriter,
      Optional<StandardJavaFileManagerFactory> fileManagerFactory) throws InterruptedException {
    ImmutableList.Builder<String> command = ImmutableList.builder();
    command.add(
        pathToJavac.isLeft() ?
            pathToJavac.getLeft().toString() :
            resolver.getAbsolutePath(pathToJavac.getRight()).toString());
    command.addAll(options);

    ImmutableList<Path> expandedSources;
    try {
      expandedSources = getExpandedSourcePaths(
          filesystem,
          invokingRule,
          javaSourceFilePaths,
          workingDirectory);
    } catch (IOException e) {
      throw new HumanReadableException(
          "Unable to expand sources for %s into %s",
          invokingRule,
          workingDirectory);
    }
    try {
      filesystem.writeLinesToPath(
          FluentIterable.from(expandedSources)
              .transform(Functions.toStringFunction())
              .transform(ARGFILES_ESCAPER),
          pathToSrcsList);
      command.add("@" + pathToSrcsList);
    } catch (IOException e) {
      context.logError(
          e,
          "Cannot write list of .java files to compile to %s file! Terminating compilation.",
          pathToSrcsList);
      return 1;
    }

    // Run the command
    int exitCode = -1;
    try {
      ProcessExecutorParams params = ProcessExecutorParams.builder()
          .setCommand(command.build())
          .setEnvironment(context.getEnvironment())
          .setDirectory(filesystem.getRootPath().toAbsolutePath())
          .build();
      ProcessExecutor.Result result = context.getProcessExecutor().launchAndExecute(params);
      exitCode = result.getExitCode();
    } catch (IOException e) {
      e.printStackTrace(context.getStdErr());
      return exitCode;
    }

    return exitCode;
  }

  private ImmutableList<Path> getExpandedSourcePaths(
      ProjectFilesystem projectFilesystem,
      BuildTarget invokingRule,
      ImmutableSet<Path> javaSourceFilePaths,
      Optional<Path> workingDirectory) throws IOException {

    // Add sources file or sources list to command
    ImmutableList.Builder<Path> sources = ImmutableList.builder();
    for (Path path : javaSourceFilePaths) {
      String pathString = path.toString();
      if (pathString.endsWith(".java")) {
        sources.add(path);
      } else if (pathString.endsWith(SRC_ZIP) || pathString.endsWith(SRC_JAR)) {
        if (!workingDirectory.isPresent()) {
          throw new HumanReadableException(
              "Attempting to compile target %s which specified a .src.zip input %s but no " +
                  "working directory was specified.",
              invokingRule.toString(),
              path);
        }
        // For a Zip of .java files, create a JavaFileObject for each .java entry.
        ImmutableList<Path> zipPaths = Unzip.extractZipFile(
            projectFilesystem.resolve(path),
            projectFilesystem.resolve(workingDirectory.get()),
            Unzip.ExistingFileMode.OVERWRITE);
        sources.addAll(
            FluentIterable.from(zipPaths)
                .filter(
                    new Predicate<Path>() {
                      @Override
                      public boolean apply(Path input) {
                        return input.toString().endsWith(".java");
                      }
                    }));
      }
    }
    return sources.build();
  }
}
