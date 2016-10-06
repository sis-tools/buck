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
package com.facebook.buck.cxx;

import com.facebook.buck.rules.Tool;
import com.google.common.collect.ImmutableList;

public class WindowsCompiler extends DefaultCompiler {

  public WindowsCompiler(Tool tool) {
    super(tool);
  }

  @Override
  public boolean isDependencyFileSupported() {
    return false;
  }

  @Override
  public ImmutableList<String> outputArgs(String outputPath) {
    return ImmutableList.of("/Fo" + outputPath);
  }

  @Override
  public boolean isArgFileSupported() {
    return false;
  }

  @Override
  public ImmutableList<String> outputDependenciesArgs(String outputPath) {
    return ImmutableList.of();
  }

  @Override
  public ImmutableList<String> languageArgs(String inputLanguage) {
    return ImmutableList.of();
  }
}
