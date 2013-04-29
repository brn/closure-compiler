camp.module("using.require", ['Test'], function (exports) {
  var Test = camp.using('using.provide.Test');

  /**
   * @constructor
   */
  exports.Test = function() {
    this._test = new Test();
  };
});