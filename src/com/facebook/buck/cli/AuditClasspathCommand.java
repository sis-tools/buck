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

package com.facebook.buck.cli;

import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.graph.Dot;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.jvm.java.HasClasspathEntries;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetException;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.NoSuchBuildTargetException;
import com.facebook.buck.rules.ActionGraphCache;
import com.facebook.buck.rules.BuildRule;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreExceptions;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Function;
import com.google.common.base.Functions;
import com.google.common.base.Preconditions;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.LinkedHashMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.SortedSet;

import javax.annotation.Nullable;

public class AuditClasspathCommand extends AbstractCommand {

  /**
   * Expected usage:
   * <pre>
   * buck audit classpath --dot //java/com/facebook/pkg:pkg > /tmp/graph.dot
   * dot -Tpng /tmp/graph.dot -o /tmp/graph.png
   * </pre>
   */
  @Option(name = "--dot",
      usage = "Print dependencies as Dot graph")
  private boolean generateDotOutput;

  public boolean shouldGenerateDotOutput() {
    return generateDotOutput;
  }

  @Option(name = "--json",
      usage = "Output in JSON format")
  private boolean generateJsonOutput;

  public boolean shouldGenerateJsonOutput() {
    return generateJsonOutput;
  }

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public List<String> getArgumentsFormattedAsBuildTargets(BuckConfig buckConfig) {
    return getCommandLineBuildTargetNormalizer(buckConfig).normalizeAll(getArguments());
  }

  @Override
  public int runWithoutHelp(final CommandRunnerParams params)
      throws IOException, InterruptedException {
    // Create a TargetGraph that is composed of the transitive closure of all of the dependent
    // BuildRules for the specified BuildTargets.
    final ImmutableSet<BuildTarget> targets = FluentIterable
        .from(getArgumentsFormattedAsBuildTargets(params.getBuckConfig()))
        .transform(new Function<String, BuildTarget>() {
                     @Override
                     public BuildTarget apply(String input) {
                       return BuildTargetParser.INSTANCE.parse(
                           input,
                           BuildTargetPatternParser.fullyQualified(),
                           params.getCell().getCellPathResolver());
                     }
                   })
        .toSet();

    if (targets.isEmpty()) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          "Please specify at least one build target."));
      return 1;
    }

    TargetGraph targetGraph;
    try (CommandThreadManager pool = new CommandThreadManager(
        "Audit",
        getConcurrencyLimit(params.getBuckConfig()))) {
      targetGraph = params.getParser().buildTargetGraph(
          params.getBuckEventBus(),
          params.getCell(),
          getEnableParserProfiling(),
          pool.getExecutor(),
          targets);
    } catch (BuildFileParseException | BuildTargetException e) {
      params.getBuckEventBus().post(ConsoleEvent.severe(
          MoreExceptions.getHumanReadableOrLocalizedMessage(e)));
      return 1;
    }

    try {
      if (shouldGenerateDotOutput()) {
        return printDotOutput(params, targetGraph);
      } else if (shouldGenerateJsonOutput()) {
        return printJsonClasspath(params, targetGraph, targets);
      } else {
        return printClasspath(params, targetGraph, targets);
      }
    } catch (NoSuchBuildTargetException e) {
      throw new HumanReadableException(e.getHumanReadableErrorMessage());
    }
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  @VisibleForTesting
  int printDotOutput(CommandRunnerParams params, TargetGraph targetGraph) {
    Dot<TargetNode<?>> dot = new Dot<>(
        targetGraph,
        "target_graph",
        new Function<TargetNode<?>, String>() {
          @Override
          public String apply(TargetNode<?> targetNode) {
            return "\"" + targetNode.getBuildTarget().getFullyQualifiedName() + "\"";
          }
        },
        params.getConsole().getStdOut());
    try {
      dot.writeOutput();
    } catch (IOException e) {
      return 1;
    }
    return 0;
  }

  @VisibleForTesting
  int printClasspath(
      CommandRunnerParams params,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> targets) throws NoSuchBuildTargetException {
    BuildRuleResolver resolver = Preconditions.checkNotNull(
        ActionGraphCache.getFreshActionGraph(params.getBuckEventBus(), targetGraph)).getResolver();
    SortedSet<Path> classpathEntries = Sets.newTreeSet();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(resolver.requireRule(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries != null) {
        classpathEntries.addAll(hasClasspathEntries.getTransitiveClasspaths());
      } else {
        throw new HumanReadableException(rule.getFullyQualifiedName() + " is not a java-based" +
            " build target");
      }
    }

    for (Path path : classpathEntries) {
      params.getConsole().getStdOut().println(path);
    }

    return 0;
  }

  @VisibleForTesting
  int printJsonClasspath(
      CommandRunnerParams params,
      TargetGraph targetGraph,
      ImmutableSet<BuildTarget> targets)
      throws IOException, NoSuchBuildTargetException {
    BuildRuleResolver resolver = Preconditions.checkNotNull(
        ActionGraphCache.getFreshActionGraph(params.getBuckEventBus(), targetGraph)).getResolver();
    Multimap<String, String> targetClasspaths = LinkedHashMultimap.create();

    for (BuildTarget target : targets) {
      BuildRule rule = Preconditions.checkNotNull(resolver.requireRule(target));
      HasClasspathEntries hasClasspathEntries = getHasClasspathEntriesFrom(rule);
      if (hasClasspathEntries == null) {
        continue;
      }
      targetClasspaths.putAll(
          target.getFullyQualifiedName(),
          Iterables.transform(
              hasClasspathEntries.getTransitiveClasspaths(),
              Functions.toStringFunction()));
    }

    // Note: using `asMap` here ensures that the keys are sorted
    params.getObjectMapper().writeValue(params.getConsole().getStdOut(), targetClasspaths.asMap());

    return 0;
  }

  @Nullable
  private HasClasspathEntries getHasClasspathEntriesFrom(BuildRule rule) {
    if (rule instanceof HasClasspathEntries) {
      return (HasClasspathEntries) rule;
    }
    return null;
  }

  @Override
  public String getShortDescription() {
    return "provides facilities to audit build targets' classpaths";
  }

}
