

camp.module('camp.vm.interaction', function (exports) {
  var Injector = camp.using('camp.injector.Injector');

  /**
   * @constructor
   * @param {string} name1
   * @param {camp.vm.interaction.Test2} test2
   */
  exports.Test = function (name1, test2) {
    this._x = name1;
    this._test2 = test2;
  }
  Injector.inject(exports.Test);


  /**
   * @returns {string}
   */
  exports.Test.prototype.getName = function () {
    return this._x + this._test2.getName();
  }


  /**
   * @constructor
   * @param {string} name2
   */
  exports.Test2 = function (name2) {
    this._name = name2;
  }
  Injector.inject(exports.Test2);


  /**
   * @returns {string}
   */
  exports.Test2.prototype.getName = function () {
    return this._name;
  }


  /**
   * @constructor
   */
  exports.Module = function () {
    /**
     * @type {string}
     */
    this.name1 = 'name';

    /**
     * @type {string}
     */
    this.name2 = 'name2';

    /**
     * @type {function(new:camp.vm.interaction.Test2,string):void}
     */
    this.test2 = exports.Test2;
  }

  exports.main = function () {
    var injector = new Injector(new exports.Module);
    var l = injector.createInstance(exports.Test);
    window.localStorage['foo'] = l.getName();
    return 'a' in l? true : false;
  }
});
