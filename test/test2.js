/**
 * @template T
 * @param {function(new:T,...):void} fn
 */
camp.singleton = function (fn) {
  /**
   * @constructor
   */
  function e () {}
  e.prototype = fn.prototype;
  fn.getInstance = function () {
    if (fn._ins) {
      return fn._ins;
    } else {
      var ret = new e();
      fn._ins = ret;
      fn.apply(ret, arguments);
    }
    return ret;
  }
}

camp.injections = {}

camp.module('camp.injector', function (exports) {

  /**
   * @constructor
   * @param {Object} prop
   */
  exports.Injector = function (prop) {
    this._injections = prop;
  }


  /**
   * @template T
   * @param {function(new:T,...):void} c
   * @param {...string} var_args
   */
  exports.Injector.inject = function (c, var_args) {
    c.prototype._factory = function () {return new c;};
  }


  /**
   * @template T
   * @param {function(new:T,...):void} c
   * @param {...string} var_args
   */
  exports.Injector.injectSingleton = function (c, var_args) {
    c.getInstance = function () {return new c};
  }

  /**
   * @template T
   * @param {function(new:T,...):void} c
   * @returns {T}
   */
  exports.Injector.prototype.createInstance = function (c) {
    return c.prototype._factory(this._injections, this);
  }

});