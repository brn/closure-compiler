package com.google.javascript.jscomp;

import java.io.File;
import java.util.Collection;
import java.util.List;

import com.google.common.collect.Lists;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.Node;

public class CampModuleProcessorTest extends CompilerTestCase {

  private static String EXTERNS = "var window;";

  private static String MODULE_PREFIX = "camp.module('test.foo.bar.baz', function(exports){";

  private static String MODULE_SUFFIX = "});";


  public CampModuleProcessorTest() {
    super(EXTERNS);
  }


  private String module(String... codes) {
    StringBuilder builder = new StringBuilder();
    for (String code : codes) {
      builder.append(code + "\n");
    }
    return MODULE_PREFIX + builder.toString() + MODULE_SUFFIX;
  }


  private String code(String... codes) {
    StringBuilder builder = new StringBuilder();
    for (String code : codes) {
      builder.append(code + "\n");
    }
    return builder.toString();
  }


  private JSModule[] readFrom(String... pathList) {
    JSModule[] modules = new JSModule[pathList.length];
    for (int i = 0, size = pathList.length; i < size; i++) {
      JSModule module = modules[i] = new JSModule(pathList[i]);
      module.add(SourceFile.fromFile(new File(pathList[i])));
    }
    return modules;
  }


  public void testLvalueAccessOfCampModule() {
    test(
        module(
        "camp.module = {};"
        ),
        code(
        "camp.module = {}"
        ));
  }


  public void testLvalueAccessOfCampUsing() {
    test(
        module(
        "camp.using = {};"
        ),
        code(
        "camp.using = {}"
        ));
  }


  public void testSimpleAlias() {
    test(
        module(
        "exports.Test = function(){};"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test');",
            "test.foo.bar.baz.Test = function(){};"
        ));
  }


  public void testAliasPrototype() {
    test(
        module(
            "exports.Test1 = function(){}",
            "exports.Test1.prototype.test = function(){};",
            "exports.Test1.prototype.TEST_VALUE = 100;"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test1')",
            "test.foo.bar.baz.Test1 = function(){};",
            "test.foo.bar.baz.Test1.prototype.test = function(){};",
            "test.foo.bar.baz.Test1.prototype.TEST_VALUE = 100;"
        ));
  }


  public void testMultiAlias() {
    test(
        module(
            "exports.Test1 = function(){};",
            "exports.Test1.prototype.test = function(){};",
            "exports.Test2 = function(){};",
            "exports.Test2.prototype.test = function(){};"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test2');",
            "goog.provide('test.foo.bar.baz.Test1');",
            "test.foo.bar.baz.Test1 = function(){};",
            "test.foo.bar.baz.Test1.prototype.test = function(){};",
            "test.foo.bar.baz.Test2 = function(){};",
            "test.foo.bar.baz.Test2.prototype.test = function(){};"
        ));
  }


  public void testInClosure() {
    test(
        module(
            "exports.Test1 = function(){};",
            "exports.Test1.prototype.test = function(){};",
            "exports.Test2 = function(){this._test1 = new exports.Test1();};",
            "exports.Test2.prototype.test = function(){return this._test1;};"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test2');",
            "goog.provide('test.foo.bar.baz.Test1');",
            "test.foo.bar.baz.Test1 = function(){};",
            "test.foo.bar.baz.Test1.prototype.test = function(){};",
            "test.foo.bar.baz.Test2 = function(){this._test1 = new test.foo.bar.baz.Test1();};",
            "test.foo.bar.baz.Test2.prototype.test = function(){return this._test1;};"
        ));
  }


  public void testModulePrivate() {
    test(
        module(
            "exports.Test1 = function(){};",
            "exports.Test1.prototype.test = function(){};",
            "function Test2(){}",
            "Test2.prototype.test = function(){};"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test1');",
            "test.foo.bar.baz.Test1 = function(){}",
            "test.foo.bar.baz.Test1.prototype.test = function(){};",
            "function test_foo_bar_baz_0_Test2(){}",
            "test_foo_bar_baz_0_Test2.prototype.test = function(){};"
        ));
  }


  public void testLocalToExportsAlias() {
    test(
        module(
            "function Test(){}",
            "Test.prototype.test = function(){};",
            "exports.Test = Test;"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test');",
            "function test_foo_bar_baz_0_Test(){}",
            "test_foo_bar_baz_0_Test.prototype.test = function(){};",
            "test.foo.bar.baz.Test = test_foo_bar_baz_0_Test"
        ));
  }


  public void testLocalToExportsAliasAndModulePrivate() {
    test(
        module(
            "function Test1(){}",
            "Test1.prototype.test = function(){};",
            "function Test2(){}",
            "Test2.prototype.test = function(){};",
            "exports.Test = Test2;"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test');",
            "function test_foo_bar_baz_0_Test1(){}",
            "test_foo_bar_baz_0_Test1.prototype.test = function(){};",
            "function test_foo_bar_baz_0_Test2(){}",
            "test_foo_bar_baz_0_Test2.prototype.test = function(){};",
            "test.foo.bar.baz.Test = test_foo_bar_baz_0_Test2"
        ));
  }


  public void testExternalLocalToExportsAliasAndModulePrivate() {
    test(
        "function Test2(){}" +
            module(
                "function Test1(){}",
                "Test1.prototype.test = function(){};",
                "function Test2(){}",
                "Test2.prototype.test = function(){};",
                "exports.Test = Test2;"
            ),
        code(
            "function Test2(){}",
            "goog.provide('test.foo.bar.baz.Test');",
            "function test_foo_bar_baz_0_Test1(){}",
            "test_foo_bar_baz_0_Test1.prototype.test = function(){};",
            "function test_foo_bar_baz_0_Test2(){}",
            "test_foo_bar_baz_0_Test2.prototype.test = function(){};",
            "test.foo.bar.baz.Test = test_foo_bar_baz_0_Test2;"
        ));
  }


  public void testExportsMain() {
    test(
        module(
        "exports.main = function(){};"
        ),
        code(
        "(function(){})();"
        ));
  }


  public void testExportsMainVariable() {
    test(
        module(
            "var hoge = function(){};",
            "exports.main = hoge"
        ),
        code(
            "var test_foo_bar_baz_0_hoge = function(){};",
            "test_foo_bar_baz_0_hoge();"
        ));
  }


  public void testExportsMainLocation() {
    test(
        module(
            "var hoge = function(){};",
            "exports.main = hoge;",
            "exports.Test = function(){}"
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test');",
            "var test_foo_bar_baz_0_hoge = function(){};",
            "test.foo.bar.baz.Test = function(){};",
            "test_foo_bar_baz_0_hoge();"
        ));
  }


  public void testUsing() {
    JSModule[] modules = readFrom(
        "testJsFiles/campModuleProcessorTest/usingProvideTest.js",
        "testJsFiles/campModuleProcessorTest/usingRequireTest.js"
        );

    String[] expected = {
        code(
            "goog.provide('using.provide.Test');",
            "using.provide.Test = function(){};",
            "using.provide.Test.prototype.test = function(){};"
        ),
        code(
            "goog.provide('using.require.Test')",
            "goog.require('using.provide.Test');",
            "using.require.Test = function(){this._test = new using.provide.Test();}"
        )
    };
    test(modules, expected);
  }


  public void testNoAliasUsingCall() {
    test(
        module(
            "camp.using('test.foo.Using');",
            "exports.A = function(){};"
        ),
        code(
            "goog.provide('test.foo.bar.baz.A');",
            "goog.require('test.foo.Using');",
            "test.foo.bar.baz.A = function(){};"
        ));
  }


  public void testUsingStatic() {
    test(
        module(
            "var utils = camp.using('test.foo.Using').utils",
            "utils.foo();"
        ),
        code(
            "goog.require('test.foo.Using');",
            "test.foo.Using.utils.foo();"
        ));
  }


  public void testUsingStatic2Level() {
    test(
        module(
            "var b = camp.using('test.foo.Using').a.b",
            "b.foo();"
        ),
        code(
            "goog.require('test.foo.Using');",
            "test.foo.Using.a.b.foo();"
        ));
  }


  public void testUsingStaticGetElem() {
    test(
        module(
            "var a = camp.using('test.foo.Using')['a']",
            "a.foo();"
        ),
        code(
            "goog.require('test.foo.Using');",
            "test.foo.Using['a'].foo();"
        ));
  }


  public void testUsingStaticGetElem2Level() {
    test(
        module(
            "var b = camp.using('test.foo.Using')['a']['b']",
            "b.foo();"
        ),
        code(
            "goog.require('test.foo.Using');",
            "test.foo.Using['a']['b'].foo();"
        ));
  }


  private void testTypes(String module, String code) {
    test(module, code);
    verifyTypes();
  }


  private void verifyTypes() {
    Compiler lastCompiler = getLastCompiler();
    new TypeVerifyingPass(lastCompiler).process(lastCompiler.externsRoot,
        lastCompiler.jsRoot);
  }


  private void testTypeForExports(String tag) {
    testTypeForExports(tag, "exports.Test", "test.foo.bar.baz.Test");
  }


  private void testTypeForExports(String tag, String type) {
    testTypeForExports(tag, String.format(type, "exports.Test"),
        String.format(type, "test.foo.bar.baz.Test"));
  }


  private void testTypeForExports(String tag, String actual, String expected) {
    testTypes(
        module(
            "exports.Test = function(){};",
            String.format("/**@%s {%s} actual*/ var a = 0;", tag, actual),
            String.format("/**@%s {%s} expected*/ var b = 0;", tag, expected)
        ),
        code(
            "goog.provide('test.foo.bar.baz.Test');",
            "test.foo.bar.baz.Test = function(){};",
            "var test_foo_bar_baz_0_a = 0;",
            "var test_foo_bar_baz_0_b = 0;"
        ));
  }


  private void testTypeForUsing(String tag) {
    testTypeForUsing(tag, "exports.Test", "test.foo.bar.baz.Test");
  }


  private void testTypeForUsing(String tag, String type) {
    testTypeForUsing(tag, String.format(type, "exports.Test"),
        String.format(type, "test.foo.bar.baz.Test"));
  }


  private void testTypeForUsing(String tag, String actual, String expected) {
    testTypes(
        module(
            "var Test = camp.using('test.foo.bar.baz.Test');",
            String.format("/**@%s {%s} actual*/ var a = 0;", tag, actual),
            String.format("/**@%s {%s} expected*/ var b = 0;", tag, expected)
        ),
        code(
        "goog.require('test.foo.bar.baz.Test');",
        "var test_foo_bar_baz_0_a = 0;",
        "var test_foo_bar_baz_0_b = 0;"
        ));
  }


  private void testTypeForLocal(String tag) {
    testTypeForLocal(tag, "Test", "test_foo_bar_baz_0_Test");
  }


  private void testTypeForLocal(String tag, String type) {
    testTypeForLocal(tag, String.format(type, "Test"),
        String.format(type, "test_foo_bar_baz_0_Test"));
  }


  private void testTypeForLocal(String tag, String actual, String expected) {
    testTypes(
        module(
            "var Test = function(){}",
            String.format("/**@%s {%s} actual*/ var a = 0;", tag, actual),
            String.format("/**@%s {%s} expected*/ var b = 0;", tag, expected)
        ),
        code(
        "var test_foo_bar_baz_0_Test = function(){};",
        "var test_foo_bar_baz_0_a = 0;",
        "var test_foo_bar_baz_0_b = 0;"
        ));
  }


  public void testTypeType() {
    testTypeForExports("type");
  }


  public void testParamType() {
    testTypeForExports("param");
  }


  public void testExtendsType() {
    testTypeForExports("extends");
  }


  public void testImplementsType() {
    testTypeForExports("implements");
  }


  public void testPrivateType() {
    testTypeForExports("private");
  }


  public void testConstType() {
    testTypeForExports("const");
  }


  public void testEnumType() {
    testTypeForExports("enum");
  }


  public void testThisType() {
    testTypeForExports("this");
  }


  public void testReturnType() {
    testTypeForExports("return");
  }


  public void testThrowsType() {
    testTypeForExports("throws");
  }


  public void testSubType() {
    testTypeForExports("type", "%s.Subtype");
  }


  public void testTypedef() {
    testTypeForExports("typedef");
  }


  public void testArrayType() {
    testTypeForExports("type", "Array.<%s>");
  }


  public void testObjectType() {
    testTypeForExports("type", "Object.<string, %s>");
  }


  public void testUnionType() {
    testTypeForExports("type", "(string|%s)");
  }


  public void testFunctionNewType() {
    testTypeForExports("type", "function(new:%s)");
  }


  public void testFunctionThisType() {
    testTypeForExports("type", "function(this:%s)");
  }


  public void testFunctionReturnType() {
    testTypeForExports("type", "function():%s");
  }


  public void testUsingTypeType() {
    testTypeForUsing("type");
  }


  public void testUsingParamType() {
    testTypeForUsing("param");
  }


  public void testUsingExtendsType() {
    testTypeForUsing("extends");
  }


  public void testUsingImplementsType() {
    testTypeForUsing("implements");
  }


  public void testUsingPrivateType() {
    testTypeForUsing("private");
  }


  public void testUsingConstType() {
    testTypeForUsing("const");
  }


  public void testUsingEnumType() {
    testTypeForUsing("enum");
  }


  public void testUsingThisType() {
    testTypeForUsing("this");
  }


  public void testUsingReturnType() {
    testTypeForUsing("return");
  }


  public void testUsingThrowsType() {
    testTypeForUsing("throws");
  }


  public void testUsingSubType() {
    testTypeForUsing("type", "%s.Subtype");
  }


  public void testUsingTypedef() {
    testTypeForUsing("typedef");
  }


  public void testUsingArrayType() {
    testTypeForUsing("type", "Array.<%s>");
  }


  public void testUsingObjectType() {
    testTypeForUsing("type", "Object.<string, %s>");
  }


  public void testUsingUnionType() {
    testTypeForUsing("type", "(string|%s)");
  }


  public void testUsingFunctionNewType() {
    testTypeForUsing("type", "function(new:%s)");
  }


  public void testUsingFunctionThisType() {
    testTypeForUsing("type", "function(this:%s)");
  }


  public void testUsingFunctionReturnType() {
    testTypeForUsing("type", "function():%s");
  }


  public void testLocalTypeType() {
    testTypeForLocal("type");
  }


  public void testLocalParamType() {
    testTypeForLocal("param");
  }


  public void testLocalExtendsType() {
    testTypeForLocal("extends");
  }


  public void testLocalImplementsType() {
    testTypeForLocal("implements");
  }


  public void testLocalPrivateType() {
    testTypeForLocal("private");
  }


  public void testLocalConstType() {
    testTypeForLocal("const");
  }


  public void testLocalEnumType() {
    testTypeForLocal("enum");
  }


  public void testLocalThisType() {
    testTypeForLocal("this");
  }


  public void testLocalReturnType() {
    testTypeForLocal("return");
  }


  public void testLocalThrowsType() {
    testTypeForLocal("throws");
  }


  public void testLocalSubType() {
    testTypeForLocal("type", "%s.Subtype");
  }


  public void testLocalTypedef() {
    testTypeForLocal("typedef");
  }


  public void testLocalArrayType() {
    testTypeForLocal("type", "Array.<%s>");
  }


  public void testLocalObjectType() {
    testTypeForLocal("type", "Object.<string, %s>");
  }


  public void testLocalUnionType() {
    testTypeForLocal("type", "(string|%s)");
  }


  public void testLocalFunctionNewType() {
    testTypeForLocal("type", "function(new:%s)");
  }


  public void testLocalFunctionThisType() {
    testTypeForLocal("type", "function(this:%s)");
  }


  public void testLocalFunctionReturnType() {
    testTypeForLocal("type", "function():%s");
  }


  private void testFailure(String code, DiagnosticType expectedError) {
    test(code, null, expectedError);
  }


  public void testModuleFirstArguments() {
    testFailure("camp.module();", CampModuleInfoCollector.MESSAGE_MODULE_FIRST_ARGUMENT_NOT_VALID);
  }


  public void testModuleSecondArguments() {
    testFailure("camp.module('test.test');",
        CampModuleInfoCollector.MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID);
  }


  public void testModuleClosureArguments() {
    testFailure("camp.module('test.test', function(){});",
        CampModuleInfoCollector.MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID);
  }


  public void testModuleClosureArgumentsSize() {
    testFailure("camp.module('test.test', function(a,b){});",
        CampModuleInfoCollector.MESSAGE_MODULE_SECOND_ARGUMENT_NOT_VALID);
  }


  public void testModuleAccess() {
    testFailure("var hoge = camp.module", CampModuleInfoCollector.MESSAGE_INVALID_ACCESS_TO_ENTITY);
  }


  public void testUsingAccess() {
    testFailure("var hoge = camp.using", CampModuleInfoCollector.MESSAGE_INVALID_ACCESS_TO_ENTITY);
  }


  public void testMainDuplicate() {
    testFailure(module("exports.main = function(){};exports.main = function(){};"),
        CampModuleInfoCollector.MESSAGE_MAIN_ALREADY_FOUNDED);
  }


  public void testModuleInClosure() {
    testFailure(
        "(function(){" +
            module("exports.Test = Test2;") + ";})();",
        CampModuleInfoCollector.MESSAGE_MODULE_NOT_ALLOWED_IN_CLOSURE);
  }


  public void testUsingOutsideOfModule() {
    testFailure("camp.using('test.foo.bar.baz.Test');",
        CampModuleInfoCollector.MESSAGE_INVALID_USE_OF_USING);
  }


  public void testStaticUsingCall() {
    testFailure(
        module("var a = camp.using('test.Using').a()"),
        CampModuleInfoCollector.MESSAGE_INVALID_PARENT_OF_USING);
  }
  
  public void testStaticUsingCallNotAlias() {
    testFailure(
        module("camp.using('test.Using').a()"),
        CampModuleInfoCollector.MESSAGE_INVALID_PARENT_OF_USING);
  }
  
  public void testUsingDirectCall() {
    testFailure(
        module("var Using = camp.using('test.Using')()"),
        CampModuleInfoCollector.MESSAGE_INVALID_PARENT_OF_USING);
  }
  
  public void testUsingDirectCallNotAlias() {
    testFailure(
        module("camp.using('test.Using')()"),
        CampModuleInfoCollector.MESSAGE_INVALID_PARENT_OF_USING);
  }


  @Override
  protected CampModuleProcessor getProcessor(Compiler compiler) {
    return new CampModuleProcessor(compiler);
  }


  private static class TypeVerifyingPass
      implements CompilerPass, NodeTraversal.Callback {
    private final Compiler compiler;

    private List<Node> actualTypes = null;


    public TypeVerifyingPass(Compiler compiler) {
      this.compiler = compiler;
    }


    @Override
    public void process(Node externs, Node root) {
      NodeTraversal.traverse(compiler, root, this);
    }


    @Override
    public boolean shouldTraverse(NodeTraversal nodeTraversal, Node n,
        Node parent) {
      return true;
    }


    @Override
    public void visit(NodeTraversal t, Node n, Node parent) {
      JSDocInfo info = n.getJSDocInfo();
      if (info != null) {
        Collection<Node> typeNodes = info.getTypeNodes();
        if (typeNodes.size() > 0) {
          if (actualTypes != null) {
            List<Node> expectedTypes = Lists.newArrayList();
            for (Node typeNode : info.getTypeNodes()) {
              expectedTypes.add(typeNode);
            }
            assertEquals("Wrong number of JsDoc types",
                expectedTypes.size(), actualTypes.size());
            for (int i = 0; i < expectedTypes.size(); i++) {
              assertNull(expectedTypes.get(i).checkTreeEquals(actualTypes.get(i)));
            }
          } else {
            actualTypes = Lists.newArrayList();
            for (Node typeNode : info.getTypeNodes()) {
              actualTypes.add(typeNode);
            }
          }
        }
      }
    }
  }
}
