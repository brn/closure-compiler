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
  exports.LoginAfterInformationViewModel = function() {
  };
  exports.LoginAfterInformationViewModel.createInstance = function() {
    return new exports.LoginAfterInformationViewModel();
  };

  exports.LoginAfterInformationViewModel.prototype.foo = function() {
    return 'foo';
  };

  exports.LoginAfterInformationViewModel.prototype.hideInformationAction = function() {
  };


  /**
   * @constructor
   */
  exports.TooltipViewModel = function() {
  };
  exports.TooltipViewModel.createInstance = function() {
    return new exports.TooltipViewModel;
  };


  exports.TooltipViewModel.prototype.showAction = function() {
  };


  exports.TooltipViewModel.prototype.hideAction = function() {
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
  function ComponentRegistry() {}

  /**
   * @param {function(new:T,...):?} c
   * @template T
   * @return {T}
   */
  ComponentRegistry.prototype.resolve = function(c) {
    return c.createInstance();
  };

  exports.main = function() {
    var c = new ComponentRegistry();
    document.getElementById('aaa').innerHTML = c.resolve(exports.LoginAfterInformationViewModel).foo();
  };
});