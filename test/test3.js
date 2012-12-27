
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

  /**
   * @returns {string}
   */
  exports.Test.prototype.getName = function () {
    return this._x + this._test2.getName();
  }


  /**
   * @constructor
   */
  exports.Test2 = function (name) {
    this._name = name;
  }

  exports.Test2.prototype.getName = function () {
    return this._name;
  }


  Injector.inject(exports.Test, "name1", "Test2");
  Injector.inject(exports.Test2, "name2");


  /**
   * @constructor
   */
  exports.Module = function () {
    this.name1 = 'name';
    this.name2 = 'name2';
    /**
     * @type {Function}
     */
    this.Test2 = exports.Test2;
  }

  exports.main = function () {
    var injector = new Injector(new exports.Module);
    var l = injector.createInstance(exports.Test);
    window.localStorage['foo'] = l.getName();
  }
});
