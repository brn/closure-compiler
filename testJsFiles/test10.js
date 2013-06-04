var goog = {
      provide : function(){},
      require : function(){},
      inherits : function(a,b) {

      }
    };

camp.module("camp.test", ['InstanceContainer'], function (exports) {
  /**
   * @constructor
   */
  exports.BaseFactory = function() {};

  /**
   * @extends {exports.BaseFactory}
   * @constructor
   * @template T, R
   * @param {R} context
   * @param {function(this:R):T} instance
   */
  exports.FactoryContainer = function(context, instance) {
    /**
     * @type {function(this:R):T}
     */
    this._instance = instance;

    /**
     * @type {R}
     */
    this._context = context;
  };
  goog.inherits(exports.FactoryContainer, exports.BaseFactory);


  /**
   * @returns {T}
   * @param {R} p
   */
  exports.FactoryContainer.prototype.getInstance = function(p) {
    return this._instance().call(this._context);
  };


  /**
   * @constructor
   * @param {{a:string}} param
   */
  function Test(param) {
    this.param = param;
  }


  /**
   * @constructor
   * @param {string} param
   */
  function Test2(param) {
    this.param = param;
  }



  /**
   * @template T
   * @this {T}
   */
  function componentExtensions() {

    /**
     * @type {function():Test}
     */
    this.testA = function(){
      return new Test(this.testParam());
    };


    /**
     * @type {function():Test2}
     */
    this.test2 = function(o) {
      return new Test2('aaa');
    };
  }


  /**
   * @template T
   * @this {T}
   */
  function parameterExtensions() {
    /**
     * @type {function():{a:string}}
     */
    this.testParam = function(o) {
      return {
        a: 'ok!'
      };
    };
  }


  /**
   * @constructor
   * @struct
   */
  function ComponentRegistry() {
    componentExtensions.call(this);
    parameterExtensions.call(this);
  }

  var s = new ComponentRegistry();

  /**
   * @type {Test}
   */
  var test = s.testA();
  /**
   * @type {Test2}
   */
  var test2 = s.test2();
  window.console.log(test.param);
  window.console.log(test2.param);
});