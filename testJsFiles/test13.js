camp.module("test.foo.bar", function (exports) {
  /**
   * @constructor
   */
  function Binder() {
  }

  var modules = {
        /**
         * @param {Array.<Module>} modList
         * @param {function(Object):void} closure
         */
        init : function(modList, closure) {
          var binder = new Binder();
          for (var i = 0, len = modList.length; i < len; i++) {
            modList[i].configure.call(binder);
          }
          closure(binder);
        }
      };


  /**
   * @interface {Module}
   */
  function Module() {

  }

  /**
   * @this {Binder}
   */
  Module.prototype.configure = function() {

  };


  /**
   * @constructor
   * @param {string} name1
   * @param {string} name2
   */
  function Test(name1, name2) {
    this.name1 = name1;
    this.name2 = name2;
  }


  /**
   * @constructor
   * @implements {Module}
   */
  function XModule() {

  }

  /**
   * @this {Binder}
   */
  XModule.prototype.configure = function() {
    /**
     * @type {string}
     */
    this.userName = 'name';
    /**
     * @type {string}
     */
    this.mailAddress = 'name2';
    /**
     * @returns {Test}
     */
    this.testProvider = function() {
      return new Test(this.userName, this.num);
    };
  };


  /**
   * @constructor
   * @implements {Module}
   */
  function YModule() {

  }
  /**
   * @this {Binder}
   */
  YModule.prototype.configure = function() {
    this.num = 1;
  };
  modules.init([new XModule, new YModule()], function(binder) {
    var test = binder.testProvider();
    document.getElementById('aaa').innerHTML = test.name1 + test.name2;
  });

});
