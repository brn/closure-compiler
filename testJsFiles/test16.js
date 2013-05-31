camp.module("camp.test.foo.bar", ['SDKListConfig', 'DefaultConfig'], function (exports) {

  /**
   * @constructor
   * @param {string} tokenUtil
   */
  function Test(tokenUtil) {

  }

  /**
   * @template T
   * @param {function(new:T, ...):?} dst
   * @param {Object} src
   * @param {Object=} opt_override
   */
  camp.mixin = function(dst, src, opt_override) {
    opt_override = opt_override || {};
    var prop;
    var target = typeof dst === 'function'? dst.prototype :
          dst.__trait__? dst : null;

    if (target === null) {
      throw new Error('camp.mixin is appliable only trait or constructor');
    }

    for (prop in opt_override) {
      dst.prototype[prop] = opt_override[prop];
    }

    for (prop in src) {
      if (prop === '__trait__') continue;
      !(prop in opt_override) &&
        (dst.prototype[prop] = src.prototype[prop]);
    };
  };


  exports.DefaultConfig = camp.trait({
    /**
     * @return {number}
     */
    cookies : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    tooltip : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    pubsub : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    requestManager : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    isolate : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    tokenUtil : function() {
      return 1;
    },

    /**
     * @return {number}
     */
    snapshotRecorder : function() {
      return 1;
    }
  });

  exports.SDKListConfig = camp.trait({});
  camp.mixin(exports.SDKListConfig, [exports.DefaultConfig], {
    /**
     * @return {number}
     */
    sdkListRequest : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    bindingResources : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    sdkDeleteUrl : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    sdkListRequestService : function() {
      return 1;
    },


    /**
     * @return {number}
     */
    resultProvider : function() {
      return 1;
    }
  });


  /**
   * @constructor
   */
  exports.SDKListVMConfig = function() {};
  camp.mixin(exports.SDKListVMConfig, [exports.SDKListConfig]);
});