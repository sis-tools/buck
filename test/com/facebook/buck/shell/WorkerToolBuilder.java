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

package com.facebook.buck.shell;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.AbstractNodeBuilder;
import com.google.common.base.Optional;

import javax.annotation.Nullable;

public class WorkerToolBuilder extends AbstractNodeBuilder<WorkerToolDescription.Arg> {
  private WorkerToolBuilder(BuildTarget target) {
    super(new WorkerToolDescription(), target);
  }

  public static WorkerToolBuilder newWorkerToolBuilder(BuildTarget target) {
    return new WorkerToolBuilder(target);
  }

  public WorkerToolBuilder setExe(BuildTarget exe) {
    arg.exe = exe;
    return this;
  }

  public WorkerToolBuilder setArgs(@Nullable String args) {
    arg.args = Optional.fromNullable(args);
    return this;
  }

  public WorkerToolBuilder setMaxWorkers(Optional<Integer> maxWorkers) {
    arg.maxWorkers = maxWorkers;
    return this;
  }
}
