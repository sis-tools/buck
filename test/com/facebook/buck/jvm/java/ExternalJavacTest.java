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

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.Either;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.DefaultTargetNodeToBuildRuleTransformer;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.NoopBuildRule;
import com.facebook.buck.rules.RuleKey;
import com.facebook.buck.rules.RuleKeyBuilder;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.UncachedRuleKeyBuilder;
import com.facebook.buck.rules.keys.DefaultRuleKeyBuilderFactory;
import com.facebook.buck.testutil.FakeFileHashCache;
import com.facebook.buck.testutil.integration.TemporaryPaths;
import com.facebook.buck.util.FakeProcess;
import com.facebook.buck.util.FakeProcessExecutor;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedSet;
import com.google.common.hash.HashCode;
import com.google.common.hash.Hashing;

import org.easymock.EasyMockSupport;
import org.junit.Rule;
import org.junit.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

public class ExternalJavacTest extends EasyMockSupport {
  private static final Path PATH_TO_SRCS_LIST = Paths.get("srcs_list");
  public static final ImmutableSortedSet<Path> SOURCE_PATHS =
      ImmutableSortedSet.of(Paths.get("foobar.java"));

  @Rule
  public TemporaryPaths root = new TemporaryPaths();

  @Rule
  public TemporaryPaths tmpFolder = new TemporaryPaths();


  @Test
  public void testJavacCommand() {
    ExternalJavac firstOrder = createTestStep();
    ExternalJavac warn = createTestStep();
    ExternalJavac transitive = createTestStep();

    assertEquals("fakeJavac -source 6 -target 6 -g -d . -classpath foo.jar @" + PATH_TO_SRCS_LIST,
        firstOrder.getDescription(
            getArgs().add("foo.jar").build(),
            SOURCE_PATHS,
            PATH_TO_SRCS_LIST));
    assertEquals("fakeJavac -source 6 -target 6 -g -d . -classpath foo.jar @" + PATH_TO_SRCS_LIST,
        warn.getDescription(
            getArgs().add("foo.jar").build(),
            SOURCE_PATHS,
            PATH_TO_SRCS_LIST));
    assertEquals("fakeJavac -source 6 -target 6 -g -d . -classpath bar.jar" + File.pathSeparator +
        "foo.jar @" + PATH_TO_SRCS_LIST,
        transitive.getDescription(
            getArgs().add("bar.jar" + File.pathSeparator + "foo.jar").build(),
            SOURCE_PATHS,
            PATH_TO_SRCS_LIST));
  }

  @Test
  public void externalJavacWillHashTheExternalIfNoVersionInformationIsReturned()
      throws IOException {
    Path javac = Files.createTempFile("fake", "javac");
    javac.toFile().deleteOnExit();

    Map<Path, HashCode> hashCodes = ImmutableMap.of(javac, Hashing.sha1().hashInt(42));
    FakeFileHashCache fileHashCache = new FakeFileHashCache(hashCodes);
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//example:target").build();
    BuildRule buildRule = new NoopBuildRule(params, pathResolver);
    DefaultRuleKeyBuilderFactory fakeRuleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(0, fileHashCache, pathResolver);

    RuleKey javacKey = new UncachedRuleKeyBuilder(
        pathResolver,
        fileHashCache,
        fakeRuleKeyBuilderFactory)
        .setReflectively("javac", javac.toString())
        .build();
    RuleKeyBuilder<RuleKey> builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    builder.setReflectively("key.appendableSubKey", javacKey);
    RuleKey expected = builder.build();

    ProcessExecutorParams javacExe = ProcessExecutorParams.builder().addCommand(
        javac.toAbsolutePath().toString(),
        "-version").build();
    FakeProcess javacProc = new FakeProcess(0, "", "");
    final FakeProcessExecutor executor = new FakeProcessExecutor(
        ImmutableMap.of(javacExe, javacProc));

    builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    ExternalJavac compiler = new ExternalJavac(Either.<Path, SourcePath>ofLeft(javac)) {
      @Override
      ProcessExecutor createProcessExecutor() {
        return executor;
      }
    };
    builder.setReflectively("key", compiler);
    RuleKey seen = builder.build();

    assertEquals(expected, seen);
  }

  @Test
  public void externalJavacWillHashTheJavacVersionIfPresent()
      throws IOException {
    Path javac = Files.createTempFile("fake", "javac");
    javac.toFile().deleteOnExit();
    String reportedJavacVersion = "mozzarella";

    JavacVersion javacVersion = JavacVersion.of(reportedJavacVersion);

    Map<Path, HashCode> hashCodes = ImmutableMap.of(javac, Hashing.sha1().hashInt(42));
    FakeFileHashCache fileHashCache = new FakeFileHashCache(hashCodes);
    SourcePathResolver pathResolver = new SourcePathResolver(
        new BuildRuleResolver(TargetGraph.EMPTY, new DefaultTargetNodeToBuildRuleTransformer())
    );
    BuildRuleParams params = new FakeBuildRuleParamsBuilder("//example:target").build();
    BuildRule buildRule = new NoopBuildRule(params, pathResolver);
    DefaultRuleKeyBuilderFactory fakeRuleKeyBuilderFactory =
        new DefaultRuleKeyBuilderFactory(0, fileHashCache, pathResolver);

    RuleKey javacKey = new UncachedRuleKeyBuilder(
        pathResolver,
        fileHashCache,
        fakeRuleKeyBuilderFactory)
        .setReflectively("javac.version", javacVersion.toString())
        .build();
    RuleKeyBuilder<RuleKey> builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    builder.setReflectively("key.appendableSubKey", javacKey);
    RuleKey expected = builder.build();

    ProcessExecutorParams javacExe = ProcessExecutorParams.builder().addCommand(
        javac.toAbsolutePath().toString(),
        "-version").build();
    FakeProcess javacProc = new FakeProcess(0, "", reportedJavacVersion);
    final FakeProcessExecutor executor = new FakeProcessExecutor(
        ImmutableMap.of(javacExe, javacProc));

    builder = fakeRuleKeyBuilderFactory.newInstance(buildRule);
    ExternalJavac compiler = new ExternalJavac(Either.<Path, SourcePath>ofLeft(javac)) {
      @Override
      ProcessExecutor createProcessExecutor() {
        return executor;
      }
    };
    builder.setReflectively("key", compiler);
    RuleKey seen = builder.build();

    assertEquals(expected, seen);
  }

  private ImmutableList.Builder<String> getArgs() {
    return ImmutableList.<String>builder().add(
          "-source", "6",
          "-target", "6",
          "-g",
          "-d", ".",
          "-classpath");
  }

  private ExternalJavac createTestStep() {
    Path fakeJavac = Paths.get("fakeJavac");
    return new ExternalJavac(Either.<Path, SourcePath>ofLeft(fakeJavac));
  }
}
