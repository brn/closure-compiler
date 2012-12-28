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
   */
  exports.AbstractModule = function () {}

  /**
   * @protected
   * @template T
   * @param {function(new:T,...):void} classConstructor
   * @returns {function(...):T}
   */
  exports.AbstractModule.prototype.bind = function (classConstructor) {
    return classConstructor._factory;
  }

  /**
   * @protected
   * @template T
   * @param {T} value
   * @returns {function():T}
   */
  exports.AbstractModule.prototype.identify = function (value) {
    return function () {return value};
  }

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
   * @param {(function(new:T,...):T|T)} c
   * @returns {T}
   */
  exports.Injector.prototype.createInstance = function (c) {
    return c && c.prototype && c.prototype._factory? c.prototype._factory(this._injections) : c;
  }

});