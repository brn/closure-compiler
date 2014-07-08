package com.google.javascript.jscomp;

public class FactoryInjectorProcessorTest extends CompilerTestCase {

  private static String EXTERNS = "var window;";


  public FactoryInjectorProcessorTest() {
    super(EXTERNS);
  }


  private String code(String... codes) {
    StringBuilder builder = new StringBuilder();
    for (String code : codes) {
      builder.append(code + "\n");
    }
    return builder.toString();
  }


  public void simpleTest() {
    test(code(
        "/**@constructor",
        " * @param {string} foo",
        " * @param {string} bar",
        " * @param {number} baz",
        " */",
        "function Foo(foo, bar, baz){}",
        "camp.utils.dependencies.inject(Foo, {foo:'foo', bar: 'bar', baz: 1})"
        ),
        code(
            "/**@constructor",
            " * @param {string} foo",
            " * @param {string} bar",
            " * @param {number} baz",
            " */",
            "function Foo(foo, bar, baz){}",
            "/**",
            " * @returns {Foo}",
            " * @param {{foo: string, bar: string, baz: number}} foo",
            " */",
            "Foo.jscomp$newInstance = function(binding) {",
            "  return new Foo(binding.foo, binding.bar, binding.baz);",
            "}",
            "Foo.jscomp$newInstance({foo:'foo', bar: 'bar', baz: 1})"
        ));
  }


  @Override
  protected FactoryInjectorProcessor getProcessor(Compiler compiler) {
    return new FactoryInjectorProcessor(compiler);
  }
}
