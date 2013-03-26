

camp.module('camp.vm.interaction', function (exports) {
  var injector = camp.using('camp.dependencies.injector');
  var binder = camp.using('camp.dependencies.binder');
    var x = injector.bind ? injector.bind : binder.bind;

  /**
   * @constructor
   */
  function Service() {}
  goog.addSingletonGetter(Service);
  injector.inject(Service, 'setNode');
  Service.prototype._node = null;

  /**
   * @returns {Element}
   */
  Service.prototype.getNode = function () {
    return this._node;
  };

  Service.prototype.setNode = function (node) {
    this._node = node;
  };

  /**
   * @type {function(new:Service)}
   */
  exports.Service = Service;

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

  binder.bindProvider(exports.Test4, function (name1, name2) {
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


  binder.bindProvider(exports.DataSourceManager, function() {
    return new exports.DataSourceManager(new PubSub);
  });


  injector.inject(exports.Test3, "setService");

  exports.main = function () {
    binder.bindInterceptor("camp.*", "*", function(methodInvocation) {
      window.console.log('call before ' + methodInvocation.getClassName() + '.' + methodInvocation.getMethodName());
      var ret = methodInvocation.proceed();
      return ret;
    });

    binder.bindInterceptor("camp.*", "*", function nullify(methodInvocation) {
      var ret = methodInvocation.proceed();

      return ret? ret : null;
    });


    binder.bindInterceptor("goog.*", "*", function nullify(methodInvocation) {
      var ret = methodInvocation.proceed();

      return ret? ret : null;
    });

    var m = {
          a : 1,
          b : 2,
          c : 3
        };
    binder.bind('name1', 'name1');
    binder.bind('name2', 'name2');
    binder.bind('node', document.getElementById('id'));
    binder.bind('service', exports.Service);
    binder.bind('test2', exports.Test2);
    var l = injector.createInstance(exports.Test3);
    var v = injector.createInstance(exports.Test);
    var o = injector.createInstance(exports.DataSourceManager);
    o.echo(l.getName() + v.getName());
    window.localStorage['foo'] = l.getName() + v.getName();
    window.console.log(injector.createInstance(exports.Test4));
  }
});
