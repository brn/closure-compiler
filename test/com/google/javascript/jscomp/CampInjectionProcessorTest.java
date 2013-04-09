package com.google.javascript.jscomp;

public class CampInjectionProcessorTest extends CompilerTestCase {
  private static final String EXTERNS = "var window;";

  private static final String MODULE_PREFIX = 
      "/**\n" +
      "  * @constructor\n" +
      "  * @implements camp.injections.Module\n" +
      "  */\n" +
      "function Module() {}\n" +
      "Module.prototype.configure = function(binder) {\n";
      

  private static final String MODULE_SUFFIX = "\n}";

  private static final String MODULE_INIT_PREFIX = "camp.injections.modules.init([Module], function(injector) {";
  
  private static final String MODULE_INIT_SUFFIX = "});";

  public CampInjectionProcessorTest() {
    super(EXTERNS);
  }


  private String code(String... codes) {
    StringBuilder builder = new StringBuilder();
    for (String code : codes) {
      builder.append(code + "\n");
    }
    return builder.toString();
  }


  @Override
  protected CampInjectionProcessor getProcessor(Compiler compiler) {
    return new CampInjectionProcessor(compiler);
  }


  private String module(String... codes) {
    StringBuilder builder = new StringBuilder();
    for (String code : codes) {
      builder.append(code + "\n");
    }
    return MODULE_PREFIX + builder.toString() + MODULE_SUFFIX;
  }

  private String initModule(String... codes) {
    StringBuilder builder = new StringBuilder();
    for (String code : codes) {
      builder.append(code + "\n");
    }
    return MODULE_INIT_PREFIX + builder.toString() + MODULE_INIT_SUFFIX;
  }

  public void testSimpleDi() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            module("binder.bind('foo', 'test')"),
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(foo){};",
            initModule("var test = injector.createInstance(testNs.foo.bar.Test)")
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.foo = 'test';",
            "  return this;",
            "}",
            "testNs.foo.bar.Test = function(foo){};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var test = new testNs.foo.bar.Test(module.foo)",
            "})();"
        ));
  }
  
  public void testResoveBindingOfBinding() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(testDeps){};",
            "/**@constructor*/",
            "testNs.foo.bar.TestDeps = function(foo){};",
            module(
                "binder.bind('foo', 'test')",
                "binder.bind('testDeps', testNs.foo.bar.TestDeps);"
                ),
            initModule("var test = injector.createInstance(testNs.foo.bar.Test)")
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(testDeps){};",
            "testNs.foo.bar.TestDeps = function(foo){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.foo = 'test';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var test = new testNs.foo.bar.Test(new testNs.foo.bar.TestDeps(module.foo));",
            "})();"
        ));
  }
  
  public void testResoveBindingOfBinding2Level() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(testDeps){};",
            "/**@constructor*/",
            "testNs.foo.bar.TestDeps = function(foo){};",
            "/**@constructor*/",
            "testNs.foo.bar.Foo = function(bar){};",
            module(
                "binder.bind('foo', testNs.foo.bar.Foo)",
                "binder.bind('bar', function() {})",
                "binder.bind('testDeps', testNs.foo.bar.TestDeps);"
                ),
            initModule("var test = injector.createInstance(testNs.foo.bar.Test)")
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(testDeps){};",
            "testNs.foo.bar.TestDeps = function(foo){};",
            "testNs.foo.bar.Foo = function(bar) {};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.bar = function(){};",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var test = new testNs.foo.bar.Test(new testNs.foo.bar.TestDeps(new testNs.foo.bar.Foo(module.bar)));",
            "})();"
        ));
  }
  
  public void testResoveMethodInjection() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(){};",
            "testNs.foo.bar.Test.prototype.setBinding1 = function(binding1) {};",
            "testNs.foo.bar.Test.prototype.setBinding2 = function(binding2) {};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setBinding1', 'setBinding2');",
            module(
                "binder.bind('binding1', 'binding1')",
                "binder.bind('binding2', 'binding2')"
                ),
            initModule("var test = injector.createInstance(testNs.foo.bar.Test)")
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(){};",
            "testNs.foo.bar.Test.prototype.setBinding1 = function(binding1) {};",
            "testNs.foo.bar.Test.prototype.setBinding2 = function(binding2) {};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var instance$0;",
            "  var test = (instance$0 = new testNs.foo.bar.Test(), instance$0.setBinding1(module.binding1), instance$0.setBinding2(module.binding2), instance$0);",
            "})();"
        ));
  }
}
