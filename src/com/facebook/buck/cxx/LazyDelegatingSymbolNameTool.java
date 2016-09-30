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

package com.facebook.buck.cxx;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleParams;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.SourcePath;
import com.facebook.buck.rules.SourcePathResolver;
import com.google.common.base.Supplier;
import com.google.common.base.Suppliers;

public class LazyDelegatingSymbolNameTool implements SymbolNameTool {
  private Supplier<SymbolNameTool> delegate;

  public LazyDelegatingSymbolNameTool(Supplier<SymbolNameTool> delegate) {
    this.delegate = Suppliers.memoize(delegate);
  }

  @Override
  public SourcePath createUndefinedSymbolsFile(
      BuildRuleParams baseParams,
      BuildRuleResolver ruleResolver,
      SourcePathResolver pathResolver,
      BuildTarget target,
      Iterable<? extends SourcePath> linkerInputs) {
    return delegate.get().createUndefinedSymbolsFile(
        baseParams,
        ruleResolver,
        pathResolver,
        target,
        linkerInputs);
  }
}
