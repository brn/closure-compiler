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
   * @param {function(R):T} instance
   */
  exports.FactoryContainer = function(instance) {
    /**
     * @type {function(R):T}
     */
    this._instance = instance;
  };
  goog.inherits(exports.FactoryContainer, exports.BaseFactory);


  /**
   * @returns {T}
   * @param {R} p
   */
  exports.FactoryContainer.prototype.getInstance = function(p) {
    return this._instance(p);
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
   * @type {exports.FactoryContainer.<!Test,{testParam:function():{a:string}}>}
   */
  var c = new exports.FactoryContainer(function(o) {
        var param = o.testParam();
        return new Test(param);
      });

  /**
   * @type {exports.FactoryContainer.<!Test2,Object>}
   */
  var v = new exports.FactoryContainer(function(o) {
        return new Test2('aaa');
      });

  /**
   * @type {exports.FactoryContainer.<{a:string},Object>}
   */
  var o = new exports.FactoryContainer(function(o) {
        return {
          a: 'ok!'
        };
      });

  /**
   * @constructor
   * @struct
   */
  function ComponentRegistry() {

    this.test = function() {
      return c.getInstance(this);
    };


    this.test2 = function () {
      return v.getInstance(this);
    };


    this.testParam = function(){
      return o.getInstance(this);
    };
  }

  var s = new ComponentRegistry();

  /**
   * @type {Test}
   */
  var test = s.test();
  /**
   * @type {Test2}
   */
  var test2 = s.test2();
  window.console.log(test.param);
  window.console.log(test2.param);
});