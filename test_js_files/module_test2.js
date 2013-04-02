camp.module("test.hoge.hoge", function (exports) {
  /**
   * @constructor
   * @param {string} module_test_param
   */
  exports.A = function(module_test_param){
    this.x = module_test_param;
  };
  exports.A.hoge = function(){};
  exports.A.prototype.hoge = function(){};

  /**
   * @constructor
   */
  exports.Ba = function(){

  };
  exports.Ba.x = function () {

  };
});