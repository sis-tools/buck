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

package com.facebook.buck.step;

import com.facebook.buck.util.ProcessExecutor;
import com.facebook.buck.util.immutables.BuckStyleTuple;
import com.google.common.base.Optional;
import org.immutables.value.Value;

@Value.Immutable
@BuckStyleTuple
abstract class AbstractStepExecutionResult {

  public abstract int getExitCode();
  public abstract Optional<String> getStderr();

  @Value.Derived
  public Boolean isSuccess() {
    return getExitCode() == 0;
  }

  public static StepExecutionResult of(int exitCode) {
    return StepExecutionResult.of(exitCode, Optional.<String>absent());
  }

  public static StepExecutionResult of(ProcessExecutor.Result result) {
    return StepExecutionResult.of(result.getExitCode(), result.getStderr());
  }

  public static final StepExecutionResult SUCCESS = StepExecutionResult.of(0);
  public static final StepExecutionResult ERROR = StepExecutionResult.of(1);

}
