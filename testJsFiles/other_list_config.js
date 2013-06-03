camp.module("camp.test.foo.bar", ['OtherListConfig'], function (exports) {
  var ListConfig = camp.using('camp.test.foo.bar.ListConfig');

  exports.OtherListConfig = camp.trait([ListConfig], {
    /**
     * @return {number}
     */
    listVMConfig : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    exportName : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    widgetControllerFactory : function() {
      return 1;
    }
  });


  /**
   * @constructor
   * @param {number} pubsub
   */
  function DataSourceManager(pubsub) {
    document.getElementById('aaa').innerHTML = pubsub;
  }

  /**
   * @constructor
   */
  exports.SDKListVMConfig = function() {};
  camp.mixin(exports.SDKListVMConfig, [exports.OtherListConfig]);
  var e = new exports.SDKListVMConfig();
  camp.utils.dependencies.inject(DataSourceManager, e);
});