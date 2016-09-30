/*
 * Copyright 2016-present Facebook, Inc.
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

package com.facebook.buck.versions;

import static org.hamcrest.junit.MatcherAssert.assertThat;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.model.HasBuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetGraphAndBuildTargets;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.testutil.TargetGraphFactory;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;

import org.hamcrest.Matchers;
import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.ForkJoinPool;

public class VersionedTargetGraphBuilderTest {

  private static final ForkJoinPool POOL = new ForkJoinPool(1);

  private static String getVersionedTarget(
      BuildTarget target,
      ImmutableSortedMap<BuildTarget, Version> versions) {
    return target.withAppendedFlavors(VersionedTargetGraphBuilder.getVersionedFlavor(versions))
        .toString();
  }

  private static String getVersionedTarget(String target, String dep, String version) {
    return getVersionedTarget(
        BuildTargetFactory.newInstance(target),
        ImmutableSortedMap.of(BuildTargetFactory.newInstance(dep), Version.of(version)));
  }

  private static void assertEquals(TargetNode<?> expected, TargetNode<?> actual) {
    assertThat(
        actual.getBuildTarget(),
        Matchers.equalTo(expected.getBuildTarget()));
    assertThat(
        String.format("%s: declared deps: ", expected.getBuildTarget()),
        actual.getDeclaredDeps(),
        Matchers.equalTo(expected.getDeclaredDeps()));
    assertThat(
        String.format("%s: extra deps: ", expected.getBuildTarget()),
        actual.getExtraDeps(),
        Matchers.equalTo(expected.getExtraDeps()));
    assertThat(
        String.format("%s: inputs: ", expected.getBuildTarget()),
        actual.getInputs(),
        Matchers.equalTo(expected.getInputs()));
    for (Field field : actual.getConstructorArg().getClass().getFields()) {
      try {
        assertThat(
            field.get(actual.getConstructorArg()),
            Matchers.equalTo(field.get(expected.getConstructorArg())));
      } catch (IllegalAccessException e) {
        throw new RuntimeException(e);
      }
    }
  }

  private static void assertEquals(TargetGraph expected, TargetGraph actual) {
    ImmutableMap<BuildTarget, TargetNode<?>> expectedNodes =
        Maps.uniqueIndex(expected.getNodes(), HasBuildTarget.TO_TARGET);
    ImmutableMap<BuildTarget, TargetNode<?>> actualNodes =
        Maps.uniqueIndex(actual.getNodes(), HasBuildTarget.TO_TARGET);
    assertThat(actualNodes.keySet(), Matchers.equalTo(expectedNodes.keySet()));
    for (Map.Entry<BuildTarget, TargetNode<?>> ent : expectedNodes.entrySet()) {
      assertEquals(ent.getValue(), actualNodes.get(ent.getKey()));
    }
  }

  @Test
  public void singleRootNode() throws Exception {
    TargetNode<?> root = new VersionRootBuilder("//:root").build();
    TargetGraph graph = TargetGraphFactory.newInstance(root);
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(graph, ImmutableSet.of(root.getBuildTarget())));
    TargetGraph versionedGraph = builder.build();
    assertEquals(graph, versionedGraph);
  }

  @Test
  public void rootWithDepOnRoot() throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new VersionRootBuilder("//:root2")
                .build(),
            new VersionRootBuilder("//:root1")
                .setDeps("//:root2")
                .build());
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//:root1"),
                    BuildTargetFactory.newInstance("//:root2"))));
    TargetGraph versionedGraph = builder.build();
    assertEquals(graph, versionedGraph);
  }

  @Test
  public void versionedSubGraph() throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:dep")
                .build(),
            new VersionedAliasBuilder("//:versioned")
                .setVersions("1.0", "//:dep")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps("//:versioned")
                .build());
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))));
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:dep")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps("//:dep")
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  public void versionedSubGraphWithDepOnRoot() throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new VersionRootBuilder("//:dep_root")
                .build(),
            new VersionPropagatorBuilder("//:dep")
                .setDeps("//:dep_root")
                .build(),
            new VersionedAliasBuilder("//:versioned")
                .setVersions("1.0", "//:dep")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps("//:versioned")
                .build());
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))));
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstance(
            new VersionRootBuilder("//:dep_root")
                .build(),
            new VersionPropagatorBuilder("//:dep")
                .setDeps("//:dep_root")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps("//:dep")
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  public void versionedSubGraphWithDepAnotherVersionedSubGraph() throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:dep2")
                .build(),
            new VersionedAliasBuilder("//:versioned2")
                .setVersions("1.0", "//:dep2")
                .build(),
            new VersionRootBuilder("//:root2")
                .setDeps("//:versioned2")
                .build(),
            new VersionPropagatorBuilder("//:dep1")
                .setDeps("//:root2")
                .build(),
            new VersionedAliasBuilder("//:versioned1")
                .setVersions("1.0", "//:dep1")
                .build(),
            new VersionRootBuilder("//:root1")
                .setDeps("//:versioned1")
                .build());
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(BuildTargetFactory.newInstance("//:root1"))));
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:dep2")
                .build(),
            new VersionRootBuilder("//:root2")
                .setDeps("//:dep2")
                .build(),
            new VersionPropagatorBuilder("//:dep1")
                .setDeps("//:root2")
                .build(),
            new VersionRootBuilder("//:root1")
                .setDeps("//:dep1")
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  public void versionedSubGraphWithVersionedFlavor() throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:dep")
                .build(),
            new VersionedAliasBuilder("//:versioned")
                .setVersions("1.0", "//:dep")
                .build(),
            new VersionPropagatorBuilder("//:a")
                .setDeps("//:versioned")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps("//:a")
                .build());
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new NaiveVersionSelector(),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(BuildTargetFactory.newInstance("//:root"))));
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:dep")
                .build(),
            new VersionPropagatorBuilder(getVersionedTarget("//:a", "//:versioned", "1.0"))
                .setDeps("//:dep")
                .build(),
            new VersionRootBuilder("//:root")
                .setDeps(getVersionedTarget("//:a", "//:versioned", "1.0"))
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

  @Test
  public void versionedSubGraphWithConstraints() throws Exception {
    TargetGraph graph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:v2")
                .build(),
            new VersionPropagatorBuilder("//:v1")
                .build(),
            new VersionedAliasBuilder("//:dep")
                .setVersions("1.0", "//:v1", "2.0", "//:v2")
                .build(),
            new VersionPropagatorBuilder("//:lib")
                .setDeps("//:dep")
                .build(),
            new VersionRootBuilder("//:a")
                .setDeps("//:lib")
                .setVersionedDeps("//:dep", ExactConstraint.of(Version.of("1.0")))
                .build(),
            new VersionRootBuilder("//:b")
                .setDeps("//:lib")
                .setVersionedDeps("//:dep", ExactConstraint.of(Version.of("2.0")))
                .build());
    BuildTarget a = BuildTargetFactory.newInstance("//:a");
    BuildTarget b = BuildTargetFactory.newInstance("//:b");
    BuildTarget dep = BuildTargetFactory.newInstance("//:dep");
    VersionedTargetGraphBuilder builder =
        new VersionedTargetGraphBuilder(
            POOL,
            new FixedVersionSelector(
                ImmutableMap.of(
                    a, ImmutableMap.of(dep, Version.of("1.0")),
                    b, ImmutableMap.of(dep, Version.of("2.0")))),
            TargetGraphAndBuildTargets.of(
                graph,
                ImmutableSet.of(
                    BuildTargetFactory.newInstance("//:a"),
                    BuildTargetFactory.newInstance("//:b"))));
    TargetGraph versionedGraph = builder.build();
    TargetGraph expectedTargetGraph =
        TargetGraphFactory.newInstance(
            new VersionPropagatorBuilder("//:v1")
                .build(),
            new VersionPropagatorBuilder(getVersionedTarget("//:lib", "//:dep", "1.0"))
                .setDeps("//:v1")
                .build(),
            new VersionRootBuilder("//:a")
                .setDeps(getVersionedTarget("//:lib", "//:dep", "1.0"), "//:v1")
                .build(),
            new VersionPropagatorBuilder("//:v2")
                .build(),
            new VersionPropagatorBuilder(getVersionedTarget("//:lib", "//:dep", "2.0"))
                .setDeps("//:v2")
                .build(),
            new VersionRootBuilder("//:b")
                .setDeps(getVersionedTarget("//:lib", "//:dep", "2.0"), "//:v2")
                .build());
    assertEquals(expectedTargetGraph, versionedGraph);
  }

}
