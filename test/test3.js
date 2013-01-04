

camp.module('camp.vm.interaction', function (exports) {
  var injector = camp.using('camp.dependencies.injector');

  /**
   * @constructor
   */
  exports.Service = function () {
    this._node = document.getElementById('id');
  }

  /**
   * @returns {Element}
   */
  exports.Service.prototype.getNode = function () {
    return this._node;
  }


  /**
   * @constructor
   */
  exports.Service2 = function () {
    this._node = document.getElementById('id');
  }

  /**
   * @returns {Element}
   */
  exports.Service2.prototype.getNode = function () {
    return this._node;
  }


  /**
   * @constructor
   * @param {string} name1
   * @param {camp.vm.interaction.Test2} test2
   */
  exports.Test = function (name1, test2) {
    this._x = name1;
    this._test2 = test2;
  }

  injector.inject(exports.Test, 'setService');


  /**
   * @returns {string}
   */
  exports.Test.prototype.getName = function () {
    return this._x + this._test2.getName() + this._service.getNode().innerHTML;
  }


  /**
   * @param {camp.vm.interaction.Service} service
   */
  exports.Test.prototype.setService = function (service) {
    this._service = service;
  }

  /**
   * @private {camp.vm.interaction.Service}
   */
  exports.Test.prototype._service = null;

  /**
   * @constructor
   * @param {string} name2
   */
  exports.Test2 = function (name2) {
    this._name = name2;
  }


  /**
   * @returns {string}
   */
  exports.Test2.prototype.getName = function () {
    return this._name;
  }


  /**
   * @constructor
   * @extends {camp.vm.interaction.Test}
   */
  exports.Test3 = function (name1, test2) {
    exports.Test.call(this, name1, test2);
  }
  goog.inherits(exports.Test3, exports.Test);

  injector.inject(exports.Test3, "setService");

  exports.main = function () {
    var m = {
          a : 1,
          b : 2,
          c : 3
        }
    injector.bind('name1', 'name1');
    injector.bind('name2', 'name2');
    injector.bind('service', exports.Service2);
    injector.bind('test2', exports.Test2);
    var l = injector.createInstance(exports.Test3);
    window.localStorage['foo'] = l.getName();
  }
});
