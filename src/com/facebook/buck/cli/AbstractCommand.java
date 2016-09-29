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

import com.facebook.buck.config.CellConfig;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.log.LogConfigSetup;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.parser.BuildTargetParser;
import com.facebook.buck.parser.BuildTargetPatternParser;
import com.facebook.buck.parser.BuildTargetPatternTargetNodeParser;
import com.facebook.buck.parser.TargetNodeSpec;
import com.facebook.buck.rules.CellPathResolver;
import com.facebook.buck.rules.RelativeCellName;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.concurrent.ConcurrencyLimit;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

public abstract class AbstractCommand implements Command {

  private static final String HELP_LONG_ARG = "--help";
  private static final String NO_CACHE_LONG_ARG = "--no-cache";
  private static final String OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG = "--output-test-events-to-file";
  private static final String PROFILE_PARSER_LONG_ARG = "--profile-buck-parser";
  private static final String NUM_THREADS_LONG_ARG = "--num-threads";
  private static final String LOAD_LIMIT_LONG_ARG = "--load-limit";

  /**
   * This value should never be read. {@link VerbosityParser} should be used instead.
   * args4j requires that all options that could be passed in are listed as fields, so we include
   * this field so that {@code --verbose} is universally available to all commands.
   */
  @Option(
      name = VerbosityParser.VERBOSE_LONG_ARG,
      aliases = { VerbosityParser.VERBOSE_SHORT_ARG },
      usage = "Specify a number between 0 and 8. '-v 1' is default, '-v 8' is most verbose.")
  @SuppressWarnings("PMD.UnusedPrivateField")
  private int verbosityLevel = -1;

  @Option(name = NUM_THREADS_LONG_ARG, aliases = "-j", usage = "Default is 1.25 * num processors.")
  @Nullable
  private Integer numThreads = null;

  @Nullable
  @Option(name = LOAD_LIMIT_LONG_ARG,
      aliases = "-L",
      usage = "[Float] Do not start new jobs when system load is above this level." +
          " See uptime(1).")
  private Double loadLimit = null;

  @Option(
      name = "--config",
      aliases = {"-c"},
      usage = "")
  private Map<String, String> configOverrides = Maps.newLinkedHashMap();

  @Override
  public CellConfig getConfigOverrides() {
    CellConfig.Builder builder = CellConfig.builder();

    // Parse command-line config overrides.
    for (Map.Entry<String, String> entry : configOverrides.entrySet()) {
      List<String> key = Splitter.on("//").limit(2).splitToList(entry.getKey());
      RelativeCellName cellName = RelativeCellName.ROOT_CELL_NAME;
      String configKey = key.get(0);
      if (key.size() == 2) {
        // Here we explicitly take the whole string as the cell name. We don't support transitive
        // path overrides for cells.
        cellName = RelativeCellName.of(ImmutableSet.of(key.get(0)));
        configKey = key.get(1);
      }
      key = Splitter.on('.').limit(2).splitToList(configKey);
      String value = entry.getValue();
      if (value == null) {
        value = "";
      }
      if (key.size() != 2) {
        throw new HumanReadableException(
            "Invalid config override \"%s=%s\".  Expected \"<section>.<field>=<value>\".",
            entry.getKey(),
            value);
      }

      // Overrides for locations of transitive children of cells are weird as the order of overrides
      // can affect the result (for example `-c a/b/c.k=v -c a/b//repositories.c=foo` causes an
      // interesting problem as the a/b/c cell gets created as a side-effect of the first override,
      // but the second override wants to change its identity).
      // It's generally a better idea to use the .buckconfig.local mechanism when overriding
      // repositories anyway, so here we simply disallow them.
      String section = key.get(0);
      if (section.equals("repositories")) {
        throw new HumanReadableException("Overriding repository locations from the command line " +
            "is not supported. Please place a .buckconfig.local in the appropriate location and " +
            "use that instead.");
      }

      builder.put(cellName, section, key.get(1), value);
    }
    if (numThreads != null) {
      builder.put(CellConfig.ALL_CELLS_OVERRIDE, "build", "threads", String.valueOf(numThreads));
    }
    if (noCache) {
      builder.put(CellConfig.ALL_CELLS_OVERRIDE, "cache", "mode", "");
    }

    return builder.build();
  }

  @Override
  public LogConfigSetup getLogConfig() {
    return LogConfigSetup.DEFAULT_SETUP;
  }

  @Option(
      name = NO_CACHE_LONG_ARG,
      usage = "Whether to ignore the [cache] declared in .buckconfig.")
  private boolean noCache = false;

  @Nullable
  @Option(
      name = OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG,
      aliases = { "--output-events-to-file" },
      usage = "Serialize test-related event-bus events to the given file " +
          "as line-oriented JSON objects.")
  private String eventsOutputPath = null;

  @Option(
      name = PROFILE_PARSER_LONG_ARG,
      usage = "Enable profiling of buck.py internals (not the target being compiled) in the debug" +
          "log and trace.")
  private boolean enableParserProfiling = false;

  @Option(
      name = HELP_LONG_ARG,
      usage = "Prints the available options and exits.")
  private boolean help = false;

  /** @return {code true} if the {@code [cache]} in {@code .buckconfig} should be ignored. */
  public boolean isNoCache() {
    return noCache;
  }

  public boolean showHelp() {
    return help;
  }

  public Optional<Path> getEventsOutputPath() {
    if (eventsOutputPath == null) {
      return Optional.absent();
    } else {
      return Optional.of(Paths.get(eventsOutputPath));
    }
  }

  @Override
  public final int run(CommandRunnerParams params) throws IOException, InterruptedException {
    if (showHelp()) {
      new AdditionalOptionsCmdLineParser(this).printUsage(params.getConsole().getStdErr());
      return 1;
    }
    if (params.getConsole().getAnsi().isAnsiTerminal()) {
      ImmutableList<String> motd = params.getBuckConfig().getMessageOfTheDay();
      if (!motd.isEmpty()) {
        for (String line : motd) {
          params.getBuckEventBus().post(ConsoleEvent.info(line));
        }
      }
    }
    return runWithoutHelp(params);
  }

  public abstract int runWithoutHelp(CommandRunnerParams params)
      throws IOException, InterruptedException;

  protected CommandLineBuildTargetNormalizer getCommandLineBuildTargetNormalizer(
      BuckConfig buckConfig) {
    return new CommandLineBuildTargetNormalizer(buckConfig);
  }

  public boolean getEnableParserProfiling() {
    return enableParserProfiling;
  }

  public ImmutableList<TargetNodeSpec> parseArgumentsAsTargetNodeSpecs(
      BuckConfig config,
      Iterable<String> targetsAsArgs) {
    ImmutableList.Builder<TargetNodeSpec> specs = ImmutableList.builder();
    CommandLineTargetNodeSpecParser parser =
        new CommandLineTargetNodeSpecParser(
            config,
            new BuildTargetPatternTargetNodeParser());
    for (String arg : targetsAsArgs) {
      specs.add(parser.parse(config.getCellPathResolver(), arg));
    }
    return specs.build();
  }

  /**
   *
   * @param cellNames
   * @param buildTargetNames The build targets to parse, represented as strings.
   * @return A set of {@link BuildTarget}s for the input buildTargetNames.
   */
  protected ImmutableSet<BuildTarget> getBuildTargets(
      CellPathResolver cellNames,
      Iterable<String> buildTargetNames) {
    ImmutableSet.Builder<BuildTarget> buildTargets = ImmutableSet.builder();

    // Parse all of the build targets specified by the user.
    for (String buildTargetName : buildTargetNames) {
      buildTargets.add(
          BuildTargetParser.INSTANCE.parse(
              buildTargetName,
              BuildTargetPatternParser.fullyQualified(),
              cellNames));
    }

    return buildTargets.build();
  }

  protected ExecutionContext createExecutionContext(CommandRunnerParams params) {
    return ExecutionContext.builder()
        .setConsole(params.getConsole())
        .setAndroidPlatformTargetSupplier(params.getAndroidPlatformTargetSupplier())
        .setBuckEventBus(params.getBuckEventBus())
        .setPlatform(params.getPlatform())
        .setEnvironment(params.getEnvironment())
        .setJavaPackageFinder(params.getJavaPackageFinder())
        .setObjectMapper(params.getObjectMapper())
        .setExecutors(params.getExecutors())
        .build();
  }

  public ConcurrencyLimit getConcurrencyLimit(BuckConfig buckConfig) {
    Double loadLimit = this.loadLimit;
    if (loadLimit == null) {
      loadLimit = (double) buckConfig.getLoadLimit();
    }

    return new ConcurrencyLimit(
        buckConfig.getNumThreads(),
        loadLimit,
        buckConfig.getResourceAllocationFairness(),
        buckConfig.getManagedThreadCount(),
        buckConfig.getDefaultResourceAmounts(),
        buckConfig.getMaximumResourceAmounts());
  }

  protected ImmutableList<String> getOptions() {
    ImmutableList.Builder<String> builder = ImmutableList.builder();
    if (verbosityLevel != -1) {
      builder.add(VerbosityParser.VERBOSE_LONG_ARG);
      builder.add(String.valueOf(verbosityLevel));
    }
    if (numThreads != null) {
      builder.add(NUM_THREADS_LONG_ARG);
      builder.add(numThreads.toString());
    }
    if (loadLimit != null) {
      builder.add(LOAD_LIMIT_LONG_ARG);
      builder.add(loadLimit.toString());
    }
    if (noCache) {
      builder.add(NO_CACHE_LONG_ARG);
    }
    if (eventsOutputPath != null) {
      builder.add(OUTPUT_TEST_EVENTS_TO_FILE_LONG_ARG);
      builder.add(eventsOutputPath);
    }
    if (enableParserProfiling) {
      builder.add(PROFILE_PARSER_LONG_ARG);
    }
    return builder.build();
  }

}
