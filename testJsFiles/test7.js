camp.module("test.foo.bar", function (exports) {
  var AbstractClassMatcher = camp.using('camp.injections.AbstractClassMatcher');
  var AbstractMethodMatcher = camp.using('camp.injections.AbstractMethodMatcher');
  var AbstractAllMatcher = camp.using('camp.injections.AbstractAllMatcher');
  var Matchers = camp.using('camp.injections.Matchers');

  /**
   * @constructor
   * @param {AbstractClassMatcher} a
   */
  function A(a) {
    this.a = a;
  }

  exports.main = function() {
    document.body.innerHTML = new A(new AbstractClassMatcher).a;
  };
});