package com.google.javascript.jscomp;

public class CampModuleProcessorTest extends CompilerTestCase {
  
  private static String EXTERNS = "var window;";
  
  private static String MODULE_PREFIX = "camp.module('test.foo.bar.baz', function(exports){";
  
  private static String MODULE_SUFFIX = "});";
  
  public CampModuleProcessorTest() {
    super(EXTERNS);
  }
  
  private void doTest(String code, String expected) {
    test(MODULE_PREFIX + code + MODULE_SUFFIX, expected);
  }
  
  public void testSimpleModule() {
    this.doTest(
        "exports.Test = function(){};",
        "goog.dom.createElement(goog.dom.TagName.DIV);");
  }
  
  @Override
  protected CampModuleProcessor getProcessor(Compiler compiler) {
    return new CampModuleProcessor(compiler);
  }
}
