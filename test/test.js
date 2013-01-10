function Foo2() {}

/**
 * @namespace camp.vm
 */
camp.module('camp.vm', function (exports) {
  /**
   * @constructor
   */
  exports.Foo = function (name) {
    if (!name.a) {
      name.a = name;
    } else {
      name.b = name;
    }
    this.name = name;
  }
  exports.a = function () {return 'a'}
  /**
   * @param {Array.<exports.Foo>} foo
   */
  exports.doRun = function (foo) {
    window.console.log(foo[0].name);
    return exports.a();
  }

  exports.main = function () {
    var arr = [new exports.Foo('a')];
    exports.doRun(arr);
  }
});
