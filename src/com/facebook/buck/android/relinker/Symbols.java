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
package com.facebook.buck.android.relinker;

import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.Tool;
import com.facebook.buck.util.Console;
import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.ProcessExecutorParams;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.CharStreams;
import com.google.common.io.LineProcessor;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Symbols {
  public ImmutableSet<String> undefined;
  public ImmutableSet<String> global;
  public ImmutableSet<String> all;

  private Symbols(
      ImmutableSet<String> undefined,
      ImmutableSet<String> global,
      ImmutableSet<String> all) {
    this.undefined = undefined;
    this.global = global;
    this.all = all;
  }

  // See `man objdump`.
  static final Pattern SYMBOL_RE = Pattern.compile(
      "\\s*" +
          "(?<address>[0-9a-f]{8})" +
          " " +
          "(?<global>.)" +
          "(?<weak>.)" +
          "(?<constructor>.)" +
          "(?<warning>.)" +
          "(?<indirect>.)" +
          "(?<debugging>.)" +
          "(?<type>.)" +
          "\\s*" +
          "(?<section>[^\\s]*)" +
          "\\s*" +
          "(?<align>[0-9a-f]*)" +
          "\\s*" +
          "((?<lib>[^\\s]*)\\s+)?" +
          "(?<name>[^\\s]+)");

  static class SymbolInfo {
    String symbol;
    boolean isUndefined;
    boolean isGlobal;
    SymbolInfo(String symbol, boolean undefined, boolean global) {
      this.symbol = symbol;
      this.isUndefined = undefined;
      this.isGlobal = global;
    }
  }

  public static SymbolInfo extractSymbolInfo(String line) {
    Matcher m = SYMBOL_RE.matcher(line);
    if (!m.matches()) {
      return null;
    }
    return new SymbolInfo(
        m.group("name"),
        "*UND*".equals(m.group("section")),
        "gu!".contains(m.group("global")));
  }

  public static Symbols getSymbols(
      Tool objdump,
      SourcePathResolver resolver,
      Path lib) throws IOException, InterruptedException {
    final ImmutableSet.Builder<String> undefined = ImmutableSet.builder();
    final ImmutableSet.Builder<String> global = ImmutableSet.builder();
    final ImmutableSet.Builder<String> all = ImmutableSet.builder();

    runObjdump(objdump, resolver, lib, ImmutableList.of("-T"),
        new LineProcessor<Void>() {
          @Override
          public boolean processLine(String line) throws IOException {
            SymbolInfo si = extractSymbolInfo(line);
            if (si == null) {
              return true;
            }
            if (si.isUndefined) {
              undefined.add(si.symbol);
            } else if (si.isGlobal) {
              global.add(si.symbol);
            }
            all.add(si.symbol);
            return true;
          }

          @Override
          public Void getResult() {
            return null;
          }
        });

    return new Symbols(undefined.build(), global.build(), all.build());
  }

  public static ImmutableSet<String> getDtNeeded(
      Tool objdump,
      SourcePathResolver resolver,
      Path lib) throws IOException, InterruptedException {
    final ImmutableSet.Builder<String> builder = ImmutableSet.builder();

    final Pattern re = Pattern.compile("^ *NEEDED *(\\S*)$");

    runObjdump(objdump, resolver, lib, ImmutableList.of("-p"),
        new LineProcessor<Void>() {
          @Override
          public boolean processLine(String line) throws IOException {
            Matcher m = re.matcher(line);
            if (!m.matches()) {
              return true;
            }
            builder.add(m.group(1));
            return true;
          }

          @Override
          public Void getResult() {
            return null;
          }
        });

    return builder.build();
  }

  private static void runObjdump(
      Tool objdump,
      SourcePathResolver resolver,
      Path lib,
      ImmutableList<String> flags,
      LineProcessor<Void> lineProcessor) throws IOException, InterruptedException {
    ImmutableList<String> args = ImmutableList.<String>builder()
        .addAll(objdump.getCommandPrefix(resolver))
        .addAll(flags)
        .add(lib.toString())
        .build();

    ProcessExecutorParams params = ProcessExecutorParams.builder()
        .setCommand(args)
        .setRedirectError(ProcessBuilder.Redirect.INHERIT)
        .build();
    ProcessExecutor executor = new ProcessExecutor(Console.createNullConsole());
    ProcessExecutor.LaunchedProcess p = executor.launchProcess(params);
    BufferedReader output = new BufferedReader(new InputStreamReader(p.getInputStream()));
    CharStreams.readLines(output, lineProcessor);
    ProcessExecutor.Result result = executor.waitForLaunchedProcess(p);

    if (result.getExitCode() != 0) {
      throw new RuntimeException(result.getMessageForUnexpectedResult("Objdump"));
    }
  }
}
