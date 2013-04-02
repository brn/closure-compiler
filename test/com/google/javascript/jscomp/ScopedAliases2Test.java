/*
 * Copyright 2010 The Closure Compiler Authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.javascript.jscomp;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.javascript.jscomp.CompilerOptions.AliasTransformation;
import com.google.javascript.jscomp.CompilerOptions.AliasTransformationHandler;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.SourcePosition;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * Tests for {@link ScopedAliases}
 *
 * @author robbyw@google.com (Robby Walker)
 */
public class ScopedAliases2Test extends CompilerTestCase {

  private static final String GOOG_SCOPE_START_BLOCK =
      "goog.scope(function() {";
  private static final String GOOG_SCOPE_END_BLOCK = "});";

  private static String EXTERNS = "var window;";

  AliasTransformationHandler transformationHandler =
      CompilerOptions.NULL_ALIAS_TRANSFORMATION_HANDLER;

  public ScopedAliases2Test() {
    super(EXTERNS);
  }

  private void testScoped(String code, String expected) {
    test(GOOG_SCOPE_START_BLOCK + code + GOOG_SCOPE_END_BLOCK, expected);
  }

  public void testOneLevel() {
    testScoped("var g = goog;g.dom.createElement(g.dom.TagName.DIV);",
        "goog.dom.createElement(goog.dom.TagName.DIV);");
  }

  
  @Override
  protected ScopedAliases getProcessor(Compiler compiler) {
    return new ScopedAliases(compiler, null, transformationHandler);
  }
}
