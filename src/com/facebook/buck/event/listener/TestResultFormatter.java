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

package com.facebook.buck.event.listener;

import com.facebook.buck.io.MoreFiles;
import com.facebook.buck.log.Logger;
import com.facebook.buck.test.TestCaseSummary;
import com.facebook.buck.test.TestResultSummary;
import com.facebook.buck.test.TestResultSummaryVerbosity;
import com.facebook.buck.test.TestResults;
import com.facebook.buck.test.TestStatusMessage;
import com.facebook.buck.test.selectors.TestSelectorList;
import com.facebook.buck.util.Ansi;
import com.facebook.buck.util.Verbosity;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Splitter;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

public class TestResultFormatter {

  private static final int DEFAULT_MAX_LOG_LINES = 50;
  private static final Logger LOG = Logger.get(TestResultFormatter.class);

  private final Ansi ansi;
  private final Verbosity verbosity;
  private final TestResultSummaryVerbosity summaryVerbosity;
  private final Locale locale;
  private final Optional<Path> testLogsPath;
  private final TimeZone timeZone;

  public enum FormatMode {
      BEFORE_TEST_RUN,
      AFTER_TEST_RUN
  }

  public TestResultFormatter(
      Ansi ansi,
      Verbosity verbosity,
      TestResultSummaryVerbosity summaryVerbosity,
      Locale locale,
      Optional<Path> testLogsPath) {
    this(ansi, verbosity, summaryVerbosity, locale, testLogsPath, TimeZone.getDefault());
  }

  @VisibleForTesting
  TestResultFormatter(
      Ansi ansi,
      Verbosity verbosity,
      TestResultSummaryVerbosity summaryVerbosity,
      Locale locale,
      Optional<Path> testLogsPath,
      TimeZone timeZone) {
    this.ansi = ansi;
    this.verbosity = verbosity;
    this.summaryVerbosity = summaryVerbosity;
    this.locale = locale;
    this.testLogsPath = testLogsPath;
    this.timeZone = timeZone;
  }

  public void runStarted(
      ImmutableList.Builder<String> addTo,
      boolean isRunAllTests,
      TestSelectorList testSelectorList,
      boolean shouldExplainTestSelectorList,
      ImmutableSet<String> targetNames,
      FormatMode formatMode) {
    String prefix;
    if (formatMode == FormatMode.BEFORE_TEST_RUN) {
      prefix = "TESTING";
    } else {
      prefix = "RESULTS FOR";
    }
    if (!testSelectorList.isEmpty()) {
      addTo.add(prefix + " SELECTED TESTS");
      if (shouldExplainTestSelectorList) {
        addTo.addAll(testSelectorList.getExplanation());
      }
    } else if (isRunAllTests) {
      addTo.add(prefix + " ALL TESTS");
    } else {
      addTo.add(prefix + " " + Joiner.on(' ').join(targetNames));
    }
  }

  /** Writes a detailed summary that ends with a trailing newline. */
  public void reportResult(ImmutableList.Builder<String> addTo, TestResults results) {
    if (
        verbosity.shouldPrintBinaryRunInformation() &&
            results.getTotalNumberOfTests() > 1) {
      addTo.add("");
      addTo.add(
          String.format(
              locale,
              "Results for %s (%d/%d) %s",
              results.getBuildTarget().getFullyQualifiedName(),
              results.getSequenceNumber(),
              results.getTotalNumberOfTests(), verbosity));
    }

    boolean shouldReportLogSummaryAfterTests = false;

    for (TestCaseSummary testCase : results.getTestCases()) {
      StringBuilder oneLineSummary = new StringBuilder(
          testCase.getOneLineSummary(locale, results.getDependenciesPassTheirTests(), ansi));
      addTo.add(oneLineSummary.toString());

      // Don't print the full error if there were no failures (so only successes and assumption
      // violations)
      if (testCase.isSuccess()) {
        continue;
      }

      for (TestResultSummary testResult : testCase.getTestResults()) {
        if (!results.getDependenciesPassTheirTests()) {
          continue;
        }

        // Report on either explicit failure
        if (!testResult.isSuccess()) {
          shouldReportLogSummaryAfterTests = true;
          reportResultSummary(addTo, testResult);
        }
      }
    }

    if (shouldReportLogSummaryAfterTests && verbosity != Verbosity.SILENT) {
      for (Path testLogPath : results.getTestLogPaths()) {
        if (Files.exists(testLogPath)) {
          reportLogSummary(
              locale,
              addTo,
              testLogPath,
              summaryVerbosity.getMaxDebugLogLines().or(DEFAULT_MAX_LOG_LINES));
        }
      }
    }
  }

  private static void reportLogSummary(
      Locale locale,
      ImmutableList.Builder<String> addTo,
      Path logPath,
      int maxLogLines) {
    if (maxLogLines <= 0) {
      return;
    }
    try {
      List<String> logLines = Files.readAllLines(
          logPath,
          StandardCharsets.UTF_8);
      if (logLines.isEmpty()) {
        return;
      }
      addTo.add("====TEST LOGS====");

      int logLinesStartIndex;
      if (logLines.size() > maxLogLines) {
        addTo.add(String.format(locale, "Last %d test log lines from %s:", maxLogLines, logPath));
        logLinesStartIndex = logLines.size() - maxLogLines;
      } else {
        addTo.add(String.format(locale, "Logs from %s:", logPath));
        logLinesStartIndex = 0;
      }
      addTo.addAll(logLines.subList(logLinesStartIndex, logLines.size()));
    } catch (IOException e) {
      LOG.error(e, "Could not read test logs from %s", logPath);
    }
  }

  public void reportResultSummary(ImmutableList.Builder<String> addTo,
                                  TestResultSummary testResult) {
    addTo.add(
        String.format(
            locale, "%s %s %s: %s",
            testResult.getType().toString(),
            testResult.getTestCaseName(),
            testResult.getTestName(),
            testResult.getMessage()));

    if (testResult.getStacktrace() != null) {
      for (String line : Splitter.on("\n").split(testResult.getStacktrace())) {
        if (line.contains(testResult.getTestCaseName())) {
          addTo.add(ansi.asErrorText(line));
        } else {
          addTo.add(line);
        }
      }
    }

    if (summaryVerbosity.getIncludeStdOut() && testResult.getStdOut() != null) {
      addTo.add("====STANDARD OUT====", testResult.getStdOut());
    }

    if (summaryVerbosity.getIncludeStdErr() && testResult.getStdErr() != null) {
      addTo.add("====STANDARD ERR====", testResult.getStdErr());
    }
  }

  public void runComplete(
      ImmutableList.Builder<String> addTo,
      List<TestResults> completedResults,
      List<TestStatusMessage> testStatusMessages) {
    // Print whether each test succeeded or failed.
    boolean isAllTestsPassed = true;
    boolean isAnyAssumptionViolated = false;
    ListMultimap<TestResults, TestCaseSummary> failingTests = ArrayListMultimap.create();

    int numFailures = 0;
    int numTestResults = 0;
    ImmutableList.Builder<Path> testLogPathsBuilder = ImmutableList.builder();

    for (TestResults summary : completedResults) {
      testLogPathsBuilder.addAll(summary.getTestLogPaths());
      if (!summary.isSuccess()) {
        isAllTestsPassed = false;
        numFailures += summary.getFailureCount();
        failingTests.putAll(summary, summary.getFailures());
      }
      for (TestCaseSummary testCaseSummary : summary.getTestCases()) {
        numTestResults += testCaseSummary.getFailureCount() + testCaseSummary.getPassedCount();

        if (testCaseSummary.hasAssumptionViolations()) {
          // Only count skipped tests as "run" if there was a dynamic failure,
          // otherwise, we consider skipped tests as "not run"
          numTestResults += testCaseSummary.getSkippedCount();
          isAnyAssumptionViolated = true;
          break;
        }
      }
    }

    ImmutableList<Path> testLogPaths = testLogPathsBuilder.build();

    // Print the summary of the test results.
    if (numTestResults == 0) {
      addTo.add(ansi.asHighlightedFailureText("TESTS PASSED (NO TESTS RAN)"));
    } else if (isAllTestsPassed) {
      if (testLogsPath.isPresent() && verbosity != Verbosity.SILENT) {
        try {
          if (MoreFiles.concatenateFiles(testLogsPath.get(), testLogPaths)) {
            addTo.add("Updated test logs: " + testLogsPath.get().toString());
          }
        } catch (IOException e) {
          LOG.warn(e, "Could not concatenate test logs %s to %s", testLogPaths, testLogsPath.get());
        }
      }
      if (isAnyAssumptionViolated) {
        addTo.add(ansi.asHighlightedWarningText("TESTS PASSED (with some assumption violations)"));
      } else {
        addTo.add(ansi.asHighlightedSuccessText("TESTS PASSED"));
      }
    } else {
      if (!testStatusMessages.isEmpty()) {
        addTo.add("====TEST STATUS MESSAGES====");
        SimpleDateFormat timestampFormat = new SimpleDateFormat(
            "[yyyy-MM-dd HH:mm:ss.SSS]",
            Locale.US);
        timestampFormat.setTimeZone(timeZone);

        for (TestStatusMessage testStatusMessage : testStatusMessages) {
          addTo.add(
              String.format(
                  locale,
                  "%s[%s] %s",
                  timestampFormat.format(new Date(testStatusMessage.getTimestampMillis())),
                  testStatusMessage.getLevel(),
                  testStatusMessage.getMessage()));
        }
      }

      addTo.add(
          ansi.asHighlightedFailureText(
              String.format(
                  locale,
                  "TESTS FAILED: %d %s",
                  numFailures,
                  numFailures == 1 ? "FAILURE" : "FAILURES")));
      for (TestResults results : failingTests.keySet()) {
        addTo.add("Failed target: " + results.getBuildTarget().getFullyQualifiedName());
        for (TestCaseSummary summary : failingTests.get(results)) {
          addTo.add(summary.toString());
        }
      }
    }
  }
}
