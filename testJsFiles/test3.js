

camp.module('camp.vm.interaction', function (exports) {
  var Injector = camp.using('camp.injections.Injector');
  var Module = camp.using('camp.injections.Module');
  var Matchers = camp.using('camp.injections.Matchers');

  /**
   * @constructor
   */
  function Service() {}
  goog.addSingletonGetter(Service);
  Injector.inject(Service, 'setNode');
  Service.prototype._node = null;

  /**
   * @returns {Element}
   */
  Service.prototype.getNode = function () {
    return this._node;
  };

  Service.prototype.setNode = function (node) {
    //goog.base(this, 1, 2, 3);
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
  Injector.inject(exports.Test, 'setService');


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
   */
  function Fuga() {}

  /**
   * @constructor
   * @extends {Fuga}
   */
  function Hoge() {};
  goog.inherits(Hoge, Fuga);

  exports.Fuga = Fuga;
  exports.Hoge = Hoge;

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
  exports.DataSourceManager = function(pubsub, test4) {
    /**
     * @private {!Object}
     */
    this._dataSources = {};

    /**
     * @private {PubSub}
     */
    this._pubsub = pubsub;

    this._test4 = test4;
  };

  /**
   * @param {string} value
   */
  exports.DataSourceManager.prototype.echo = function(value) {
    this._pubsub.getNode().innertHTML = value;
  };


  Injector.inject(exports.Test3, "setService");

  /**
   * @constructor
   */
  exports.Base1 = function() {

  };

  exports.Base1.prototype.setName = function(name1) {
    this.name1 = name1;
  };

  /**
   * @constructor
   * @extends {exports.Base1}
   */
  exports.Base2 = function() {

  };


  /**
   * @constructor
   * @extends {exports.Base2}
   */
  exports.Base3 = function() {

  };
  Injector.inject(exports.Base3, "setName");

  exports.Base3.prototype.insert = function() {
    document.getElementById('test').innertHTML = "test";
  };

  /**
   * @constructor
   * @implements {Module}
   */
  exports.DefaultModule = function() {};

  exports.DefaultModule.prototype.configure = function(binder) {
    binder.bindInterceptor(
      Matchers.inNamespace('camp.vm.interaction'),
      Matchers.like("set*"),
      function(methodInvocation) {
        window.console.log('call before ' + methodInvocation.getQualifiedName());
        return methodInvocation.proceed();
      }
    );

    binder.bindInterceptor(
      Matchers.inNamespace('camp.vm.interaction'),
      Matchers.like("set*"),
      function(methodInvocation) {
        var ret = methodInvocation.proceed();
        window.console.log('call after ' + methodInvocation.getQualifiedName());
        return ret;
      }
    );

    binder.bindInterceptor(
      Matchers.subclassOf(exports.Base1),
      Matchers.like("insert"),
      function(methodInvocation) {
        var ret = methodInvocation.proceed();
        window.console.log('ok !' + ret);
        return ret;
      }
    );

/*
    binder.bindInterceptor("camp.*", "*", function nullify(methodInvocation) {
      var ret = methodInvocation.proceed();

      return ret? ret : null;
    });


    binder.bindInterceptor("goog.*", "*", function nullify(methodInvocation) {
      var ret = methodInvocation.proceed();

      return ret? ret : null;
    });*/

    binder.bindProvider(null, exports.DataSourceManager, function() {
      return new exports.DataSourceManager(new PubSub);
    });
    var m = {
          a : 1,
          b : 2,
          c : 3
        };

    binder.bind('node', document.getElementById('id'));
    binder.bind('service', exports.Service);
    binder.bind('test2', exports.Test2);

    binder.bindProvider('test4', exports.Test4, function (name1, name2, test2) {
      var a = new exports.Test4(name1, name2);
      a.setC(test2);
      return a;
    });
  };

  /**
   * @constructor
   * @implements {Module}
   */
  exports.DefaultModule2 = function() {};

  exports.DefaultModule2.prototype.configure = function(binder) {
    binder.bind('name1', 'name1');
    binder.bind('name2', 'name2');
  };

  /**
   * @interface
   */
  function Aa() {
  }

  Aa.prototype.b = function(){};

  /**
   * @constructor
   * @implements Aa
   */
  function Ba() {
  }

  Ba.prototype.b = function(){};

  /**
   * @constructor
   * @extends {Ba}
   */
  function Ca() {
  }
  goog.inherits(Ca, Ba);



  /**
   * @param {Aa} a
   */
  function da(a) {
    window.console.log(a);
  }

  da(new Ca());
});
