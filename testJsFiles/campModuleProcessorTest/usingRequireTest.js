camp.module("using.require", function (exports) {
  var Test = camp.using('using.provide.Test');

  /**
   * @constructor
   */
  exports.Test = function() {
    this._test = new Test();
  };
});