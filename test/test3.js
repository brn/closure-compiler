

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
  function Service2 () {
    this._node = document.getElementById('id');
  }

  /**
   * @returns {Element}
   */
  Service2.prototype.getNode = function () {
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
   * @param {string} name1
   * @param {string} name2
   */
  exports.Test4 = function (name1, name2) {
    this.a = name1;
    this.b = name2;
  }

  injector.defineProvider(exports.Test4, function (name1, name2) {
    var a = new exports.Test4(name1, name2);
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

  /**
   * @constructor
   */
  function PubSub(){
    this._node = document.getElementsById('echo');
  }

  /**
   * @return {Element} node
   */
  PubSub.prototype.getNode = function() {
    return this._node;
  };

  /**
   * DataSourceをまとめて公開するためのユーティリティ
   * @constructor
   * @param {PubSub} pubsub
   */
  exports.DataSourceManager = function(pubsub) {
    /**
     * @private {!Object}
     */
    this._dataSources = {};

    /**
     * @private {PubSub}
     */
    this._pubsub = pubsub;
  };

  /**
   * @param {string} value
   */
  exports.DataSourceManager.prototype.echo = function(value) {
    this._pubsub.getNode().innertHTML = value;
  };


  injector.defineProvider(exports.DataSourceManager, function() {
    return new exports.DataSourceManager(new PubSub);
  });


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
    var s = injector.get('service');
    var l = injector.createInstance(exports.Test3);
    var v = injector.createInstance(exports.Test);
    var o = injector.createInstance(exports.DataSourceManager);
    o.echo(l.getName() + v.getName());
    window.localStorage['foo'] = l.getName() + v.getName() + new Service2().getNode().innerHTML;// + s.getNode().innerHTML;
    window.console.log(injector.createInstance(exports.Test4));
  }
});
