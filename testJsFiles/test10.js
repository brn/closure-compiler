/**
 * @fileoverview
 * @author
 */
var goog = {
      inherits : function(a,b) {

      }
    };

camp.module('camp.viewModel.common.loginAfterInformation', ['LoginAfterInformationViewModel'], function(exports) {

  /**
   * @constructor
   */
  exports.MethodCollection1 = function() {

  };

  exports.MethodCollection1.prototype.foo1 = function() {

  };

  exports.MethodCollection1.prototype.foo2 = function() {

  };


  /**
   * @constructor
   * @extends {exports.MethodCollection1}
   */
  exports.MethodCollection2 = function() {

  };
  goog.inherits(exports.MethodCollection2, exports.MethodCollection1);
  exports.MethodCollection2.prototype.bar1 = function() {

  };
  exports.MethodCollection2.prototype.bar2 = function() {

  };



  /**
   * @constructor
   * @extends {exports.MethodCollection2}
   */
  exports.LoginAfterInformationViewModel = function() {
  };
  goog.inherits(exports.LoginAfterInformationViewModel, exports.MethodCollection2);


  exports.LoginAfterInformationViewModel.prototype.hideInformationAction = function() {
  };


  /**
   * @constructor
   */
  exports.TooltipViewModel = function() {
  };


  /**
   * @param {Event} e
   * @param {Object} attr
   */
  exports.TooltipViewModel.prototype.showAction = function(e, attr) {
  };


  /**
   * @param {Event} e
   */
  exports.TooltipViewModel.prototype.hideAction = function(e) {
  };


  /**
   * @private
   * @param {boolean} visibility
   */
  exports.TooltipViewModel.prototype._toggleSelectElements = function(visibility) {
  };


  /**
   * @constructor
   */
  function TestClass() {

  }

  TestClass.prototype.foo = function() {

  };


  function NotClass() {}


  /**
   * @constructor
   * @template T
   */
  var Hoge = function() {

      };

  Hoge.prototype.hoge = function() {
    alert(1);
  };


  /**
   * @constructor
   */
  exports.TestMixin = camp.mixin({
    /**
     * @type {Hoge.<string>}
     */
    hoge : Hoge
  }, function() {
    this._hoge = 'hoge';
  });

  var m = new exports.TestMixin();

  m.hoge();
});