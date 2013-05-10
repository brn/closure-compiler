var goog = {
      inherits : function() {
      }
    };

camp.module('test.foo.bar.baz', function(exports) {

  /**
   * @constructor
   */
  var Test = function() {
      };

  Test.prototype.hoge = function() {

  };

  /**
   * @template T
   * @param {function(new:T, ...):?} m
   * @return {T}
   */
  function interceptor(m) {
    var arg = Array.prototype.slice.call(m);
    /**
     * @constructor
     */
    function Proxy(){}
    goog.inherits(Test, arg.shift());
    Proxy.prototype.hoge = function() {
      window.console.log('a');
    };
    var i = new Proxy;
    m.apply(i, arg);
    return i;
  }

  var i = interceptor(Test);
  i.hoge();
});