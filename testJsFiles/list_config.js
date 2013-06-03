camp.module("camp.test.foo.bar", ['ListConfig'], function (exports) {
  var DefaultConfig = camp.using('camp.test.foo.bar.DefaultConfig');
  exports.ListConfig = camp.trait([DefaultConfig], {
    /**
     * @return {number}
     */
    listVMConfig : function() {
      return 1;
    },

    /**
     * @return {number}
     */
    bindingResources : function() {
      return 1;
    }
  });
  /**
   * @constructor
   */
  exports.ListVMConfig = function() {};
  camp.mixin(exports.ListVMConfig, [exports.ListConfig]);
});