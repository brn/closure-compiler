camp.module("camp.foo.bar", function (exports) {
  var Module = camp.using('camp.injections.Module');

  /**
   * @constructor
   * @implements {Module}
   */
  exports.Module = function(){}
  exports.Module.prototype.configure = function() {

  };
});