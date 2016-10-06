/*
 * Copyright 2013-present Facebook, Inc.
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

import com.facebook.buck.io.ProjectFilesystem;
import com.facebook.buck.json.BuildFileParseException;
import com.facebook.buck.json.ProjectBuildFileParser;
import com.facebook.buck.rules.BuckPyFunction;
import com.facebook.buck.rules.ConstructorArgMarshaller;
import com.facebook.buck.rules.coercer.DefaultTypeCoercerFactory;
import com.facebook.buck.util.Escaper;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.MoreStrings;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.base.Predicate;
import com.google.common.collect.FluentIterable;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;

import org.kohsuke.args4j.Argument;
import org.kohsuke.args4j.Option;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.SortedSet;

import javax.annotation.Nullable;

/**
 * Evaluates a build file and prints out an equivalent build file with all includes/macros
 * expanded. When complex macros are in play, this helps clarify what the resulting build rule
 * definitions are.
 */
public class AuditRulesCommand extends AbstractCommand {

  /** Indent to use in generated build file. */
  private static final String INDENT = "  ";

  /** Properties that should be listed last in the declaration of a build rule. */
  private static final ImmutableSet<String> LAST_PROPERTIES = ImmutableSet.of("deps", "visibility");

  @Option(name = "--type",
      aliases = { "-t" },
      usage = "The types of rule to filter by")
  @Nullable
  private List<String> types = null;

  @Option(name = "--json", usage = "Print JSON representation of each rule")
  private boolean json;

  @Argument
  private List<String> arguments = Lists.newArrayList();

  public List<String> getArguments() {
    return arguments;
  }

  @VisibleForTesting
  void setArguments(List<String> arguments) {
    this.arguments = arguments;
  }

  public ImmutableSet<String> getTypes() {
    return types == null ? ImmutableSet.<String>of() : ImmutableSet.copyOf(types);
  }

  @Override
  public String getShortDescription() {
    return "List build rule definitions resulting from expanding macros.";
  }

  @Override
  public int runWithoutHelp(CommandRunnerParams params) throws IOException, InterruptedException {
    ProjectFilesystem projectFilesystem = params.getCell().getFilesystem();
    try (ProjectBuildFileParser parser = params.getCell().createBuildFileParser(
        new ConstructorArgMarshaller(new DefaultTypeCoercerFactory(
            params.getObjectMapper())),
        params.getConsole(),
        params.getBuckEventBus(),
        /* ignoreBuckAutodepsFiles */ false)) {
      PrintStream out = params.getConsole().getStdOut();
      for (String pathToBuildFile : getArguments()) {
        if (!json) {
          // Print a comment with the path to the build file.
          out.printf("# %s\n\n", pathToBuildFile);
        }

        // Resolve the path specified by the user.
        Path path = Paths.get(pathToBuildFile);
        if (!path.isAbsolute()) {
          Path root = projectFilesystem.getRootPath();
          path = root.resolve(path);
        }

        // Parse the rules from the build file.
        List<Map<String, Object>> rawRules;
        try {
          rawRules = parser.getAll(path);
        } catch (BuildFileParseException e) {
          throw new HumanReadableException(e);
        }

        // Format and print the rules from the raw data, filtered by type.
        final ImmutableSet<String> types = getTypes();
        Predicate<String> includeType = new Predicate<String>() {
          @Override
          public boolean apply(String type) {
            return types.isEmpty() || types.contains(type);
          }
        };
        printRulesToStdout(params, rawRules, includeType);
      }
    } catch (BuildFileParseException e) {
      throw new HumanReadableException("Unable to create parser");
    }

    return 0;
  }

  @Override
  public boolean isReadOnly() {
    return true;
  }

  private void printRulesToStdout(
      CommandRunnerParams params,
      List<Map<String, Object>> rawRules,
      final Predicate<String> includeType) throws IOException {
    final PrintStream stdOut = params.getConsole().getStdOut();

    Iterable<Map<String, Object>> filteredRules = FluentIterable
        .from(rawRules)
        .filter(new Predicate<Map<String, Object>>() {
          @Override
          public boolean apply(Map<String, Object> rawRule) {
            String type = (String) rawRule.get(BuckPyFunction.TYPE_PROPERTY_NAME);
            return includeType.apply(type);
          }
        });

    if (json) {
      Map<String, Object> rulesKeyedByName = new HashMap<>();
      for (Map<String, Object> rawRule : filteredRules) {
        String name = (String) rawRule.get("name");
        Preconditions.checkNotNull(name);
        rulesKeyedByName.put(name, rawRule);
      }

      // We create a new JsonGenerator that does not close the stream.
      ObjectMapper mapper = params.getObjectMapper();
      JsonFactory factory = mapper.getFactory();
      try (JsonGenerator generator = factory.createGenerator(stdOut)
          .disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET)
          .useDefaultPrettyPrinter()) {
        mapper.writeValue(generator, rulesKeyedByName);
      }
      stdOut.print('\n');
    } else {
      for (Map<String, Object> rawRule : filteredRules) {
        printRuleAsPythonToStdout(stdOut, rawRule);
      }
    }
  }

  private void printRuleAsPythonToStdout(PrintStream out, Map<String, Object> rawRule) {
    String type = (String) rawRule.get(BuckPyFunction.TYPE_PROPERTY_NAME);
    out.printf("%s(\n", type);

    // The properties in the order they should be displayed for this rule.
    LinkedHashSet<String> properties = Sets.newLinkedHashSet();

    // Always display the "name" property first.
    properties.add("name");

    // Add the properties specific to the rule.
    SortedSet<String> customProperties = Sets.newTreeSet();
    for (String key : rawRule.keySet()) {
      // Ignore keys that start with "buck.".
      if (!(key.startsWith(BuckPyFunction.INTERNAL_PROPERTY_NAME_PREFIX) ||
          LAST_PROPERTIES.contains(key))) {
        customProperties.add(key);
      }
    }
    properties.addAll(customProperties);

    // Add common properties that should be displayed last.
    properties.addAll(Sets.intersection(LAST_PROPERTIES, rawRule.keySet()));

    // Write out the properties and their corresponding values.
    for (String property : properties) {
      String displayValue = createDisplayString(INDENT, rawRule.get(property));
      out.printf("%s%s = %s,\n", INDENT, property, displayValue);
    }

    // Close the rule definition.
    out.printf(")\n\n");
  }

  /**
   * @param value in a Map returned by {@link ProjectBuildFileParser#getAll(Path)}.
   * @return a string that represents the Python equivalent of the value.
   */
  @VisibleForTesting
  static String createDisplayString(@Nullable Object value) {
    return createDisplayString("", value);
  }

  static String createDisplayString(String indent, @Nullable Object value) {
    if (value == null) {
      return "None";
    } else if (value instanceof Boolean) {
      return MoreStrings.capitalize(value.toString());
    } else if (value instanceof String) {
      return Escaper.escapeAsPythonString(value.toString());
    } else if (value instanceof Number) {
      return value.toString();
    } else if (value instanceof List) {
      StringBuilder out = new StringBuilder("[\n");

      String indentPlus1 = indent + INDENT;
      for (Object item : (List<?>) value) {
        out.append(indentPlus1)
            .append(createDisplayString(indentPlus1, item))
            .append(",\n");
      }

      out.append(indent).append("]");
      return out.toString();
    } else if (value instanceof Map) {
      StringBuilder out = new StringBuilder("{\n");

      String indentPlus1 = indent + INDENT;
      for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
        out.append(indentPlus1)
            .append(createDisplayString(indentPlus1, entry.getKey()))
            .append(": ")
            .append(createDisplayString(indentPlus1, entry.getValue()))
            .append(",\n");
      }

      out.append(indent).append("}");
      return out.toString();
    } else {
      throw new IllegalStateException();
    }
  }

}
