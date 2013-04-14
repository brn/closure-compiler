package com.google.javascript.jscomp;

public class DIProcessorTest extends CompilerTestCase {
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


  public DIProcessorTest() {
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
  protected DIProcessor getProcessor(Compiler compiler) {
    return new DIProcessor(compiler);
  }


  @Override
  protected CompilerOptions getOptions(CompilerOptions options) {
    options = super.getOptions(options);
    options.prettyPrint = true;
    return options;
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
            module("binder.bind('foo').toInstance('test')"),
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(foo){};",
            initModule("var test = injector.getInstance(testNs.foo.bar.Test)")
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
                "binder.bind('foo').toInstance('test')",
                "binder.bind('testDeps').to(testNs.foo.bar.TestDeps);"
            ),
            initModule("var test = injector.getInstance(testNs.foo.bar.Test)")
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
                "binder.bind('foo').to(testNs.foo.bar.Foo)",
                "binder.bind('bar').toInstance(function() {})",
                "binder.bind('testDeps').to(testNs.foo.bar.TestDeps);"
            ),
            initModule("var test = injector.getInstance(testNs.foo.bar.Test)")
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
                "binder.bind('binding1').toInstance('binding1')",
                "binder.bind('binding2').toInstance('binding2')"
            ),
            initModule("var test = injector.getInstance(testNs.foo.bar.Test)")
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


  public void testGetInstanceByName() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test)",
                "binder.bind('binding1').toInstance('binding1')"
            ),
            initModule("var test = injector.getInstanceByName('testClass')")
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var test = new testNs.foo.bar.Test(module.binding1);",
            "})();"
        ));
  }


  public void testProvider() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            module(
                "binder.bind('testClass').toProvider(function(binding1) {",
                "  return new testNs.foo.bar.Test(binding1);",
                "})",
                "binder.bind('binding1').toInstance('binding1')"
            ),
            initModule("var test = injector.getInstanceByName('testClass')")
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.testClass = function(binding1) {",
            "    return new testNs.foo.bar.Test(binding1)",
            "  }",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var test = module.testClass(module.binding1);",
            "})();"
        ));
  }


  public void testSingletonScope() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.SINGLETON)",
                "binder.bind('binding1').toInstance('binding1')"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test)",
                "var test2 = injector.getInstance(testNs.foo.bar.Test);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  var test = (singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0));",
            "  var test2 = (singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0));",
            "})();"
        ));
  }


  public void testSingletonScopeWithBinding() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClass) {};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.SINGLETON)",
                "binder.bind('binding1').toInstance('binding1')"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2)",
                "var test2 = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test2 = function(testClass){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  var test = new testNs.foo.bar.Test2((singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0)));",
            "  var test2 = new testNs.foo.bar.Test2((singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0)));",
            "})();"
        ));
  }


  public void testSingletonScopeWithMethodInjection() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setItem')",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){}",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClass) {};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.SINGLETON)",
                "binder.bind('binding1').toInstance('binding1')",
                "binder.bind('binding2').toInstance('binding2')"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2)",
                "var test2 = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){};",
            "testNs.foo.bar.Test2 = function(testClass){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  var test = new testNs.foo.bar.Test2((singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0)));",
            "  var test2 = new testNs.foo.bar.Test2((singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0)));",
            "})();"
        ));
  }


  public void testSingletonScopeWithMethodInjectionAndGetByName() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setItem')",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){}",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.SINGLETON)",
                "binder.bind('binding1').toInstance('binding1')",
                "binder.bind('binding2').toInstance('binding2')"
            ),
            initModule(
                "var test = injector.getInstanceByName('testClass')",
                "var test2 = injector.getInstanceByName('testClass');"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  var test = singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0);",
            "  var test2 = singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0);",
            "})();"
        ));
  }


  public void testSingletonScopeWithMethodInjection2Method() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setItem', 'setItem2')",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){}",
            "testNs.foo.bar.Test.prototype.setItem2 = function(binding3){}",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClass) {};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.SINGLETON)",
                "binder.bind('binding1').toInstance('binding1')",
                "binder.bind('binding2').toInstance('binding2')",
                "binder.bind('binding3').toInstance('binding3')"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2)",
                "var test2 = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){};",
            "testNs.foo.bar.Test.prototype.setItem2 = function(binding3){};",
            "testNs.foo.bar.Test2 = function(testClass){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  this.binding3 = 'binding3';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  var test = new testNs.foo.bar.Test2((singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0.setItem2(module.binding3), singletonInstance0)));",
            "  var test2 = new testNs.foo.bar.Test2((singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0.setItem2(module.binding3), singletonInstance0)));",
            "})();"
        ));
  }


  public void testSingletonScopeWithMethodInjection2MethodAndGetByName() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setItem', 'setItem2')",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){}",
            "testNs.foo.bar.Test.prototype.setItem2 = function(binding3){}",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.SINGLETON)",
                "binder.bind('binding1').toInstance('binding1')",
                "binder.bind('binding2').toInstance('binding2')",
                "binder.bind('binding3').toInstance('binding3')"
            ),
            initModule(
                "var test = injector.getInstanceByName('testClass')",
                "var test2 = injector.getInstanceByName('testClass');"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setItem = function(binding2){};",
            "testNs.foo.bar.Test.prototype.setItem2 = function(binding3){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  this.binding3 = 'binding3';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  var test = singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0.setItem2(module.binding3), singletonInstance0);",
            "  var test2 = singletonInstance0? singletonInstance0 : (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setItem(module.binding2), singletonInstance0.setItem2(module.binding3), singletonInstance0);",
            "})();"
        ));
  }


  public void testEagerSingetonScope() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.EAGER_SINGLETON)",
                "binder.bind('binding1').toInstance('binding1');"
            ),
            initModule()
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  singletonInstance0 = (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0);",
            "})();"
        ));
  }


  public void testEagerSingetonScopeWithBinding() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClass){}",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.EAGER_SINGLETON)",
                "binder.bind('binding1').toInstance('binding1');"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2);",
                "var test2 = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test2 = function(testClass){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  singletonInstance0 = (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0);",
            "  var test = new testNs.foo.bar.Test2(singletonInstance0)",
            "  var test2 = new testNs.foo.bar.Test2(singletonInstance0)",
            "})();"
        ));
  }


  public void testEagerSingetonScopeWithInjections() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setBinding')",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2) {};",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClass){}",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.EAGER_SINGLETON)",
                "binder.bind('binding1').toInstance('binding1');",
                "binder.bind('binding2').toInstance('binding2');"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2);",
                "var test2 = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2){};",
            "testNs.foo.bar.Test2 = function(testClass){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  singletonInstance0 = (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setBinding(module.binding2), singletonInstance0);",
            "  var test = new testNs.foo.bar.Test2(singletonInstance0)",
            "  var test2 = new testNs.foo.bar.Test2(singletonInstance0)",
            "})();"
        ));
  }


  public void testEagerSingetonScopeWithInjectionsAndGetByName() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setBinding')",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2) {};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.EAGER_SINGLETON)",
                "binder.bind('binding1').toInstance('binding1');",
                "binder.bind('binding2').toInstance('binding2');"
            ),
            initModule(
                "var test = injector.getInstanceByName('testClass');",
                "var test2 = injector.getInstanceByName('testClass');"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  singletonInstance0 = (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setBinding(module.binding2), singletonInstance0);",
            "  var test = singletonInstance0",
            "  var test2 = singletonInstance0;",
            "})();"
        ));
  }


  public void testEagerSingetonScopeWithInjections2Methods() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setBinding', 'setBinding2')",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2) {};",
            "testNs.foo.bar.Test.prototype.setBinding2 = function(binding3) {};",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClass){}",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.EAGER_SINGLETON)",
                "binder.bind('binding1').toInstance('binding1');",
                "binder.bind('binding2').toInstance('binding2');",
                "binder.bind('binding3').toInstance('binding3');"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2);",
                "var test2 = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2){};",
            "testNs.foo.bar.Test.prototype.setBinding2 = function(binding3){};",
            "testNs.foo.bar.Test2 = function(testClass){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  this.binding3 = 'binding3';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  singletonInstance0 = (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setBinding(module.binding2), singletonInstance0.setBinding2(module.binding3), singletonInstance0);",
            "  var test = new testNs.foo.bar.Test2(singletonInstance0)",
            "  var test2 = new testNs.foo.bar.Test2(singletonInstance0)",
            "})();"
        ));
  }


  public void testEagerSingetonScopeWithInjections2MethodsAndGetByName() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "camp.injections.Injector.inject(testNs.foo.bar.Test, 'setBinding', 'setBinding2')",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2) {};",
            "testNs.foo.bar.Test.prototype.setBinding2 = function(binding3) {};",
            module(
                "binder.bind('testClass').to(testNs.foo.bar.Test).as(camp.injections.Scopes.EAGER_SINGLETON)",
                "binder.bind('binding1').toInstance('binding1');",
                "binder.bind('binding2').toInstance('binding2');",
                "binder.bind('binding3').toInstance('binding3');"
            ),
            initModule(
                "var test = injector.getInstanceByName('testClass');",
                "var test2 = injector.getInstanceByName('testClass');"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test.prototype.setBinding = function(binding2){};",
            "testNs.foo.bar.Test.prototype.setBinding2 = function(binding3){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.binding1 = 'binding1';",
            "  this.binding2 = 'binding2';",
            "  this.binding3 = 'binding3';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var singletonInstance0;",
            "  singletonInstance0 = (singletonInstance0 = new testNs.foo.bar.Test(module.binding1), singletonInstance0.setBinding(module.binding2), singletonInstance0.setBinding2(module.binding3), singletonInstance0);",
            "  var test = singletonInstance0;",
            "  var test2 = singletonInstance0;",
            "})();"
        ));
  }


  public void testPassProvider() {
    test(
        code(
            "var testNs = {foo:{bar:{}}}",
            "/**@constructor*/",
            "testNs.foo.bar.Test = function(binding1){};",
            "/**@constructor*/",
            "testNs.foo.bar.Test2 = function(testClassProvider) {}",
            module(
                "binder.bind('testClass').toProvider(function(binding1) {",
                "  return new testNs.foo.bar.Test(binding1);",
                "});",
                "binder.bind('binding1').toInstance('binding1');"
            ),
            initModule(
                "var test = injector.getInstance(testNs.foo.bar.Test2);"
            )
        ),
        code(
            "var testNs = {foo:{bar:{}}}",
            "testNs.foo.bar.Test = function(binding1){};",
            "testNs.foo.bar.Test2 = function(testClassProvider){};",
            "function Module() {}",
            "Module.prototype.configure = function() {",
            "  this.testClass = function(binding1) {",
            "    return new testNs.foo.bar.Test(binding1)",
            "  }",
            "  this.binding1 = 'binding1';",
            "  return this;",
            "};",
            "(function(){",
            "  var module = (new Module).configure();",
            "  var test = new testNs.foo.bar.Test2(function() {return module.testClass(module.binding1)});",
            "})();"
        ));
  }
}