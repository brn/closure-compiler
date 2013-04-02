camp.module("camp.injections", function (exports) {
  /**
   * @interface
   */
  exports.Config = function () {};

  exports.Config.prototype.setup = function () {};

  exports.Config.prototype.verify = function() {};
});