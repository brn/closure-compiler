

camp.module('camp.vm.interaction', function (exports) {
  var Injector = camp.using('camp.injector.Injector');

  /**
   * @constructor
   * @param {number} name
   */
  exports.Test = function (name) {
    this._x = name;
  }

  /**
   * @returns {string}
   */
  exports.Test.prototype.getName = function () {
    return this._x;
  }


  //Injector.inject(exports.Test);
  //Injector.injectSingleton(exports.Test);
  /**
   * @param {Object} injections
   * @returns {camp.vm.interaction.Test}
   */
  exports.Test.prototype._factory = function (injections) {
    return new exports.Test(injections.name1);
  }

  exports.Test.prototype.get = function () {return this._x;}

  exports.main = function () {
    var injector = new Injector({name1 : 'name'});
    var l = injector.createInstance(exports.Test);
    document.getElementById('div').innerHTML = l.getName();
  }
});
