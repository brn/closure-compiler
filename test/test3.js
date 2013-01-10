

camp.module('camp.vm.interaction', function (exports) {
  var injector = camp.using('camp.dependencies.injector');

  /**
   * @constructor
   */
  exports.Service = function () {}
  goog.addSingletonGetter(exports.Service);
  injector.inject(exports.Service, 'setNode');
  exports.Service.prototype._node = null;

  /**
   * @returns {Element}
   */
  exports.Service.prototype.getNode = function () {
    return this._node;
  }

  exports.Service.prototype.setNode = function (node) {
    this._node = node;
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


  exports.Test.prototype = {
    /**
     * @returns {string}
     */
    getName : function () {
      return this._x + this._test2.getName() + this._service.getNode().innerHTML;
    },


    /**
     * @param {camp.vm.interaction.Service} service
     */
    setService : function (service) {
      this._service = service;
    },

    /**
     * @private {camp.vm.interaction.Service}
     */
    _service : null
  }



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


  /**
   * @constructor
   * @param {string} a
   * @param {string} b
   */
  exports.Test4 = function (a, b) {
    this.a = a;
    this.b = b;
  }

  injector.defineProvider(exports.Test4, function () {
    var a = new exports.Test4(injector.get('name1'), injector.get('name2'));
    a.setC(injector.get('test2'));
    return a;
  })

  exports.Test4.prototype.c = '';

  /**
   * @param {camp.vm.interaction.Test2} c
   */
  exports.Test4.prototype.setC = function (c) {
    this.c = c;
  }

  exports.Test4.prototype.get = function () {
    return this.a + this.b + this.c.getName();
  }


  injector.inject(exports.Test3, "setService");

  exports.main = function () {
    var m = {
          a : 1,
          b : 2,
          c : 3
        }
    injector.bind('name1', 'name1');
    injector.bind('name2', 'name2');
    injector.bind('node', document.getElementById('id'));
    injector.bind('service', exports.Service);
    injector.bind('test2', exports.Test2);
    var s = injector.createInstance(exports.Service);
    var l = injector.createInstance(exports.Test3);
    var v = injector.createInstance(exports.Test);
    window.localStorage['foo'] = l.getName() + v.getName() + s.getNode().innerHTML;
    window.console.log(injector.createInstance(exports.Test4));
  }
});
