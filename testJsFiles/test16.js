camp.module("camp.test.foo.bar", ['SDKListConfig', 'DefaultConfig'], function (exports) {

  /**
   * @constructor
   * @param {string} tokenUtil
   */
  function Test(tokenUtil) {

  }

  camp.mixin = function(a, b, opt_rename){
    opt_rename = opt_rename || {};
    var prop;

    for (prop in opt_rename) {
      a.prototype[prop] = b.prototype[opt_rename[prop]];
    }

    for (prop in b.prototype) {
      if (!(prop in a.prototype)) {
        a.prototype[prop] = b.prototype[prop];
      }
    }
  }


  /**
   * @constructor
   */
  exports.DefaultConfig = function() {};


  exports.DefaultConfig.prototype.cookies = function() {
    return camp.utils.dependencies.inject(Test, this);
  };


  exports.DefaultConfig.prototype.tooltip = function() {
    return 1;
  };

  exports.DefaultConfig.prototype.pubsub = function() {
    return 1;
  };


  exports.DefaultConfig.prototype.requestManager = function() {
    return 1;
  };


  exports.DefaultConfig.prototype.isolate = function() {
    return '1';
  };

  exports.DefaultConfig.prototype.tokenUtil = function() {
    return '1';
  };

  /**
   * @constructor
   */
  exports.SDKListConfig = function() {};
  camp.mixin(exports.SDKListConfig, exports.DefaultConfig);

  exports.SDKListConfig.prototype.sdkListRequest = function() {
    return 1;
  };


  /**
   * @return {Object}
   */
  exports.SDKListConfig.prototype.bindingResources = function() {
    return {

    };
  };


  /**
   * @return {string}
   */
  exports.SDKListConfig.prototype.sdkDeleteUrl = function() {
    return 'a';
  };



  exports.SDKListConfig.prototype.sdkListRequestService = function() {
    return 'a';
  };



  exports.SDKListConfig.prototype.tokenUtil = function() {
    return 'a';
  };


  /**
   * @return {function():string}
   */
  exports.SDKListConfig.prototype.resultProvider = function() {
    /**
     *
     * @return {string}
     */
    return function() {
      return 'aa';
    };
  };
});