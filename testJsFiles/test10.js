camp.module('test.foo.bar.baz', function(exports) {

  /**
   * @constructor
   */
  var Test = function() {
      };

  /**
   * @enum {string}
   */
  Test.Foo = {a:'a'};

  /**
   * @param {Test.Foo} v
   */
  function m(v) {
    window.console.log(v);
  }

  m(Test.Foo.a);
});