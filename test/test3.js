/**
 * @namespace camp.vm
 */

camp.module('camp.vm.interaction', function (exports) {
  /**
   * @typedef {!Function}
   */
  exports.XXX;
  
  /**
   * @constructor
   * @param {!number} m
   */
  exports.Test = function (m) {
    this._x = m;
  }
  
  exports.Test.prototype.get = function () {return this._x;}
  
  exports.main = function () {
    window.console.log(new exports.Test('aaa'))
  }
  //exports.main = function () {}
});
