/**
 * @const
 * @type {(!number|!string)}
 */
var ITEM = 200;

/**
 * @namespace camp.vm
 */
camp.module('camp.vm.interaction', function (exports) {
  var vm = camp.using('camp.vm.vm.vm');
  /**
   * @constructor
   */
  exports.ActionRegistry = function () {}

  /**
   * @constructor
   */
  exports.Trigger = function () {}
});