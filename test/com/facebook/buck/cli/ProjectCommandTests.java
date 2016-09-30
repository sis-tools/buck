/*
 * Copyright 2015-present Facebook, Inc.
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

package com.facebook.buck.cli;

import com.facebook.buck.android.FakeAndroidDirectoryResolver;
import com.facebook.buck.apple.project_generator.ProjectGenerator;
import com.facebook.buck.artifact_cache.NoopArtifactCache;
import com.facebook.buck.event.BuckEventBusFactory;
import com.facebook.buck.httpserver.WebServer;
import com.facebook.buck.jvm.java.FakeJavaPackageFinder;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.Cell;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndTargets;
import com.facebook.buck.rules.TestCellBuilder;
import com.facebook.buck.testutil.FakeProjectFilesystem;
import com.facebook.buck.testutil.TestConsole;
import com.facebook.buck.timing.SettableFakeClock;
import com.facebook.buck.util.ObjectMappers;
import com.facebook.buck.util.environment.Platform;
import com.google.common.base.Optional;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

public class ProjectCommandTests {
  // Utility class, do not instantiate.
  private ProjectCommandTests() { }

  public static TargetGraphAndTargets createTargetGraph(
      TargetGraph projectGraph,
      ProjectCommand.Ide targetIde,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      boolean withTests,
      boolean withDependenciesTests
  ) {
    ProjectPredicates projectPredicates = ProjectPredicates.forIde(targetIde);

    ImmutableSet<BuildTarget> graphRoots;
    if (!passedInTargetsSet.isEmpty()) {
      ImmutableSet<BuildTarget> supplementalGraphRoots =
          ProjectCommand.getRootBuildTargetsForIntelliJ(
              targetIde,
              projectGraph,
              projectPredicates);
      graphRoots = Sets.union(passedInTargetsSet, supplementalGraphRoots).immutableCopy();
    } else {
      graphRoots = ProjectCommand.getRootsFromPredicate(
          projectGraph,
          projectPredicates.getProjectRootsPredicate());
    }

    ImmutableSet<BuildTarget> graphRootsOrSourceTargets =
        ProjectCommand.replaceWorkspacesWithSourceTargetsIfPossible(graphRoots, projectGraph);

    ImmutableSet<BuildTarget> explicitTests;
    if (withTests) {
      explicitTests = TargetGraphAndTargets.getExplicitTestTargets(
          graphRootsOrSourceTargets,
          projectGraph,
          withDependenciesTests);
    } else {
      explicitTests = ImmutableSet.of();
    }

    return TargetGraphAndTargets.create(
        graphRoots,
        projectGraph,
        projectPredicates.getAssociatedProjectPredicate(),
        withTests,
        explicitTests);
  }

  public static Map<Path, ProjectGenerator> generateWorkspacesForTargets(
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> passedInTargetsSet,
      boolean isWithTests,
      boolean isWithDependenciesTests,
      boolean isReadonly,
      boolean isBuildWithBuck,
      boolean isCombinedProjects,
      boolean isCombinesTestBundles)
      throws IOException, InterruptedException {
    TargetGraphAndTargets targetGraphAndTargets = ProjectCommandTests.createTargetGraph(
        targetGraph,
        ProjectCommand.Ide.XCODE,
        passedInTargetsSet,
        isWithTests,
        isWithDependenciesTests);

    Map<Path, ProjectGenerator> projectGenerators = new HashMap<>();
    ProjectCommand.generateWorkspacesForTargets(
        ProjectCommandTests.createCommandRunnerParamsForTests(),
        targetGraphAndTargets,
        passedInTargetsSet,
        ProjectCommand.buildWorkspaceGeneratorOptions(
            isReadonly,
            isWithTests,
            isWithDependenciesTests,
            isCombinedProjects,
            true /* shouldUseHeaderMaps */),
        ImmutableList.<String>of(),
        ImmutableList.<BuildTarget>of(),
        projectGenerators,
        isBuildWithBuck,
        isCombinedProjects,
        isCombinesTestBundles);
    return projectGenerators;
  }

  private static CommandRunnerParams createCommandRunnerParamsForTests()
      throws IOException, InterruptedException {
    Cell cell = new TestCellBuilder()
        .setFilesystem(new FakeProjectFilesystem(new SettableFakeClock(0, 0)))
        .build();
    return CommandRunnerParamsForTesting.createCommandRunnerParamsForTesting(
        new TestConsole(),
        cell,
        new FakeAndroidDirectoryResolver(),
        new NoopArtifactCache(),
        BuckEventBusFactory.newInstance(),
        FakeBuckConfig.builder().build(),
        Platform.detect(),
        ImmutableMap.copyOf(System.getenv()),
        new FakeJavaPackageFinder(),
        ObjectMappers.newDefaultInstance(),
        Optional.<WebServer>absent());
  }
}
