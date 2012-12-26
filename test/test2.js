camp.module('camp.injector', function (exports) {
  /**
   * @constructor
   * @param {Object} prop
   */
  exports.Injector = function (prop) {
    /**
     * @type {Object}
     */
    this._injections = prop;
  }

  /**
   * @template T
   * @param {function(...):T} classConstructor
   */
  exports.Injector.inject = function (classConstructor) {
    classConstructor._factory = function () {
      return new classConstructor;
    }
  }

  /**
   * @template T
   * @param {function(...):T} classConstructor
   * @returns {T}
   */
  exports.Injector.prototype.createInstance = function (classConstructor) {
    return classConstructor.prototype._factory(this._injections);
  }
});